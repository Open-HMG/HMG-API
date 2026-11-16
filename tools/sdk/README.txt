HMG-API mod SDK
===============

What a HMG-API mod outside the HMG-API repo builds, tests and packages with. Its
version (VERSION) is the HMG-API release it comes from; it works for a mod whose
"hmgapi" floor is at or below it. No manufacturer files, no Android SDK files: install
the Android SDK (build-tools, a platform android.jar) and set ANDROID_HOME.

  lib/hmgapi-runtime.jar        HMG-API's runtime (hmgapi.hook.*, ModEntry...): compile only
  lib/hmgapi-runtime-stubs.jar  the car classes runtime signatures name: compile only
  lib/hmgapi-client.jar         for a mod's own app or service (bundled in it)
  lib/hmgapi-engine.jar         the engine on this machine (HMGAPI_HOST=1), Signing, ScriptCheck
  bin/hmgapi-mod <command>      a mod's whole loop, wrapping the tools below (see under)
  bin/mod-deps.py <mod>         the mods it requires, from HMG-API's catalogue (hmgapi-mod deps)
  bin/build-mod.sh <mod> <out>  hooks.jar from src/, the APK from app/
  bin/mkmodpkg.sh <mod> <key>   the signed package, out/packages/<id>-<version>.zip
  bin/mknemesismod.sh <zip>     the same mod as a NEMESIS package, out/dist/
  bin/check-scripts.sh <mod>    unit-only commands in the mod's scripts (unit-commands.txt)
  bin/check-points.py <mod>     the "hmgapi" floor and hooks.points against HMG-API's releases
  bin/lpatch-host, src/lpatch/  lpatch for host tests (src/lpatch/build.sh builds it elsewhere)
  bin/mkrecipe.py, mkdelta.py, mkmemrecipe.py, mkicon.sh, mod-draft.py   as in HMG-API's tools/
  test/lib.sh                   check/has/eng/tree for a mod's test.sh; car_tree/car_prop: the
                                stock car files tools/cars.py test runs it on (HMGAPI_CAR);
                                plan_test <mod> [<required mod>...]: the plan test (below)
  test/plan.py                  the fake head units plan_test makes from maps/
  test/hmgapi-engine-test.jar   hmgapi.engine.MemRecipeTest: native recipes on real libraries;
                                hmgapi.engine.RecipeCheck: without them, against other mods'
  maps/                         HMG-API's hook maps (public: point names and the md5s of the
                                stock files each map was made from; no car files)
  points.txt, catalogue/        the hook points this release has, and their catalogue
  nemesis/                      the NEMESIS package templates

hmgapi-mod, from the mod repository's root (<mod>: the mod's folder, by default the one
folder there with a mod.json):
  hmgapi-mod new <id> [--name <name>] [--author <name>]   <id>/ (mod.json, an entry class,
                                test.sh), hmgapi-sdk.json (this SDK's release, its sha256 from
                                the release's SHA256SUMS if it can be fetched), CI, .gitignore
  hmgapi-mod build [<mod>]      bin/build-mod.sh into out/build/<id>
  hmgapi-mod check [<mod>]      bin/check-points.py and bin/check-scripts.sh
  hmgapi-mod deps [<mod>]       each mod "requires" names (and what those require), from HMG-API's
                                catalogue: its SHA-256 and author key checked, cached in
                                .hmgapi/deps; prints their folders. HMGAPI_CATALOG: a catalog/
                                folder or URL; hmgapi-deps.txt: "<id> <version> <url> <sha256>"
                                pins of a pre-release, from the mod's own repo, same author key
  hmgapi-mod test [<mod>]       build, deps, then its test.sh (HMGAPI_MOD_BUILD, HMGAPI_MOD_DEPS
                                set), or the plan test if it has none
  hmgapi-mod package [<mod>] --key <file>    bin/mkmodpkg.sh, then bin/mknemesismod.sh
  hmgapi-mod install --adb [<zip>|<mod>] [--no-reboot]   push, hmgapid engine install, engine
                                apply, wait for adb (the apply may restart adbd), hmgapid reboot
  hmgapi-mod logs <id> [<service>]           its lines in HMG-API's log (logcat only holds
                                seconds on these units), or one of its services' log
  hmgapi-mod settings get <id> | set <id> <file>   its settings; set pushes the file (JSON
                                in a shell command line loses its quotes)
The adb commands need HMG-API on the unit and USB adb; --dry-run prints them instead.
ADB: the adb binary; ANDROID_SERIAL: which device.

The plan test: in a mod's test.sh, after `. "$HMGAPI_SDK/test/lib.sh"`,
  plan_test <mod folder> [<folder of each mod it requires>...]
makes a fake head unit for each car the mod's "system" names that has a map here, puts
the mods on it and runs engine plan as that car: no problems in the plan, the "system"
takes the unit, HMG-API uses the car's map, every hook point the mod declares (and those
in PLAN_POINTS) is mapped, its hooks load, which points it shares with other mods (info),
and its native recipes stay clear of the required mods' recipes for the same library
build. File changes aren't tested there (the unit has stand-ins, not stock files): test
them on stock files with car_tree. HMGAPI_MAPS: other maps (a newer HMG-API's maps/).

A key: java -cp lib/hmgapi-engine.jar hmgapi.engine.Signing keygen <key file>
How mods are built outside HMG-API: docs/mod-reference.md in the HMG-API repo,
"Building a mod outside this repo". AGPL-3.0 (LICENSE).
