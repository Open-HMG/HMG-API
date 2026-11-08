# Car trees: testing on the stock files you have

Some tests need a car's real stock files: the hook maps are rebuilt from them, every hook
point is looked for in them, the on-car mapper is compared against `tools/map-car.py` on
them, and the installers run against the car's own `app_process`. Those files are the
manufacturer's, so they never go in this repo, a PR, an issue or a log you post. What you
share is the result: `tools/cars.py test --report`.

## Layout

Keep each car's stock `/system` in `$HMGAPI_CARS` (default `~/hmg-cars`), laid out like
`maps/`:

```
~/hmg-cars/
  kia/ev6/usa/260507/system/          the US EV6 on 260507: its /system
    app/HKMC_GPM.apk, app/HKMC_GPM.odex, ...
    framework/framework.odex, ...
    bin/app_process
    lib/, onebin/, etc/, build.prop   (if you have them)
  hyundai/neev/can/260206/system/
```

The folder is the car's map folder (`maps/<make>/<model>/<region>/<firmware>`), so it is
also the car's name in every result. A tree may be partial (the framework and a few
apps): what needs a file it doesn't have is skipped, not failed. `system` can be a
symlink, and so can anything in it. A folder with `app/` or `framework/` and no
`system/` is read as the `/system` tree itself.

Only stock files, from your own car or a model-level source, as the car has them before
anything is installed: a dump taken after HMG-API, NEMESIS packages or older mods were
installed has their changes in it. Never put `/data`, the telesecurity partition,
certificates or anything else per-car in a tree.

## Commands

```
tools/cars.py list
```

Every tree, against its map's `car.json` md5s:

- **exact**: every file the map was made from is there, with the map's md5
- **partial**: the ones that are there match; the ones that aren't are listed
- **different**: these files' md5s aren't the map's: not stock (modified by an install),
  or another firmware. Fix the tree, or put it under the firmware it is
- **no map**: no `maps/` folder for it yet (a car or firmware not mapped:
  `tools/map-car.py`)

```
tools/cars.py test [--only kia/ev6/usa/260507] [--suites maps,hooks] [--mods ../hmgapi-wireless-carplay-aa]
```

Every suite that needs stock files, on every tree, one at a time, at the lowest CPU
priority. It is heavy (a full tree is decompiled several times; each needs ~400 MB in
the temp dir): start with one car (`--only`). Each suite's output is in
`out/cars/<car>/<suite>.log`.

| suite | what | skipped when |
|---|---|---|
| `maps` | `tools/check-maps.py --rebuild`: the map made again from the tree comes out identical | no map, the tree isn't exact, no baksmali |
| `hooks` | `tools/test-hooks.py`: every hook point, no regression against `maps/coverage.json` | no baksmali |
| `probe` | `tools/probe-test.py`: the on-car mapper finds what `map-car.py` finds | no baksmali or javac |
| `install` | `install/common/install-test.sh` with the car's `bin/app_process` | no `bin/app_process`, no `./build.sh payload` |
| `usb` | `install/usb/test.sh`, the same | the same |
| `mod:<id>` | each mod in the repos `--mods` (or `$HMGAPI_MODS`) names: its `test.sh` and `tools/*/test.sh` through the mod SDK, with the car | no SDK (`$HMGAPI_SDK`, or `./build.sh sdk`), the car isn't in the mod's `"system"` |

The result is a table, car × suite: `ok`, `FAIL` or `skip` (with why). The exit code is 1
on any FAIL.

```
tools/cars.py test --report
```

The same run, printed as Markdown for the PR's "Tested on cars" section: per car its
`list` result, a digest, and the suites. The digest is one md5 over the map's file names
and md5s the tree has (which `car.json` already publishes): two people with the same
digest tested the same files. Nothing else from the files and no paths from your machine
are in it.

```
tools/cars.py where <dir>...
```

For trees kept some other way: the map each one fits (by md5) and the `mkdir`/`ln`
commands that would link it into `$HMGAPI_CARS`. It moves nothing. A tree that fits no
map, or two maps equally (apps that are the same on both cars), is listed as such.

## Coverage

`maps/coverage.json` is the last `hooks` result per car: point ids and car names only.
`cars.py test` updates the rows of each exact or partial car whose `hooks` suite passed;
every other car's rows stay as they are, so maintainers with different cars each add
theirs. Commit the change with the PR.

## Mod tests

A mod repo's test that needs stock files reads the car from the SDK's `test/lib.sh`:

```
S=$(car_tree) || { echo "no stock tree (skipped)"; exit 0; }
PRODUCT=$(car_prop ro.build.product)
```

`cars.py test --mods <repo>` sets `HMGAPI_CAR` (that car's `/system`), `HMGAPI_CAR_ID`
(`kia/ev6/usa/260507`) and `HMGAPI_CAR_PRODUCTS` (its map's products) for each car. A test
never names a path of its own.

## The old lists

`tools/hook-trees.local` and `tools/map-trees.local` (`<label or map folder> <tree>`
lines) still work, with a note that they are going away: a map folder replaces that
car's tree, and a label becomes the car whose map the tree fits. Move the trees into
`$HMGAPI_CARS` (`cars.py where` prints the commands) and delete the files.
