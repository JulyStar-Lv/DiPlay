#!/usr/bin/env python3
"""Keep new projection contracts and OEM drivers out of each other's implementation modules."""
from pathlib import Path
import re
import sys
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
errors = []


def reject_imports(directory, prefixes):
    for path in (ROOT / directory).rglob('*'):
        if path.suffix not in {'.kt', '.java'}:
            continue
        for line in path.read_text(encoding='utf-8').splitlines():
            if re.search(r'(?<![\w.])(?:' + '|'.join(re.escape(p) for p in prefixes) + r')', line):
                errors.append(f'{path.relative_to(ROOT)}: {line.strip()}')


reject_imports('display-api/src/main', ['android.', 'org.json.', 'com.shilapi.xcertplay.', 'com.shilapi.vehicle.'])
reject_imports('display-android/src/main', ['com.shilapi.xcertplay.', 'com.shilapi.vehicle.'])
reject_imports('vehicle-mzd/src/main', ['com.shilapi.xcertplay.'])
reject_imports('shared/src/main', ['com.shilapi.vehicle.'])
reject_imports('common/src/main', ['com.shilapi.vehicle.'])
reject_imports('shared/src/main/java/com/shilapi/xcertplay/projection', ['com.shilapi.xcertplay.hud.'])
reject_imports('mazda/src/main', ['com.shilapi.xcertplay.mazda.hudbench.'])

for module in ['display-api', 'display-android', 'vehicle-mzd', 'shared', 'common', 'mobile', 'automotive', 'samples/home', 'samples/maphost']:
    for path in (ROOT / module).glob('build.gradle*'):
        dependencies = re.findall(r'''project\s*\(?\s*['"]:([^'"]+)['"]''', path.read_text(encoding='utf-8'))
        forbidden = {'vehicle-mzd'}
        if module in {'display-api', 'display-android', 'vehicle-mzd'}:
            forbidden |= {'shared', 'common', 'mobile', 'mazda'}
        for dependency in dependencies:
            if dependency in forbidden:
                errors.append(f'{path.relative_to(ROOT)}: forbidden dependency {dependency}')

if errors:
    raise SystemExit('Vehicle display boundaries violated:\n' + '\n'.join(errors))
print('Vehicle display boundaries passed: neutral API, isolated OEM driver, Mazda-only assembly.')

if '--apks' in sys.argv:
    for module, application, expected in [
        ('mobile', 'mobile', False), ('samples/home', 'home', False),
        ('samples/maphost', 'maphost', False), ('mazda', 'mazda', True),
    ]:
        path = ROOT / module / f'build/outputs/apk/debug/{application}-debug.apk'
        with ZipFile(path) as apk:
            has_driver = any(b'Lcom/shilapi/vehicle/mzd/' in apk.read(name)
                             for name in apk.namelist() if re.fullmatch(r'classes\d*\.dex', name))
            has_bench = any(b'Lcom/shilapi/xcertplay/mazda/hudbench/HudBenchActivity;' in apk.read(name)
                            for name in apk.namelist() if re.fullmatch(r'classes\d*\.dex', name))
            manifest = apk.read('AndroidManifest.xml')
            marker = 'com.shilapi.vehicle.mzd.HUD_ENABLED'
            has_metadata = any(marker.encode(encoding) in manifest for encoding in ('utf-8', 'utf-16le'))
        if has_driver != expected or has_metadata != expected or has_bench != expected:
            raise SystemExit(f'{application}: unexpected OEM driver/Manifest inclusion')
        print(f'{application}: vehicle-mzd classes={has_driver}, OEM metadata={has_metadata}, debug bench={has_bench}')

if '--release' in sys.argv:
    manifest = ROOT / 'mazda/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml'
    classes = ROOT / 'mazda/build/intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes'
    if not manifest.is_file() or not classes.is_dir():
        raise SystemExit('Build :mazda:compileReleaseKotlin :mazda:processReleaseManifest first')
    if 'hudbench' in manifest.read_text(encoding='utf-8') or any('hudbench' in str(path.relative_to(classes)) for path in classes.rglob('*.class')):
        raise SystemExit('HUD bench leaked into release classes/Manifest')
    print('mazda release: no bench classes or Manifest entry')
