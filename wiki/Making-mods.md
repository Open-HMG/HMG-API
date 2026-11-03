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

## Listing your mod

The [Community mods](Community-mods) page lists mods from their authors'
own repositories. One pull request to HMG-API adds yours and puts your author key
on the trusted list, so owners install it without the unknown-author warning (once
a HMG-API release carries the list).

1. Publish your signed zip (`tools/mkmodpkg.sh`) where it stays put, such as a
   GitHub release asset. Don't replace a published file: each version gets its own.
2. In your fork of HMG-API, one command:

   ```
   tools/catalog.py add my-mod-1.0.0.zip --download https://github.com/you/my-mod/releases/download/v1.0.0/my-mod-1.0.0.zip --repo https://github.com/you/my-mod
   ```

   It checks the zip's signature and writes `catalog/<id>.json` (name, description,
   cars and version from your `mod.json`, the zip's SHA-256, your author key) and
   your entry in `maps/authors.json`. `--repo` can come from `"repo"` in `mod.json`.
3. Open the pull request with both files. CI downloads the zip and checks that its
   SHA-256, signature, id and version match the entry.

A new version is the same steps with the new zip: the entry's author key can't
change (only your key updates your mod), and its version can't go back. A pull
request changes only its own author's entry in the trusted list. Listing means the
package is whole and signed by you; the maintainer reviews each new author before
merging.

## Scripts on the head unit

The head unit's shell is mksh with a small toolbox: no `sed`, `tr`, `awk`, `head`,
`tail` or busybox. See the mod reference for what there is, and run
`tools/check-scripts.sh <your mod>` before packaging. HMG-API warns at install when a
script calls a command the unit doesn't have.
