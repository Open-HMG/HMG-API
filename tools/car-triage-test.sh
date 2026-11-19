#!/bin/sh
# tools/car-triage.py decides as a maintainer would on the shapes of real Car Reports
# (the QR form, the app's markdown report, a hand-written request): old firmware closed,
# a known build folded into its tracker, a new build made a tracker, anything unclear left.
cd "$(dirname "$0")/.."
exec python3 - <<'EOF'
import runpy, sys
t = runpy.run_path('tools/car-triage.py')
decide, messages, products = t['decide'], t['messages'], t['products']
fail = 0
def check(name, want, got):
    global fail
    if want == got: print('ok    ' + name)
    else: print('FAIL  %s: wanted [%r], got [%r]' % (name, want, got)); fail = 1

QR = ('### Report summary\n\nProduct: %s\nHMG-API: 1.1.0-rc.4\nMap: probed (hooks on)\n'
      'Found on this car: 351 points, 0 not found\n\n### What do you need?\n\n'
      'A car or region that isn\'t supported\n\n### Anything else\n\n_No response_\n')
JSON = '\n[car-report-x.json](https://github.com/user-attachments/files/35001358/car-report-x.json)'
def issue(n, title, body, comments=()):
    return {'number': n, 'title': title, 'body': body, 'comments': [{'body': c} for c in comments]}
trackers = [
    {'number': 12, 'title': 'Car tracker: Kia Niro EV — USA 260507 (SG2EV)'},
    {'number': 200, 'title': 'Car tracker: Kia Niro EV — USA 260507 (DE22EV)'},
    {'number': 1, 'title': 'Car tracker: Ioniq 5 — USA 260903 (NEEV / NEEV23)'},
    {'number': 231, 'title': 'Car tracker: Genesis G70 — Canada 260903 (IKFL) — modified EPP setup'},
    {'number': 274, 'title': 'Car tracker: Ioniq 5 — Canada 260903 (NEEV)'},
    {'number': 247, 'title': 'Car Reports wanted for 1.1.0-rc.3: one per build'},
]

r = issue(324, 'Car Report: Niro EV USA 260507', QR % 'DE22EV.USA.0000.V015.012.260507' + JSON)
check('same model, region and firmware: folded into its tracker (not the other Niro)', ('dup', 200), decide(r, trackers))
check('the tracker gets the facts', 'Car Report #324: `DE22EV.USA.0000.V015.012.260507`, HMG-API 1.1.0-rc.4, '
      '351 points, 0 not found, asks: A car or region that isn\'t supported, .json attached.', messages(r, 'dup', 200)['tracker'])
check('with a .json: not asked for one', False, 'attach the `.json`' in messages(r, 'dup', 200)['report'])

r = issue(321, 'Car Report: EV6 CAN 260206', QR % 'CVEV.CAN.0000.V015.011.260206')
check('no tracker for this build: it becomes the tracker', ('new', 'Car tracker: EV6 — Canada 260206 (CVEV)'), decide(r, trackers))
check('no .json: asked for it', True, 'attach the `.json`' in messages(r, 'new', '')['report'])

r = issue(309, 'Car Report: 2022 IONIQ 5 SEL USA', '2022 Hyundai IONIQ 5 SEL.\n\nProduct: NEEV23.USA.0000.V016.010.260903\n')
check('one of several models in a tracker title', ('dup', 1), decide(r, trackers))

r = issue(300, 'Car Report: Ioniq 5 CAN 260903', '# Car Report: Ioniq 5 CAN 260903\n\n- HMG-API 1.1.0-rc.4, product '
          '`NEEV.CAN.0000.V016.010.260903`, platform tcc803x\n- Mapped from this car\'s files: 351 points found, 0 not found\n' + JSON)
check('the app\'s markdown report', ('dup', 274), decide(r, trackers))
check('its version and points read', True, 'HMG-API 1.1.0-rc.4, 351 points found, 0 not found' in messages(r, 'dup', 274)['tracker'])

r = issue(311, 'Car Report: Ioniq 5 SGP 251120', QR % 'NEEV.SGP.0000.V004.001.251120' + JSON)
check('firmware from 2025: closed as old', 'old', decide(r, trackers)[0])
check('the old-firmware answer names its version', True, 'is on 251120' in messages(r, 'old', decide(r, trackers)[1])['report'])

r = issue(308, 'Car Report: null KOR 230911', QR % 'LX2.KOR.0000.V031.001.230911')
check('a 2023 build: old', 'old', decide(r, trackers)[0])

r = issue(320, 'Hyundai Palisade 2020-22 — USA 260903 (LX2)', 'Software on my car: LX2.USA.S5W_M.V006.001.210621\n'
          'Software this request is about: LX2.USA.S5W_M.V016.010.260903\n')
check('not titled "Car Report:": left alone', 'skip', decide(r, trackers)[0])
r['title'] = 'Car Report: Palisade USA 210621'
check('names a 2021 and a 2026 version: left for a maintainer', 'skip', decide(r, trackers)[0])
check('both versions read, in order', [('LX2', 'USA', 210621), ('LX2', 'USA', 260903)], products(r['body']))

r = issue(400, 'Car Report: EV6 USA 250101', 'my car, no product string')
check('title only, old: closed as old', ('old', (None, 'USA', 250101)), decide(r, trackers))
r = issue(401, 'Car Report: something', 'please support my car')
check('nothing to read: left alone', 'skip', decide(r, trackers)[0])
r = issue(402, 'Car Report: null KOR 260902', QR % 'CEEV.KOR.0000.V039.001.260902')
check('"null" name: the model instead', ('new', 'Car tracker: CEEV — Korea 260902 (CEEV)'), decide(r, trackers))
r = issue(231, 'Car Report: G70 CAN 260903', QR % 'IKFL.CAN.0000.V016.010.260903')
check('a tracker never matches itself; suffix after the models is fine', ('dup', 231), decide(dict(r, number=999), trackers))
check('the report text never mentions anyone', False, '@' in messages(issue(5, 'Car Report: x', '@someone ' + QR % 'CVEV.USA.0000.V015.012.260507'), 'dup', 6)['tracker'])
sys.exit(fail)
EOF
