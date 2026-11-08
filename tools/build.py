import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASE = Path(os.environ.get('WORKSHOP_BASE', ROOT / '.inputs/baseline' if os.environ.get('GITHUB_ACTIONS') == 'true' else ROOT.parent / 'ewelink-analysis-20261107'))
WORK = ROOT / '.build'
SDK = Path(os.environ.get('ANDROID_HOME', Path.home() / 'android-sdk'))
ANDROID = SDK / 'platforms/android-35/android.jar'
EDITOR = ROOT / 'toolchain/APKEditor-1.4.9.jar'
EDITOR_SHA = 'a9cd40df818845456be6d696de6110c89edf4b0a0580cb83438ed6b25a366e67'
NS = '{http://schemas.android.com/apk/res/android}'
ET.register_namespace('android', NS[1:-1])

def digest(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()

def run(label, command):
    print(json.dumps({'stage': label}), flush=True)
    with (ROOT / 'evidence/build.log').open('a') as log:
        log.write('\n' + label + '\n')
        log.flush()
        result = subprocess.run([str(part) for part in command], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        tail = (ROOT / 'evidence/build.log').read_text(errors='replace')[-3500:]
        raise RuntimeError(label + ' failed: ' + tail)

def catalog():
    managed = json.loads((BASE / 'evidence/foundation-managed-modules.json').read_text())
    native = json.loads((BASE / 'evidence/foundation-native-modules.json').read_text())
    labels = {
        'device_lan_discovery_transport': 'Local network discovery',
        'device_ble_provisioning_wifi': 'Bluetooth and Wi-Fi setup',
        'device_matter_controller_discovery': 'Matter devices',
        'device_camera_ipc_webrtc': 'Cameras and video transport',
        'device_infrared_sdk': 'Infrared controls',
        'device_widgets_shortcuts': 'Widgets and shortcuts',
        'device_wear': 'Wearable devices',
        'integration_google_home': 'Home integrations',
        'integration_account_social': 'Original account integrations',
        'optional_commerce': 'Original commerce interfaces',
        'optional_telemetry_support_push': 'Original support and push interfaces',
        'runtime_dynamic_bundle_bridge': 'Runtime and bundle loading',
        'runtime_phone_wireless_and_platform': 'Phone wireless interfaces',
        'runtime_diagnostics_local': 'Local diagnostics',
        'runtime_phone_capture_media': 'Phone media interfaces',
        'runtime_location_automation': 'Location interfaces',
        'runtime_storage_files_network': 'Files and network utilities',
        'runtime_framework_and_ui': 'Framework and interface components'
    }
    value = {
        'baselineVersion': '5.30.0',
        'bridgeDeclarations': sum(module['declared_ReactMethod_count'] for module in managed['modules']),
        'groups': [{'id': group['id'], 'label': labels.get(group['id'], group['id']), 'classes': group['owner_class_count'], 'methods': group['ReactMethod_declaration_count']} for group in managed['groups']],
        'classes': [{'name': module['class_name'], 'group': module['group'], 'methods': module['declared_ReactMethod_count']} for module in managed['modules']],
        'libraries': [{'name': module['filename'], 'role': module['role'], 'sha256': module['sha256']} for module in native['modules']]
    }
    if value['bridgeDeclarations'] != 1101 or len(value['libraries']) != 100:
        raise ValueError('Module inventory mismatch')
    return json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode()

def manifest():
    source = ET.parse(BASE / 'decompiled/resources/AndroidManifest.xml').getroot()
    target = ET.parse(ROOT / 'src/AndroidManifest.xml')
    root = target.getroot()
    excluded = {'com.android.vending.BILLING', 'com.google.android.gms.permission.AD_ID', 'com.google.android.c2dm.permission.RECEIVE', 'android.permission.GET_ACCOUNTS'}
    retained = [item.get(NS + 'name') for item in root.findall('uses-permission')]
    removed = []
    index = 1
    for permission in source.findall('uses-permission'):
        name = permission.get(NS + 'name', '')
        if name in excluded or not name.startswith('android.permission.') or name.startswith('android.permission.ACCESS_ADSERVICES'):
            removed.append(name)
            continue
        if name in retained:
            continue
        root.insert(index, permission)
        index += 1
        retained.append(name)
    target.write(WORK / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
    (ROOT / 'evidence/manifest-changes.json').write_text(json.dumps({'package': root.get('package'), 'application': 'android.app.Application', 'activities': ['com.ayrvokan.wireless.MainActivity', 'com.ayrvokan.wireless.JadxActivity', 'com.ayrvokan.wireless.DiagnosticsActivity'], 'original_component_entry_points_registered': 0, 'providers': 0, 'services': 1, 'receivers': 0, 'retained_device_permissions': retained, 'omitted_permissions': removed}, indent=2) + '\n')

def signing_key():
    private = ROOT / 'private'
    private.mkdir(mode=0o700, exist_ok=True)
    private.chmod(0o700)
    key = private / 'workshop.p12'
    password = private / 'store-password'
    if not key.exists():
        password.write_text(secrets.token_urlsafe(32) + '\n')
        password.chmod(0o600)
        run('create-private-signing-key', ['keytool', '-genkeypair', '-keystore', key, '-storetype', 'PKCS12', '-storepass:file', password, '-keypass:file', password, '-alias', 'workshop', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Wireless Workshop, O=Personal'])
        key.chmod(0o600)
    return key, password

def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise RuntimeError('APK builds run only in GitHub Actions')
    for folder in ['evidence', 'dist', 'toolchain']:
        (ROOT / folder).mkdir(exist_ok=True)
    WORK.mkdir(exist_ok=True)
    for name in ['classes', 'dex']:
        folder = WORK / name
        if folder.exists():
            shutil.rmtree(folder)
        folder.mkdir()
    if digest(EDITOR) != EDITOR_SHA:
        raise ValueError('APKEditor digest differs from the pinned release')
    baseline = json.loads((BASE / 'evidence/artifact-hashes-before.json').read_text())
    for item in baseline:
        path = BASE / 'artifacts' / Path(item['path']).name
        if digest(path) != item['sha256']:
            raise ValueError('Baseline changed: ' + path.name)
    inputs = {Path(item['path']).name: item['sha256'] for item in baseline}
    merged = WORK / 'merged.apk'
    cache = WORK / 'merge-inputs.json'
    merge_inputs = {'apks': inputs, 'merger_sha256': digest(ROOT / 'tools/MergeInputs.java')}
    if not merged.exists() or not cache.exists() or json.loads(cache.read_text()) != merge_inputs:
        merged.unlink(missing_ok=True)
        (WORK / 'merger').mkdir(exist_ok=True)
        run('compile-resource-preserving-merger', ['javac', '-classpath', EDITOR, '-d', WORK / 'merger', ROOT / 'tools/MergeInputs.java'])
        run('merge-original-splits', ['java', '-Xmx768m', '-classpath', str(EDITOR) + os.pathsep + str(WORK / 'merger'), 'MergeInputs', BASE / 'artifacts', merged])
        cache.write_text(json.dumps(merge_inputs, sort_keys=True))
    manifest()
    run('compile-private-manifest', ['aapt2', 'link', '--manifest', WORK / 'AndroidManifest.xml', '-I', ANDROID, '-o', WORK / 'manifest.apk'])
    sources = sorted((ROOT / 'src').rglob('*.java'))
    run('compile-private-entry-code', ['javac', '--release', '8', '-classpath', ANDROID, '-d', WORK / 'classes', *sources])
    run('compile-private-dex', ['d8', '--min-api', '26', '--lib', ANDROID, '--output', WORK / 'dex', *sorted((WORK / 'classes').rglob('*.class'))])
    unsigned = WORK / 'unsigned.apk'
    with zipfile.ZipFile(WORK / 'manifest.apk') as archive:
        binary_manifest = archive.read('AndroidManifest.xml')
    with zipfile.ZipFile(merged) as original, zipfile.ZipFile(unsigned, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6, allowZip64=True) as output:
        highest = 0
        for item in original.infolist():
            match = re.fullmatch(r'classes(\d*)\.dex', item.filename)
            if match:
                highest = max(highest, int(match.group(1) or 1))
            if item.filename == 'AndroidManifest.xml' or item.filename.startswith('META-INF/') or item.filename == 'stamp-cert-sha256':
                continue
            with original.open(item) as source, output.open(item, 'w') as target:
                shutil.copyfileobj(source, target, 1048576)
        output.writestr('AndroidManifest.xml', binary_manifest)
        output.writestr('classes' + str(highest + 1) + '.dex', (WORK / 'dex/classes.dex').read_bytes())
        output.writestr('assets/workshop/catalog.json', catalog())
        for asset in sorted((WORK / 'jadx-seed').rglob('*')):
            if asset.is_file():
                name = str(asset.relative_to(WORK / 'jadx-seed'))
                destination = 'lib/arm64-v8a/' + asset.name if name.startswith('native/') else 'assets/workshop/jadx/' + name
                output.write(asset, destination)
    run('align-single-apk', ['zipalign', '-f', '-P', '16', '4', unsigned, WORK / 'aligned.apk'])
    unsigned.unlink()
    key, password = signing_key()
    signed = WORK / 'signed.apk'
    run('sign-private-apk', ['apksigner', 'sign', '--ks', key, '--ks-key-alias', 'workshop', '--ks-pass', 'file:' + str(password), '--v4-signing-enabled', 'false', '--out', signed, WORK / 'aligned.apk'])
    (WORK / 'aligned.apk').unlink()
    run('verify-private-signature', ['apksigner', 'verify', '--verbose', '--print-certs', signed])
    run('verify-apk-alignment', ['zipalign', '-c', '-P', '16', '4', signed])
    final = ROOT / 'dist/wireless-workshop.apk'
    signed.replace(final)
    receipt = {'package': 'com.ayrvokan.wireless', 'version': '0.3.0', 'artifact': str(final), 'bytes': final.stat().st_size, 'sha256': digest(final), 'baseline_apks': inputs, 'added_dex': 'classes' + str(highest + 1) + '.dex', 'editor': {'version': '1.4.9', 'sha256': EDITOR_SHA}, 'built_at_utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}
    (ROOT / 'evidence/build.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps({'stage': 'apk-built', 'artifact': str(final), 'bytes': receipt['bytes'], 'sha256': receipt['sha256']}), flush=True)

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
