#!/usr/bin/env python3
"""Car Report triage: what to do with a new "Car Report:" issue (.github/workflows/car-triage.yml).

  tools/car-triage.py <issue number> [--dry-run]     decide and act (gh, GH_TOKEN)
  tools/car-triage.py --decide <issue.json> <open-issues.json>   decide only, print the action

One open tracker per build keeps the reports for a car in one place, so a new report is:
  - firmware older than 2026 (every product it names is below 260000): answered with
    "update the head unit first" and closed (not planned). A report that also names a
    2026 version (an owner asking about the version they want) is left for a maintainer.
  - the same model, region and firmware as an open "Car tracker:" issue: its summary goes
    on the tracker, the report gets a pointer there and is closed as a duplicate.
  - otherwise: the report becomes that build's tracker (retitled "Car tracker: ...").
Nothing it can't read (no product string) is touched. It never copies the owner's own
words to another issue (only the facts the QR code fills in), so nobody is mentioned twice.
"""
import json, os, re, subprocess, sys

REGIONS = {'USA': 'USA', 'CAN': 'Canada', 'KOR': 'Korea', 'IND': 'India', 'SGP': 'Singapore',
           'AUS': 'Australia', 'NZL': 'New Zealand', 'EUR': 'Europe', 'GEN': 'General', 'MEX': 'Mexico',
           'RUS': 'Russia', 'CHN': 'China', 'JPN': 'Japan', 'BRA': 'Brazil', 'ARB': 'Middle East'}
NAMES = {v.lower(): k for k, v in REGIONS.items()}
OLDEST = 260000   # firmware from 2026 on (CLAUDE.md, Recommendations)

# CVEV.USA.0000.V015.012.260507, LX2.USA.S5W_M.V016.010.260903
PRODUCT = re.compile(r'\b([A-Z0-9]{2,12})\.([A-Z]{3})\.[A-Z0-9_]{1,8}\.V\d{3}\.\d{3}\.(\d{6})\b')
PRODUCT_LINE = re.compile(r'^\s*[-*]?\s*(?:Product:\s*|.*\bproduct\s+`)' + PRODUCT.pattern, re.M | re.I)
TITLE_REPORT = re.compile(r'^Car Report:\s*(.*?)\s+([A-Z]{3})\s+(\d{6})\s*$')
TITLE_TRACKER = re.compile(r'^Car tracker:.*?[—-]\s*([A-Za-z][A-Za-z ]*?)\s+(\d{6})\s*\(([^)]+)\)')
JSON_ATTACHED = re.compile(r'\]\(https://github\.com/user-attachments/files/\d+/[^)\s]*\.json\)')
VERSION = re.compile(r'^\s*[-*]?\s*(?:HMG-API:?\s*|.*\bHMG-API\s+)(\d+\.\d+\.\d+(?:-[a-z]+\.\d+)?)', re.M)
FOUND = re.compile(r'Found on this car:\s*([^\n]+)|Mapped from this car.s files:\s*([^\n]+)')
KIND = re.compile(r'### What do you need\?\s*\n+([^\n#]+)')

RELEASES = 'https://github.com/Open-HMG/HMG-API/releases'
REPORT_HELP = ('USB stick: HMG-API reports; adb: `adb pull /data/hmgapi/public/reports/`')


def products(text):
    """every product string in the text, as (model, region, firmware int), first the Product: line"""
    out = []
    for m in list(PRODUCT_LINE.finditer(text)) + list(PRODUCT.finditer(text)):
        p = (m.group(1), m.group(2), int(m.group(3)))
        if p not in out:
            out.append(p)
    return out


def tracker_key(title):
    """("REGION", firmware, {models}) of a "Car tracker: ... — Canada 260206 (SG2EV)" title"""
    m = TITLE_TRACKER.match(title or '')
    if not m:
        return None
    region = NAMES.get(m.group(1).strip().lower())
    if not region:
        return None
    models = {s for s in re.split(r'[\s/,]+', m.group(3).upper()) if s}
    return region, int(m.group(2)), models


def summary(issue):
    """one line of facts for the tracker: product, HMG-API version, points, .json or not"""
    body = issue.get('body') or ''
    ps = products(body)
    bits = ['`%s`' % full_product(body, ps[0]) if ps else 'no product']
    v = VERSION.search(body)
    if v:
        bits.append('HMG-API ' + v.group(1))
    f = FOUND.search(body)
    if f:
        bits.append((f.group(1) or f.group(2)).strip())
    k = KIND.search(body)
    if k and not k.group(1).startswith('_'):
        bits.append('asks: ' + k.group(1).strip())
    bits.append('.json attached' if has_json(issue) else 'no .json yet')
    return ', '.join(bits)


def full_product(body, p):
    for m in PRODUCT.finditer(body):
        if (m.group(1), m.group(2), int(m.group(3))) == p:
            return m.group(0)
    return '%s.%s %d' % p


def has_json(issue):
    texts = [issue.get('body') or ''] + [c.get('body') or '' for c in issue.get('comments') or []]
    return any(JSON_ATTACHED.search(t) for t in texts)


def decide(issue, open_issues):
    """(action, detail): ('old', product) | ('dup', tracker number) | ('new', title) | ('skip', why)"""
    title = issue.get('title') or ''
    if not title.startswith('Car Report:'):
        return 'skip', 'not a Car Report'
    body = issue.get('body') or ''
    ps = products(body)
    t = TITLE_REPORT.match(title)
    if not ps and t:   # a title from the QR code but no product in the body: firmware only
        if int(t.group(3)) < OLDEST:
            return 'old', (None, t.group(2), int(t.group(3)))
        return 'skip', 'no product string in the report'
    if not ps:
        return 'skip', 'no product string in the report'
    if all(p[2] < OLDEST for p in ps):
        return 'old', ps[0]
    model, region, fw = ps[0]
    if fw < OLDEST:   # on old firmware, asking about a newer one: a maintainer's call
        return 'skip', 'names firmware older and newer than 2026'
    for o in sorted(open_issues, key=lambda o: o['number']):
        if o['number'] == issue['number']:
            continue
        k = tracker_key(o.get('title'))
        if k and k[0] == region and k[1] == fw and model in k[2]:
            return 'dup', o['number']
    name = t.group(1).strip() if t else ''
    if not name or name.lower() == 'null':
        name = model
    return 'new', 'Car tracker: %s — %s %d (%s)' % (name, REGIONS.get(region, region), fw, model)


def messages(issue, action, detail):
    """{'tracker': comment on the tracker, 'report': comment on the report} for an action"""
    n = issue['number']
    ask_json = '' if has_json(issue) else (
        '\n\nCould you attach the `.json` Car Report file%s (%s)? The map work needs it.'
        % (' there' if action == 'dup' else ' here', REPORT_HELP))
    if action == 'old':
        _, region, fw = detail
        return {'report': (
            'Thanks for the report! This head unit is on %d. HMG-API supports head unit software '
            'from 2026 on (`26xxxx`) for now, and each region\'s newest version is the one we map.\n\n'
            'Please update the head unit to the newest software for your region (the update site for '
            'your car\'s make, or the dealer), then send a new Car Report from the '
            '[latest HMG-API release](%s). A report from the new software is what a map needs. '
            'Closing this one for now; the new report can go in a new issue.\n\n'
            '<sub>Sorted automatically by firmware version. If this is wrong, say so here and a '
            'maintainer will reopen it.</sub>' % (fw, RELEASES))}
    if action == 'dup':
        return {
            'tracker': 'Car Report #%d: %s.' % (n, summary(issue)),
            'report': (
                'Thanks! Folded into #%d, the tracker for this build: one open issue per build keeps '
                'the reports and the follow-ups in one place. Closing this doesn\'t mean everything in '
                'it is fixed; please follow up in #%d.%s\n\n'
                '<sub>Sorted automatically by model, region and firmware. If it\'s the wrong tracker, '
                'say so here and a maintainer will reopen it.</sub>' % (detail, detail, ask_json))}
    if action == 'new':
        return {'report': (
            'Thanks! There\'s no tracker for this build yet, so this issue is now its tracker. '
            'Further reports and follow-ups for this model, region and firmware go here.%s\n\n'
            '<sub>Sorted automatically by model, region and firmware.</sub>' % ask_json)}
    return {}


def gh(*args, data=None):
    r = subprocess.run(['gh'] + list(args), input=data, capture_output=True, text=True)
    if r.returncode:
        sys.exit('gh %s: %s' % (' '.join(args[:3]), r.stderr.strip()))
    return r.stdout


def load(number):
    issue = json.loads(gh('issue', 'view', str(number), '--json', 'number,title,body,state,comments,labels'))
    opened = json.loads(gh('issue', 'list', '--state', 'open', '--label', 'car-report', '--limit', '1000',
                           '--json', 'number,title'))
    return issue, opened


def act(issue, action, detail, dry):
    n = str(issue['number'])
    msg = messages(issue, action, detail)
    steps = []
    if action == 'old':
        steps = [('issue', 'comment', n, '--body-file', '-', msg['report']),
                 ('issue', 'close', n, '--reason', 'not planned')]
    elif action == 'dup':
        steps = [('issue', 'comment', str(detail), '--body-file', '-', msg['tracker']),
                 ('issue', 'comment', n, '--body-file', '-', msg['report']),
                 ('issue', 'close', n, '--duplicate-of', str(detail))]
    elif action == 'new':
        steps = [('issue', 'edit', n, '--title', detail),
                 ('issue', 'comment', n, '--body-file', '-', msg['report'])]
    for s in steps:
        if '--body-file' in s:
            args, body = s[:-1], s[-1]
        else:
            args, body = s, None
        if dry:
            print('gh', ' '.join(args), ('<<\n' + body) if body else '')
        else:
            gh(*args, data=body)


def main():
    a = sys.argv[1:]
    if a and a[0] == '--decide':
        issue = json.load(open(a[1]))
        print(json.dumps(decide(issue, json.load(open(a[2])))))
        return
    if not a or not a[0].isdigit():
        sys.exit(__doc__)
    issue, opened = load(int(a[0]))
    if issue.get('state') != 'OPEN':
        print('#%d is closed: nothing to do' % issue['number'])
        return
    action, detail = decide(issue, opened)
    print('#%d: %s %s' % (issue['number'], action, detail))
    act(issue, action, detail, '--dry-run' in a)


if __name__ == '__main__':
    main()
