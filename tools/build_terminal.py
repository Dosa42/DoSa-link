import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / '.build/terminal'
SEED = ROOT / '.build/terminal-seed'

def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()

def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise RuntimeError('Native builds run only in GitHub Actions')
    config = json.loads((ROOT / 'terminal/runtime.json').read_text())
    WORK.mkdir(parents=True, exist_ok=True)
    SEED.mkdir(parents=True, exist_ok=True)
    archive = WORK / 'mksh.tar.gz'
    with urllib.request.urlopen(config['url'], timeout=60) as source, archive.open('wb') as output:
        shutil.copyfileobj(source, output)
    source = WORK / 'source'
    source.mkdir(exist_ok=True)
    with tarfile.open(archive) as tree:
        records = {item.name: hashlib.sha256(tree.extractfile(item).read()).hexdigest() for item in tree.getmembers() if item.isfile()}
        if any(not item.isfile() and not item.isdir() for item in tree.getmembers()) or hashlib.sha256(json.dumps(records, sort_keys=True, separators=(',', ':')).encode()).hexdigest() != config['treeSha256']:
            raise ValueError('Pinned mksh source tree hash mismatch')
        tree.extractall(source, filter='data')
    ndk = Path(os.environ['ANDROID_HOME']) / 'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin'
    compiler = ndk / 'aarch64-linux-android26-clang'
    environment = dict(os.environ, TARGET_OS='Android', CC=str(compiler), CFLAGS='-O2 -fPIE -fno-strict-aliasing -fwrapv', LDFLAGS='-pie -Wl,-z,max-page-size=16384', CPPFLAGS='-DMKSH_DEFAULT_TMPDIR="/data/local/tmp"')
    with (ROOT / 'evidence/terminal-build.log').open('w') as log:
        subprocess.run(['sh', 'Build.sh', '-r'], cwd=source / 'src', env=environment, stdout=log, stderr=subprocess.STDOUT, check=True)
        subprocess.run([str(compiler), '-O2', '-fPIE', '-pie', '-Wall', '-Wextra', '-Werror', '-Wl,-z,max-page-size=16384', str(ROOT / 'terminal/launcher.c'), '-o', str(SEED / 'libworkshop_terminal.so')], stdout=log, stderr=subprocess.STDOUT, check=True)
    shutil.copyfile(source / 'src/mksh', SEED / 'libworkshop_mksh.so')
    subprocess.run([str(ndk / 'llvm-strip'), str(SEED / 'libworkshop_mksh.so'), str(SEED / 'libworkshop_terminal.so')], check=True)
    shutil.copyfile(source / 'NOTICE', SEED / 'NOTICE')
    files = {path.name: digest(path) for path in sorted(SEED.iterdir()) if path.is_file() and path.name != 'tool.json'}
    report = dict(config, schema='wireless-workshop.terminal/1', abi='arm64-v8a', api=26, files=files, launcherSourceSha256=digest(ROOT / 'terminal/launcher.c'), utilities='Android platform utilities through /system/bin; shell and supervisor bundled; file tools implemented in app')
    (SEED / 'tool.json').write_text(json.dumps(report, indent=2) + '\n')
    (ROOT / 'evidence/terminal-package.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report), flush=True)

if __name__ == '__main__':
    main()
