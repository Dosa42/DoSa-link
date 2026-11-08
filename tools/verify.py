import hashlib
import json
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile
from androguard.core.axml import ARSCParser, AXMLPrinter
from loguru import logger

logger.disable('androguard')
ROOT = Path(__file__).resolve().parents[1]
BASE = Path(os.environ.get('WORKSHOP_BASE', ROOT / '.inputs/baseline' if os.environ.get('GITHUB_ACTIONS') == 'true' else ROOT.parent / 'ewelink-analysis-20261107'))
NS = '{http://schemas.android.com/apk/res/android}'

def sha(data):
    return hashlib.sha256(data).hexdigest()

def resource_ids(data):
    parser = ARSCParser(data)
    result = {}
    locales = set()
    for package in parser.get_packages_names():
        for locale in parser.get_locales(package):
            locales.add(locale.replace('\x00', ''))
            xml = ET.fromstring(parser.get_public_resources(package, locale))
            for entry in xml:
                result[entry.attrib['id']] = [entry.attrib['type'], entry.attrib['name']]
    return result, sorted(locales)

def main():
    final = ROOT / 'dist/wireless-workshop.apk'
    expected_ids = {}
    expected_resources = set()
    expected_members = {}
    categories = {'dex': 0, 'hermes': 0, 'native': 0}
    baseline = json.loads((BASE / 'evidence/artifact-hashes-before.json').read_text())
    original_apks = []
    for record in baseline:
        path = BASE / 'artifacts' / Path(record['path']).name
        with path.open('rb') as source:
            actual = hashlib.file_digest(source, 'sha256').hexdigest()
        if actual != record['sha256']:
            raise ValueError('Original APK changed: ' + path.name)
        original_apks.append({'file': path.name, 'sha256': actual, 'unchanged': True})
        with zipfile.ZipFile(path) as archive:
            ids, locales = resource_ids(archive.read('resources.arsc')) if 'resources.arsc' in archive.namelist() else ({}, [])
            for identity, name in ids.items():
                if identity in expected_ids and expected_ids[identity] != name:
                    raise ValueError('Conflicting original resource ID: ' + identity)
                expected_ids[identity] = name
            for member in archive.infolist():
                if member.is_dir():
                    continue
                category = None
                if re.fullmatch(r'classes\d*\.dex', member.filename):
                    category = 'dex'
                elif member.filename.startswith('lib/arm64-v8a/') and member.filename.endswith('.so'):
                    category = 'native'
                elif member.filename.startswith('assets/'):
                    with archive.open(member) as stream:
                        if stream.read(8) == bytes.fromhex('c61fbc03c103191f'):
                            category = 'hermes'
                if category:
                    expected_members[member.filename] = {'sha256': sha(archive.read(member)), 'category': category}
                    categories[category] += 1
                if member.filename.startswith('res/'):
                    expected_resources.add(sha(archive.read(member)))
    with zipfile.ZipFile(final) as archive:
        mismatches = [name for name, record in expected_members.items() if name not in archive.namelist() or sha(archive.read(name)) != record['sha256']]
        if mismatches:
            raise ValueError('Changed/missing preserved code: ' + repr(mismatches))
        actual_ids, locales = resource_ids(archive.read('resources.arsc'))
        changed_ids = [identity for identity, name in expected_ids.items() if actual_ids.get(identity) != name]
        if changed_ids:
            raise ValueError('Changed/missing resource IDs: ' + repr(changed_ids[:20]))
        payloads = {sha(archive.read(member)) for member in archive.infolist() if member.filename.startswith('res/') and not member.is_dir()}
        missing_resources = sorted(expected_resources - payloads)
        manifest = ET.fromstring(AXMLPrinter(archive.read('AndroidManifest.xml')).get_xml())
        application = manifest.find('application')
        activities = application.findall('activity')
        components = {name: len(application.findall(name)) for name in ['activity', 'service', 'receiver', 'provider']}
        if manifest.get('package') != 'com.ayrvokan.wireless' or application.get(NS + 'name') != 'com.ayrvokan.wireless.WorkshopApplication':
            raise ValueError('Unexpected private app identity/startup')
        if components != {'activity': 6, 'service': 5, 'receiver': 0, 'provider': 0} or activities[0].get(NS + 'name') != 'com.ayrvokan.wireless.MainActivity':
            raise ValueError('Unexpected startup components')
        if {item.get(NS + 'name') for item in activities} != {'com.ayrvokan.wireless.MainActivity', 'com.ayrvokan.wireless.JadxActivity', 'com.ayrvokan.wireless.DiagnosticsActivity', 'com.ayrvokan.wireless.TerminalActivity', 'com.ayrvokan.wireless.FilesActivity', 'com.ayrvokan.wireless.DebugActivity'}:
            raise ValueError('Unexpected activities')
        service = next(item for item in application.findall('service') if item.get(NS + 'name') == 'com.ayrvokan.wireless.JadxService')
        if service.get(NS + 'name') != 'com.ayrvokan.wireless.JadxService' or service.get(NS + 'process') != ':jadx' or service.get(NS + 'exported') != 'false':
            raise ValueError('Unexpected JADX worker')
        if any('requiredSplit' in key or key == 'split' for key in manifest.attrib):
            raise ValueError('Output still requires a split')
        permissions = [item.get(NS + 'name') for item in manifest.findall('uses-permission')]
        if 'android.permission.BLUETOOTH_ADVERTISE' not in permissions:
            raise ValueError('Bluetooth advertising permission lost')
        if any('AD_ID' in name or 'ADSERVICES' in name or 'BILLING' in name for name in permissions):
            raise ValueError('Commercial permission retained')
        catalog = json.loads(archive.read('assets/workshop/catalog.json'))
        if categories != {'dex': 6, 'hermes': 6, 'native': 100}:
            raise ValueError('Unexpected baseline code counts')
        if catalog['bridgeDeclarations'] != 1101 or len(catalog['libraries']) != 100:
            raise ValueError('Packaged catalog mismatch')
        tool = json.loads(archive.read('assets/workshop/jadx/tool.json'))
        for name, expected in tool['files'].items():
            member = 'lib/arm64-v8a/' + Path(name).name if name.startswith('native/') else 'assets/workshop/jadx/' + name
            if sha(archive.read(member)) != expected:
                raise ValueError('Bundled engine input changed: ' + name)
        if tool['version'] != '1.5.6' or tool.get('backend') != 'jni-hotspot' or len(tool['pluginProviders']) < 3:
            raise ValueError('JADX engine/plugin manifest mismatch')
        terminal = json.loads(archive.read('assets/workshop/terminal/tool.json'))
        for name, expected in terminal['files'].items():
            member = 'lib/arm64-v8a/' + name if name.endswith('.so') else 'assets/workshop/terminal/' + name
            data = archive.read(member)
            if sha(data) != expected:
                raise ValueError('Bundled terminal file mismatch: ' + name)
            if name.endswith('.so') and (data[:4] != b'\x7fELF' or int.from_bytes(data[18:20], 'little') != 183):
                raise ValueError('Terminal executable is not ARM64 ELF: ' + name)
        worker = next(item for item in application.findall('service') if item.get(NS + 'name') == 'com.ayrvokan.wireless.TerminalService')
        if worker.get(NS + 'process') != ':terminal' or worker.get(NS + 'exported') != 'false':
            raise ValueError('Unexpected terminal worker')
        debugger = json.loads(archive.read('assets/workshop/debugger/tool.json'))
        for name, expected in debugger['files'].items():
            member = 'lib/arm64-v8a/' + name if name.endswith('.so') else 'assets/workshop/debugger/' + name
            data = archive.read(member)
            if sha(data) != expected:
                raise ValueError('Bundled debugger mismatch: ' + name)
            if name.endswith('.so') and (data[:4] != b'\x7fELF' or int.from_bytes(data[18:20], 'little') != 183):
                raise ValueError('Debugger native component is not ARM64 ELF: ' + name)
        for component, process in [('DebugService', ':debugger'), ('DebugProbeService', ':debugprobe'), ('NativeGuardianService', ':debugguardian')]:
            worker = next(item for item in application.findall('service') if item.get(NS + 'name') == 'com.ayrvokan.wireless.' + component)
            if worker.get(NS + 'process') != process or worker.get(NS + 'exported') != 'false' or int(worker.get(NS + 'foregroundServiceType', '0'), 0) != 0x40000000 or worker.find('property') is None:
                raise ValueError('Unexpected debugger component: ' + component)
        debug_activity = next(item for item in activities if item.get(NS + 'name') == 'com.ayrvokan.wireless.DebugActivity')
        if debug_activity.get(NS + 'process') != ':debugger':
            raise ValueError('Debugger UI is not isolated from its targets')
        report = {'bundled_debugger': debugger, 'bundled_terminal': terminal, 'bundled_runtime_backend': tool['backend'], 'bundled_runtime_version': tool['runtimeVersion'], 'bundled_native_bridge_sha256': tool['nativeBridgeSha256'], 'bundled_jadx_version': tool['version'], 'bundled_jadx_engine_sha256': tool['engineSha256'], 'bundled_tool_files_verified': len(tool['files']), 'status': 'pass' if not missing_resources else 'resource-payload-review-required', 'original_apks': original_apks, 'byte_identical_original_code': categories, 'code_files_checked': len(expected_members), 'resource_ids_preserved': len(expected_ids), 'resource_ids_changed_or_missing': changed_ids, 'distinct_resource_payloads_checked': len(expected_resources), 'resource_payload_hashes_missing': missing_resources, 'locales': locales, 'private_startup_components': components, 'application': application.get(NS + 'name'), 'package': manifest.get('package'), 'single_apk': True, 'catalog_classes': len(catalog['classes']), 'catalog_bridge_declarations': catalog['bridgeDeclarations'], 'runtime_executed': False}
        (ROOT / 'evidence/preservation.json').write_text(json.dumps(report, indent=2) + '\n')
        (ROOT / 'evidence/packaged-manifest.xml').write_bytes(AXMLPrinter(archive.read('AndroidManifest.xml')).get_xml())
    if missing_resources:
        raise ValueError('Resource payload preservation requires review; see evidence/preservation.json')
    print(json.dumps({key: value for key, value in report.items() if key not in ['original_apks', 'resource_payload_hashes_missing', 'locales']}))

if __name__ == '__main__':
    main()
