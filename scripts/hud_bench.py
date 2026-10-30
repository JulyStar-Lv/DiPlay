#!/usr/bin/env python3
"""Drive the debug-only DiPlay HUD bench and collect Linux screenshots. Requires only stdlib + adb."""
import argparse
import hashlib
import html
import ipaddress
import json
import os
from pathlib import Path, PurePosixPath
import shlex
import shutil
import subprocess
import sys
import time
import uuid
import zipfile

PACKAGE = 'com.thunder.carplay.mazdatest'
COMPONENT = PACKAGE + '/com.shilapi.xcertplay.mazda.hudbench.HudBenchActivity'


def valid_uuid(value):
    return str(uuid.UUID(value)) == value


def verify_archive(path, run_id):
    with zipfile.ZipFile(path) as archive:
        if len(archive.infolist()) > 4096 or sum(entry.file_size for entry in archive.infolist()) > 512 * 1024 * 1024:
            raise ValueError('Archive exceeds evidence limits')
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate archive entries')
        for name in names:
            relative = PurePosixPath(name)
            if relative.is_absolute() or '..' in relative.parts or '\\' in name:
                raise ValueError('Unsafe archive path')
        manifest = json.loads(archive.read('manifest.json'))
        if len(manifest) != len(names) - 1 or {row['path'] for row in manifest} != set(names) - {'manifest.json'}:
            raise ValueError('Manifest coverage mismatch')
        for row in manifest:
            data = archive.read(row['path'])
            if len(data) != row['bytes'] or hashlib.sha256(data).hexdigest() != row['sha256']:
                raise ValueError('Evidence digest mismatch: ' + row['path'])
        report = json.loads(archive.read('report.json'))
        if report['runId'] != run_id or report['displayVerified'] is not False:
            raise ValueError('Wrong run or unexpected automatic visual approval')
        for capture in report['captures']:
            if capture['status'] == 'captured':
                image = archive.read(capture['image'])
                if hashlib.sha256(image).hexdigest() != capture['imageInfo']['sha256']:
                    raise ValueError('Screenshot digest mismatch')
        return report


def write_review(path, report):
    """An offline image index for agent review. Never infer success from IPC or capture completion."""
    folder = path.parent / 'review'
    folder.mkdir(exist_ok=True)
    cards = []
    with zipfile.ZipFile(path) as archive:
        for capture in report['captures']:
            label = f"{capture['caseId']} / {capture['phase']} / {capture['status']}"
            if capture['status'] == 'captured':
                name = capture['captureId'] + '.png'
                if not valid_uuid(capture['captureId']):
                    raise ValueError('Invalid capture ID')
                (folder / name).write_bytes(archive.read(capture['image']))
                cards.append(f'<figure><figcaption>{html.escape(label)}</figcaption><img src="{name}" loading="lazy"></figure>')
            else:
                cards.append('<p>' + html.escape(label) + '</p>')
    (folder / 'index.html').write_text('<!doctype html><meta charset="utf-8"><title>HUD bench evidence</title>'
        '<style>body{font-family:system-ui;background:#171717;color:#eee}main{display:flex;flex-wrap:wrap}figure{margin:8px}img{width:600px;max-width:95vw}</style>'
        '<h1>HUD 台架证据：待图片审阅</h1><p>执行完成、Binder接受和截图成功均不代表显示通过。</p><main>' + ''.join(cards) + '</main>', encoding='utf-8')


class Adb:
    def __init__(self, executable, serial=None):
        self.prefix = [executable] + (['-s', serial] if serial else [])

    def call(self, *args, timeout=10):
        result = subprocess.run(self.prefix + list(args), capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(result.stderr.decode('utf-8', errors='replace')[-2000:])
        return result.stdout

    def shell(self, *args, timeout=10):
        return self.call('shell', ' '.join(shlex.quote(str(arg)) for arg in args), timeout=timeout)

    def read_json(self, path):
        try:
            data = self.call('exec-out', 'cat', path, timeout=5)
            if len(data) > 2 * 1024 * 1024:
                raise ValueError('Oversized JSON')
            return json.loads(data)
        except (RuntimeError, ValueError, subprocess.TimeoutExpired):
            return None


def serve_capture(adb, request, remote, local, run_id, host):
    capture_id = request.get('captureId', '')
    if request.get('version') != 1 or request.get('runId') != run_id or request.get('screenId') != 0 or not valid_uuid(capture_id):
        raise ValueError('Invalid capture request')
    shared = '/data/nfs/hdcap-' + capture_id + '.png'
    result = {'runId': run_id, 'captureId': capture_id, 'status': 'failed', 'startedAtMillis': int(time.time() * 1000)}
    try:
        # Only the fixed screenshot command is accepted; requests cannot provide a command/path/host.
        adb.shell('wtdbclient', '-y', 'root@' + host,
                  'LayerManagerControl dump screen 0 to /var/update/hdcap-' + capture_id + '.png', timeout=8)
        size = int(adb.shell('stat', '-c', '%s', shared, timeout=3).strip())
        if not 24 <= size <= 8 * 1024 * 1024:
            raise ValueError('Screenshot size outside limit')
        data = adb.call('exec-out', 'cat', shared, timeout=4)
        if len(data) != size or not data.startswith(b'\x89PNG\r\n\x1a\n'):
            raise ValueError('Incomplete/non-PNG screenshot')
        image = local / (capture_id + '.png')
        image.write_bytes(data)
        # A bench Wi-Fi transfer exceeded 4s even for a valid small PNG. Keep the
        # App's finite 20s handshake, but allow that transfer a bounded 8s window.
        adb.call('push', str(image), remote + '/inbox/' + capture_id + '.png', timeout=8)
        result.update(status='captured', sha256=hashlib.sha256(data).hexdigest(), bytes=len(data))
    except (RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
        result['error'] = str(error)
    finally:
        try:
            adb.shell('rm', '-f', shared, timeout=3)
        except (RuntimeError, subprocess.TimeoutExpired) as error:
            result['cleanupError'] = str(error)
    result['completedAtMillis'] = int(time.time() * 1000)
    response = local / (capture_id + '.json')
    response.write_text(json.dumps(result, ensure_ascii=False), encoding='utf-8')
    adb.call('push', str(response), remote + '/inbox/' + capture_id + '.json', timeout=4)
    print(f"{request['caseId']} {request['phase']}: {result['status']}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    default_adb = shutil.which('adb') or str(Path(os.environ.get('ANDROID_HOME', Path.home() / 'AppData/Local/Android/Sdk')) / 'platform-tools/adb.exe')
    parser.add_argument('--adb', default=default_adb)
    parser.add_argument('--serial')
    parser.add_argument('--mode', choices=['snapshot', 'replay', 'both'], default='both')
    parser.add_argument('--suite', choices=['all', 'navigation', 'music', 'phone', 'lifecycle', 'coexistence', 'regression'], default='all')
    parser.add_argument('--linux-host', default='192.168.100.1')
    parser.add_argument('--no-capture', action='store_true')
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1] / 'build/hud-bench')
    parser.add_argument('--timeout', type=int, default=1800)
    args = parser.parse_args()
    host = str(ipaddress.IPv4Address(args.linux_host))
    if args.timeout < 30:
        parser.error('--timeout must be at least 30 seconds')
    adb = Adb(args.adb, args.serial)
    if adb.call('get-state').strip() != b'device':
        raise RuntimeError('ADB device is not ready')
    adb.shell('pm', 'path', PACKAGE)
    run_id = str(uuid.uuid4())
    local = args.output.resolve() / run_id
    local.mkdir(parents=True, exist_ok=False)
    remote = f'/sdcard/Android/data/{PACKAGE}/files/hud-bench/{run_id}'
    command = ['am', 'start', '-W', '-n', COMPONENT, '--ez', 'autorun', 'true', '--es', 'run_id', run_id,
               '--es', 'mode', args.mode, '--es', 'suite', args.suite, '--ez', 'capture', str(not args.no_capture).lower()]
    launch = adb.shell(*command, timeout=15).decode('utf-8', errors='replace')
    (local / 'launch.txt').write_text(launch, encoding='utf-8')
    if 'Error' in launch or 'Exception' in launch:
        raise RuntimeError(launch)
    print(f'Run: {run_id}\nOutput: {local}', flush=True)
    served = set()
    deadline = time.monotonic() + args.timeout
    stopping = False
    finished = None
    report_seen = False
    startup_deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        try:
            finished = adb.read_json(remote + '/finished.json')
            if finished:
                break
            if not report_seen:
                report_seen = adb.read_json(remote + '/report.json') is not None
                if not report_seen and time.monotonic() > startup_deadline:
                    raise RuntimeError('No bench report within 45s. Check the debug APK, App status and ADB access to its external files directory.')
            request = adb.read_json(remote + '/capture-request.json')
            if request and request.get('captureId') not in served:
                serve_capture(adb, request, remote, local, run_id, host)
                served.add(request['captureId'])
            time.sleep(0.3)
        except KeyboardInterrupt:
            if stopping:
                raise
            stopping = True
            print('Stopping; waiting for App cleanup and archive...', flush=True)
            adb.shell('am', 'start', '-n', COMPONENT, '--ez', 'stop', 'true')
            deadline = time.monotonic() + 45
    if not finished:
        adb.shell('am', 'start', '-n', COMPONENT, '--ez', 'stop', 'true')
        raise TimeoutError(f'No finished archive. Raw evidence remains at {remote}; inspect App status. No force-stop was used.')
    if finished.get('runId') != run_id:
        raise ValueError('Finish marker does not match run')
    archive = local / 'report.zip'
    adb.call('pull', remote + '/report.zip', str(archive), timeout=60)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != finished['sha256']:
        raise ValueError('Downloaded archive SHA-256 mismatch')
    report = verify_archive(archive, run_id)
    write_review(archive, report)
    (local / 'finished.json').write_text(json.dumps(finished, indent=2), encoding='utf-8')
    print(f"Execution: {report['status']}; visual review pending\n{archive}\n{local / 'review/index.html'}")
    return 0 if report['status'] in {'completed', 'completed_without_captures'} else 2


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, RuntimeError, TimeoutError, subprocess.TimeoutExpired) as error:
        print('HUD bench error: ' + str(error), file=sys.stderr)
        sys.exit(2)
