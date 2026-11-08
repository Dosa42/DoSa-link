import ast
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import yaml

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
RULE = Path.home() / '.codex/RULES/no-comments.json'
spec = importlib.util.spec_from_file_location('policy_scanner', Path.home() / '.codex/goal-core/comment-policy/scanner.py')
scanner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scanner)

def main():
    old = json.loads((ROOT / 'evidence/code-policy.json').read_text())
    files = {Path(item['path']) for item in old['results'] if '::' not in item['path']}
    files.update(ROOT / name for name in subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard'], cwd=ROOT, text=True).splitlines() if Path(name).suffix in ['.py', '.java', '.c', '.cpp', '.gradle', '.xml', '.yml', '.yaml'])
    results = []
    for path in sorted(files):
        text = path.read_text()
        language = 'c' if path.suffix in ['.java', '.c', '.cpp', '.gradle'] else 'python' if path.suffix == '.py' else 'data'
        item = scanner.scan(path, text, language)
        item['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        if language == 'python':
            ast.parse(text, filename=str(path))
        if path.suffix == '.xml':
            ET.fromstring(text)
            if '<!--' in text:
                item['findings'].append('XML comment')
        if path.suffix in ['.yaml', '.yml']:
            document = yaml.safe_load(text)
            if re.search(r'^\s*#', text, re.M):
                item['findings'].append('YAML comment')
            for job, value in document.get('jobs', {}).items():
                for index, step in enumerate(value.get('steps', [])):
                    if 'run' in step:
                        results.append(scanner.scan(str(path) + '::' + job + ':' + str(index), step['run'], 'shell'))
        results.append(item)
    receipt = {'rule_path': str(RULE), 'ok': all(not item['findings'] and not item['errors'] for item in results), 'results': results, 'findings': sum(len(item['findings']) for item in results), 'errors': sum(len(item['errors']) for item in results)}
    (ROOT / 'evidence/code-policy.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps({key: receipt[key] for key in ['ok', 'findings', 'errors']} | {'checks': len(results)}))
    raise SystemExit(0 if receipt['ok'] else 1)

if __name__ == '__main__':
    main()
