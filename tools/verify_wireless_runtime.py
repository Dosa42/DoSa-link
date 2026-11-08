import base64
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shlex
import subprocess
import sys
import time
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('debug_runtime', ROOT / 'tools/verify_debugger_runtime.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
RESULTS = {}

def save():
    (ROOT / 'evidence/delivery25-runtime-tests.json').write_text(json.dumps(RESULTS, indent=2) + '\n')

def launch(activity='WirelessActivity', **values):
    command = ['am', 'start', '-W', '-f', '0x30000000', '-n', m.PACKAGE + '/.' + activity]
    for key, value in values.items():
        command.extend(['--es', key, value])
    result = m.shell(*command).stdout.decode()
    assert 'Error:' not in result, result

def current():
    return m.document('files/wireless-state.json')

def wait_for(predicate, label, seconds=30):
    return m.wait(predicate, label, seconds)

def export(module):
    launch('DiagnosticsActivity', action='preview-export', module=module)
    wait_for(lambda: m.document('files/diagnostics-preview.json') if m.document('files/diagnostics-preview.json').get('filters', {}).get('module') == module else None, module + ' export')
    data = m.read('cache/diagnostics-preview.zip')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        manifest = json.loads(archive.read('manifest.json'))
        rows = [json.loads(line) for line in archive.read('events.jsonl').splitlines()]
        assert len(rows) == manifest['eventCount'] and rows and all(row['module'] == module for row in rows)
        assert any(file['path'].endswith('/report.json') for file in manifest['files'])
        for file in manifest['files']:
            payload = archive.read(file['path'])
            assert len(payload) == file['bytes'] and hashlib.sha256(payload).hexdigest() == file['sha256']
    (ROOT / ('evidence/delivery25-' + module + '-export.zip')).write_bytes(data)
    return {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(), 'events': len(rows), 'attachments': len(manifest['files']), 'allHashesVerified': True}

def main():
    before = json.loads((ROOT / 'evidence/delivery25-before.json').read_text())
    with m.db() as db:
        assert db.execute('PRAGMA user_version').fetchone()[0] == before['schema']
        for table, order in [('settings', 'name'), ('profiles', 'id'), ('jobs', 'id')]:
            assert [list(row) for row in db.execute('SELECT * FROM ' + table + ' ORDER BY ' + order)] == before[table]
        assert [list(row) for row in db.execute('SELECT * FROM events WHERE id<=? ORDER BY id', (before['events'][-1][0],))] == before['events']
    RESULTS['retention'] = {'schema': before['schema'], 'events': len(before['events']), 'jobs': len(before['jobs']), 'settingsAndProfilesUnchanged': True}
    save()
    launch(action='fixture')
    def resolved():
        value = current()
        found = [device for device in value['devices'] if device.get('resolved') and device['name'].startswith('Workshop-proof-')]
        return (value, found[0]) if found else None
    session, device = wait_for(resolved, 'controlled NSD service discovery and resolution', 45)
    assert device['host'] and device['port'] == session['fixture']['port'] and device['port'] > 0
    assert base64.b64decode(device['txtBase64']['proof']).decode() == session['id']
    RESULTS['lanFixture'] = {'id': session['id'], 'fixture': session['fixture'], 'observed': device, 'networkContext': session['networks']}
    launch(action='bind', key=device['key'], profile='nspanel')
    wait_for(lambda: m.document('files/wireless-devices.json')['bindings'].get('nspanel') == device['key'], 'local profile association')
    RESULTS['profileAssociation'] = {'profile': 'nspanel', 'key': device['key'], 'persisted': True}
    launch(action='stop')
    stopped = wait_for(lambda: current() if current().get('phase') == 'completed' else None, 'explicit discovery stop')
    assert not any(stopped['cleanup'].values()), stopped
    RESULTS['lanStop'] = stopped
    save()
    launch(action='ble')
    observed = wait_for(lambda: current() if current().get('ble') not in ['idle', ''] else None, 'BLE initial permission state')
    RESULTS['bleInitialState'] = observed['ble']
    for permission in ['BLUETOOTH_SCAN', 'BLUETOOTH_CONNECT', 'ACCESS_FINE_LOCATION']:
        m.shell('pm', 'grant', m.PACKAGE, 'android.permission.' + permission)
    launch(action='ble')
    scanning = wait_for(lambda: current() if current().get('ble') in ['scanning', 'bluetooth-off', 'location-off', 'unsupported'] else None, 'BLE adapter scan')
    assert scanning['ble'] == 'scanning', scanning
    launch(action='lan', types='_ewelink._tcp., _http._tcp.')
    end = time.monotonic() + 12
    while time.monotonic() < end:
        value = current()
        if any(device['transport'] == 'ble' for device in value['devices']):
            break
        time.sleep(.5)
    value = current()
    RESULTS['wirelessObserved'] = value
    RESULTS['bleAdvertisementsObserved'] = sum(device['transport'] == 'ble' for device in value['devices'])
    save()
    launch('CompatibilityActivity')
    background = wait_for(lambda: current() if current().get('phase') == 'completed' else None, 'background scan teardown')
    assert background['reason'] == 'screen closed' and not any(background['cleanup'].values())
    RESULTS['backgroundStop'] = background['cleanup']
    compatibility = wait_for(lambda: m.document('files/compatibility-state.json'), 'module loader and JNI compatibility', 60)
    assert compatibility['phase'] == 'completed' and len(compatibility['elf']) == 100
    assert all(item['exists'] and item['machine'] == 183 and item['compatibleWithCurrentPageSize'] for item in compatibility['elf'])
    assert compatibility['jni']['executed'], compatibility['jni']
    assert compatibility['constructedModule']['constructed'], compatibility['constructedModule']
    assert all(item['loaded'] for item in compatibility['nativeLoads']), compatibility['nativeLoads']
    RESULTS['compatibility'] = compatibility
    save()
    RESULTS['wirelessExport'] = export('wireless')
    RESULTS['compatibilityExport'] = export('compatibility')
    registry = m.document('files/wireless-devices.json')
    registry['devices'] = {key: value for key, value in registry['devices'].items() if not value.get('name', '').startswith('Workshop-proof-')}
    registry['bindings'] = {key: value for key, value in registry['bindings'].items() if value in registry['devices']}
    command = shlex.join(['run-as', m.PACKAGE, 'sh', '-c', 'cat > files/wireless-devices.json.part && mv files/wireless-devices.json.part files/wireless-devices.json'])
    subprocess.run(['adb', '-s', m.SERIAL, 'shell', command], env=m.ENV, input=(json.dumps(registry, indent=2) + '\n').encode(), capture_output=True, check=True)
    m.shell('run-as', m.PACKAGE, 'rm', '-f', 'cache/diagnostics-preview.zip', 'cache/diagnostics-preview.zip.part', 'files/diagnostics-preview.json', 'files/diagnostics-preview.json.part')
    RESULTS['cleanup'] = {'fixtureRemovedFromDeviceRegistry': True, 'observationsKept': len(registry['devices']), 'scansStopped': True, 'historiesRetained': True}
    save()
    print(json.dumps({'status': 'pass', 'checks': list(RESULTS), 'bleAdvertisements': RESULTS['bleAdvertisementsObserved'], 'jni': compatibility['jni']}), flush=True)

if __name__ == '__main__':
    main()
