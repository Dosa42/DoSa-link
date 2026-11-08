import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

def fetch(url, target, headers=None):
    part = target.with_name(target.name + '.part')
    for attempt in range(3):
        try:
            request = urllib.request.Request(url, headers=headers or {})
            with urllib.request.urlopen(request, timeout=30) as response, part.open('wb') as output:
                shutil.copyfileobj(response, output, 1048576)
            part.replace(target)
            return
        except (urllib.error.URLError, OSError) as error:
            part.unlink(missing_ok=True)
            if attempt == 2 or getattr(error, 'code', 500) not in [408, 429, 500, 502, 503, 504]:
                raise
            print(json.dumps({'fetch': 'retry', 'attempt': attempt + 2, 'errorType': type(error).__name__}), flush=True)
            time.sleep(2 ** attempt)

def prepare():
    config = json.loads((ROOT / 'ci-inputs.json').read_text())
    transfer = ROOT / '.transfer'
    transfer.mkdir(exist_ok=True)
    encrypted = transfer / 'inputs.enc'
    fetch(config.get('api_url', config['url']), encrypted, {'Accept': 'application/octet-stream'})
    with encrypted.open('rb') as source:
        actual = hashlib.file_digest(source, 'sha256').hexdigest()
    if actual != config['sha256']:
        raise ValueError('Encrypted input digest mismatch')
    process = subprocess.Popen(['openssl', 'enc', '-d', '-aes-256-cbc', '-pbkdf2', '-iter', '200000', '-pass', 'env:WORKSHOP_INPUT_PASSWORD', '-in', str(encrypted)], stdout=subprocess.PIPE)
    with tarfile.open(fileobj=process.stdout, mode='r|') as archive:
        archive.extractall(ROOT / '.inputs', filter='data')
    process.stdout.close()
    if process.wait():
        raise RuntimeError('Input decryption failed')
    encrypted.unlink()
    shutil.copytree(ROOT / '.inputs/private', ROOT / 'private', dirs_exist_ok=True)
    editor = ROOT / 'toolchain/APKEditor-1.4.9.jar'
    editor.parent.mkdir(exist_ok=True)
    fetch('https://github.com/REAndroid/APKEditor/releases/download/V1.4.9/APKEditor-1.4.9.jar', editor)

def finish():
    output = ROOT / '.transfer/output'
    output.mkdir(parents=True, exist_ok=True)
    subprocess.run(['openssl', 'enc', '-aes-256-cbc', '-pbkdf2', '-iter', '200000', '-pass', 'env:WORKSHOP_INPUT_PASSWORD', '-in', str(ROOT / 'dist/wireless-workshop.apk'), '-out', str(output / 'wireless-workshop.apk.enc')], check=True)
    for name in ['build.json', 'preservation.json', 'manifest-changes.json', 'packaged-manifest.xml', 'build.log', 'jadx-dependencies.json', 'jadx-package.json', 'jadx-jvm-fixture.json', 'jadx-jvm-multi-fixture.json', 'jadx-jvm-batch-fixture.json', 'terminal-package.json', 'terminal-build.log', 'debugger-package.json', 'debugger-build.log']:
        shutil.copyfile(ROOT / 'evidence' / name, output / name)
    receipt = {'run_id': os.environ['GITHUB_RUN_ID'], 'commit': os.environ['GITHUB_SHA'], 'repository': os.environ['GITHUB_REPOSITORY'], 'runner': os.environ['RUNNER_OS']}
    (output / 'remote-build.json').write_text(json.dumps(receipt, indent=2) + '\n')

if __name__ == '__main__':
    {'prepare': prepare, 'finish': finish}[sys.argv[1]]()
