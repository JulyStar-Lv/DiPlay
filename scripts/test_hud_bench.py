import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

import hud_bench


class EvidenceTest(unittest.TestCase):
    def archive(self, root, *, corrupt=False, unsafe=False):
        report = {'runId': 'fixture', 'displayVerified': False, 'captures': [], 'status': 'completed_without_captures'}
        name = '../escape' if unsafe else 'report.json'
        data = json.dumps(report).encode()
        path = root / 'report.zip'
        manifest = [{'path': name, 'bytes': len(data), 'sha256': 'bad' if corrupt else hashlib.sha256(data).hexdigest()}]
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr(name, data)
            archive.writestr('manifest.json', json.dumps(manifest))
        return path

    def test_archive_is_checked_before_review_and_never_marks_visual_success(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.archive(Path(directory))
            report = hud_bench.verify_archive(path, 'fixture')
            self.assertFalse(report['displayVerified'])
            hud_bench.write_review(path, report)
            self.assertTrue((path.parent / 'review/index.html').is_file())
            with self.assertRaises(ValueError):
                hud_bench.verify_archive(path, 'another-run')

    def test_corrupt_or_unsafe_evidence_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            for option in ['corrupt', 'unsafe']:
                path = self.archive(Path(directory), **{option: True})
                with self.assertRaises(ValueError):
                    hud_bench.verify_archive(path, 'fixture')

    def test_capture_request_cannot_supply_a_shell_command_or_arbitrary_file(self):
        class UnexpectedAdb:
            def shell(self, *args, **kwargs):
                raise AssertionError('Invalid request must not reach adb')
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                hud_bench.serve_capture(UnexpectedAdb(), {'version': 1, 'runId': 'run', 'screenId': 0,
                    'captureId': '../outside;echo arbitrary'}, '/remote', Path(directory), 'run', '192.168.100.1')


if __name__ == '__main__':
    unittest.main()
