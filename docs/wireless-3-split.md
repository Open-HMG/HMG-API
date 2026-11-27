# Wireless 3.0 and Phone Turn-by-Turn: release checklist

Turn-by-turn leaves the Wireless mod (`wireless-carplay-aa` 3.0) and becomes its own mod,
Phone Turn-by-Turn (`projection-tbt`). This page is the order the releases go out in, across
three repositories, and what owners are told. Rules are [releasing.md](releasing.md) and
[mod-repos.md](mod-repos.md).

| Repository | What | Pull request |
|---|---|---|
| `AwsomeFox/egmp-aa-ev-hmg-api` | `egmp-aa-ev` asks for Wireless below 3.0 | (separate task) |
| `Open-HMG/hmgapi-wireless-cp-aa` | Wireless 3.0.0-beta.1 without turn-by-turn | #124, and the upgrade notes on top of it |
| `AwsomeFox/hmg-api-turn-by-turn` (moving to `Open-HMG/hmgapi-projection-tbt`) | release workflow, author key, upgrade notes | `ci/release-workflow` |
| `Open-HMG/HMG-API` | catalogue entries, `maps/authors.json`, wiki, then `tools/release-mods.txt` | this branch (`catalog/projection-tbt`) |

## Why the order matters

- `projection-tbt` declares `conflicts` with `wireless-carplay-aa <3.0.0-beta.1`: Wireless 2.x
  still has the same turn-by-turn hooks. With both installed, HMG-API loads the first by id,
  and `projection-tbt` sorts first: **installing it next to Wireless 2.x turns Wireless off**.
  So the two ship together, and owners update Wireless first.
- The catalogue lists finals only (`catalog.py add` refuses a pre-release), and a HMG-API
  pre-release can bundle a mod's pre-release only when the mod already has a catalogue entry
  (`release-mods.txt` pins). `projection-tbt` has none until its 1.0.0. A HMG-API pre-release
  that bundled Wireless 3.0-beta would leave owners without turn-by-turn and no way to get it
  from that download. So **no HMG-API release bundles Wireless 3.x until `projection-tbt` 1.0.0
  is in the catalogue**; `release-mods.txt` stays on Wireless 2.2.x until then.
- A catalogue PR that only bumps one entry merges itself (`catalog-automerge.yml`). A Wireless
  3.0.0 bump on its own would go live before `projection-tbt` is listed, so **both entries go
  in one PR** (two files: not a bump, so a maintainer merges it).

## Decided here

- **Author key: VysseCodes' (`7251ce71-…`), the Wireless mod's.** The key is per author, not
  per mod; it's already on every HMG-API release's trusted list (a new key would show the
  unknown-author warning until a HMG-API release carries it, and forever on units that don't
  update); and it can never change once a release is out, so a personal key would tie every
  future update to whoever holds it. The cost is a fifth `release` environment with `MOD_KEY`.
  A repository's admins can read its environment secrets through a workflow, so the key only
  goes in a repository VysseCodes controls: **the turn-by-turn repository moves to Open-HMG
  before its first tag.**
- **Its address after the move: `https://github.com/Open-HMG/hmgapi-projection-tbt`** (the
  `hmgapi-<id>` convention). That's the address in `maps/authors.json` and the prepared entry
  here; the release workflow refuses a tag unless `mod.json`'s `repo` is the repository it
  runs in. If the name is different, change it in both.

## Checklist

### 0. Before any tag

- [ ] Transfer `AwsomeFox/hmg-api-turn-by-turn` to Open-HMG as `hmgapi-projection-tbt`
      (GitHub redirects the old links).
- [ ] In that repository: `projection-tbt/mod.json` `"repo"` set to the new address; README,
      NOTICE and `docs/` links; `.github/CODEOWNERS` (its `@Open-HMG/maintainers` team only
      works inside Open-HMG).
- [ ] VysseCodes: a `release` environment (deployment tags `v*` only, as in the Wireless repo)
      with `MOD_KEY`, the same secret as the Wireless repo's.
- [ ] Tag ruleset as the other mod repos ("Protect release tags").
- [ ] Merge `ci/release-workflow` (release workflow, `.github/mod-key.pub`, upgrade notes).
- [ ] Wireless repo: the README's links to the turn-by-turn repository point at the new address.

### 1. `egmp-aa-ev` first (separate task)

- [ ] `hmgapi-mod/mod.json`: `"requires": ["wireless-carplay-aa >=2.1.0 <3.0.0"]`, released
      and published **before Wireless 3.0.0-beta.1 is published**, so an owner updating
      Wireless is told `egmp-aa-ev` doesn't work with it, instead of finding out on the road.
- [ ] Its README: stay on Wireless 2.x until an `egmp-aa-ev` release supports 3.0.

### 2. The betas, together

- [ ] Wireless: merge #124 and the upgrade-notes PR on top of it; release PR with
      `.github/release-notes/v3.0.0-beta.1.md`; tag `v3.0.0-beta.1`.
- [ ] Turn-by-turn: release PR (`1.0.0-beta.1` is already `mod.json`'s version) with
      `.github/release-notes/v1.0.0-beta.1.md` (fill in "What's new" from `git log`); tag
      `v1.0.0-beta.1`.
- [ ] Check both drafts off-car (every file in `SHA256SUMS`, versions, signature by
      `7251ce71-…`).
- [ ] Publish both at the same time, Wireless first. Their notes link each other and say
      "update Wireless first".
- [ ] `tools/release-mods.txt` here: **unchanged** (Wireless 2.2.x).

### 3. On cars before the finals

- [ ] Update from Wireless 2.2.x: Wireless 3.0 first, then `projection-tbt`; CarPlay and
      Android Auto connect, turns on the cluster and HUD.
- [ ] Wrong order on purpose: `projection-tbt` next to 2.2.x shows the conflict and Wireless
      comes back once it's 3.0.
- [ ] `projection-tbt` alone, wired phone. Wireless 3.0 alone: the car's navigation keeps the cluster.
- [ ] CarPlay after the Bluetooth-to-Wi-Fi handover: route guidance keeps coming (the two mods'
      agreement, Wireless `docs/tbt-contract.md`).
- [ ] The HMG-API app installing both from one stick: which goes first, and what the owner
      sees in between (not checked yet).

### 4. The finals

- [ ] Wireless `3.0.0` and `projection-tbt` `1.0.0`: `"hmgapi"` floor a final (`">=1.1.0"`;
      `check-points.py` refuses a pre-release floor), release notes, tags, drafts checked on a
      car, both published together.

### 5. Catalogue (this branch)

- [ ] Regenerate both entries from the published zips (the ones here are prepared from the
      mod.json files, with a zero SHA-256 that `catalog.yml`'s download check refuses):

      ```
      curl -fLO https://github.com/Open-HMG/hmgapi-wireless-cp-aa/releases/download/v3.0.0/wireless-carplay-aa-3.0.0.zip
      tools/catalog.py add wireless-carplay-aa-3.0.0.zip --download https://github.com/Open-HMG/hmgapi-wireless-cp-aa/releases/download/v3.0.0/wireless-carplay-aa-3.0.0.zip --repo https://github.com/Open-HMG/hmgapi-wireless-cp-aa
      curl -fLO https://github.com/Open-HMG/hmgapi-projection-tbt/releases/download/v1.0.0/projection-tbt-1.0.0.zip
      tools/catalog.py add projection-tbt-1.0.0.zip --download https://github.com/Open-HMG/hmgapi-projection-tbt/releases/download/v1.0.0/projection-tbt-1.0.0.zip --repo https://github.com/Open-HMG/hmgapi-projection-tbt
      ```

      (the same commands as the two release runs' summaries).
- [ ] If Wireless 2.2.0 entered the catalogue meanwhile (for HMG-API 1.1.0), rebase first;
      `add` refuses a version older than the entry.
- [ ] Both entries in one PR, merged by a maintainer. The descriptions are the mods' own
      (`mod.json`) and tell owners turn-by-turn is its own mod and to update Wireless first.
      The wiki's Community mods page and the pages changed here (Home, the NEMESIS packages
      table in Installing HMG-API) update on merge.

### 6. Bundled in HMG-API

- [ ] The next HMG-API release after step 5: `tools/release-mods.txt` lists
      `wireless-carplay-aa` and `projection-tbt` by id (no pins). Its release notes: update
      Wireless first, then install Phone Turn-by-Turn; `egmp-aa-ev` owners stay on 2.x.

### 7. Afterwards

- [ ] Both READMEs: drop "set up but not released" and the betas' wording.
- [ ] Wireless README "Install and connect": the version and HMG-API floor.
- [ ] `egmp-aa-ev`: a release that works with Wireless 3.0, then lift the cap.

## What owners are told, and where

| Where | Text |
|---|---|
| Wireless `mod.json` description (its catalogue entry, the Community mods page) | From 3.0, turn-by-turn is its own mod; update this one first, then install that one |
| `projection-tbt` `mod.json` description | Part of Wireless until 3.0; update Wireless to 3.0 first, then install this one |
| Wireless README, "Updating from 2.x" | the order, why (the conflict), settings set again, `egmp-aa-ev`, going back |
| Turn-by-turn README, "Updating from the Wireless mod 2.x" | the same, from this side; wired-only cars install it alone |
| Both first release notes | the order, what to test, going back (remove `projection-tbt` first) |
| Wiki: Home, Installing HMG-API (NEMESIS packages table) | turn-by-turn listed on its own; `projection-tbt`'s NEMESIS conflicts |
