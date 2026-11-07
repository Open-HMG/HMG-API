## What this changes

## Tested on

- [ ] `tools/test-all.sh` passes (or the tests `tools/test-select.py` names for this change)
- [ ] Cars (model, region, software version), or "off-car only":

## Checklist

- [ ] New or changed hook points: mapped for every car (`tools/map-car.py`), and `tools/test-hooks.py` shows no regressions on the cars we have files for
- [ ] Mod scripts: `tools/check-scripts.sh <mod>` passes
- [ ] Catalogue entry (`catalog/`, `maps/authors.json`): made with `tools/catalog.py add`, only my own author entry changed
- [ ] Anything written to `/system` keeps a stock check, a backup and an exact restore
- [ ] No manufacturer apps or libraries, no per-car data (VIN, serials, keys, `/data` contents)
- [ ] Vehicle commands go through the car's own services, no CAN frames of our own
- [ ] User-facing change: `CHANGELOG.md` and the wiki (`wiki/`) updated
