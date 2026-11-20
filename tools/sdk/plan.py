#!/usr/bin/env python3
"""Fake head units for a mod's plan test, from HMG-API's hook maps (in the mod SDK as
test/plan.py; test/lib.sh's plan_* functions call it, see there).

  plan.py cars <maps> <mod dir>
      one line per car the mod's "system" names that has a map: "<map folder> <product>",
      e.g. "kia/ev6/usa/260507 CVEV.USA.0000.V015.012.260507". A map with several
      products (the 2022 and 2023 Ioniq 5) gives a line for each. Only what a map says
      about a car counts (make, model, name, region, firmware, product): the rest of
      "system" (features, props, year...) is the fake unit's to fake (below).
  plan.py unit <root> <maps> <map folder> <product> <mod dir>...
      a fake head unit at <root> (emptied first) for that car: its maps, a stand-in for
      every stock file a map was made from (the maps' md5s are the stand-ins', so the
      engine takes the map as this car's), the features the mods' "system" ask for, and
      the mods in data/hmgapi/mods/<id> (without test.sh). No car file is in it.
  plan.py unmapped <root> <mod dir> [<point>...]
      the hook points the mod declares (hooks.points), and any named, that the unit's
      map hasn't: one per line, with why. Nothing when all are mapped.
  plan.py requires <mod dir> [<mod dir>...]
      the ids the first mod requires ("requires") that none of the others is.
  plan.py problems [file]
      engine plan's problems (stdin or file) except those about the fake unit's stand-in
      files, which no stock file was ever behind (missing, not stock, a delta that
      doesn't fit): mod.json mistakes, a required mod missing, conflicts between mods.
  plan.py shared <mod id> [file]
      the hook points engine plan's output says the mod shares: "<point>: <the others>".

No manufacturer files: the maps are HMG-API's (point names, md5s), the stand-ins a line
of text each. Only python3's standard library.
"""
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

# what a map tells about a car; the rest of "system" is the fake unit's (features) or unknown here
CAR_KEYS = ("make", "model", "name", "region", "firmware", "product")
MIDDLE = "0000.V000.000."   # a product's middle fields, when the map gives no whole product


def die(msg):
    sys.stderr.write("plan.py: %s\n" % msg)
    sys.exit(2)


def load(path):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError) as e:
        die("%s: %s" % (path, e))


def mod_json(mod):
    m = load(os.path.join(mod, "mod.json"))
    if not isinstance(m, dict) or not m.get("id"):
        die("%s/mod.json has no id" % mod)
    return m


def setups(system):
    """mod.json "system" as a list of setups (dicts); [] for any unit"""
    if system is None or system in ("all", "*"):
        return []
    return system if isinstance(system, list) else [system]


def maps_of(maps):
    out = []
    for cj in sorted(glob.glob(os.path.join(maps, "*", "*", "*", "*", "car.json"))):
        out.append((os.path.relpath(os.path.dirname(cj), maps), load(cj)))
    return out


def sdk_jar():
    here = os.path.dirname(os.path.abspath(__file__))
    for j in (os.path.join(here, "..", "lib", "hmgapi-engine.jar"), os.environ.get("ENGINE", "")):
        if j and os.path.isfile(j):
            return j
    die("no hmgapi-engine.jar (run from the SDK's test/, or set ENGINE)")


def cars(maps, mod):
    m = mod_json(mod)
    wanted = setups(m.get("system"))
    # the engine's own check (SystemCheck), on the car keys of each setup only
    keep = [{k: v for k, v in s.items() if k in CAR_KEYS} for s in wanted if isinstance(s, dict)]
    tmp = tempfile.mkdtemp()
    try:
        with open(os.path.join(tmp, "mod.json"), "w") as f:
            json.dump({"id": m["id"], "system": keep if keep else "all"}, f)
        os.makedirs(os.path.join(tmp, "root"))
        for folder, car in maps_of(maps):
            whole = (car.get("verified") or {}).get("product") or ""
            for prefix in car.get("products") or []:
                product = whole if whole.startswith(prefix) else prefix + MIDDLE + str(car.get("firmware"))
                env = dict(os.environ, HMGAPI_ROOT=os.path.join(tmp, "root"), HMGAPI_PRODUCT=product)
                r = subprocess.run(["java", "-cp", sdk_jar(), "hmgapi.engine.SystemCheck", tmp], env=env,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True)
                if r.returncode == 0:
                    print(folder, product)
                elif not r.stdout.startswith(m["id"] + ": "):
                    die("SystemCheck: " + r.stdout.strip())
    finally:
        shutil.rmtree(tmp)


def unit(root, maps, folder, product, mods):
    if not os.path.isfile(os.path.join(maps, folder, "car.json")):
        die("no map %s in %s" % (folder, maps))
    if os.path.exists(root):
        shutil.rmtree(root)
    data = os.path.join(root, "data", "hmgapi")
    for d in ("system/etc/permissions", "data/hmgapi/mods", "data/hmgapi/settings/allow", "data/hmgapi/public"):
        os.makedirs(os.path.join(root, d))
    model, region = (product.split(".") + ["", ""])[:2]
    # the onebin folder isn't in the maps: <model>_<2 letters of the region> (cvev_us on the US EV6)
    onebin = os.environ.get("HMGAPI_ONEBIN") or "%s_%s" % (model.lower(), region[:2].lower())
    props = {"ro.build.product": product, "ro.build.version.release": "4.4.2", "ro.mobis.onebindir": onebin}
    with open(os.path.join(root, "props.txt"), "w") as f:
        f.writelines("%s=%s\n" % kv for kv in props.items())
    with open(os.path.join(root, "system", "build.prop"), "w") as f:
        f.writelines("%s=%s\n" % kv for kv in props.items())
    shutil.copytree(maps, os.path.join(data, "maps"))
    # every point this HMG-API has (the SDK's points.txt): a point a map lacks is "newer than the map"
    sdk_points = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "points.txt")
    if not os.path.isfile(os.path.join(data, "maps", "points.txt")) and os.path.isfile(sdk_points):
        shutil.copy(sdk_points, os.path.join(data, "maps", "points.txt"))
    # a stand-in for each stock file a map was made from, and the maps' md5s the stand-ins'
    for cj in glob.glob(os.path.join(data, "maps", "*", "*", "*", "*", "car.json")):
        car = load(cj)
        files = car.get("files")
        if not isinstance(files, dict):
            continue
        for path in files:
            f = root + path
            if not os.path.exists(f):
                os.makedirs(os.path.dirname(f), exist_ok=True)
                with open(f, "wb") as o:
                    o.write(("stand-in for " + path + "\n").encode())
            with open(f, "rb") as i:
                files[path] = hashlib.md5(i.read()).hexdigest()
        with open(cj, "w") as o:
            json.dump(car, o, indent=2)
    # a map not checked on a car yet (mapped, probed) runs as with the app's "testing maps" switch
    open(os.path.join(data, "settings", "allow", "testing_maps"), "w").close()
    # the features the mods ask for: the fake unit has them (hmgapi.wifi: a Wi-Fi chip seen once)
    features = set()
    ids = []
    for mod in mods:
        m = mod_json(mod)
        ids.append(m["id"])
        for s in setups(m.get("system")):
            fs = s.get("features") if isinstance(s, dict) else None
            for x in ([fs] if isinstance(fs, str) else fs or []):
                if isinstance(x, str) and not x.startswith("!") and x not in ("all", "*"):
                    features.add(x)
        dst = os.path.join(data, "mods", m["id"])
        if os.path.exists(dst):
            die("two mods with the id %s" % m["id"])
        shutil.copytree(mod, dst, symlinks=True)
        if os.path.exists(os.path.join(dst, "test.sh")):
            os.remove(os.path.join(dst, "test.sh"))
    if "hmgapi.wifi" in features:
        features.discard("hmgapi.wifi")
        open(os.path.join(data, "public", "wifi-chip"), "w").close()
    with open(os.path.join(root, "system", "etc", "permissions", "hmgapi-plan-test.xml"), "w") as f:
        f.write("<permissions>\n%s</permissions>\n" % "".join('    <feature name="%s" />\n' % x for x in sorted(features)))
    with open(os.path.join(root, ".plan-car"), "w") as f:
        f.write("%s %s %s\n" % (folder, product, onebin))


def unmapped(root, mod, extra):
    try:
        with open(os.path.join(root, ".plan-car")) as f:
            folder = f.read().split()[0]
    except OSError:
        die("%s isn't a fake unit from plan.py unit" % root)
    d = os.path.join(root, "data", "hmgapi", "maps", folder)
    car = load(os.path.join(d, "car.json"))
    have = {}
    for app in car.get("apps") or []:
        have.update(load(os.path.join(d, app + ".json")))
    missing = car.get("missing") or {}
    points = (mod_json(mod).get("hooks") or {}).get("points") or {}
    want = list(points) + [p for p in extra if p not in points]
    for p in want:
        if p in missing:
            print("%s: not found in this car's apps (%s)" % (p, missing[p]))
        elif p not in have:
            print("%s: not in this car's map (newer than the map, or not a point HMG-API has)" % p)


def requires(mod, given):
    """the ids the mod requires that none of the given mod folders is"""
    have = {mod_json(g)["id"] for g in given}
    r = mod_json(mod).get("requires")
    for dep in (r if isinstance(r, list) else []):   # as the engine reads it (Version.deps)
        dep_id = str(dep.get("id")) if isinstance(dep, dict) else (str(dep).split() or [""])[0]
        if dep_id and dep_id not in have:
            print(dep_id)


def lines(path):
    if path:
        with open(path) as f:
            return f.read().splitlines()
    return sys.stdin.read().splitlines()


# problems the fake unit causes: a file a mod changes isn't there, or is a stand-in, not stock
UNIT_FILE = re.compile(r"^/\S+: (not on this head unit|not the stock file|already on the head unit|"
                       r".*doesn't fit|can't add classes\.dex|codec changes need the file|"
                       r".* built \S+, not the expected result|the props edit would lose)")


def problems(path):
    seen = False
    for line in lines(path):
        if re.match(r"^nothing changed: \d+ problem", line):
            seen = True
            continue
        if not seen or not line.startswith("  "):
            continue
        p = line[2:]
        if UNIT_FILE.match(re.sub(r"^conflict: ", "", p)):
            continue
        print(p)


def shared(mod_id, path):
    for line in lines(path):
        m = re.match(r"^hooks +(\S+): (.*) share it$", line)
        if not m:
            continue
        mods = [x.strip() for x in m.group(2).split(", ")]
        if any(x.split(" (")[0] == mod_id for x in mods):
            print("%s: %s" % (m.group(1), ", ".join(x for x in mods if x.split(" (")[0] != mod_id)))


def main(a):
    if len(a) >= 3 and a[0] == "cars":
        cars(a[1], a[2])
    elif len(a) >= 6 and a[0] == "unit":
        unit(a[1], a[2], a[3], a[4], a[5:])
    elif len(a) >= 3 and a[0] == "unmapped":
        unmapped(a[1], a[2], a[3:])
    elif len(a) in (1, 2) and a[0] == "problems":
        problems(a[1] if len(a) > 1 else None)
    elif len(a) >= 2 and a[0] == "requires":
        requires(a[1], a[2:])
    elif len(a) in (2, 3) and a[0] == "shared":
        shared(a[1], a[2] if len(a) > 2 else None)
    else:
        sys.stderr.write(__doc__)
        sys.exit(2)


if __name__ == "__main__":
    main(sys.argv[1:])
