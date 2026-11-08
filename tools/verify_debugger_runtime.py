from contextlib import contextmanager
import hashlib
import io
import json
import os
from pathlib import Path
import shlex
import sqlite3
import subprocess
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.ayrvokan.wireless'
CONFIG = json.loads((Path.home() / '.codex/android-adb/connection.json').read_text())
SERIAL = CONFIG['serial']
ENV = dict(os.environ, LD_LIBRARY_PATH='/data/data/com.termux/files/usr/lib')
RESULTS = {}

def adb(*args, check=True):
    return subprocess.run(['adb', '-s', SERIAL, *args], env=ENV, capture_output=True, check=check)

def shell(*args, check=True):
    return adb('shell', shlex.join(args), check=check)

def read(path):
    return adb('exec-out', 'run-as', PACKAGE, 'cat', path).stdout

def document(path):
    return json.loads(read(path))

@contextmanager
def db():
    connection = sqlite3.connect(':memory:')
    connection.deserialize(read('databases/workshop.db'))
    try:
        yield connection
    finally:
        connection.close()

def wait(check, label, seconds=90):
    end = time.monotonic() + seconds
    last = None
    while time.monotonic() < end:
        try:
            last = check()
            if last:
                return last
        except (ValueError, KeyError, sqlite3.DatabaseError):
            pass
        time.sleep(.25)
    raise AssertionError(label + ': ' + str(last))

def launch(activity='DebugActivity', **values):
    args = ['am', 'start', '-W', '-f', '0x10008000', '-n', PACKAGE + '/.' + activity]
    for key, value in values.items():
        args.extend(['--es', key, value])
    response = shell(*args).stdout.decode()
    assert 'Error:' not in response, response

def row(id):
    with db() as connection:
        return connection.execute('SELECT state,detail FROM jobs WHERE id=?', (id,)).fetchone()

def directory(id):
    return 'files/debug-jobs/' + id

def state(id):
    current = row(id)
    if current and current[0] == 'failed':
        raise AssertionError(document(directory(id) + '/failure.json'))
    return document(directory(id) + '/state.json')

def begin(mode):
    try:
        previous_state = document('files/debugger-state.json')
        previous = previous_state.get('jobId')
    except ValueError:
        previous_state = {}
        previous = None
    launch(action=mode + '-proof')
    def created():
        value = document('files/debugger-state.json')
        if value == previous_state:
            return None
        assert 'error' not in value, value
        return value['jobId'] if value.get('jobId') != previous else None
    return wait(created, mode + ' debug job')

def control(id, action, **values):
    launch(action=action, id=id, values=json.dumps(values))

def paused(id):
    value = state(id)
    return value if value.get('phase') == 'paused' else None

def gone(pid):
    return shell('run-as', PACKAGE, 'test', '-d', '/proc/' + str(pid), check=False).returncode != 0

def finish(id):
    control(id, 'detach')
    wait(lambda: row(id)[0] == 'completed' if row(id)[0] != 'failed' else state(id), 'debug detach')
    report = document(directory(id) + '/report.json')
    request = document(directory(id) + '/request.json')
    wait(lambda: gone(request['targetPid']), 'owned fixture worker cleanup')
    return report

def save():
    (ROOT / 'evidence/delivery24-runtime-tests.json').write_text(json.dumps(RESULTS, indent=2) + '\n')

def main():
    launch('MainActivity')
    before = json.loads((ROOT / 'evidence/delivery24-before.json').read_text())
    with db() as connection:
        assert connection.execute('PRAGMA user_version').fetchone()[0] == before['schema']
        identities = {x[0] for x in before['jobs']}
        assert [list(x) for x in connection.execute('SELECT * FROM jobs ORDER BY id') if x[0] in identities] == before['jobs']
        assert [list(x) for x in connection.execute('SELECT * FROM events WHERE id<=? ORDER BY id', (before['events'][-1][0],))] == before['events']
        for table, order in [('settings', 'name'), ('profiles', 'id')]:
            assert [list(x) for x in connection.execute('SELECT * FROM ' + table + ' ORDER BY ' + order)] == before[table]
    RESULTS['retention'] = {'schema': before['schema'], 'eventsRetained': len(before['events']), 'jobsRetained': len(before['jobs']), 'settingsAndProfilesRetained': True}
    save()
    id = begin('java')
    captured = wait(lambda: paused(id), 'real Java breakpoint')
    top = captured['frames'][0]
    assert top['class'] == 'Lcom/ayrvokan/wireless/DebugProbe;' and top['method'] == 'mark' and top['location'] == 0
    assert top['sourceFile'] == 'DebugProbe.java' and top['sourceLine'] > 0
    local = next(x for x in top['locals'] if x['name'] == 'observed')
    assert local['value'] == 63 and local['error'] == 0
    assert any(x['method'] == 'calculate' and any(v['name'] == 'product' and v['value'] == 63 for v in x['locals']) for x in captured['frames'])
    RESULTS['javaBreakpoint'] = {'jobId': id, 'snapshot': captured}
    save()
    control(id, 'set-int', depth=0, slot=local['slot'], value=84)
    def edited():
        value = paused(id)
        return value if value and any(v['name'] == 'observed' and v['value'] == 84 for v in value['frames'][0]['locals']) else None
    changed = wait(edited, 'Java local edited at real breakpoint')
    assert changed['editError'] == 0
    control(id, 'step')
    stepped = wait(lambda: paused(id) if paused(id) and paused(id)['sequence'] > changed['sequence'] else None, 'Java instruction step')
    assert stepped['frames'][0]['location'] != top['location'] or stepped['frames'][0]['method'] != top['method']
    control(id, 'resume')
    result = wait(lambda: document(directory(id) + '/probe-result.json'), 'edited Java execution result')
    assert result['result'] == 85 and result['databaseWriteLockHeldAtBreakpoint']
    RESULTS['javaEditStepResume'] = {'jobId': id, 'edited': changed, 'stepped': stepped, 'result': result, 'report': finish(id)}
    save()
    id = begin('native')
    request = wait(lambda: document(directory(id) + '/request.json'), 'native request')
    def native_hit():
        value = paused(id)
        if not value:
            return None
        pc = value.get('registers', {}).get('pc', {}).get('value')
        return value if pc and int(pc, 16) == int(request['address'], 16) else None
    captured = wait(native_hit, 'real native software breakpoint')
    assert captured['stopReply'].startswith('T05') or captured['stopReply'].startswith('S05')
    assert captured['frames'][0]['symbol'] == 'workshop_debug_native_leaf'
    assert int(captured['registers']['x0']['value'], 16) == int(request['variableAddress'], 16)
    assert captured['variable']['bytesLittleEndian'] == '29000000'
    assert any('workshop_debug_native_middle' == x.get('symbol') for x in captured['frames'])
    RESULTS['nativeBreakpoint'] = {'jobId': id, 'snapshot': captured, 'server': document(directory(id) + '/server.json'), 'process': document(directory(id) + '/server-process.json')}
    save()
    control(id, 'write-memory', address=request['variableAddress'], data='63000000')
    changed = wait(lambda: paused(id) if paused(id) and paused(id).get('memory', {}).get('bytes') == '63000000' else None, 'native variable write and readback')
    control(id, 'step')
    stepped = wait(lambda: paused(id) if paused(id) and paused(id)['registers']['pc']['value'] != captured['registers']['pc']['value'] else None, 'native instruction step')
    control(id, 'resume')
    result = wait(lambda: document(directory(id) + '/probe-result.json'), 'edited native execution result')
    assert result['result'] == 100 and result['native']['variableValue'] == 99 and result['databaseWriteLockHeldAtBreakpoint']
    report = finish(id)
    server = RESULTS['nativeBreakpoint']['process']['pid']
    wait(lambda: gone(server), 'LLDB guardian cleanup')
    process = RESULTS['nativeBreakpoint']['process']
    wait(lambda: gone(process['serverPid']), 'LLDB child cleanup')
    guardian = document(directory(id) + '/guardian-report.json')
    assert guardian['detached']
    wait(lambda: gone(document(directory(id) + '/guardian-worker.json')['pid']), 'guardian Android worker cleanup')
    RESULTS['nativeEditStepResume'] = {'jobId': id, 'edited': changed, 'stepped': stepped, 'result': result, 'report': report, 'serverGone': True, 'guardian': guardian}
    save()
    for mode in ['java', 'native']:
        id = begin(mode)
        wait(lambda: paused(id), mode + ' crash-recovery breakpoint')
        request = document(directory(id) + '/request.json')
        if mode == 'native':
            wait(lambda: paused(id) if paused(id) and int(paused(id)['registers']['pc']['value'], 16) == int(request['address'], 16) else None, 'native recovery breakpoint reached')
        worker = document(directory(id) + '/worker.json')['pid']
        process = document(directory(id) + '/server-process.json') if mode == 'native' else None
        server = process['pid'] if process else None
        shell('run-as', PACKAGE, 'kill', '-9', str(worker))
        result = wait(lambda: document(directory(id) + '/probe-result.json'), mode + ' resumed after controller death')
        assert result['result'] == (64 if mode == 'java' else 42)
        status = read('/proc/' + str(request['targetPid']) + '/status').decode()
        assert 'TracerPid:\t0' in status, status
        if server:
            wait(lambda: gone(server), 'LLDB guardian parent-death cleanup')
            wait(lambda: gone(process['serverPid']), 'LLDB child parent-death cleanup')
            guardian = document(directory(id) + '/guardian-report.json')
            assert guardian['detached'] and guardian['controllerGone']
            wait(lambda: gone(document(directory(id) + '/guardian-worker.json')['pid']), 'guardian Android worker parent-death cleanup')
        launch(action='recover')
        wait(lambda: row(id)[0] == 'interrupted', mode + ' persistent session recovery')
        wait(lambda: gone(request['targetPid']), mode + ' owned recovery fixture cleanup')
        RESULTS[mode + 'ControllerDeath'] = {'jobId': id, 'controllerPid': worker, 'serverPid': server, 'targetResumed': True, 'result': result, 'tracerDetached': True, 'state': row(id)}
        if server:
            RESULTS[mode + 'ControllerDeath']['guardian'] = guardian
        save()
    launch('DiagnosticsActivity', action='preview-export', module='debug')
    wait(lambda: document('files/diagnostics-preview.json') if document('files/diagnostics-preview.json').get('filters', {}).get('module') == 'debug' else None, 'debugger evidence export')
    data = read('cache/diagnostics-preview.zip')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        manifest = json.loads(archive.read('manifest.json'))
        rows = [json.loads(x) for x in archive.read('events.jsonl').splitlines()]
        assert len(rows) == manifest['eventCount'] and all(x['module'] == 'debug' for x in rows)
        for file in manifest['files']:
            payload = archive.read(file['path'])
            assert len(payload) == file['bytes'] and hashlib.sha256(payload).hexdigest() == file['sha256']
        assert any(x['path'].endswith('/rsp.jsonl') for x in manifest['files'])
        assert any(x['path'].endswith('/agent-events.jsonl') for x in manifest['files'])
    (ROOT / 'evidence/delivery24-export.zip').write_bytes(data)
    RESULTS['export'] = {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(), 'events': len(rows), 'files': len(manifest['files']), 'allAttachmentHashesVerified': True}
    save()
    print(json.dumps({'status': 'pass', 'checks': list(RESULTS), 'export': RESULTS['export']}), flush=True)

if __name__ == '__main__':
    main()
