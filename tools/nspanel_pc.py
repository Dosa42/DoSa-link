import argparse
import io
import json
from pathlib import Path
import shlex
import subprocess
import sys
import tarfile

ROOT = Path(__file__).resolve().parents[1]
CONFIG = json.loads((Path.home() / '.codex/goal-core/pc/connection.json').read_text())
REMOTE = '/home/dosa/Work/wireless-workshop/nspanel'


def ssh():
    return ['ssh', '-F', '/dev/null', '-i', CONFIG['identityFile'], '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'HostKeyAlias=' + CONFIG['hostKeyAlias'], '-o', 'UserKnownHostsFile=' + CONFIG['runtime'] + '/known_hosts', '-o', 'ConnectTimeout=8', CONFIG['user'] + '@' + CONFIG['hosts'][0]]


def run(code, data=None):
    return subprocess.run(ssh() + ['python3 -c ' + shlex.quote(code)], input=data, capture_output=True, check=True)


def stage():
    files = {
        'controller-esp32.bin': ROOT / 'devices/nspanel/firmware/controller-esp32.bin',
        'controller-esp32.elf': ROOT / 'devices/nspanel/decoded/controller-esp32.elf',
        'controller-esp32.json': ROOT / 'devices/nspanel/decoded/controller-esp32.json',
        'display-hmix86.tft': ROOT / 'devices/nspanel/firmware/display-hmix86.tft',
        'NSPanelSeed.java': ROOT / 'tools/ghidra/NSPanelSeed.java',
        'NSPanelExport.java': ROOT / 'tools/ghidra/NSPanelExport.java',
    }
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode='w:gz') as archive:
        for name, path in files.items():
            archive.add(path, arcname=name)
    code = "import hashlib,io,json,pathlib,tarfile,sys;root=pathlib.Path(" + repr(REMOTE) + ");root.mkdir(parents=True,exist_ok=True);archive=tarfile.open(fileobj=io.BytesIO(sys.stdin.buffer.read()),mode='r:gz');allowed=" + repr(set(files)) + ";assert all(x.isfile() and x.name in allowed for x in archive.getmembers());receipt=[]\nfor member in archive.getmembers():\n data=archive.extractfile(member).read();p=root/member.name;p.write_bytes(data);receipt.append({'name':member.name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})\nprint(json.dumps({'directory':str(root),'files':receipt}))"
    result = json.loads(run(code, buffer.getvalue()).stdout)
    for item in result['files']:
        import hashlib
        assert hashlib.sha256(files[item['name']].read_bytes()).hexdigest() == item['sha256']
    (ROOT / 'devices/nspanel/pc-transfer.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['stage', 'exec', 'fetch'])
    parser.add_argument('value', nargs='?')
    args = parser.parse_args()
    if args.action == 'stage':
        stage()
    elif args.action == 'exec':
        sys.stdout.buffer.write(run(args.value if args.value is not None else sys.stdin.read()).stdout)
    else:
        name = args.value
        if not name or Path(name).name != name:
            raise ValueError('Expected result basename')
        data = run('import pathlib,sys;sys.stdout.buffer.write(pathlib.Path(' + repr(REMOTE + '/' + name) + ').read_bytes())').stdout
        target = ROOT / 'devices/nspanel/decoded' / name
        target.write_bytes(data)
        print(json.dumps({'path': str(target), 'bytes': len(data)}))


if __name__ == '__main__':
    main()
