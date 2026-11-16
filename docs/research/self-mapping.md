# Research: maps and native patches that need less maintainer work

Status: research, with off-car prototypes. Nothing here changes what HMG-API does on a car
yet: the prototypes are a class the engine doesn't call (`SiteFind`), a class nothing calls
(`SelfTest`), a host tool (`tools/sitefind.py`) and their tests. **No car and no adb were
used for any of it.**

## The problem

Two things cost maintainer time for every new car, firmware and hook point:

- **Maps.** Every hook point has to be in every car's map (`maps/<make>/<model>/<region>/<firmware>`).
  A new point means a Car Report from each car, a maintainer PR, and a release. Until then the
  point is listed as missing on that car. With the EV readings (#212) and EV hook points (#214),
  the new points are mapped on one car (US Ioniq 5 260507) and listed as missing on the other
  nine maps.
- **Native patches.** A memory recipe (`MemRecipe`) pins byte offsets to one build of a library.
  Each new build needs its offsets found by hand. The Wireless mod's
  `aa-tbt-a0bea1c4.recipe` is the Canadian Ioniq 5 build of `aa-tbt-48ee793a`, "hand-ported".

The five ideas below each cut part of that work. Each section says what exists today, what
to add, how to test it with no car, and why it can't harm a head unit.

## Summary

| # | Idea | What exists today | Recommendation | Order |
|---|---|---|---|---|
| 1 | Local self-mapping of missing points | Most of it: the on-car mapper, `map-car --write`, `verify-map`, the Car Report, `report-map.py` | Build it: a per-point overlay for points the shipped map lacks, by-name points only | **1st** |
| 2 | Re-map after a firmware update | Detection: `CarMap.load` compares `car.json` "files" md5s at every start | Build it on top of 1, as a boot step, for 26xxxx firmware only | **3rd** |
| 3 | Read-only plausibility self-tests | Nothing on the unit; manual steps in `docs/car-test.md` §3 | Build it: `SelfTest` (prototype here), run by the Car Report | **2nd** |
| 4 | Recipes that find their sites | Nothing; recipes are fixed offsets | Build it: sites by masked hash and by exported symbol, resolved by the engine, cached per library md5 (prototype here) | **4th** |
| 5 | Recipe generator for any stock library | `mkrecipe.py`, `mkmemrecipe.py` (stock/patched pair → recipe) | Grow it out of 4: patch operators whose bytes are computed per build (`jump` prototyped here), and `rel32` targets by symbol | **5th** |

1 first because the gain is largest and it's mostly built: every point missing on every map today
is one the on-car mapper finds by name (below). 3 next because it's read-only, and it checks
what 1 makes possible: a point mapped on a car nobody has tested. 2 is 1 run automatically. 4
and 5 change how native patches are written, so they touch mods (the Wireless mod, egmp-aa-ev)
and are worth doing once a second build of a library is in hand.

### What changes for a mod author

- **1 and 2: nothing.** A point a mod needs is either there or not, as today. It's there on more
  cars, sooner. A mod that must not run on a point nobody has checked by hand (a `replace` hook,
  a command) gets that by default (below).
- **3: nothing to do.** A reading a mod uses gets checked on every car that sends a Car Report.
- **4 and 5: a new recipe form, opt-in.** `tools/sitefind.py relocate` turns an existing recipe
  into the relocatable form from its text. `make --also` adds masks once a second build is in
  hand. Older HMG-API versions can't read `site` lines (`MemRecipe.parse` refuses unknown lines).
  So relocatable recipes would go under their own key in `mod.json`, next to the fixed ones, and
  need the HMG-API version that resolves them. A mod keeps its fixed recipes for older versions.

## What exists today (checked against the code)

**Maps.** `CarMap.load` (`core/runtime/java/hmgapi/engine/CarMap.java:71`) runs in the zygote, the
engine and the vehicle service. It finds the map by product prefix and firmware, then:

- adds every point in `/data/hmgapi/maps/points.txt` the map doesn't have as missing, "newer than
  this car's map" (`:103`);
- compares each stock file in `car.json` "files" with the car's (or HMG-API's backup of it). A
  different app drops that app's points (`:124`). A different framework makes the whole map
  `mismatch`, hooks off (`:119`);
- runs hooks on a `verified` map, or on a `mapped`/`probed` one that passed `engine verify-map` on
  this car (`state/map-check.json`), or with the owner's "Try unchecked maps" switch
  (`testing_maps`, `:129`).

A mod or feature that needs a missing point is off; everything else runs (`Points.need`,
`Points.needAll`, `HookPlan`).

**The on-car mapper.** `MapProbe` (`engine map-car`) applies `tools/map-car.py`'s rules to the dex
in each stock odex, on the unit. Points described only by a smali regex are taken "by reference"
from other cars' maps, if this car has the same name with the same shape. `tools/probe-test.py`
checks that it finds what `map-car.py` finds on every stock tree. `map-car` always writes its
result to `state/map-probe.json`. With `--write` it saves a whole map, status `probed`, **only
when HMG-API has no map for the car** (`MapProbe.java:352`). The Settings tab has this as "Map this
car from its files" and "Check the map on this car".

**Verify.** `engine verify-map` (`MapCheck`) loads each mapped app's stock APK in a class loader of
its own (the app doesn't start) and resolves every entry by reflection: class, method with exact
parameter and return types, field with its type, static or not. It refuses a `mismatch` map.

**Car Report.** `engine report` runs the on-car mapper every time and includes its points, the
md5s they came from, what it didn't find, and `differs_from_map` (entries that differ from the
shipped map). Maintainers turn reports into maps with `tools/report-map.py` (points must agree,
md5s by majority) and mark a map verified with `tools/mark-verified.py` from a `map-check.json`.

**Native patches.** `MemRecipe.apply` writes nothing unless every verify region's md5 is the stock
bytes'. It puts the added code in first, then patches, then branches, and checks every region
came out as the recipe says. `NativePatch` (in each app process) uses the first of a mod's recipes
that fits the library and refuses a recipe whose regions overlap another mod's on the same library.
The library file is never changed. Turning a mod off takes an app restart.

**Rollback.** The zygote wrapper's boot guard (`core/zygote/zygote.c`) turns hooks off after three
starts that didn't last 120 s. `engine recover` rolls back an apply that was cut off.

### Two gaps found in passing

- `map-car --write` refuses whenever a map folder matched, **including a `mismatch` one**:
  `CarMap.load` sets `folder` before it compares md5s. So a car whose framework build differs
  from its map (same firmware number) can't map itself today.
- A `probed` map written by `map-car --write` goes into `/data/hmgapi/maps`, which every install or
  update of HMG-API **replaces as a whole** (`install/common/install.sh:192`). The owner's own map
  is lost at the next update. Anything kept on the unit has to live outside `maps/`.

### How much idea 1 would cover

Every catalogue point is described by name, by shape, or by a smali pattern. A by-name point
(its class by name, the member by name) can only be found or not found: there's nothing to guess.
Counted over `core/hookpoints` and every map's `car.json` "missing":

| | catalogue points | by name | by shape | smali only | missing, all maps | of those by name |
|---|---|---|---|---|---|---|
| `main` | 351 | 295 | 42 | 14 | 24 (3 maps) | **24** |
| `feat/ev-hook-points` (#212 + #214) | 364 | 308 | 42 | 14 | 141 (9 maps) | **141** |

Reasons in the missing lists on that branch: 102 "not in the Car Report" (the report came from an
older HMG-API), 39 "newer than this map". Not one is a point the mapper looked for and couldn't
find. Every one is a point nobody has looked for on that car yet.

## 1. Local self-mapping

**Proposal.** When a catalogue point is missing from the shipped map only because the map is
older than the point ("newer than this map", "not in the Car Report"), the unit maps it with
`MapProbe` and uses it locally, marked `probed`. The Car Report makes it official as today.

- **Where.** `/data/hmgapi/state/local-map.json`, outside `maps/` so an update keeps it. It holds,
  per point, its entry, and per app, the md5s of the stock files it came from.
- **When.** `engine map-car --local`, run by the engine after an install or update of HMG-API
  (the catalogue changed) and from the Settings tab. Not in the zygote: `CarMap.load` only reads
  the file.
- **Which points.** Only:
  - points the shipped map lists as missing for one of those two reasons;
  - points the mapper finds **by name**. No by-shape, no by-reference: those need a maintainer's
    eyes on more than one car;
  - points whose app's stock files have the md5s the shipped map was made from. Otherwise that's
    idea 2.
  
  A shipped entry is never overridden.
- **Checked before use.** The same `MapCheck` resolution as `verify-map`, run on the overlay's
  points right after mapping. Only points that resolve are written. A point that doesn't is
  reported, not used.
- **Loading.** `CarMap.load` merges the overlay after the shipped map, in its own `try`, so a
  broken file costs the overlay only, never the map (`load` turns any exception into status
  `none`, hooks off). The merge happens only if the map is runnable and every overlay md5 equals
  the car's file. `Points.source(id)` says `map` or `probed`; the app and the Car Report show which
  points are local.
- **What a probed point may do.** Readings, `observe` hooks and calls that only read, by default.
  `change`/`replace` hooks and anything a vehicle command uses need the shipped map, or the owner's
  "Try unchecked maps" switch (the same warning and override HMG-API has today).
- **Off switch.** `settings/allow/local_points`, default on, in the Settings tab next to
  "Try unchecked maps". Off, or `engine map-car --forget`: the overlay is ignored or deleted, and the
  next start runs with the shipped map alone.

**Effort.** Small: `MapProbe` already produces the entries. The work is the overlay file, the
filter, running `MapCheck` on a subset, and the merge in `CarMap.load`.

**Virtual test.**
- `core/runtime/map-test.sh`'s mock roots, extended: a shipped map listing a point as "newer than
  this map", an overlay with it. Merged only when the md5s match. A file that changed drops it. A
  broken overlay leaves the shipped map running. The switch off ignores it. A shipped entry wins.
- `tools/probe-test.py`, on every stock tree in `$HMGAPI_CARS`, with each car's own map **cut down**
  to the points `main` had before #212/#214. The overlay `MapProbe` produces must equal the entries
  the full map has (the US Ioniq 5's for #212/#214's points, `map-car.py`'s for the rest).
- `MapCheck`'s resolution, which needs Dalvik, stays an on-car step. Off-car it is covered by
  `check-maps.py --rebuild` (the same names come out of baksmali) and by the runtime compiling
  against android.jar.

**Safety.**
- **Read-only to the car.** Mapping reads odex files. Verify loads classes without starting the
  app.
- **Unique match or nothing.** `MapProbe` keeps a point only if exactly one class or member
  matches. By-name only, so there's nothing to guess.
- **Verify before use.** Every point resolves by reflection on this car before it's written.
- **No `/system` writes.** One file in `/data/hmgapi/state`.
- **Off switch.** `local_points`.
- **Rollback.** Delete the file. A probed point that crashes an app at boot is caught by the boot
  guard (hooks off after three short starts), as for any map.
- **Scope.** A point can only enable a feature that was off. Nothing that ran before changes.

## 2. Re-map after a firmware update

**What exists.** Detection is there: `CarMap.load` compares the "files" md5s at every start. A
new firmware number finds no map folder (status `none`). The same number with other files drops
the apps that differ, or the whole map if the framework differs (`mismatch`). The installer's
`firmware-reset` handles `/system` coming back stock.

**Proposal.** A boot step, after `sys.boot_completed` and at idle priority. If the map is `none`,
`mismatch`, or dropped an app for its md5s, and the car's file md5s aren't the ones the last local
mapping saw, run `map-car --local` for the whole catalogue. For `none`/`mismatch` this is a whole
local map, with the same rules as 1. Write it to `state/local-map.json`, never `maps/`, and fix the
`map-car --write` refusal on a `mismatch` folder. Then:
- only for **26xxxx firmware** (the supported range; anything else: a warning and the owner's
  override, as for other unsupported cars);
- hooks still need a restart, and the owner sees "mapped on this car after the firmware update"
  in the app;
- a Car Report from that car sends the new firmware's map upstream.

**Virtual test.**
- `map-test.sh` mock roots: change a stock file's bytes and the product's firmware. The boot step
  must decide to map, map once (not at every start: the md5s are remembered), and write only
  `state/`.
- On stock trees: two trees of the same car on two firmwares. The US Ioniq 5 (`NEEV23.USA.`) has
  maps on 260507 and 260903; with both trees, mapping the newer one from the older map's state
  must give the newer map.

**Safety.** As 1: read-only mapping, verify before use, `/data` only, the same switch. Points of
an app whose stock file changed are never reused from the old build: they're mapped again or
missing. The old firmware's local map is invalid by its md5s, so it can't be used by mistake.

## 3. Read-only self-tests of the readings

**Proposal.** `engine self-test` takes about a minute of samples of the vehicle readings through
the vehicle service, the way a mod reads them (`hmgapid vehicle ...`, every 5 s). It checks
them and puts the result in the Car Report (`self_test`). The checks (`SelfTest.java`):

| check | passes when | catches |
|---|---|---|
| `soc.range` | `hv.soc` is 0..100 | a change to the reading's code (`Hub` already refuses raw values over 200, so today it can't fail) |
| `energy.capacity` | `hv.capacity_nominal` is 20..120 kWh | a wrong scale (2 Wh steps) |
| `energy.vs_soc` | `hv.energy` / (`hv.soc` % × capacity) is 0.80..1.10 (median, `hv.soc` ≥ 20) | `hv.energy` or capacity off by 2 (says which way) |
| `range.per_soc` | `range` is 0.5..10 km per % | a wrong value for `range` |
| `range.tracks_soc` | `range` and `hv.soc` correlate ≥ 0.6 over 5+ samples spanning 3 % | a point that reads some other value; a stale cache |
| `charge.target_*.range` | 0..100 in 0.5 steps | wrong flag or scale |
| `aux.sum` | `aux.power` = its three parts | the sum's code |
| `callback.drive_mode`, `callback.outside_temp` | a value arrives within the run (10 s at least) | a listener that isn't registered, or a transaction code that's another method's on this car |

Each result carries what it saw ("median ratio 0.970 over 12 samples"), so the bands can be
tightened from many cars' reports. The bands are deliberately loose for now: they catch the
factor-of-2 and wrong-point mistakes, not small calibration errors. One thing they can't catch is
`range` in miles instead of km: that's within every band. Catching it needs the cluster's distance
unit, a reading HMG-API doesn't have yet.

**Prototype here.** `core/runtime/java/hmgapi/engine/SelfTest.java` (the checks, plain Java, runs
on the unit as is) and `core/runtime/selftest-test.sh`, which replays seven runs in
`core/runtime/test/selftest/`, each with the verdicts it expects:
- one good drive;
- energy at half scale;
- `hv.soc` not halved;
- no listener callbacks;
- `range` from a wrong point;
- a wrong `aux.power` sum;
- a short parked run where most checks must skip.

The runs are **synthetic**, modelled on #212's numbers (77.4 kWh nominal, 0.5 % SOC steps). No
on-car run was recorded for this. The first real run from a car belongs in that folder.

**Still to do.** The sampler (the engine asking the vehicle service, as root through hmgapid's
socket, as a mod would). A `self_test` section in `Report.run`. A line in the Report tab.

**Safety.** Reads only. The sampler asks for readings by name, through the vehicle service's
freshness cache, so a minute of samples costs the car what a mod reading the same readings
costs. It sends no command. The only reading that makes the car send anything is `range`: the
cluster's battery info packet, at most every 2 s (#212). `drive_mode` is passive by design. A
failed check changes nothing on the unit: it's a line in the report. It writes only the report.

## 4. Library recipes that find their sites

**Proposal.** A recipe line `site <name> <len> <md5> [mask <hex>] [in <symbol>]`: a window
found where exactly **one** window has that md5, with wildcard bytes (mask bit 0) counted as 0.
`in <symbol>` searches only inside that exported function (`.dynsym`). Offsets in `verify`,
`patch`, `branch` and `rel32` lines become `<site>+off` or `sym:<function>+off`.

The recipe still holds **no stock bytes**: a site is an md5, exactly what a verify line already
publishes. An ordinary verify region *is* a site with no mask. So an existing recipe can be turned
into a relocatable one from its text alone (`tools/sitefind.py relocate`).

**Resolution, not a new runtime.** `SiteFind.resolve(recipe, library bytes)` turns a relocatable
recipe into today's plain recipe for one library:
- every site found exactly once, else nothing (the recipe doesn't fit that library);
- every verify region's "before" md5 is that library's own bytes there, its "after" those bytes with
  the recipe's patches on.

`MemRecipe.apply` and `NativePatch` stay exactly as they are, and still check those bytes in
memory before writing anything. Resolution would run in the engine (root, not in the app's
process), when a mod is installed and after a firmware update, and be cached by library md5
(`state/recipes/<id>-<md5>.recipe`). The app process only reads the cached plain recipe.

**One rule that keeps it safe.** A plain number in a relocatable recipe would be right on one
build and wrong on the next, and no verify region would notice. The typical case is a `rel32`
call target the added code jumps to. So a plain number is allowed only with `built-for <md5>`,
and resolves only on that library. `relocate` emits `built-for` whenever a target is neither in a
site nor in an exported function.

**What the data says.**
- **Every verify region is already unique.** All 13 verify regions of the Wireless mod's four
  CarPlay recipes (its `main`, 4a59be9) were scanned (`sitefind.py scan`) against the US Ioniq 5
  260507 stock `libCarPlayAudio`, `libCarPlayCore` and `libCarPlayUtils`. Each of its own build's
  regions matches exactly once. None matches twice anywhere.
- **A recipe written twice.** `carplay-core-frames-ef49c09b` (the 260903/Korean `libCarPlayCore`
  build) and `carplay-core-wifi`'s second region (the 260507 build) are the same change. They have
  the same length, the same before md5 and the same after md5, 0x88 bytes apart
  (ProcessFrames +0x19a on 260507). The scan finds the
  ef49c09b region in the 260507 library, once, at 0x147a2. As a site, one recipe would have done
  for both builds.
- **Round trip.** Each recipe whose stock library is here (`carplay-audio-formats`,
  `carplay-core-wifi`, `carplay-utils-converter`), relocated and resolved on it, comes out as
  exactly the shipped recipe (`SiteFindTest`, through `sitefind-test.sh`).
- **Symbols.** In those libraries, 11 of the 13 patched spots are inside an exported function:
  - `AirPlayReceiverSessionStart` +0x312, ProcessFrames +0x19a;
  - the five `AudioConverter*` entry points (+0);
  - three of `libCarPlayAudio`'s audio-format functions.

  The two that aren't: the ELF program header (0x84) and added code in padding (0x89e0). Those
  are `mkrecipe.py`-style file patches that a memory recipe would do with a `segment` instead.
  (`libCarPlayCore` has 111 exported functions, `libCarPlayUtils` 141, `libCarPlayAudio` 71.)
- **The Android Auto library,** from the two shipped recipes' text alone (no stock library of it
  is on this machine):
  - 48ee793a's third region has the same md5 as a0bea1c4's, 0x60 bytes earlier. A search finds
    it with no work.
  - Its first two regions differ (moved by 0x6f84 and 0x74c4, other bytes in them). They need a
    mask from both builds (`sitefind.py make --also`) or a symbol.
  - Its `rel32` call targets move by different amounts (0x6412, 0x6438, 0x14c4), so no single
    shift fixes them. They need `sym:` names or sites of their own.
  - The one `rel32` into a verify region (`0x50` → v1+0x15) moves with its site automatically.

  Whether `libautoreceiver_infobank.so` exports those functions is the first thing to check with
  both builds in hand (`sitefind.py syms`).
- **Speed.** A whole-library search is an md5 per byte. On this Mac, 5 sites over the 469 KB
  `libCarPlayUtils` resolve in 0.6 s with the JVM start. On the head unit's Dalvik expect tens of
  seconds per library: fine for the engine, once per library build, not for an app starting.
  `in <symbol>` cuts the search to the function (hundreds of bytes). A 32-bit rolling hash of the
  window's longest compared run would make whole-library search linear. It's not in the
  prototype.

**Prototype here.**
- `core/runtime/java/hmgapi/engine/SiteFind.java`: sites, masks, `in`, `sym:`, `built-for`, `jump`,
  resolution to a plain recipe; Dalvik-safe Java, not called by the engine.
- `tools/sitefind.py`: `syms` (`readelf --dyn-syms` for these libraries, pure Python, no new
  packages), `make` (a site, `--also` for masks from other builds), `find`, `scan`, `relocate`.
- `core/runtime/sitefind-test.sh`:
  - the Python matcher and `SiteFindTest` on synthetic ELF libraries;
  - with `$HMGAPI_STOCK_LIBS` or `$HMGAPI_CARS` and `$HMGAPI_MODS`: the scan and the round trip of
    every mod recipe on its stock library.

**Virtual test plan.**
- **Uniqueness, every library.** `sitefind.py scan` of every mod's recipes against every stock
  library of that name in `$HMGAPI_CARS`: no site matches more than once anywhere. A site that
  matches once in a library it wasn't made for must resolve to verify md5s equal to that build's
  bytes. If the code differs it simply doesn't resolve.
- **Fuzzing the matcher.** Random windows of every stock library, as sites (with and without
  random masks above the 16-byte minimum). Each must be found exactly at its own offset or
  reported not unique, never somewhere else. Then, the same library with random insertions and
  deletions: found at the moved offset, or nothing. This is `SiteFindTest`'s synthetic case,
  run over real code.
- **Behaviour under emulation.** Run the patched function under **qemu-user**, which the full
  test suite already needs (`qemu-arm` binfmt in CI). Unicorn would add a GPL-2.0 test
  dependency. The setup:
  - a freestanding ARM harness, like hmgapid's (no libc), maps the library file, applies the
    resolved recipe's bytes, and calls one exported leaf function with chosen arguments;
  - a PLT call traps (the harness maps the PLT non-executable), so only self-contained
    functions can be tested;
  - example: `SensorType_IsValid(23)` returns 0 stock and 1 patched.

  This checks meaning, not just bytes. It needs the stock library, so it's a `cars.py` suite,
  not a CI test.

**Safety.**
- **Unique match or nothing.** Zero or two matches → the recipe doesn't resolve → nothing written.
- **At least 16 compared bytes per site**, so a heavily masked site can't match by chance.
- **No wildcard on a byte the recipe writes,** unless an operator decodes it (5: `jump` checks
  that the bytes are a `b<cond>.w`). Otherwise a site would also match a library that already
  carries a different change at that spot. Seen for real: `make --also` on the stock
  `libCarPlayCore` and a locally modified copy turned exactly the patched bytes of
  `carplay-core-wifi`'s second region into wildcards. The resolver refuses a recipe whose patch
  writes a wildcard byte (tested).
- **Verify before write.** Unchanged: `MemRecipe.apply` checks the exact bytes the resolver saw,
  in memory, before the first write. Mismatch → `MISMATCH`, nothing written (tested).
- **No plain offsets** without `built-for` that library.
- **No `/system` writes.** Memory only, as today. The resolved recipe is a file in `/data`.
- **Off switch.** The mod (an app restart), and the recipe cache can be deleted.
- **Rollback.** None needed for the library file: it's never changed. The boot guard covers a
  patched library that crashes at boot.

## 5. A recipe generator for any stock library

**Proposal.** Today's generators diff a stock/patched pair of one build. The step after 4 is to
describe the change, not its bytes:
- `jump <ref>`: the `b<cond>.w` there becomes a `b.w` to the same target. The bytes depend on
  the build, so the resolver computes them. **Prototyped:** `carplay-core-wifi`'s first region
  written as `site v0 … / verify v0 36 / jump v0+0x10` resolves on the stock `libCarPlayCore` to
  the same bytes as the shipped recipe (`sitefind-test.sh`).
- `call <ref> <target ref>` (a `bl`), `nop <ref> <len>`, `imm <ref> <value>` (a `movs`/`cmp`
  immediate): the same idea. Each operator decodes the instruction that's there and refuses if
  it isn't the one expected.
- Added code (`segment`) whose calls into the library are `rel32 … sym:<function>`. The
  prototype resolves these, Thumb bit kept. `mkmemrecipe.py` would emit them when the target is
  exported, and `built-for` when it isn't.
- `mkrecipe.py`-style code caves and program-header edits become `segment` + `branch` (memory
  only), so no free space has to exist in the next build.

**Virtual test.** The same as 4. Plus, per operator: on every stock library a recipe resolves on,
decode the written bytes (the operator's own decoder) and check the branch target or immediate is
what the operator says. Under qemu-user, run the function.

**Safety.** As 4. An operator never writes over an instruction it didn't decode as the expected
one, and the resolved recipe goes through `MemRecipe`'s verify like any other.

## Virtual testing: what each tool is good for

| tool | good for | limits |
|---|---|---|
| stock trees (`docs/car-trees.md`, `tools/cars.py`) | everything that reads stock files: mapping, uniqueness, round trips, emulation input | private: never in CI. Each maintainer runs `cars.py test --report` on their trees |
| `probe-test.py` | on-car mapper vs `map-car.py`, per tree. With a cut-down map, idea 1's overlay | needs baksmali and javac |
| fake car services (`CommandsTest`'s `FakeCar`) | command guards. A fake vehicle service answering `hmgapid vehicle` could feed `SelfTest`'s sampler | the fake's numbers are ours, not the car's |
| replaying recorded runs (`core/runtime/test/selftest/`) | `SelfTest`. Any `hmgapid vehicle` log or Car Report becomes a fixture | needs runs from real cars, readings only |
| qemu-user | a patched function's behaviour (4, 5) | leaf functions only. Needs the stock library |
| fuzzing the matcher | that a site can't match twice or in the wrong place | as good as the libraries it runs on |
| `map-test.sh` mock roots | `CarMap` gating, the overlay merge, the firmware-update step | fake files: md5s, not real dex |

## Safety, all five

| | 1 self-map | 2 re-map | 3 self-test | 4 sites | 5 generator |
|---|---|---|---|---|---|
| touches the car | no (reads odex) | no | reads readings | no | no |
| unique match or nothing | yes (by name, one hit) | yes | n/a | yes (one window, ≥ 16 bytes) | yes, plus the decoded instruction |
| verify before use/write | `MapCheck` resolution | `MapCheck` | n/a | `MemRecipe` verify of the resolved bytes | the same |
| writes `/system` | no | no | no | no | no |
| writes | `state/local-map.json` | the same | the report | a cached recipe | the same |
| off switch | `local_points` | the same, and the step | (it only reports) | the mod | the mod |
| rollback | delete the file; boot guard | the same | none needed | app restart; boot guard | the same |

## Coordination

- #212 and #214 add the points idea 1 would fill in on nine maps. Nothing here depends on their
  merge order.
- The session moving egmp-aa-ev's Android Auto library recipe into core (issue #44) owns that recipe.
  When a second build of `libautoreceiver_infobank.so` is in hand, `sitefind.py syms` on both and
  `make --also` on its regions are the first steps for idea 4. This PR doesn't touch that recipe.

## Tested

- `core/runtime/sitefind-test.sh`: 9 Python and 21 Java checks on synthetic libraries. With the US
  Ioniq 5 260507 stock CarPlay libraries and the Wireless mod's recipes (`main`, 4a59be9), the
  scan, the 3 round trips and the `jump` check pass. Its 2 Android Auto recipes aren't covered:
  no stock `libautoreceiver_infobank.so` is on this machine.
- `core/runtime/selftest-test.sh`: 7 synthetic runs.
- No car: nothing here ran on a head unit.
