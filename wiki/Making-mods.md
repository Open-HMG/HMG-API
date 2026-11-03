# Making mods

A mod is a folder with a `mod.json`, signed with your own key. Everything a mod does
is declared there, shown to the owner before it applies, and undone when it's turned
off or removed. In the repository:

- `docs/mod-reference.md`: the full `mod.json` reference by topic. Already have a
  Gen5W package? `tools/mod-draft.py` drafts a `mod.json` from it so you can offer a
  mod alongside it.
- `mods/examples/`: working examples (hooks, a background service, a side panel
  page with readings, a test mod using buttons, projection and commands).
- `docs/vehicle-commands.md`: the car commands and their limits.
- `docs/micom-map.md`: mapping new car data.
- `tools/mkicon.sh <picture> <folder>`: app-menu tile pictures that match the car's
  own (the stock gradient, day and night, and the greyed-out look) from any shape.
- `docs/api-plan.md`: what each community project uses and what's planned.

Packaging: `tools/mkmodpkg.sh <mod folder> <your key>` (make a key once with
`java -cp out/engine-host hmgapi.engine.Signing keygen <file>`). Keep the key safe:
only packages signed with it update your mod.

## Scripts on the head unit

The head unit's shell is mksh with a small toolbox: no `sed`, `tr`, `awk`, `head`,
`tail` or busybox. See the mod reference for what there is, and run
`tools/check-scripts.sh <your mod>` before packaging. HMG-API warns at install when a
script calls a command the unit doesn't have.
