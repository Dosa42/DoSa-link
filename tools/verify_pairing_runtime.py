import hashlib
import importlib.util
import json
from pathlib import Path
import shlex
import subprocess
import sys
import time

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('debug_runtime', ROOT / 'tools/verify_debugger_runtime.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

def current():
    return m.document('files/pairing-state.json')

def launch(action, address=None, fresh=False):
    args = ['am', 'start', '-W', '-f', '0x10008000' if fresh else '0x30000000', '-n', m.PACKAGE + '/.PairingActivity', '--es', 'action', action]
    if address:
        args.extend(['--es', 'address', address])
    result = m.shell(*args).stdout.decode()
    assert 'Error:' not in result, result

def main():
    input_file = ROOT / 'private/nspanel-wifi.json'
    if input_file.exists():
        command = shlex.join(['run-as', m.PACKAGE, 'sh', '-c', 'umask 077; cat > files/pairing-input.json.part && mv files/pairing-input.json.part files/pairing-input.json'])
        subprocess.run(['adb', '-s', m.SERIAL, 'shell', command], env=m.ENV, input=input_file.read_bytes(), capture_output=True, check=True)
    candidate = json.loads((ROOT / 'evidence/nspanel-pairing-observation.json').read_text())['candidate']
    try:
        previous = current().get('id')
    except ValueError:
        previous = None
    action = 'inspect' if '--inspect' in sys.argv else 'connect'
    launch(action, candidate['address'], fresh=True)
    started = time.monotonic()
    observations = []
    last = None
    final = None
    while time.monotonic() - started < 75:
        try:
            state = current()
        except ValueError:
            time.sleep(.3)
            continue
        if state.get('id') == previous:
            time.sleep(.3)
            continue
        final = state
        marker = (state.get('phase'), state.get('sdkState'), state.get('message'))
        if marker != last:
            print(json.dumps({'id': state['id'], 'phase': marker[0], 'sdkState': marker[1], 'message': marker[2], 'services': [service['uuid'] for service in state.get('services', [])], 'deviceId': state.get('deviceId')}), flush=True)
            observations.append({'elapsed': round(time.monotonic() - started, 3), 'state': state})
            last = marker
        if state.get('phase') in ['ready-to-provision', 'inspected', 'connection-error', 'inspection-error', 'module-error', 'response-timeout']:
            if state['phase'] == 'inspected' and state.get('coolkitCompatible') and action == 'connect':
                pass
            else:
                break
        time.sleep(.3)
    assert final is not None, 'No fresh pairing state'
    result = {'version': json.loads((ROOT / 'evidence/build.json').read_text())['version'], 'action': action, 'candidateAddress': candidate['address'], 'observations': observations, 'final': final, 'credentialsPrinted': False}
    (ROOT / 'evidence/pairing-runtime.json').write_text(json.dumps(result, indent=2) + '\n')
    if final.get('phase') == 'ready-to-provision' and '--provision' in sys.argv:
        launch('provision')
        end = time.monotonic() + 95
        while time.monotonic() < end:
            state = current()
            if state.get('phase') in ['network-status', 'module-error', 'response-timeout']:
                result['provisioning'] = state
                (ROOT / 'evidence/pairing-runtime.json').write_text(json.dumps(result, indent=2) + '\n')
                break
            time.sleep(.5)
    print(json.dumps({'saved': 'evidence/pairing-runtime.json', 'phase': final.get('phase'), 'coolkitCompatible': final.get('coolkitCompatible'), 'deviceId': final.get('deviceId')}), flush=True)

if __name__ == '__main__':
    main()
