# Releasing: versions, release candidates, hotfixes

How HMG-API and its mods are numbered and released. The same rules hold for core and every
mod repo; each counts its own numbers.

## The scheme

[Semantic versioning](https://semver.org): `MAJOR.MINOR.PATCH`, and pre-releases of the
next version with a suffix:

| Kind | Example | What it is |
|---|---|---|
| alpha | `1.2.0-alpha.1` | early, for people who build HMG-API themselves; may not install cleanly |
| beta | `1.2.0-beta.1` | feature-complete enough to try on a car; features may still land |
| release candidate | `1.2.0-rc.1` | feature freeze: what the final will be unless a blocker turns up |
| final | `1.2.0` | the release everyone gets |

They sort `-alpha.N` < `-beta.N` < `-rc.N` < final, and `N` counts as a number
(`rc.9` < `rc.10`). Only these three suffixes, `N` from 1 with no leading zeros:
NEMESIS takes dotted numbers only, so each has a fixed package-version (below), and the
release workflows refuse any other form.

### Which part goes up

| Change | Part | Example |
|---|---|---|
| Fixes only (bugs, maps for already supported cars, docs that ship) | PATCH | 1.1.0 → 1.1.1 |
| New features, new hook points, new mod API, new mod.json fields | MINOR | 1.1.1 → 1.2.0 |
| A change that breaks installed mods or needs owners to reinstall | MAJOR | 1.2.0 → 2.0.0 |

A raised version resets the parts after it (1.1.3 → 1.2.0). For a mod, "mod API" is
what other mods or the mod's settings rely on; a mod whose behaviour changes for owners
without new features is still a PATCH.

### Where the version is written

- Core: `HMGAPI_VERSION` in `core/runtime/java/hmgapi/engine/Engine.java`. It names the
  release, the SDK (`hmgapi-mod-sdk-<version>.zip`) and the downloads.
- A mod: `"version"` in its `mod.json`.
- The tag: `v<version>` (`v1.2.0-rc.1`). The release workflows refuse a tag that isn't
  exactly that version, suffix included.
- NEMESIS's `package-version` (`tools/nemesis-version.py`; `NemesisSync.packageVersion` on
  the unit): a final as is; a pre-release is the version before it, then `.97.N` (alpha),
  `.98.N` (beta) or `.99.N` (rc). `1.2.0-beta.2` is `1.1.98.2`, `1.2.0-rc.1` is `1.1.99.1`,
  `1.1.1-rc.1` is `1.1.0.99.1`, all below their final. (1.0.0's betas used `.99.N` for
  betas, `0.99.1`..`0.99.6`; those still sort below everything since.)

## The release candidate flow

1. **Betas** (optional): features may still land on `main`. Each beta is cut from `main`.
2. **rc.1 = feature freeze.** A cutoff date for maintainers goes out first (a tracking
   issue). From rc.1, `main` takes only fixes for the release until the final is out;
   features wait in their PRs (or go to the next minor).
3. **rc.N+1 only for a blocker:** something that would make the final worse than the
   release before it (install or uninstall fails, a car that worked stops working, a
   crash loop, data loss). Small fixes that aren't blockers wait for the next patch.
4. **The final is the last rc's code.** Only the version, the CHANGELOG heading and the
   release notes change between the last rc and the final. If anything else has to change,
   that's another rc.

The release PR for each (branch `release/v<version>`, as for 1.0.2): `HMGAPI_VERSION`,
the CHANGELOG ("Unreleased" becomes "<version> (date)"), and
`.github/release-notes/v<version>.md`. A maintainer merges it and pushes the tag; the
release workflow builds a **draft**; a maintainer checks it on a car and publishes.

## Hotfixes

A hotfix is a PATCH release for a bug in the current final.

1. **The fix goes on `main` first**, by PR, as any fix.
2. If `main` has nothing unreleased beyond fixes: release from `main` as usual (1.1.1).
3. If `main` already has unreleased features (they belong to the next minor), branch
   **`release/<major>.<minor>`** from the last final tag of that line, if it isn't there
   yet:

   ```
   git switch -c release/1.1 v1.1.0
   ```

   Then cherry-pick the fix from `main` (`git cherry-pick -x <commit>`), open the release PR
   against `release/1.1`, and tag `v1.1.1` on that branch. Later hotfixes for 1.1 go on the
   same branch. The CHANGELOG entry goes on both branches.
4. A hotfix can have its own rc (`1.1.1-rc.1`) when the fix is risky; usually it doesn't.

A hotfix for an older line (1.0.3 after 1.1.0 is out) is published but not "Latest":
the release workflow marks only the highest final as Latest. Check the "Set as the
latest release" box is clear when publishing such a draft.

## Mods

Mod repos follow the same rules with their own numbers (wireless 2.2.0-rc.1 can ship
alongside core 1.1.0-rc.1).

- **The tag must match `mod.json`'s `"version"`**, suffix included; the mod's release
  workflow refuses anything else and makes a pre-release for a tag with `-`.
- **`"hmgapi"` floor names a final core version** (`">=1.1.0"`): mods made for 1.1.0
  install on 1.1.0's betas and rcs as well (the engine checks a final floor against the
  release a pre-release leads to). The mod's CI accepts a final floor on that release's
  rc SDK, with that SDK's hook points (the final is the last rc's code), so a mod's final
  can come out before core's.
- **A mod pre-release may ask for a core pre-release** (`">=1.1.0-rc.1"`) when it needs
  something only that rc has. Such a floor is checked against the full core version:
  1.1.0-rc.2 and 1.1.0 meet it, 1.1.0-beta.1 doesn't. A mod's **final** release can't
  name a core pre-release (`check-points.py` refuses it): change it to the final
  (`">=1.1.0"`) in the PR that sets the final version.
- Units on 1.1.0-beta.1 or older check every floor against the version without its
  suffix, so on those `">=1.1.0-rc.1"` is met by any 1.1.0 pre-release.
- While a mod and core are both in rc, the mod's `hmgapi-sdk.json` pins core's rc (by
  commit SHA, or the rc's SDK zip); after core's final, the final's SDK zip.

## Where each kind is published

| Kind | GitHub release | Catalogue (`catalog/`, the wiki's Community mods page) | Bundled in core |
|---|---|---|---|
| core final | published, **Latest** (unless it's a hotfix to an older line) | — | — |
| core alpha, beta, rc | published as a **pre-release**, never Latest | — | — |
| mod final | published in the mod's repo | **yes**: the catalogue PR (`tools/catalog.py add`, printed by the release run) | by the next core release, through its catalogue entry |
| mod alpha, beta, rc | published as a pre-release in the mod's repo | **no**: `catalog.py` refuses pre-releases | only by a core pre-release, pinned in `tools/release-mods.txt` |

The HMG-API app offers what's on the USB stick (the downloads from a release); owners
choose a pre-release by downloading it. Pre-releases' notes say how to go back to the
last final.

## Units on 1.0.2

1.0.2 and 1.1.0-beta.1 compare pre-release suffixes as plain strings. That orders alpha,
beta and rc right, and `N` from 1 to 9, but puts `rc.10` below `rc.2`..`rc.9` (and the
same for `beta.10`). Those units compare two suffixes of the same `x.y.z` only for
**mods** (an installed mod against an update of it): core updates from 1.0.2 to any
1.1.0 compare the numbers first, and from 1.1.0-rc.1 on the unit runs the new compare.
So on such a unit a mod's `rc.10` shows as older than its `rc.9`: the app offers no
install, only `engine install --force` over adb, or removing the mod first. Core can use
`rc.10`; for a mod, ten rcs of one version shouldn't happen, and if it would, go from
`rc.9` to the final or to a new version's rc.
