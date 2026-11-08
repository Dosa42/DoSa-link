from concurrent.futures import ThreadPoolExecutor
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('github_private', ROOT / 'tools/github.py')
g = importlib.util.module_from_spec(spec)
spec.loader.exec_module(g)

def main():
    run = json.loads((ROOT / 'evidence/remote-run.json').read_text())
    if run['conclusion'] != 'success':
        raise RuntimeError('Remote build has not passed')
    artifacts = g.api('repos/' + g.REPO + '/actions/runs/' + str(run['id']) + '/artifacts')['artifacts']
    artifact = next(item for item in artifacts if item['name'].startswith('wireless-workshop-'))
    g.TRANSFER.mkdir(exist_ok=True)
    target = g.TRANSFER / 'result.zip'
    request = urllib.request.Request(artifact['archive_download_url'], headers={'Authorization': 'Bearer ' + g.TOKEN})
    with urllib.request.build_opener(g.ArtifactRedirect()).open(request, timeout=30) as response:
        url = response.geturl()
        size = int(response.headers['Content-Length'])
    prefix = target.stat().st_size if target.exists() else 0
    if prefix > size:
        raise ValueError('Existing download exceeds remote artifact')
    chunks = [(start, min(start + 4 * 1024 * 1024, size)) for start in range(prefix, size, 4 * 1024 * 1024)]
    folder = g.TRANSFER / 'ranges'
    folder.mkdir(exist_ok=True)
    started = time.monotonic()
    def fetch(bounds):
        start, end = bounds
        part = folder / str(start)
        existing = part.stat().st_size if part.exists() else 0
        if existing == end - start:
            return part
        for attempt in range(3):
            existing = part.stat().st_size if part.exists() else 0
            query = urllib.request.Request(url, headers={'Range': 'bytes=' + str(start + existing) + '-' + str(end - 1)})
            try:
                with urllib.request.urlopen(query, timeout=30) as response:
                    if response.status != 206 or not response.headers.get('Content-Range', '').startswith('bytes ' + str(start + existing) + '-'):
                        raise ValueError('Server did not honor exact range')
                    with part.open('ab') as output:
                        shutil.copyfileobj(response, output, 262144)
                if part.stat().st_size != end - start:
                    raise ValueError('Incomplete artifact range')
                return part
            except OSError:
                if attempt == 2:
                    raise
    print(json.dumps({'transfer': 'parallel-resume', 'streams': 8, 'bytesRetained': prefix, 'bytesTotal': size}), flush=True)
    with ThreadPoolExecutor(max_workers=8) as pool:
        for part in pool.map(fetch, chunks):
            with target.open('ab') as output, part.open('rb') as source:
                shutil.copyfileobj(source, output, 1048576)
            part.unlink()
    assert target.stat().st_size == size
    with zipfile.ZipFile(target) as archive:
        archive.extractall(g.TRANSFER / 'result')
    result = g.TRANSFER / 'result'
    candidate = g.TRANSFER / 'download.apk'
    subprocess.run(['openssl', 'enc', '-d', '-aes-256-cbc', '-pbkdf2', '-iter', '200000', '-pass', 'file:' + str(g.PRIVATE / 'ci-input-password'), '-in', str(result / 'wireless-workshop.apk.enc'), '-out', str(candidate)], check=True)
    receipt = json.loads((result / 'build.json').read_text())
    with candidate.open('rb') as source:
        digest = hashlib.file_digest(source, 'sha256').hexdigest()
    if digest != receipt['sha256']:
        raise ValueError('Downloaded APK digest differs from remote receipt')
    for item in result.iterdir():
        if item.name != 'wireless-workshop.apk.enc':
            shutil.copyfile(item, ROOT / 'evidence' / item.name)
    candidate.replace(ROOT / 'dist/wireless-workshop.apk')
    seconds = round(time.monotonic() - started, 3)
    report = {'run': run['html_url'], 'version': receipt['version'], 'sha256': digest, 'bytes': receipt['bytes'], 'archiveBytes': size, 'prefixRetained': prefix, 'streams': 8, 'elapsedSeconds': seconds, 'archiveBytesPerSecond': round((size - prefix) / max(seconds, .001))}
    (ROOT / 'evidence/artifact-transfer.json').write_text(json.dumps(report, indent=2) + '\n')
    shutil.rmtree(g.TRANSFER)
    print(json.dumps(report), flush=True)

if __name__ == '__main__':
    main()
