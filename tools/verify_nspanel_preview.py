from functools import partial
import http.server
import json
from pathlib import Path
import select
import subprocess
import threading

ROOT = Path(__file__).resolve().parent


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


def main():
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), partial(QuietHandler, directory=str(ROOT)))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    log = (ROOT / 'rea-preview.stderr').open('w')
    process = subprocess.Popen(['/usr/bin/python3', '/home/dosa/.local/share/rea-toolchain/launch.py', 'mcp'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log, text=True)

    def call(identifier, method, params):
        process.stdin.write(json.dumps({'jsonrpc': '2.0', 'id': identifier, 'method': method, 'params': params}) + '\n')
        process.stdin.flush()
        while True:
            if not select.select([process.stdout], [], [], 60)[0]:
                raise TimeoutError(method)
            line = process.stdout.readline()
            if not line:
                raise RuntimeError('REA process closed')
            result = json.loads(line)
            if result.get('id') == identifier:
                return result

    try:
        initialize = call(1, 'initialize', {'protocolVersion': '2025-03-26', 'capabilities': {}, 'clientInfo': {'name': 'nspanel-preview-check', 'version': '1'}})
        process.stdin.write(json.dumps({'jsonrpc': '2.0', 'method': 'notifications/initialized'}) + '\n')
        process.stdin.flush()
        actions = [
            {'step_id': 'ready', 'action': 'wait_for', 'locator': {'kind': 'css', 'selector': '#objects button:nth-child(4)'}, 'state': 'visible'},
            {'step_id': 'main-page', 'action': 'select_option', 'locator': {'kind': 'css', 'selector': '#page'}, 'value': {'source': 'literal', 'value': '1'}},
            {'step_id': 'temperature', 'action': 'click', 'locator': {'kind': 'css', 'selector': '#objects button:nth-child(5)'}},
            {'step_id': 'event-code', 'action': 'click', 'locator': {'kind': 'css', 'selector': '#details details:first-of-type summary'}},
            {'step_id': 'find-picture', 'action': 'fill', 'locator': {'kind': 'css', 'selector': '#filter'}, 'value': {'source': 'literal', 'value': '358'}},
            {'step_id': 'open-picture', 'action': 'click', 'locator': {'kind': 'css', 'selector': '#pictures img'}},
            {'step_id': 'picture-visible', 'action': 'wait_for', 'locator': {'kind': 'css', 'selector': '#dialog[open]'}, 'state': 'visible'},
            {'step_id': 'close-picture', 'action': 'click', 'locator': {'kind': 'css', 'selector': '#close'}},
        ]
        request = {'browser': {'mode': 'launch', 'executable_path': '/usr/bin/chromium', 'headless': True}, 'start_url': {'url': 'http://127.0.0.1:' + str(server.server_port) + '/screen-preview.html', 'query': []}, 'actions': actions, 'capture': {'at_end': ['screenshot', 'accessibility'], 'events': ['page-errors'], 'network': {'request_body': False, 'response_body': False, 'header_values': False}}}
        result = call(2, 'tools/call', {'name': 'capture_browser_scenario', 'arguments': request})
        (ROOT / 'rea-preview-verification.json').write_text(json.dumps({'server': initialize.get('result', {}).get('serverInfo'), 'request': request, 'response': result}, indent=2) + '\n')
        evidence = result.get('result', {}).get('structuredContent', {})
        normalized = evidence.get('normalized_result', {})
        steps = normalized.get('steps', [])
        passed = bool(steps) and all(x.get('status') == 'completed' for x in steps) and normalized.get('completeness', {}).get('status') == 'complete'
        print(json.dumps({'written': 'rea-preview-verification.json', 'passed': passed, 'steps': [{'id': x['step_id'], 'status': x['status'], 'error': x.get('error')} for x in steps], 'cleanup': normalized.get('browser', {}).get('cleanup')}), flush=True)
    finally:
        server.shutdown()
        server.server_close()
        process.stdin.close()
        process.terminate()
        process.wait(timeout=10)
        log.close()


if __name__ == '__main__':
    main()
