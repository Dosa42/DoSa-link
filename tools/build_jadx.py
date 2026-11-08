import hashlib
import json
import os
from pathlib import Path
import shutil
import tarfile
import urllib.request
import zipfile
from build import ROOT, WORK, SDK, digest, run

def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise RuntimeError('Engine and JNI compilation run only in GitHub Actions')
    (ROOT / 'evidence').mkdir(exist_ok=True)
    WORK.mkdir(exist_ok=True)
    seed = WORK / 'jadx-seed'
    inputs = {}
    for folder in ['jadx', 'native']:
        for path in sorted((ROOT / folder).rglob('*')):
            if path.is_file() and 'build' not in path.relative_to(ROOT / folder).parts and '.gradle' not in path.parts:
                inputs[str(path.relative_to(ROOT))] = digest(path)
    inputs['tools/build_jadx.py'] = digest(Path(__file__))
    fingerprint = hashlib.sha256(json.dumps(inputs, sort_keys=True).encode()).hexdigest()
    receipt = seed / 'tool.json'
    if receipt.exists():
        cached = json.loads(receipt.read_text())
        if cached.get('sourceFingerprint') == fingerprint and all((seed / name).is_file() and digest(seed / name) == expected for name, expected in cached['files'].items()):
            shutil.copyfile(seed / 'dependencies.json', ROOT / 'evidence/jadx-dependencies.json')
            shutil.copyfile(receipt, ROOT / 'evidence/jadx-package.json')
            print(json.dumps({'stage': 'reuse-unchanged-jni-jadx-engine', 'fingerprint': fingerprint}), flush=True)
            return
    if seed.exists():
        shutil.rmtree(seed)
    seed.mkdir(parents=True)
    run('build-jvm-jadx-and-fixtures', ['gradle', '-p', ROOT / 'jadx', ':engine:jar', ':fixture:assembleRelease', ':engine:recordDependencies', '--no-daemon', '--console=plain'])
    jars = seed / 'libs'
    jars.mkdir()
    shutil.copyfile(ROOT / 'jadx/engine/build/libs/engine.jar', jars / 'engine.jar')
    for file in sorted((ROOT / 'jadx/engine/build/runtime-libs').glob('*.jar')):
        shutil.copyfile(file, jars / file.name)
    fixture = ROOT / 'jadx/fixture/build/outputs/apk/release/fixture-release-unsigned.apk'
    samples = seed / 'fixtures'
    samples.mkdir()
    shutil.copyfile(fixture, samples / 'fixture.apk')
    with zipfile.ZipFile(fixture) as archive:
        (samples / 'fixture.dex').write_bytes(archive.read('classes.dex'))
    classes = list((ROOT / 'jadx/fixture/build/intermediates/javac/release').rglob('SeedProbe.class'))
    if len(classes) != 1:
        raise ValueError('Expected one compiled SeedProbe fixture')
    shutil.copyfile(classes[0], samples / 'SeedProbe.class')
    (samples / 'SeedProbe.smali').write_text('.class public final Lcom/ayrvokan/fixture/SeedProbe;\n.super Ljava/lang/Object;\n\n.method public static answer()I\n    .locals 1\n    const/16 v0, 0x2a\n    return v0\n.end method\n')
    test_request = WORK / 'jvm-fixture-request.json'
    test_output = WORK / 'jvm-fixture-result'
    test_progress = WORK / 'jvm-fixture-progress.json'
    if test_output.exists():
        shutil.rmtree(test_output)
    test_request.write_text(json.dumps({'input': str(samples / 'fixture.apk'), 'options': [], 'selectedClass': ''}))
    classpath = os.pathsep.join(str(file) for file in sorted(jars.glob('*.jar')))
    run('verify-jvm-jadx-api-and-fixture', ['java', '-classpath', classpath, 'com.ayrvokan.jadxengine.Runner', test_request, test_output, test_progress])
    test_report = json.loads((test_output / 'engine-report.json').read_text())
    if test_report['version'] != '1.5.6' or test_report['errors'] or not test_report['stages']:
        raise ValueError('Staged JVM engine fixture did not pass')
    if '42' not in (test_output / 'sources/com/ayrvokan/fixture/SeedProbe.java').read_text():
        raise ValueError('Staged JVM engine output differs from fixture')
    shutil.copyfile(test_output / 'engine-report.json', ROOT / 'evidence/jadx-jvm-fixture.json')
    runtime = json.loads((ROOT / 'native/runtime.json').read_text())
    source = WORK / 'android-jre.tar.xz'
    if not source.exists() or digest(source) != runtime['sha256']:
        with urllib.request.urlopen(runtime['url'], timeout=90) as response, source.open('wb') as output:
            shutil.copyfileobj(response, output, 1048576)
    if digest(source) != runtime['sha256']:
        raise ValueError('Android OpenJDK digest mismatch')
    runtime_files = {}
    with tarfile.open(source) as archive, zipfile.ZipFile(seed / 'runtime.zip', 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as output:
        for member in archive.getmembers():
            if not (member.isfile() or member.issym()):
                continue
            name = str(Path(member.name))
            data = archive.extractfile(member).read()
            output.writestr(name, data)
            runtime_files[name] = {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
    (seed / 'runtime-files.json').write_text(json.dumps(runtime_files, indent=2) + '\n')
    bridge = seed / 'native/libworkshop_jvm.so'
    bridge.parent.mkdir()
    compiler = SDK / 'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang'
    run('compile-in-process-jni-bridge', [compiler, '-shared', '-fPIC', '-O2', '-Wall', '-Wextra', '-Werror', '-Wl,-z,max-page-size=16384', '-Wl,-soname,libworkshop_jvm.so', ROOT / 'native/bridge.c', '-ldl', '-o', bridge])
    shutil.copyfile(ROOT / 'evidence/jadx-dependencies.json', seed / 'dependencies.json')
    providers = set()
    descriptors = set()
    class_sets = set()
    for file in sorted(jars.glob('*.jar')):
        with zipfile.ZipFile(file) as archive:
            for name in archive.namelist():
                if name.startswith('META-INF/services/'):
                    descriptors.add(name)
                if name.endswith('.jcst'):
                    class_sets.add(name)
            name = 'META-INF/services/jadx.api.plugins.JadxPlugin'
            if name in archive.namelist():
                providers.update(line.strip() for line in archive.read(name).decode().splitlines() if line.strip() and not line.strip().startswith('#'))
    if not providers or not class_sets:
        raise ValueError('JADX plugin discovery or class-set resources missing')
    dependencies = json.loads((seed / 'dependencies.json').read_text())
    files = {str(path.relative_to(seed)): digest(path) for path in sorted(seed.rglob('*')) if path.is_file()}
    classpath = {name: value for name, value in files.items() if name.startswith('libs/')}
    engine_hash = hashlib.sha256(json.dumps(classpath, sort_keys=True).encode()).hexdigest()
    value = {'id': 'jadx-headless', 'version': '1.5.6', 'backend': 'jni-hotspot', 'runtimeVersion': runtime['version'], 'runtimeSha256': runtime['sha256'], 'runtimeRelease': runtime['upstreamRelease'], 'nativeBridgeSha256': files['native/libworkshop_jvm.so'], 'engineSha256': engine_hash, 'dependencyCount': len(dependencies), 'sourceFingerprint': fingerprint, 'pluginProviders': sorted(providers), 'pluginResourcePaths': sorted(descriptors), 'classSetResources': sorted(class_sets), 'files': files}
    receipt.write_text(json.dumps(value, indent=2) + '\n')
    shutil.copyfile(receipt, ROOT / 'evidence/jadx-package.json')
    print(json.dumps({'stage': 'jni-jadx-packaged', 'runtimeBytes': (seed / 'runtime.zip').stat().st_size, 'dependencies': len(dependencies), 'fingerprint': fingerprint}), flush=True)

if __name__ == '__main__':
    main()
