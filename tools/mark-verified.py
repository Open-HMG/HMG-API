#!/usr/bin/env python3
"""Mark a car's hook map verified, from `hmgapid engine verify-map` run on that car.

usage: tools/mark-verified.py <map-check.json> --by <who> [--maps DIR]

  <map-check.json>  the car's /data/hmgapi/state/map-check.json, copied off the unit
                    right after `hmgapid engine verify-map` passed there:
                      adb shell "hmgapid sh 'cat /data/hmgapi/state/map-check.json'" > map-check.json

Checks that the report passed (ok, nothing failed), names a map in this repo, was made
from the same stock files as that map (car.json "files"), and comes from one of the
map's cars (ro.build.product starts with one of its products and ends with its
firmware). Then sets the map's car.json status to "verified" with a record: who, when,
and what was checked. Only model-level facts are kept (the map folder, the product
string, point counts). Nothing per-car. tools/check-maps.py then wants that record.
"""
import argparse, datetime, json, os, sys

ap = argparse.ArgumentParser()
ap.add_argument("report")
ap.add_argument("--by", required=True)
ap.add_argument("--maps", default=os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "maps"))
a = ap.parse_args()

raw = open(a.report, "rb").read().decode("utf-8", "replace").replace("\r", "").strip()
try:
    rep = json.loads(raw)
except ValueError:
    sys.exit("%s isn't verify-map's report (map-check.json): %s\n"
             "Copy it again, right after `adb shell hmgapid engine verify-map` passed:\n"
             "  adb shell \"hmgapid sh 'cat /data/hmgapi/state/map-check.json'\" > map-check.json"
             % (a.report, "it's empty" if not raw else "it starts with %r" % raw[:200]))
if not isinstance(rep, dict):
    sys.exit("%s isn't verify-map's report (not a JSON object)" % a.report)
folder = str(rep.get("folder") or "")
if not folder or ".." in folder.split("/") or folder.startswith("/"):
    sys.exit("the report names no map folder")
cj = os.path.join(a.maps, folder, "car.json")
if not os.path.isfile(cj):
    sys.exit("no map %s here (%s)" % (folder, cj))
car = json.load(open(cj))
why = []
if rep.get("ok") is not True or rep.get("failed"):
    why.append("verify-map didn't pass: %s" % "; ".join(map(str, rep.get("failed") or ["ok is not true"])))
if rep.get("files") != car.get("files"):
    why.append("the report's stock files aren't the ones this map was made from (map changed since? run verify-map again)")
prod = str(rep.get("product") or "")
fw = str(car["firmware"])
if not (any(prod.startswith(p) for p in car["products"]) and prod.endswith("." + fw)):
    why.append("the report is from %r, not one of this map's cars (%s, firmware %s)" % (prod, ", ".join(car["products"]), fw))
if why:
    sys.exit("%s: not marked verified:\n  %s" % (folder, "\n  ".join(why)))

car["status"] = "verified"
car["verified"] = {
    "by": a.by,
    "date": datetime.date.today().isoformat(),
    "product": prod,
    "points": rep.get("points"),
    "report": "hmgapid engine verify-map passed on the car (%s, %s)" % (prod, rep.get("date")),
}
with open(cj, "w") as f:
    json.dump(car, f, indent=2)
    f.write("\n")
print("%s: verified (%s hook points, %s)" % (folder, rep.get("points"), prod))
