#!/usr/bin/env python3
"""Observe real iPhone navigation; optionally pair HUD and Android primary-screen evidence."""
import argparse
from collections import Counter, deque
import hashlib
import html
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import threading
import time
import uuid
import zipfile

from hud_bench import Adb, verify_archive

PACKAGE = 'com.thunder.carplay'
LINE = re.compile(r'^\s*(\d+\.\d+)\s+(\d+)\s+\d+\s+[A-Z]\s+(DiPlayNav|DiPlay-MzdHUD)\s*:\s*hudnav (.+)$')
PHASES = {'NONE', 'GUIDING', 'ARRIVED', 'ENDED', 'UNKNOWN'}
MANEUVERS = {'UNKNOWN', 'STRAIGHT', 'LEFT', 'RIGHT', 'SLIGHT_LEFT', 'SLIGHT_RIGHT', 'SHARP_LEFT',
             'SHARP_RIGHT', 'U_TURN_LEFT', 'U_TURN_RIGHT', 'ROUNDABOUT_ENTER', 'ROUNDABOUT_EXIT',
             'ROUNDABOUT_NUMBERED', 'DESTINATION'}
REASONS = {'ready', 'not_guiding', 'stale', 'missing_distance', 'missing_remaining', 'missing_time',
           'distance_range', 'remaining_range', 'time_range'}


def parse_map_event(line, pid, start_epoch):
    match = re.fullmatch(r'\s*(\d+\.\d+)\s+(\d+)\s+\d+\s+[A-Z]\s+DiPlay-MzdHUD\s*:\s*hudmap (.+)', line.strip())
    if not match or int(match[2]) != pid or float(match[1]) < start_epoch:
        return None
    message = match[3]
    # Exact, bounded diagnostics only. No roads, URLs, coordinates or raw exception text.
    shapes = (
        r'action=attached request=\d{1,4}x\d{1,4} fps=\d{1,2}',
        r'action=attached request=\d{1,4}x\d{1,4} fps=\d{1,2} content=(?:map|instruction_card|map_with_instruction_card)',
        r'action=stream active=(?:true|false)',
        r'action=gate allowed=(?:true|false)',
        r'action=format size=\d{1,4}x\d{1,4} fit=contain',
        r'action=window display=\d{1,4} canvas=800x480 viewport=584,179,178,178',
        r'action=(?:first_texture_frame|cleared|detached)',
        r'action=card_tone mode=(?:light|dark|original)',
        r'action=waiting reason=display_profile',
        r'action=unavailable error=(?:SecurityException|InvalidDisplayException|BadTokenException|RuntimeException)',
    )
    if not any(re.fullmatch(shape, message) for shape in shapes):
        return None
    values = dict(part.split('=', 1) for part in message.split())
    return dict(values, stage='map', deviceTime=float(match[1]), pid=pid)


def parse_event(line, pid, start_epoch):
    """Reject unknown keys/text. Never copy raw log lines into the evidence package."""
    match = LINE.fullmatch(line.strip())
    if not match:
        return parse_map_event(line, pid, start_epoch)
    if int(match[2]) != pid or float(match[1]) < start_epoch:
        return None
    try:
        pairs = [part.split('=', 1) for part in match[4].split()]
        values = dict(pairs)
        if len(values) != len(pairs):
            return None
        stage = values.get('stage')
        schema = {
            'source': {'stage', 'frame', 'valid', 'changed', 'active', 'phase', 'maneuver',
                       'distance', 'remaining', 'seconds', 'session', 'epoch'},
            'session': {'stage', 'action', 'session', 'epoch'},
            'lanes': {'stage', 'frame', 'valid', 'index', 'count', 'layout', 'session', 'epoch'},
            'lane_output': {'stage', 'count', 'icons'},
            'gate': {'stage', 'reason'}, 'focus': {'stage', 'granted'},
            'output': {'stage', 'action'} | ({'icon', 'distance', 'remaining', 'seconds'} if values.get('action') == 'sent' else set()),
        }.get(stage)
        if set(values) != schema or (stage in {'source', 'session', 'lanes'}) != (match[3] == 'DiPlayNav'):
            return None
        for key in ('valid', 'changed', 'active', 'granted'):
            if key in values:
                if values[key] not in {'true', 'false'}:
                    return None
                values[key] = values[key] == 'true'
        for key in ('distance', 'remaining', 'seconds', 'icon', 'epoch', 'index', 'count'):
            if key in values:
                if values[key] == 'null' and key in {'distance', 'remaining', 'seconds', 'index'}:
                    values[key] = None
                elif re.fullmatch(r'-?\d{1,20}', values[key]):
                    values[key] = int(values[key])
                else:
                    return None
        if 'session' in values:
            if values['session'] != 'carplay-' + str(uuid.UUID(values['session'].removeprefix('carplay-'))):
                return None
        if stage == 'source' and (values['frame'] not in {'5201', '5202'} or values['phase'] not in PHASES or values['maneuver'] not in MANEUVERS):
            return None
        if stage == 'lanes':
            if values['frame'] != '5204' or not 0 <= values['count'] <= 16:
                return None
            layout = values['layout']
            if len(layout) > 2048 or not re.fullmatch(r'(?:none|\d{1,2}/\d{1,3}/-?\d{1,3}(?:\+-?\d{1,3}){0,7}/(?:null|-?\d{1,3})(?:;\d{1,2}/\d{1,3}/-?\d{1,3}(?:\+-?\d{1,3}){0,7}/(?:null|-?\d{1,3})){0,15})', layout):
                return None
            if values['valid'] and (values['index'] is None or not 0 <= values['index'] <= 65535):
                return None
            if (0 if layout == 'none' else len(layout.split(';'))) != values['count']:
                return None
        if stage == 'lane_output':
            if not 0 <= values['count'] <= 8 or not re.fullmatch(r'(?:none|\d{1,2}(?:,\d{1,2}){0,7})', values['icons']):
                return None
            icons = [] if values['icons'] == 'none' else [int(icon) for icon in values['icons'].split(',')]
            if len(icons) != values['count'] or any(icon > 83 for icon in icons):
                return None
        if stage == 'session' and values['action'] not in {'start', 'end'}:
            return None
        if stage == 'output' and values['action'] not in {'sent', 'clear', 'mode_on'}:
            return None
        if stage == 'gate' and values['reason'] not in REASONS:
            return None
        return dict(values, deviceTime=float(match[1]), pid=pid)
    except (ValueError, TypeError):
        return None


def summarize(events, captures):
    frames = [e for e in events if e['stage'] == 'source']
    guiding = [e for e in frames if e['valid'] and e['active'] and e['phase'] == 'GUIDING']
    sent = [e for e in events if e['stage'] == 'output' and e['action'] == 'sent']
    numeric = {(e['distance'], e['remaining'], e['seconds']) for e in guiding}
    captured = sum(c['status'] == 'captured' and c.get('surface', 'hud') == 'hud' for c in captures)
    primary = sum(c['status'] == 'captured' and c.get('surface') == 'android_primary' for c in captures)
    pairs = {}
    for row in captures:
        if row.get('pairId') and row['status'] == 'captured':
            pairs.setdefault(row['pairId'], set()).add(row.get('surface', 'hud'))
    status = ('no_source_data' if not frames else 'no_active_guidance' if not guiding else
              'no_oem_output_observed' if not sent else 'no_screenshots' if not captured else
              'output_observed_pending_image_review')
    return {'status': status, 'sourceFrames': len(frames), 'invalidFrames': sum(not e['valid'] for e in frames),
            'activeGuidingFrames': len(guiding), 'numericVariants': len(numeric),
            'maneuversObserved': sorted({e['maneuver'] for e in guiding}),
            'terminalPhasesObserved': sorted({e['phase'] for e in frames if e['valid'] and e['active'] and e['phase'] in {'ARRIVED', 'ENDED'}}),
            'gateReasons': dict(Counter(e['reason'] for e in events if e['stage'] == 'gate')),
            'focusGrants': sum(e['stage'] == 'focus' and e['granted'] for e in events),
            'outputWrites': len(sent), 'screenshots': captured, 'displayVerified': False,
            'laneSourceFrames': sum(e['stage'] == 'lanes' for e in events),
            'laneOutputWrites': sum(e['stage'] == 'lane_output' for e in events),
            'laneCountsObserved': sorted({e['count'] for e in events if e['stage'] == 'lane_output'}),
            'laneDisplayVerified': False,
            'mapEvents': dict(Counter(e['action'] for e in events if e['stage'] == 'map')),
            'mapDisplayVerified': False,
            'primaryScreenshots': primary,
            'completeScreenshotPairs': sum({'hud', 'android_primary'} <= surfaces for surfaces in pairs.values()),
            'requestedMapContents': sorted({e['content'] for e in events if e['stage'] == 'map' and 'content' in e}),
            'junctionSourceStatus': 'not_determined', 'junctionDisplayVerified': False}


def capture(adb, folder, host, elapsed):
    capture_id = str(uuid.uuid4())
    shared = '/data/nfs/hdcap-' + capture_id + '.png'
    row = {'captureId': capture_id, 'caseId': 'iphone-navigation', 'phase': f'{elapsed:.1f}s', 'status': 'failed'}
    try:
        adb.shell('wtdbclient', '-T', '-y', 'root@' + host,
                  'LayerManagerControl dump screen 0 to /var/update/hdcap-' + capture_id + '.png', timeout=8)
        size = int(adb.shell('stat', '-c', '%s', shared, timeout=3).strip())
        if not 24 <= size <= 8 * 1024 * 1024:
            raise ValueError('Screenshot size')
        data = adb.call('exec-out', 'cat', shared, timeout=8)
        if (len(data) != size or data[:16] != b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR' or
                data[-12:] != b'\x00\x00\x00\x00IEND\xaeB`\x82'):
            raise ValueError('Incomplete/incorrect HUD PNG')
        width, height = struct.unpack('>II', data[16:24])
        if (width, height) not in {(800, 400), (800, 480)}:
            raise ValueError('Unexpected HUD dimensions')
        name = 'hud/' + capture_id + '.png'
        (folder / name).write_bytes(data)
        row.update(status='captured', image=name, imageInfo={'sha256': hashlib.sha256(data).hexdigest(), 'bytes': size, 'width': width, 'height': height})
    except (RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
        row['errorClass'] = type(error).__name__
    finally:
        try:
            adb.shell('rm', '-f', shared, timeout=3)
        except (RuntimeError, subprocess.TimeoutExpired) as error:
            row['cleanupErrorClass'] = type(error).__name__
    return row


def capture_primary(adb, folder, elapsed):
    """Default Android display only; image review must establish whether it shows CarPlay."""
    capture_id = str(uuid.uuid4())
    row = {'captureId': capture_id, 'caseId': 'iphone-navigation', 'phase': f'{elapsed:.1f}s', 'status': 'failed'}
    try:
        data = adb.call('exec-out', 'screencap', '-p', timeout=8)
        if (not 36 <= len(data) <= 8 * 1024 * 1024 or
                data[:16] != b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR' or
                data[-12:] != b'\x00\x00\x00\x00IEND\xaeB`\x82'):
            raise ValueError('Incomplete/incorrect primary PNG')
        width, height = struct.unpack('>II', data[16:24])
        if not (1 <= width <= 8192 and 1 <= height <= 8192 and width * height <= 16 * 1024 * 1024):
            raise ValueError('Unexpected primary dimensions')
        name = 'primary/' + capture_id + '.png'
        (folder / name).write_bytes(data)
        row.update(status='captured', image=name, imageInfo={
            'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data), 'width': width, 'height': height})
    except (RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
        row['errorClass'] = type(error).__name__
    return row


def capture_cycle(adb, folder, host, elapsed, include_primary=False):
    """Sequential screenshots with command timing bounds; never claim frame synchronization."""
    cycle_started = time.monotonic()
    rows = []
    for surface in (('hud', 'android_primary') if include_primary else ('hud',)):
        start = elapsed + time.monotonic() - cycle_started
        row = (capture(adb, folder, host, start) if surface == 'hud' else
               capture_primary(adb, folder, start))
        row.update(surface=surface, captureStartSeconds=round(start, 3),
                   captureEndSeconds=round(elapsed + time.monotonic() - cycle_started, 3))
        rows.append(row)
    for row in rows:
        row['pairId'] = rows[0]['captureId']
    return rows


def write_evidence(folder, report, events):
    (folder / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    (folder / 'events.json').write_text(json.dumps(events, indent=2), encoding='utf-8')
    cards = []
    for row in report['captures']:
        caption = html.escape(row.get('surface', 'hud') + ' / ' + row['phase'] + ' / ' + row['status'] +
                              ' / pair ' + row.get('pairId', row['captureId'])[:8])
        picture = f'<img src="{row["image"]}" loading="lazy" width="{row["imageInfo"]["width"]}" height="{row["imageInfo"]["height"]}">' if row['status'] == 'captured' else ''
        cards.append(f'<figure><figcaption>{caption}</figcaption>{picture}</figure>')
    (folder / 'index.html').write_text('<!doctype html><meta charset="utf-8"><title>iPhone HUD navigation</title>'
        '<style>body{background:#171717;color:#eee;font-family:system-ui}img{max-width:100%;height:auto}figure{margin:12px 0}pre{white-space:pre-wrap}</style>'
        '<h1>真实 iPhone 导航：待图片审阅</h1><p>导航帧、焦点授予及 Binder 返回均不代表实际显示通过。无数据不能算通过。'
        '成对截图按顺序采集，不保证同一帧；Android 默认屏幕不保证为 CarPlay，需审图。'
        '仅含白名单导航数值；原图可能含路线、音乐/来电文字，分享前检查。</p><pre>' +
        html.escape(json.dumps(report['summary'], ensure_ascii=False, indent=2)) + '</pre>' + ''.join(cards), encoding='utf-8')
    paths = ([folder / 'report.json', folder / 'events.json', folder / 'index.html'] +
             sorted((folder / 'hud').glob('*.png')) + sorted((folder / 'primary').glob('*.png')))
    manifest = [{'path': p.relative_to(folder).as_posix(), 'bytes': p.stat().st_size,
                 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in paths]
    archive = folder / 'evidence.zip'
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as output:
        for path in paths:
            output.write(path, path.relative_to(folder).as_posix())
        output.writestr('manifest.json', json.dumps(manifest, indent=2))
    verify_archive(archive, report['runId'])
    return archive


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default=shutil.which('adb') or str(Path(os.environ.get('ANDROID_HOME', Path.home() / 'AppData/Local/Android/Sdk')) / 'platform-tools/adb.exe'))
    parser.add_argument('--serial', required=True)
    parser.add_argument('--duration', type=int, default=180)
    parser.add_argument('--interval', type=float, default=5)
    parser.add_argument('--linux-host', default='192.168.100.1')
    parser.add_argument('--capture-primary', action='store_true',
                        help='Also capture the Android default display to compare CarPlay with HUD; sequential, not frame-synchronized')
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1] / 'build/hud-navigation')
    args = parser.parse_args()
    if not 10 <= args.duration <= 3600 or not 3 <= args.interval <= 60:
        parser.error('duration must be 10..3600s; interval must be 3..60s')
    host = str(ipaddress.IPv4Address(args.linux_host))
    adb = Adb(args.adb, args.serial)
    if adb.call('get-state').strip() != b'device':
        raise RuntimeError('ADB device is not ready')
    pid_text = adb.shell('pidof', PACKAGE).decode().strip()
    if not re.fullmatch(r'\d+', pid_text):
        raise RuntimeError('Exactly one running release process required; open DiPlay and connect iPhone first')
    pid = int(pid_text)
    # Ignore the historical -T 1 entry and the partial first second; never clear device log buffers.
    start_epoch = int(adb.shell('date', '+%s').strip()) + 1
    run_id = str(uuid.uuid4())
    folder = args.output.resolve() / run_id
    (folder / 'hud').mkdir(parents=True, exist_ok=False)
    if args.capture_primary:
        (folder / 'primary').mkdir()
    pending = deque(maxlen=20000)
    dropped = 0
    def read_logs(process):
        nonlocal dropped
        for line in process.stdout:
            event = parse_event(line, pid, start_epoch)
            if event:
                if len(pending) == pending.maxlen:
                    dropped += 1
                pending.append(event)
    process = subprocess.Popen(adb.prefix + ['logcat', '-v', 'epoch', '-T', '1', '--pid', str(pid),
                               'DiPlayNav:I', 'DiPlay-MzdHUD:I', '*:S'], stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL, text=True, encoding='utf-8', errors='replace')
    reader = threading.Thread(target=read_logs, args=(process,), daemon=True)
    reader.start()
    started = time.monotonic()
    captures = []
    events = []
    completion = 'duration_elapsed'
    print(f'Capture ready. Start iPhone CarPlay navigation now.\nOutput: {folder}', flush=True)
    try:
        next_capture = started
        while time.monotonic() - started < args.duration:
            while pending:
                events.append(pending.popleft())
            if process.poll() is not None:
                completion = 'log_stream_ended'
                break
            if time.monotonic() >= next_capture:
                cycle = capture_cycle(adb, folder, host, time.monotonic() - started, args.capture_primary)
                captures.extend(cycle)
                for row in cycle:
                    print(f"{row['surface']} {row['phase']}: {row['status']}", flush=True)
                next_capture = time.monotonic() + args.interval
            time.sleep(0.1)
    except KeyboardInterrupt:
        completion = 'interrupted'
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
        reader.join(timeout=5)
        events.extend(pending)
        summary = summarize(events, captures)
        report = {'runId': run_id, 'kind': 'real-iphone-navigation', 'displayVerified': False,
                  'completion': completion, 'pid': pid, 'deviceStartEpoch': start_epoch,
                  'elapsedSeconds': round(time.monotonic() - started, 2), 'droppedEvents': dropped,
                  'primaryCaptureRequested': args.capture_primary, 'screenshotTiming': 'sequential_command_bounds',
                  'summary': summary, 'captures': captures}
        archive = write_evidence(folder, report, events)
        print(json.dumps(summary, ensure_ascii=False), flush=True)
        print(f'Evidence: {archive}', flush=True)


if __name__ == '__main__':
    main()
