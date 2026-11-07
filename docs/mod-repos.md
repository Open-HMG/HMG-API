# Mods in their own repositories

The mods HMG-API ships (`tools/release-mods.txt`: `wireless-carplay-aa`, `adb`,
`examples/nemesis-shortcut`), and `mods/adbd`, move to their own repositories in the
Open-HMG org. `mods/examples` (except `nemesis-shortcut`, see 1) stay in core as API
examples and test fixtures. Hook points and maps stay in core.

## Decisions for the maintainer

| # | Question | Recommended | Other choice |
|---|---|---|---|
| A | `nemesis-shortcut`: move or stay? | Move to `hmgapi-adb` (with `adb`, `adbd`) | Stay in `mods/examples`; core keeps a mod key and builds one shipped mod in-tree |
| B | Signing key for the moved mods | Same author key as now (`7251ce71-…`), as `MOD_KEY` in each mod repo's `release` environment | A new Open-HMG key: every installed car must remove and reinstall the mods |
| C | Who updates `catalog/` on a mod release | A maintainer, by PR (`tools/catalog.py add`, the command printed by the mod's release run) | Mod release workflow opens the PR (needs a token that can write to HMG-API) |
| D | Korean cars in the wireless mod (#22, #108) | Decided in the mod repo once a car is confirmed working; core takes the map either way | Wait until the core Korea fact is updated (job 7) |

## 1. Which mods move, to which repos

| Mod | Repo | Why |
|---|---|---|
| `wireless-carplay-aa` | `Open-HMG/hmgapi-wireless-carplay-aa` | Most of the mod PRs; own release pace, own testers |
| `adb` | `Open-HMG/hmgapi-adb` | adb access, same audience as `adbd` |
| `adbd` | `Open-HMG/hmgapi-adb` (shared) | Same feature as `adb` (who can reach adb); its build needs `third_party/` (AOSP 4.4.4 adbd, used only here), which moves with it. Still not released or listed. |
| `nemesis-shortcut` | `Open-HMG/hmgapi-adb` (decision A) | Owner tinkering tools, same audience; core then signs no mod and holds no mod key |
| `mods/examples/*` (others) | stay | API examples, test fixtures (`hello` is used by signing, catalogue and NEMESIS tests) |

Layout in every mod repo: each mod in its own folder (`wireless-carplay-aa/`, `adb/`,
`adbd/`, `nemesis-shortcut/`), with `README.md`, `LICENSE`, `.github/` and `docs/` at the
root. `mkmodpkg.sh` packages everything in a mod folder except `build.sh`, `test.sh`,
`src`, `tools`, `native`, so a mod at the repo root would ship the README and workflows.

Tags: `v<version>` in a one-mod repo; `<id>-v<version>` (e.g. `adb-v1.0.0`) in `hmgapi-adb`.
The release workflow refuses a tag that isn't the mod's `mod.json` version.

## 2. History (git filter-repo)

On main, the moving paths have one commit each: the squashed first commit (`81ea696`).
Filter-repo keeps that commit and its author. Older history is in the archived Leafia
repo and isn't imported. `docs/cluster-turns.md` only exists in #75, so it arrives with
that PR's rebase; the path is listed so a later import still catches it.

| Repo | filter-repo arguments (scratch clone only) |
|---|---|
| `hmgapi-wireless-carplay-aa` | `--path mods/wireless-carplay-aa/ --path docs/cluster-turns.md --path-rename mods/wireless-carplay-aa/:wireless-carplay-aa/` |
| `hmgapi-adb` | `--path mods/adb/ --path mods/adbd/ --path mods/examples/nemesis-shortcut/ --path third_party/ --path-rename mods/adb/:adb/ --path-rename mods/adbd/:adbd/ --path-rename mods/examples/nemesis-shortcut/:nemesis-shortcut/` |

Stays in core: `catalog/*.json` (the index), `wiki/` pages about the mods (owner docs),
`docs/api-plan.md`, `docs/car-test.md` (it installs mods from their releases after job 5).

## 3. The mod SDK

Each core release attaches `hmgapi-mod-sdk-<version>.zip` (in `SHA256SUMS` too).
Built by `./build.sh sdk`. No manufacturer files, no Android SDK files (the mod's CI
installs `android.jar` and `d8` as core's CI does).

| In the SDK | From | Used for |
|---|---|---|
| `lib/hmgapi-runtime.jar` | `out/classes` (runtime: `hmgapi.hook.*`, `ModEntry`, `Points`, `Car`, `Cluster`…) | compile `src/` (compile only, never packaged) |
| `lib/hmgapi-runtime-stubs.jar` | `out/runtime-stubs` | compile only, where runtime signatures name car classes |
| `lib/hmgapi-client.jar` | `./build.sh client` | mods' own apps and services (bundled, as now) |
| `lib/hmgapi-engine.jar` | `core/runtime/java/hmgapi/engine` | host tests (`Engine` with `HMGAPI_HOST=1`), `Signing`, `ScriptCheck` |
| `bin/lpatch-host` + `src/lpatch/` | `tools/lpatch` | host tests of patches and recipes; source to build it on macOS |
| `bin/build-mod.sh` | `build.sh mods()`, one mod | `hooks.jar` and app APK from a mod folder |
| `bin/mkmodpkg.sh`, `bin/mknemesismod.sh`, `bin/nemesis-meta.py`, `bin/nemesis-version.py`, `nemesis/mod/` | `tools/`, `install/nemesis/mod/`, `install/nemesis/bin/open.sh` | signed package, NEMESIS package |
| `bin/check-scripts.sh`, `unit-commands.txt` | `tools/check-scripts.sh`, `core/runtime/unit-commands.txt` | unit-only commands in mod scripts |
| `bin/mkrecipe.py`, `bin/mkdelta.py`, `bin/mkmemrecipe.py`, `bin/mkicon.sh`, `bin/mod-draft.py` | `tools/` | recipes, delta sets, tile pictures, new mod drafts |
| `test/lib.sh` | the `check`/`has`/`eng` helpers every mod `test.sh` copies today | mod tests |
| `points.txt`, `catalogue/*.json` | `core/hookpoints` | check the mod's `hooks.points` exist |
| `VERSION` | `Engine.HMGAPI_VERSION` | |

Version rule: the SDK's version is the core release's version; nothing else. An SDK
works for a mod whose `"hmgapi"` floor is at or below it.

Pin: each mod repo has `hmgapi-sdk.json`, `{"version": "1.0.3", "sha256": "…"}`. CI
fetches that release asset and refuses a sha256 mismatch. Bumping the SDK is a PR
changing that file.

## 4. Hook points stay in core

A mod that needs a new point:

| Step | Repo | PR | Merged when |
|---|---|---|---|
| 1 | HMG-API | catalogue entry (`core/hookpoints/*.json`), mapped on every car in `maps/`, `docs/hook-points.md` | maps checked (`tools/test-hooks.py`) |
| 2 | HMG-API | release carrying it (the SDK's `points.txt` lists it) | |
| 3 | mod | uses the point in `mod.json` `hooks.points`, `"hmgapi": ">=<that release>"`, SDK pin bumped | mod CI passes |

The mod's PR can be open (draft) during 1 and 2; its CI fails until the SDK has the point.

`"hmgapi"` rule, checked by the mod workflow: the floor is at or below the pinned SDK,
and every point in `hooks.points` is in the floor release's `points.txt` (the workflow
fetches that SDK too). A mod that raises its floor says so in its release notes.

## 5. Signing

| | Now | After |
|---|---|---|
| Mod key | `HMGAPI_MOD_KEY`/`_PUB` in core's `release` environment | `MOD_KEY`/`MOD_KEY_PUB` in each mod repo's `release` environment (`v*` tags only), same key |
| Core's mod key | signs three mods | deleted from core (with decision A) |
| Examples in core | throwaway keys in tests | unchanged |

Why the same key (decision B): the engine refuses an update signed by a key other than
the one the mod was installed with, and `tools/catalog.py check --base` refuses an entry
whose author key changed. A new key strands every installed car. Cost: the key lives in
two repo environments; both are `v*`-tag-only with Admin bypass.

`maps/authors.json`: the key's `repos` gets both new repo URLs (the catalogue checks
an entry's `repo` against them).

## 6. Core release bundling

`tools/release-mods.txt` becomes a list of catalogue ids (`wireless-carplay-aa`, `adb`,
`nemesis-shortcut`). The release workflow, for each id: fetch `catalog/<id>.json`'s
`download`, check its `sha256` and signature (`tools/catalog.py`'s fetch and verify),
put it in `out/packages/`. `mkinstallers.sh` runs unchanged from there: `HMGAPI/updates`
on the USB stick, the NEMESIS stick and `mods/*-nemesis*.zip` come out as now.

So the catalogue entry is the one pin: bumping a bundled mod in the next core release is
the catalogue PR from 7. No second list of versions and hashes.

NEMESIS conflicts: the mod rows of `install/nemesis/conflicts.txt` (`hmgapi.wireless-carplay-aa`,
`hmgapi.adb`) move into each mod's `mod.json` as `"nemesis_conflicts": [...]`. That's inside
the signed package, so `mknemesismod.sh` in core and in the mod repo make the same
NEMESIS package from the same zip. Core keeps the `hmgapi` row. (Job 2 checks the engine
ignores the new field.)

## 7. Catalogue

| Field | After |
|---|---|
| `repo` | `https://github.com/Open-HMG/hmgapi-wireless-carplay-aa`, `https://github.com/Open-HMG/hmgapi-adb` (with `/tree/main/<id>` in the shared repo) |
| `download` | the mod repo's release asset; the current v1.0.2 links stay until each mod's next release |
| `sha256`, `version` | from `tools/catalog.py add`, as now |

On a mod release (decision C): the mod's release run prints the
`tools/catalog.py add <zip> --download <url> --repo <url>` command; a maintainer opens the
HMG-API PR; `catalog.yml` downloads and checks it. The wiki's Community mods page updates
on merge.

## 8. Core tests that use the moving mods

| Test | Uses | After |
|---|---|---|
| `core/runtime/native-test.sh` | `mods/wireless-carplay-aa/recipes` on private stock libraries (skipped in CI) | synthetic part stays; the real-library part moves to the mod repo's tests (skips without stock trees, as now). CI coverage unchanged |
| `core/runtime/mod-dex-test.sh` | the id string `wireless-carplay-aa` | any id (`example-a`) |
| `install/nemesis/mod-test.sh` | real `conflicts.txt` rows for wireless and adb; `mods/adb/mod.json` as a "not a zip" input | `nemesis_conflicts` from a test mod's `mod.json`; `mods/examples/hello/mod.json` as the input |
| `mods/wireless-carplay-aa/test.sh`, `tools/aaturns`, `tools/cpturns`, `mods/adb/test.sh` | | move with the mods; off `tools/test-all.sh` |
| `tools/test-select.py` | `mods/*` rule, wireless special case | rule for `mods/examples/*` only |
| `tools/check-scripts.sh` | default `mods/*/` | `mods/examples/*/`, the installer |
| `engine-test`, `safety-test`, `overlap-test`, `net-test` | wireless-shaped synthetic mods, no files from `mods/` | unchanged |
| `signing-test`, `catalog-test`, `sync-test`, `usb-test` | `mods/examples/hello`, `!wireless.carplay.aa` (Leafia's NEMESIS package) | unchanged |

## 9. Mod repo CI

Recommended: a reusable workflow in HMG-API, `.github/workflows/mod.yml` (`on:
workflow_call`), called by each mod repo pinned to a core tag
(`uses: Open-HMG/HMG-API/.github/workflows/mod.yml@v1.0.3`). Inputs: `mods` (folders,
default all with a `mod.json`). Steps: fetch the pinned SDK (sha256), the `"hmgapi"` and
points check (4), build, each mod's `test.sh`, `check-scripts`, package with a throwaway
key. A second reusable workflow, `mod-release.yml`, signs with `MOD_KEY` and drafts the
release. Why: one place to fix CI for every mod; a template copies drift. Mod repos
still own their `release.yml` caller and secrets.

## 10. Open PRs touching `mods/`

| PR | Mod part | Plan |
|---|---|---|
| #15 CarPlay turns, left-hand traffic | wireless | merge in core before the freeze |
| #111 Bluetooth kept up | wireless | merge in core: drop its catalogue/map edits (in #120), raise `"hmgapi"` |
| #22 Niro Hybrid KOR | map + wireless car entry | map in core; car entry in the mod repo (decision D) |
| #100 CarPlay picture freeze (draft) | wireless | re-open in the mod repo; its points are in core (#120) |
| #75 AA lanes, car-format turns | wireless + `docs/cluster-turns.md` | rebase onto the mod repo (the author is a maintainer) |
| #108 Korean K5 (draft) | core + wireless | split: core PR (Wi-Fi probe, install, `SystemCheck`); mod PR in the mod repo; its by-name hook becomes a hook point |
| #104 USB Wi-Fi adapter (draft) | new mod + wireless entry | if accepted: its own repo by its author, catalogue entry; small wireless `mod.json` change in the mod repo |

Freeze: once the wireless extraction starts, nothing more merges into
`mods/wireless-carplay-aa` in core. Moving a PR keeps its commits and authors: from the
PR branch, `git format-patch --stdout <base>..HEAD -- mods/wireless-carplay-aa` piped into
`git am -p2` in the new repo (`-p2` drops `a/mods/`, leaving `wireless-carplay-aa/…`);
mod docs such as `docs/cluster-turns.md` the same way with `-p1`. Its core parts stay a
core PR.

## 11. Jobs after this doc

| Job | Branch / repo | Acceptance |
|---|---|---|
| SDK + reusable CI | `feat/mod-sdk`, HMG-API | `./build.sh sdk` makes the zip listed in 3; `mod.yml` runs green on `mods/examples/hello` in core CI; the tests in 8 off the wireless fixture with the same checks; `"nemesis_conflicts"` read by `mknemesismod.sh`, ignored by the engine; `tools/test-all.sh` passes |
| Release the SDK | tag in HMG-API | `hmgapi-mod-sdk-<v>.zip` on the release, in `SHA256SUMS` |
| Extract wireless | `import`, `hmgapi-wireless-carplay-aa` | filter-repo history as in 2; CI green on the pinned SDK; a tag drafts a release whose signed zip installs over v1.0.2's `wireless-carplay-aa-2.1.0` on the mock tester (same key, no warning); stock-tree tests pass locally |
| Extract adb, adbd, nemesis-shortcut | `import`, `hmgapi-adb` | same; `adbd` builds from `third_party/` with the NDK |
| Remove from core | `chore/remove-bundled-mods`, HMG-API | moved folders and `third_party/` gone; `release-mods.txt` is ids; local dry run of the release steps gives the same zip listings as v1.0.2 (differences explained); catalogue points at mod releases, `tools/catalog.py check --download` passes; no hit for the moved ids/paths outside `catalog/`, `wiki/`, `CHANGELOG.md`, release notes; core's `HMGAPI_MOD_KEY` deleted |
