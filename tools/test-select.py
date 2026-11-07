#!/usr/bin/env python3
"""Which off-car tests a change needs: changed paths (one per line, stdin) in, the
test scripts to run out (one per line, in tools/test-all.sh's order), or nothing
when only documentation changed. A path no rule knows runs everything, so a new
folder is tested until it gets a rule.

usage: git diff --name-only BASE HEAD | tools/test-select.py
"""
import fnmatch, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

ALL = 'ALL'
RUNTIME = ['core/runtime/*-test.sh']
INSTALL = ['install/common/test.sh', 'install/common/install-test.sh', 'install/usb/test.sh', 'install/nemesis/mod-test.sh', 'install/nemesis/sync-test.sh']
MODS = ['tools/check-scripts.sh']   # the example mods' scripts (their tests run in sdk-test)
HOOKS = ['core/hookpoints/test.sh']
SDK = ['tools/sdk-test.sh']

# (path pattern, tests): every rule a path matches adds its tests; first match of DOCS ends it
DOCS = ['docs/*', 'wiki/*', '*.md', '.github/ISSUE_TEMPLATE/*', '.github/release-notes/*', '.github/pull_request_template.md',
        'LICENSE', 'NOTICE.md', '.gitignore', 'docs/img/*']
RULES = [
    # the build itself, the test runner, the CI: everything
    (['build.sh', 'tools/env.sh', 'tools/test-all.sh', 'tools/test-select.py', '.github/workflows/ci.yml',
      'core/loader/*', 'core/client/*'], ALL),
    (['core/hmgapid/*'], ['core/hmgapid/test.sh'] + INSTALL),
    (['core/zygote/*'], ['core/zygote/test.sh'] + INSTALL),
    (['tools/lpatch/*'], ['tools/lpatch/test.sh', 'core/runtime/native-test.sh', 'core/runtime/engine-test.sh'] + INSTALL + SDK),
    (['core/runtime/unit-commands.txt'], ['tools/check-scripts.sh']),
    (['core/runtime/*'], RUNTIME + HOOKS + MODS + INSTALL + SDK),
    (['core/hookpoints/*'], SDK),   # the SDK's points.txt and catalogue
    (['core/hookpoints/*', 'maps/*', 'tools/map-car.py', 'tools/check-maps.py', 'tools/mkhookdoc.py', 'docs/hook-points.md', 'tools/test-hooks.py', 'tools/probe-test.py'], HOOKS),
    (['core/app/*'], ['core/app/qr-test.sh']),
    (['mods/examples/*'], ['core/runtime/signing-test.sh', 'install/nemesis/mod-test.sh', 'tools/check-scripts.sh'] + SDK),   # hello: its test.sh runs in sdk-test
    (['install/*'], INSTALL + ['core/runtime/packages-test.sh']),
    (['install/nemesis/mod/*', 'install/nemesis/bin/open.sh'], SDK),
    (['tools/mkdelta.py', 'tools/mkrecipe.py', 'tools/mkmemrecipe.py'], ['core/runtime/engine-test.sh', 'core/runtime/safety-test.sh', 'tools/lpatch/test.sh']),
    (['tools/mkinstallers.sh', 'tools/mkrelease.sh', 'tools/mkmodpkg.sh', 'tools/mknemesismod.sh', 'tools/nemesis-version.py', 'tools/nemesis-meta.py', 'tools/release-mods.txt'], INSTALL + ['core/runtime/signing-test.sh', 'core/runtime/usb-test.sh'] + SDK + MODS),
    # the mod SDK and the reusable mod workflow
    (['tools/build-mod.sh', 'tools/check-points.py', 'tools/sdk/*', 'tools/sdk-test.sh', 'tools/mkrecipe.py', 'tools/mkdelta.py', 'tools/mkmemrecipe.py', 'tools/mkicon.sh', 'tools/mod-draft.py', 'tools/check-scripts.sh', '.github/workflows/mod.yml'], SDK),
    (['tools/mod-draft*'], ['tools/mod-draft-test.sh']),
    (['catalog/*', 'tools/catalog*', 'maps/authors.json', '.github/workflows/catalog.yml'], ['tools/catalog-test.sh']),
    (['tools/micom-*'], ['tools/micom-decode-test.sh']),
    (['tools/check-scripts.sh'], ['tools/check-scripts.sh']),
    (['tools/requirements.txt'], ALL),
    (['.github/workflows/release.yml', '.github/workflows/wiki.yml', 'tools/push-dev.sh', 'tools/mock/*'], []),
]


def every_test():
    """tools/test-all.sh's list, in its order"""
    t = open(os.path.join(HERE, 'test-all.sh')).read()
    m = re.search(r'^ALL="(.*?)"$', t, re.M)
    return m.group(1).split()


def match(path, pats):
    return any(fnmatch.fnmatch(path, p) or (p.endswith('/*') and path.startswith(p[:-1])) for p in pats)


def select(paths):
    tests = every_test()
    want = set()
    for p in paths:
        p = p.strip()
        if not p or match(p, DOCS) and not match(p, [r for rule, _ in RULES for r in rule]):
            continue
        hit = False
        if p.startswith('mods/examples/'):   # an example mod: its own tests, if test-all.sh lists any
            d = '/'.join(p.split('/')[:3]) + '/'
            want.update(t for t in tests if t.startswith(d))
        for pats, got in RULES:
            if match(p, pats):
                hit = True
                if got == ALL:
                    return tests
                for g in got:
                    want.update(t for t in tests if fnmatch.fnmatch(t, g))
        if not hit:
            return tests   # unknown: test everything
    return [t for t in tests if t in want]


if __name__ == '__main__':
    for t in select(sys.stdin.read().split('\n')):
        print(t)
