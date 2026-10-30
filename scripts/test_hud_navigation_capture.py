import json
from pathlib import Path
import tempfile
import struct
import unittest
import uuid
from unittest import mock
import zipfile

import hud_navigation_capture as capture
from hud_bench import verify_archive


class NavigationEvidenceTest(unittest.TestCase):
    def test_card_tone_diagnostics_are_bounded_and_do_not_approve_hud_pixels(self):
        line = '100.500 123 123 I DiPlay-MzdHUD: hudmap action=card_tone mode=light'
        for mode in ('light', 'dark', 'original'):
            event = capture.parse_event(line.replace('mode=light', 'mode=' + mode), 123, 100)
            self.assertEqual(mode, event['mode'])
            self.assertFalse(capture.summarize([event], [])['mapDisplayVerified'])
        self.assertIsNone(capture.parse_event(line.replace('mode=light', 'mode=Private'), 123, 100))
        self.assertIsNone(capture.parse_event(line + ' road=Private', 123, 100))

    def test_requested_combined_content_is_observation_not_junction_image_confirmation(self):
        line = ('100.500 123 123 I DiPlay-MzdHUD: hudmap action=attached '
                'request=256x256 fps=15 content=map_with_instruction_card')
        event = capture.parse_event(line, 123, 100)
        self.assertEqual(event['content'], 'map_with_instruction_card')
        summary = capture.summarize([event], [])
        self.assertEqual(['map_with_instruction_card'], summary['requestedMapContents'])
        self.assertEqual('not_determined', summary['junctionSourceStatus'])
        self.assertFalse(summary['junctionDisplayVerified'])
        self.assertIsNone(capture.parse_event(line.replace('map_with_instruction_card', 'Private road'), 123, 100))

    def test_primary_only_cannot_satisfy_hud_screenshot_or_pair_requirement(self):
        events = [self.event(self.source(), 'DiPlayNav'),
                  self.event('stage=output action=sent icon=19 distance=150 remaining=5000 seconds=120')]
        primary = {'status': 'captured', 'surface': 'android_primary', 'pairId': 'pair'}
        summary = capture.summarize(events, [primary])
        self.assertEqual('no_screenshots', summary['status'])
        self.assertEqual(1, summary['primaryScreenshots'])
        self.assertEqual(0, summary['completeScreenshotPairs'])
        hud = dict(status='captured', surface='hud', pairId='pair')
        summary = capture.summarize(events, [primary, hud])
        self.assertEqual(1, summary['completeScreenshotPairs'])
        self.assertFalse(summary['junctionDisplayVerified'])

    def test_pair_retains_separate_timing_and_a_hud_failure_does_not_drop_primary(self):
        hud = dict(captureId='hud-id', status='failed')
        primary = dict(captureId='primary-id', status='captured')
        with mock.patch.object(capture, 'capture', return_value=hud), \
                mock.patch.object(capture, 'capture_primary', return_value=primary), \
                mock.patch.object(capture.time, 'monotonic', side_effect=[10, 10.1, 11, 11.2, 12]):
            rows = capture.capture_cycle(None, None, '192.168.100.1', 2, True)
        self.assertEqual(['hud', 'android_primary'], [row['surface'] for row in rows])
        self.assertEqual(['hud-id', 'hud-id'], [row['pairId'] for row in rows])
        self.assertEqual([2.1, 3.2], [row['captureStartSeconds'] for row in rows])
        self.assertEqual([3, 4], [row['captureEndSeconds'] for row in rows])
        self.assertEqual(0, capture.summarize([], rows)['completeScreenshotPairs'])

    def test_primary_png_is_in_archive_with_hash_but_does_not_approve_junction_display(self):
        data = (b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR' + struct.pack('>II', 1920, 720) +
                b'\x00\x00\x00\x00IEND\xaeB`\x82')
        adb = mock.Mock()
        adb.call.return_value = data
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / 'primary').mkdir()
            row = capture.capture_primary(adb, folder, 0)
            row['surface'] = 'android_primary'
            self.assertEqual('captured', row['status'])
            adb.call.assert_called_once_with('exec-out', 'screencap', '-p', timeout=8)
            report = {'runId': str(uuid.uuid4()), 'displayVerified': False, 'captures': [row],
                      'summary': capture.summarize([], [row])}
            archive = capture.write_evidence(folder, report, [])
            verified = verify_archive(archive, report['runId'])
            self.assertFalse(verified['summary']['junctionDisplayVerified'])
            with zipfile.ZipFile(archive) as output:
                self.assertEqual(data, output.read(row['image']))
                manifest = json.loads(output.read('manifest.json'))
                self.assertIn(row['image'], [item['path'] for item in manifest])
            for invalid in (b'private raw error', data[:-12], data[:16] + struct.pack('>II', 0, 720) + data[24:]):
                adb.call.return_value = invalid
                result = capture.capture_primary(adb, folder, 0)
                self.assertEqual('failed', result['status'])
                self.assertNotIn('private', json.dumps(result))

    def test_lane_output_records_entire_native_row_but_does_not_approve_pixels(self):
        event = self.event('stage=lane_output count=4 icons=16,16,0,33')
        self.assertEqual(event['count'], 4)
        summary = capture.summarize([event], [])
        self.assertFalse(summary['displayVerified'])
        self.assertFalse(summary['laneDisplayVerified'])
        self.assertEqual([4], summary['laneCountsObserved'])
        self.assertIsNotNone(self.event('stage=lane_output count=0 icons=none'))
        for value in ['count=4 icons=16', 'count=9 icons=0', 'count=1 icons=Private', 'count=1 icons=99']:
            self.assertIsNone(self.event('stage=lane_output ' + value))

    def test_lane_capture_preserves_only_bounded_numeric_layout_and_never_counts_as_display(self):
        text = ('stage=lanes frame=5204 valid=true index=3 count=2 '
                'layout=0/0/-90/null;1/2/0+90/90 '
                'session=carplay-00000000-0000-0000-0000-000000000001 epoch=1')
        event = capture.parse_event(self.line(text), 123, 100)
        self.assertEqual(event['count'], 2)
        self.assertFalse(capture.summarize([event], [])['displayVerified'])
        self.assertIsNone(capture.parse_event(self.line(text.replace('count=2', 'count=1')), 123, 100))
        self.assertIsNone(capture.parse_event(self.line(text.replace('0/0/-90/null', 'Private road')), 123, 100))

    def line(self, message, tag='DiPlayNav', pid=123, epoch=100.1):
        return f'{epoch:.3f} {pid} 456 I {tag}: hudnav {message}'

    def source(self, **overrides):
        fields = dict(stage='source', frame='5201', valid='true', changed='true', active='true', phase='GUIDING',
                      maneuver='LEFT', distance='150', remaining='5000', seconds='120',
                      session='carplay-00000000-0000-0000-0000-000000000001', epoch='1')
        fields.update(overrides)
        return ' '.join(f'{key}={value}' for key, value in fields.items())

    def event(self, message, tag='DiPlay-MzdHUD'):
        return capture.parse_event(self.line(message, tag), 123, 100)

    def test_source_whitelist_rejects_private_text_old_events_wrong_process_and_wrong_tag(self):
        line = self.line(self.source())
        event = capture.parse_event(line, 123, 100)
        self.assertEqual(event['distance'], 150)
        self.assertTrue(event['valid'])
        self.assertIsNone(capture.parse_event(line, 999, 100))
        self.assertIsNone(capture.parse_event(line, 123, 101))
        self.assertIsNone(self.event(self.source()))
        for suffix in [' road=Private', ' stage=source', '\nPrivate contact']:
            self.assertIsNone(capture.parse_event(line + suffix, 123, 100))
        for fields in [dict(maneuver='Private'), dict(distance='Private'), dict(session='Private'), dict(valid='yes')]:
            self.assertIsNone(self.event(self.source(**fields), 'DiPlayNav'))

    def test_map_texture_observation_is_not_hud_image_approval(self):
        line = '100.500 123 123 I DiPlay-MzdHUD: hudmap action=first_texture_frame'
        event = capture.parse_event(line, 123, 100)
        self.assertEqual(event['stage'], 'map')
        summary = capture.summarize([event], [{'status': 'captured'}])
        self.assertEqual(summary['mapEvents'], {'first_texture_frame': 1})
        self.assertFalse(summary['mapDisplayVerified'])
        self.assertIsNone(capture.parse_event(line + ' road=Private', 123, 100))
        self.assertIsNone(capture.parse_event(line, 999, 100))

    def test_no_data_inactive_or_malformed_guidance_never_counts_as_active_guidance(self):
        self.assertEqual(capture.summarize([], [])['status'], 'no_source_data')
        for fields in [dict(active='false'), dict(valid='false'), dict(phase='ENDED')]:
            event = self.event(self.source(**fields), 'DiPlayNav')
            summary = capture.summarize([event], [])
            self.assertEqual(summary['status'], 'no_active_guidance')
            self.assertFalse(summary['displayVerified'])
        self.assertEqual(capture.summarize([self.event(self.source(), 'DiPlayNav')], [])['status'], 'no_oem_output_observed')

    def test_focus_and_successful_write_are_observations_and_never_visual_approval(self):
        events = [self.event(self.source(), 'DiPlayNav'), self.event('stage=focus granted=true'),
                  self.event('stage=gate reason=missing_time'),
                  self.event('stage=output action=sent icon=19 distance=150 remaining=5000 seconds=120')]
        self.assertEqual(capture.summarize(events, [])['status'], 'no_screenshots')
        summary = capture.summarize(events, [{'status': 'captured'}])
        self.assertEqual(summary['status'], 'output_observed_pending_image_review')
        self.assertEqual(summary['focusGrants'], 1)
        self.assertEqual(summary['gateReasons'], {'missing_time': 1})
        self.assertFalse(summary['displayVerified'])

    def test_failed_capture_only_cleans_its_unique_remote_file_and_omits_raw_errors(self):
        class Adb:
            def __init__(self):
                self.commands = []
            def shell(self, *args, **kwargs):
                self.commands.append(args)
                if args[0] == 'wtdbclient':
                    raise RuntimeError('private device output')
        adb = Adb()
        with tempfile.TemporaryDirectory() as directory:
            result = capture.capture(adb, Path(directory), '192.168.100.1', 0)
        self.assertEqual(result['status'], 'failed')
        self.assertEqual(result['errorClass'], 'RuntimeError')
        self.assertNotIn('private', json.dumps(result))
        self.assertEqual(adb.commands[-1], ('rm', '-f', '/data/nfs/hdcap-' + result['captureId'] + '.png'))
        self.assertEqual(str(uuid.UUID(result['captureId'])), result['captureId'])

    def test_capture_preserves_both_known_hud_dimensions_and_rejects_other_displays(self):
        class Adb:
            def __init__(self, height):
                self.data = (b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR' + struct.pack('>II', 800, height) +
                             b'\x00\x00\x00\x00IEND\xaeB`\x82')
            def shell(self, *args, **kwargs):
                return str(len(self.data)).encode() if args[0] == 'stat' else b''
            def call(self, *args, **kwargs):
                return self.data
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / 'hud').mkdir()
            for height in (400, 480):
                row = capture.capture(Adb(height), folder, '192.168.100.1', 0)
                self.assertEqual(row['status'], 'captured')
                self.assertEqual(row['imageInfo']['height'], height)
            self.assertEqual(capture.capture(Adb(600), folder, '192.168.100.1', 0)['status'], 'failed')

    def test_evidence_archive_includes_identity_hashes_and_never_infers_display_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / 'hud').mkdir()
            report = {'runId': str(uuid.uuid4()), 'displayVerified': False, 'captures': [],
                      'summary': capture.summarize([], [])}
            archive = capture.write_evidence(folder, report, [])
            verified = verify_archive(archive, report['runId'])
            self.assertEqual(verified['summary']['status'], 'no_source_data')
            self.assertTrue((folder / 'index.html').is_file())
            with self.assertRaises(ValueError):
                verify_archive(archive, 'another-run')


if __name__ == '__main__':
    unittest.main()
