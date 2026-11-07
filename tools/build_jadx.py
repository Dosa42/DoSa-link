import hashlib
import json
import os
from pathlib import Path
import shutil
import zipfile
from build import ROOT, WORK, digest, run

def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise RuntimeError('Engine compilation runs only in GitHub Actions')
    (ROOT / 'evidence').mkdir(exist_ok=True)
    WORK.mkdir(exist_ok=True)
    seed = WORK / 'jadx-seed'
    inputs = {str(path.relative_to(ROOT)): digest(path) for path in sorted((ROOT / 'jadx').rglob('*')) if path.is_file() and 'build' not in path.relative_to(ROOT / 'jadx').parts and '.gradle' not in path.parts}
    inputs['tools/build_jadx.py'] = digest(Path(__file__))
    fingerprint = hashlib.sha256(json.dumps(inputs, sort_keys=True).encode()).hexdigest()
    receipt = seed / 'tool.json'
    if receipt.exists():
        cached = json.loads(receipt.read_text())
        if cached.get('sourceFingerprint') == fingerprint and all((seed / name).is_file() and digest(seed / name) == expected for name, expected in cached['files'].items()):
            shutil.copyfile(seed / 'dependencies.json', ROOT / 'evidence/jadx-dependencies.json')
            shutil.copyfile(receipt, ROOT / 'evidence/jadx-package.json')
            print(json.dumps({'stage': 'reuse-unchanged-jadx-engine', 'fingerprint': fingerprint}), flush=True)
            return
    if seed.exists():
        shutil.rmtree(seed)
    seed.mkdir(parents=True)
    run('build-bundled-jadx-and-fixtures', ['gradle', '-p', ROOT / 'jadx', ':engine:assembleRelease', ':fixture:assembleRelease', ':engine:recordDependencies', '--no-daemon', '--console=plain'])
    engine = ROOT / 'jadx/engine/build/outputs/apk/release/engine-release-unsigned.apk'
    fixture = ROOT / 'jadx/fixture/build/outputs/apk/release/fixture-release-unsigned.apk'
    upstream_manifest = (ROOT / 'evidence/jadx-upstream-manifest.mf').read_bytes()
    if b'jadx-version: 1.5.6' not in upstream_manifest:
        raise ValueError('Upstream engine version metadata differs from the pinned release')
    with zipfile.ZipFile(engine) as original, zipfile.ZipFile(seed / 'engine.apk', 'w') as output:
        for item in original.infolist():
            if item.filename != 'META-INF/MANIFEST.MF':
                output.writestr(item, original.read(item))
        output.writestr('META-INF/MANIFEST.MF', upstream_manifest)
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
    shutil.copyfile(ROOT / 'evidence/jadx-dependencies.json', seed / 'dependencies.json')
    with zipfile.ZipFile(seed / 'engine.apk') as archive:
        plugins = [name for name in archive.namelist() if name.startswith('META-INF/services/')]
        class_sets = [name for name in archive.namelist() if name.endswith('.jcst')]
        if not any(name.endswith('jadx.api.plugins.JadxPlugin') for name in plugins) or not class_sets:
            raise ValueError('JADX plugin discovery or class-set resources missing from engine')
        plugin_content = archive.read('META-INF/services/jadx.api.plugins.JadxPlugin').decode()
    dependencies = json.loads((seed / 'dependencies.json').read_text())
    files = {str(path.relative_to(seed)): digest(path) for path in sorted(seed.rglob('*')) if path.is_file()}
    value = {'id': 'jadx-headless', 'version': '1.5.6', 'engineSha256': files['engine.apk'], 'dependencyCount': len(dependencies), 'sourceFingerprint': fingerprint, 'pluginProviders': plugin_content.splitlines(), 'pluginResourcePaths': plugins, 'classSetResources': class_sets, 'files': files}
    receipt.write_text(json.dumps(value, indent=2) + '\n')
    shutil.copyfile(receipt, ROOT / 'evidence/jadx-package.json')
    print(json.dumps({'stage': 'jadx-engine-packaged', 'engineBytes': engine.stat().st_size, 'dependencies': len(dependencies), 'fingerprint': fingerprint}), flush=True)

if __name__ == '__main__':
    main()
