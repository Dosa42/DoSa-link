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
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.ayrvokan.wireless'
SERIAL = json.loads((Path.home() / '.codex/android-adb/connection.json').read_text())
SERIAL = SERIAL.get('serial') or SERIAL.get('endpoint') or (SERIAL['host'] + ':' + str(SERIAL['port']))
ENV = dict(os.environ, LD_LIBRARY_PATH='/data/data/com.termux/files/usr/lib')
RESULTS = {}

def adb(*args):
    result = subprocess.run(['adb', '-s', SERIAL, *args], env=ENV, capture_output=True, check=True)
    return result.stdout

def shell(*args):
    result = adb('shell', shlex.join(args)).decode()
    return result

def read(path):
    return adb('exec-out', 'run-as', PACKAGE, 'cat', path)

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

def wait(test, label, seconds=90):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            last = test()
            if last:
                return last
        except (ValueError, KeyError, sqlite3.DatabaseError):
            pass
        time.sleep(.4)
    raise AssertionError(label + ': ' + str(last))

def launch(activity, **values):
    args = ['am', 'start', '-W', '-f', '0x10008000', '-n', PACKAGE + '/.' + activity]
    for key, value in values.items():
        args.extend(['--es', key, value])
    output = shell(*args)
    assert 'Error:' not in output, output

def job(id):
    with db() as connection:
        return connection.execute('SELECT state,detail FROM jobs WHERE id=?', (id,)).fetchone()

def begin(command, **values):
    try:
        before = document('files/terminal-state.json')
    except ValueError:
        before = {}
    launch('TerminalActivity', action='run', command=command, **values)
    def created():
        state = document('files/terminal-state.json')
        if state.get('error'):
            raise AssertionError(state)
        return state.get('jobId') if state.get('jobId') != before.get('jobId') else None
    return wait(created, 'new terminal job')

def finish(id):
    wait(lambda: job(id) if job(id)[0] not in ('queued', 'running', 'stopping') else None, 'job completion')
    directory = 'files/terminal-jobs/' + id
    report = document(directory + '/report.json')
    for stream in ('stdout', 'stderr'):
        data = read(directory + '/' + stream + '.log')
        assert len(data) == report[stream]['bytes']
        assert hashlib.sha256(data).hexdigest() == report[stream]['sha256']
    worker = document(directory + '/worker.json')['pid']
    wait(lambda: gone(worker), 'worker exit')
    return report

def gone(pid):
    result = subprocess.run(['adb', '-s', SERIAL, 'shell', shlex.join(['run-as', PACKAGE, 'test', '-d', '/proc/' + str(pid)])], env=ENV, capture_output=True)
    return result.returncode != 0

def save():
    (ROOT / 'evidence/delivery23-runtime-tests.json').write_text(json.dumps(RESULTS, indent=2) + '\n')

def main():
    launch('MainActivity')
    with db() as connection:
        before = json.loads((ROOT / 'evidence/delivery23-before.json').read_text())
        assert connection.execute('PRAGMA user_version').fetchone()[0] == 4
        assert [list(row) for row in connection.execute('SELECT id,recorded_at,kind,message FROM events WHERE id<=? ORDER BY id', (before['events'][-1][0],))] == before['events']
        assert [list(row) for row in connection.execute('SELECT id,label,state,detail,updated_at,session_id,started_at FROM jobs WHERE kind=\'jadx\' ORDER BY id')] == before['jobs']
        for name in ('settings', 'profiles'):
            assert [list(row) for row in connection.execute('SELECT * FROM ' + name + ' ORDER BY ' + ('name' if name == 'settings' else 'id'))] == before[name]
        RESULTS['migration'] = {'schema': 4, 'eventsRetained': len(before['events']), 'jadxJobsRetained': len(before['jobs']), 'settingsAndProfilesRetained': True}
    save()
    id = begin('printf \'shell=%s\\n\' "$KSH_VERSION"; printf \'cwd=%s\\n\' "$PWD"; printf \'env=%s\\n\' "$WW_PROBE"; printf \'one\\ntwo\\n\' | wc -l; printf \'stderr-proof\\n\' >&2; printf \'file-proof\\n\' > "$WORKSHOP_JOB/probe.txt"; exit 7', environment=json.dumps({'WW_PROBE': 'embedded-✓'}))
    report = finish(id)
    out = read('files/terminal-jobs/' + id + '/stdout.log').decode()
    assert report['exitCode'] == 7 and report['status'] == 'failed'
    assert out.splitlines()[-1].strip() == '2'
    assert 'MIRBSD KSH R59' in out and 'env=embedded-✓' in out and 'cwd=' + report['cwd'] in out
    assert read('files/terminal-jobs/' + id + '/stderr.log') == b'stderr-proof\n'
    assert read('files/terminal-jobs/' + id + '/probe.txt') == b'file-proof\n'
    RESULTS['command'] = {'jobId': id, 'report': report, 'stdout': out, 'fileVerified': True}
    save()
    custom = report['cwd'].rsplit('/', 1)[0]
    id = begin('test \"$PWD\" = \"$EXPECT_CWD\" || exit 19; IFS= read -r value; printf \'got=%s\\n\' "$value"; cat; printf \'eof\\n\'', cwd=custom, environment=json.dumps({'EXPECT_CWD': custom}))
    wait(lambda: job(id)[0] == 'running', 'stdin command running')
    launch('TerminalActivity', action='input', id=id, text='hello-✓\nsecond-line\n')
    wait(lambda: 'got=hello-✓' in read('files/terminal-jobs/' + id + '/stdout.log').decode(), 'stdin output')
    launch('TerminalActivity', action='eof', id=id)
    report = finish(id)
    out = read('files/terminal-jobs/' + id + '/stdout.log').decode()
    assert out == 'got=hello-✓\nsecond-line\neof\n' and report['exitCode'] == 0
    assert report['cwd'] == custom
    RESULTS['stdinEof'] = {'jobId': id, 'report': report, 'stdout': out, 'stdinLog': read('files/terminal-jobs/' + id + '/stdin.log').decode()}
    save()
    id = begin('sleep 600 &\nprintf \'child=%s\\n\' "$!"\nwait')
    wait(lambda: job(id)[0] == 'running', 'cancel command running')
    child = int(wait(lambda: read('files/terminal-jobs/' + id + '/stdout.log').decode().split('child=')[-1].strip(), 'child pid'))
    identity = document('files/terminal-jobs/' + id + '/process.json')
    launch('TerminalActivity', action='stop', id=id)
    report = finish(id)
    assert report['status'] == 'stopped'
    for pid in (child, identity['shellPid'], identity['supervisorPid']):
        wait(lambda: gone(pid), 'cancel descendant removed')
    RESULTS['cancel'] = {'jobId': id, 'report': report, 'childPid': child, 'allProcessesGone': True}
    save()
    id = begin('sleep 600 &\nprintf \'child=%s\\n\' "$!"\nwait')
    wait(lambda: job(id)[0] == 'running', 'recovery command running')
    child = int(wait(lambda: read('files/terminal-jobs/' + id + '/stdout.log').decode().split('child=')[-1].strip(), 'recovery child pid'))
    identity = document('files/terminal-jobs/' + id + '/process.json')
    worker = document('files/terminal-jobs/' + id + '/worker.json')['pid']
    shell('run-as', PACKAGE, 'touch', 'files/terminal-jobs/' + id + '/report.json.part')
    shell('run-as', PACKAGE, 'kill', '-9', str(worker))
    for pid in (child, identity['shellPid'], identity['supervisorPid']):
        wait(lambda: gone(pid), 'parent-death cleanup')
    launch('TerminalActivity', action='recover')
    wait(lambda: job(id)[0] == 'interrupted', 'interrupted job recovery')
    assert not read('files/terminal-jobs/' + id + '/stdout.log').startswith(b'cat:')
    result = subprocess.run(['adb', '-s', SERIAL, 'shell', shlex.join(['run-as', PACKAGE, 'test', '-e', 'files/terminal-jobs/' + id + '/report.json.part'])], env=ENV, capture_output=True)
    assert result.returncode != 0
    RESULTS['recovery'] = {'jobId': id, 'workerPid': worker, 'process': identity, 'childPid': child, 'allProcessesGone': True, 'partialRemoved': True, 'logsRetained': True}
    save()
    launch('FilesActivity', action='check')
    check = wait(lambda: document('files/files-check.json'), 'file tools check')
    assert check['passed'] and len(check['operations']) == 7
    RESULTS['fileTools'] = check
    save()
    token = str(uuid.uuid4())
    partial = custom + '/workspace/.workshop-' + token + '.part'
    ledger = 'files/file-pending/' + token + '.json'
    shell('run-as', PACKAGE, 'touch', partial)
    with db() as connection:
        session = connection.execute("SELECT value FROM settings WHERE name='diagnostic_session'").fetchone()[0]
    value = json.dumps({'id': token, 'part': partial, 'document': '', 'completed': False, 'session': session}).encode()
    subprocess.run(['adb', '-s', SERIAL, 'shell', shlex.join(['run-as', PACKAGE, 'sh', '-c', 'cat > ' + shlex.quote(ledger)])], env=ENV, input=value, capture_output=True, check=True)
    launch('MainActivity')
    def missing(path):
        return subprocess.run(['adb', '-s', SERIAL, 'shell', shlex.join(['run-as', PACKAGE, 'test', '-e', path])], env=ENV, capture_output=True).returncode != 0
    wait(lambda: missing(partial) and missing(ledger), 'owned file partial recovery')
    with db() as connection:
        trace = connection.execute("SELECT level,module,phase,session_id FROM events WHERE operation_id=?", (token,)).fetchone()
        assert trace == ('WARN', 'files', 'interrupted', session)
    RESULTS['fileRecovery'] = {'operationId': token, 'ownedPartialRemoved': True, 'ledgerRemoved': True, 'trace': trace, 'method': 'Replay interrupted-operation ledger, then execute app recovery'}
    save()
    launch('DiagnosticsActivity', action='preview-export', module='terminal')
    manifest = wait(lambda: document('files/diagnostics-preview.json') if document('files/diagnostics-preview.json').get('filters', {}).get('module') == 'terminal' else None, 'terminal diagnostic export')
    data = read('cache/diagnostics-preview.zip')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        manifest = json.loads(archive.read('manifest.json'))
        events = [json.loads(row) for row in archive.read('events.jsonl').splitlines()]
        assert len(events) == manifest['eventCount'] and all(row['module'] == 'terminal' for row in events)
        for file in manifest['files']:
            payload = archive.read(file['path'])
            assert len(payload) == file['bytes'] and hashlib.sha256(payload).hexdigest() == file['sha256']
        assert any(file['path'].endswith('/report.json') for file in manifest['files'])
    (ROOT / 'evidence/delivery23-export.zip').write_bytes(data)
    RESULTS['export'] = {'events': len(events), 'files': len(manifest['files']), 'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data), 'allAttachmentHashesVerified': True}
    save()
    print(json.dumps({'status': 'pass', 'checks': list(RESULTS), 'export': RESULTS['export']}), flush=True)

if __name__ == '__main__':
    main()
