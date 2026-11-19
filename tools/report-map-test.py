#!/usr/bin/env python3
"""Report import regressions, using synthetic reports only (no car files)."""
import copy, json, pathlib, subprocess, sys, tempfile, unittest

REPO = pathlib.Path(__file__).resolve().parent.parent


class ReportMapTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.report = {
            'report': 'hmgapi car report 1',
            'hmgapi': {'version': '1.1.0-rc.3'},
            'unit': {'make': 'hyundai', 'name': 'Ioniq 6', 'model': 'CEEV',
                     'region': 'CAN', 'firmware': '260206'},
            'map_car': {
                'points': {'btsettings.projection_helper': {'class': 'example.Helper'}},
                'files': {'/system/app/BtSettings.odex': '0' * 32},
                'app_files': {'btsettings': {'package': 'example.settings',
                                            'files': ['/system/app/BtSettings.odex']}},
                'not_found': {'btsettings.projection_helper.open_wireless_page': '0 methods match'},
                'apps_absent': {},
                'by_reference': ['btsettings.projection_helper'],
            },
        }

    def run_import(self, reports=None, args=()):
        paths = []
        for i, report in enumerate(reports or [self.report]):
            path = self.root / ('report-%d.json' % i)
            path.write_text(json.dumps(report))
            paths.append(str(path))
        return subprocess.run([sys.executable, str(REPO / 'tools/report-map.py'), *paths,
                               '--maps', str(self.root / 'maps'), *args], capture_output=True, text=True)

    def car(self):
        return json.loads((self.root / 'maps/hyundai/ceev/can/260206/car.json').read_text())

    def assert_refused(self, result, message):
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(message, result.stderr)
        self.assertNotIn('Traceback', result.stderr)
        self.assertFalse((self.root / 'maps').exists())

    NEWER = 'not in the Car Report: newer than the HMG-API that made it'

    def assert_missing(self, missing, expected):
        """the report's own missing points, with their reasons; every other catalogue
        point the report lacks is listed as newer than the HMG-API that made it"""
        for pid, reason in expected.items():
            self.assertEqual(missing.get(pid), reason, pid)
        for pid, reason in missing.items():
            if pid not in expected:
                self.assertTrue(reason.startswith(self.NEWER), pid)

    def test_current_report_keeps_missing_points_and_hashes(self):
        result = self.run_import()
        self.assertEqual(result.returncode, 0, result.stderr)
        car = self.car()
        self.assert_missing(car['missing'], self.report['map_car']['not_found'])
        self.assertEqual(car['files'], self.report['map_car']['files'])
        self.assertEqual(car['by_reference'], self.report['map_car']['by_reference'])
        self.assertEqual(car['status'], 'probed')
        self.assertIsNone(car['verified'])

    def test_unknown_make_requires_explicit_override(self):
        self.report['unit'].update(make=None, name=None)
        self.assert_refused(self.run_import(), 'supply --make')
        result = self.run_import(args=['--make', 'hyundai', '--name', 'Ioniq 6'])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.car()['make'], self.car()['name']), ('hyundai', 'Ioniq 6'))

    def test_legacy_missing_field_is_preserved(self):
        mc = self.report['map_car']
        mc['missing'] = mc.pop('not_found')
        result = self.run_import()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_missing(self.car()['missing'], mc['missing'])

    def test_absent_app_accounts_for_all_its_catalogue_points(self):
        cat = json.loads((REPO / 'core/hookpoints/iap2.json').read_text())
        reason = 'no stock /system/app/Iap2.odex on this car'
        mc = self.report['map_car']
        mc['app_files'][cat['app']] = {'package': cat['package'], 'files': cat['files']}
        mc['apps_absent'][cat['app']] = reason
        result = self.run_import()
        self.assertEqual(result.returncode, 0, result.stderr)
        expected = dict(mc['not_found'], **{p['id']: reason for p in cat['points']})
        self.assert_missing(self.car()['missing'], expected)
        self.assertEqual(json.loads((self.root / 'maps/hyundai/ceev/can/260206/iap2.json').read_text()), {})

    def test_points_not_in_the_catalogue_are_left_out(self):
        # a fork's report: its own points, found, missing and by reference
        mc = self.report['map_car']
        mc['points']['btsettings.fork_only'] = {'class': 'example.Fork'}
        mc['points']['btsettings.fork_only.go'] = {'method': 'go', 'params': [], 'ret': 'void'}
        mc['not_found']['btsettings.fork_missing'] = '0 methods match'
        mc['by_reference'].append('btsettings.fork_only')
        result = self.run_import()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('not in the catalogue, left out: 3 point(s)', result.stdout)
        car = self.car()
        text = json.dumps(car)
        for pid in ('btsettings.fork_only', 'btsettings.fork_only.go', 'btsettings.fork_missing'):
            self.assertNotIn('"%s"' % pid, text)
        self.assertEqual(car['by_reference'], ['btsettings.projection_helper'])

    def test_catalogue_points_newer_than_the_report_are_listed(self):
        result = self.run_import()
        self.assertEqual(result.returncode, 0, result.stderr)
        missing = self.car()['missing']
        cat = json.loads((REPO / 'core/hookpoints/framework.json').read_text())
        for p in cat['points']:
            self.assertTrue(missing[p['id']].startswith(self.NEWER), p['id'])
        self.assertNotIn('btsettings.projection_helper', missing)

    def test_conflicting_found_and_missing_is_rejected(self):
        self.report['map_car']['not_found']['btsettings.projection_helper'] = 'not found'
        self.assert_refused(self.run_import(), 'both found and missing')

    def test_invalid_identity_is_rejected_before_writing(self):
        self.report['unit']['model'] = '../elsewhere'
        self.assert_refused(self.run_import(), 'invalid or missing unit.model')

    def test_missing_mapper_result_is_rejected_without_traceback(self):
        del self.report['map_car']['points']
        self.assert_refused(self.run_import(), 'map_car.points: expected an object')

    def test_multiple_reports_keep_majority_file_hash(self):
        other = copy.deepcopy(self.report)
        other['map_car']['files']['/system/app/BtSettings.odex'] = '1' * 32
        result = self.run_import([self.report, self.report, other])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.car()['files'], self.report['map_car']['files'])
        self.assertIn('outvoted: report-2.json', result.stdout)

    # by-reference classes the import doesn't keep (#47: a borrowed name, a different class)
    MEMBERS = ['btsettings.projection_helper.device_connections_available',
               'btsettings.projection_helper.open_device_connections',
               'btsettings.projection_helper.open_other_page',
               'btsettings.projection_helper.open_wireless_page']

    def test_by_reference_class_from_an_old_mapper_is_left_to_the_unit(self):
        self.report['hmgapi'] = {'version': '1.1.0-rc.1'}
        self.report['map_car']['points']['btsettings.projection_helper.open_other_page'] = {
            'method': 'aj', 'params': ['android.content.Context'], 'ret': 'void', 'static': True}
        self.report['map_car']['not_found'] = {}
        result = self.run_import()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('distrusted: btsettings.projection_helper', result.stdout)
        car = self.car()
        for pid in ['btsettings.projection_helper'] + self.MEMBERS:
            self.assertTrue(car['missing'][pid].startswith('not kept from the Car Report: its by-reference class came from'), pid)
            self.assertIn('1.1.0-rc.1', car['missing'][pid])
        self.assertNotIn('btsettings.projection_helper', car.get('by_reference', []))
        self.assertFalse((self.root / 'maps/hyundai/ceev/can/260206/btsettings.json').exists()
                         and 'btsettings.projection_helper' in (self.root / 'maps/hyundai/ceev/can/260206/btsettings.json').read_text())

    def test_by_reference_class_with_no_members_is_left_to_the_unit(self):
        self.report['map_car']['not_found'] = {pid: '0 methods match in example.Helper' for pid in self.MEMBERS}
        result = self.run_import()
        self.assertEqual(result.returncode, 0, result.stderr)
        car = self.car()
        for pid in ['btsettings.projection_helper'] + self.MEMBERS:
            self.assertIn('has none of its 4 members here', car['missing'][pid], pid)

    def test_by_reference_class_with_members_from_a_current_mapper_is_kept(self):
        self.report['map_car']['points']['btsettings.projection_helper.open_other_page'] = {
            'method': 'aj', 'params': ['android.content.Context'], 'ret': 'void', 'static': True}
        result = self.run_import()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn('distrusted', result.stdout)
        car = self.car()
        self.assertNotIn('btsettings.projection_helper', car['missing'])
        self.assertIn('btsettings.projection_helper', car['by_reference'])


if __name__ == '__main__':
    unittest.main()
