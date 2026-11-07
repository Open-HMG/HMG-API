# Contributing

Thanks for helping. Bug reports, Car Reports and ideas go in
[issues](https://github.com/Open-HMG/HMG-API/issues/new/choose); code and docs in pull requests.

## Before a pull request

- Build and test off the car: `./build.sh`, then `tools/test-all.sh` (or only what your
  change touches: `git diff --name-only main | tools/test-select.py` names the tests,
  the same ones CI runs). Needs clang + lld, a JDK, the Android SDK and the Python
  packages in `tools/requirements.txt`; no car files or keys.
- Say which cars (model, region, software version) a change was tested on, or "off-car only".
- New or changed hook points: map them for every car (`tools/map-car.py`); if you have
  stock trees, `tools/test-hooks.py` must show no regressions.
- Mod scripts: only commands the head unit has (`tools/check-scripts.sh <mod>`).
- The wiki lives in `wiki/`; changes there are published when merged.
- Who reviews what (`.github/CODEOWNERS`): VysseCodes reviews root, signing, `/system`
  writes and CI (`.github/`, `core/hmgapid/`, `core/zygote/`, `core/loader/`,
  `install/`, `tools/lpatch/`, `SECURITY.md`, `CLAUDE.md`); everything else needs one
  of the Open-HMG maintainers.

## Rules

- Anything that writes to `/system` keeps a stock checksum check, a backup and an
  uninstall that restores the exact stock bytes.
- The repo holds no manufacturer apps or libraries: only hooks, hash-only recipes,
  delta patch sets, checksums and our own code.
- Never collect or publish per-car secrets (VIN, serials, keys, account tokens,
  `/data` contents), in code, reports, logs or issues.
- Vehicle commands go through the car's own services, never our own CAN frames.
- Commit messages describe the change in plain words.

By contributing you agree your work is licensed under the project's AGPL-3.0.
