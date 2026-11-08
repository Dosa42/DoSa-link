import ast
import json
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = '{http://schemas.android.com/apk/res/android}'

def main():
    for path in (ROOT / 'tools').glob('*.py'):
        ast.parse(path.read_text(), filename=str(path))
    manifest = ET.parse(ROOT / 'src/AndroidManifest.xml').getroot()
    app = manifest.find('application')
    components = []
    for node in list(app):
        if node.tag not in ['activity', 'service']:
            continue
        name = node.get(NS + 'name')
        if not (ROOT / 'src' / (name.replace('.', '/') + '.java')).is_file():
            raise ValueError('Component source missing: ' + name)
        components.append(name)
    if len(components) != len(set(components)):
        raise ValueError('Duplicate application component')
    compiled = False
    if os.environ.get('GITHUB_ACTIONS') == 'true':
        android = Path(os.environ['ANDROID_HOME']) / 'platforms/android-35/android.jar'
        with tempfile.TemporaryDirectory(prefix='workshop-preflight-') as destination:
            subprocess.run(['javac', '--release', '8', '-g', '-classpath', str(android), '-d', destination, *map(str, sorted((ROOT / 'src').rglob('*.java')))], check=True)
        compiled = True
    print(json.dumps({'preflight': 'pass', 'components': len(components), 'androidCompilation': compiled}))

if __name__ == '__main__':
    main()
