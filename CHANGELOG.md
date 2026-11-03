# Changelog

## 1.0.0 (unreleased)

HMG-API replaces HMG Leafia Plus 1.x. Leafia was one NEMESIS package for wireless
CarPlay and Android Auto; HMG-API is a mod manager. Any number of mods can be
installed together, none of them overwrites another, and removing one puts back
exactly what it changed.

**Tested on:** a US Kia EV6 260507 (2026-10-31, `docs/car-test.md`): the hook runtime,
mods, the app, buttons, the side panel, Android Auto turn-by-turn and projection events,
the MICOM recording, the Car Report. Not yet: CarPlay on HMG-API, installs without adb,
other cars.
HMG-API runs on any Gen5W navigation head unit. A car's hook map (US Kia EV6 260507
today) turns on the parts that change the car's own apps; without one, or for points a
map lacks, only those parts stay off and the app says why.

### For owners

- **Install** from a USB stick (with or without NEMESIS), as a NEMESIS package, or
  over adb. Uninstalling puts every file and setting back to the stock bytes.
- **Mods as NEMESIS packages** (`<mod>-<version>-nemesis.zip`): install, update and
  uninstall a mod from NEMESIS Manager or a stick. HMG-API's engine does the work, with
  the same checks as the app; uninstalling puts the mod's files back to stock. Authors
  make one with `tools/mknemesismod.sh`.
- **The HMG-API app** (its own menu tile and ★ choice): a status bar (hooks on, changes
  not applied, restart needed) with *Apply changes* (a plan of what will change before
  anything does), *Check USB updates* and *Restart unit*; tabs for **Mods** (each mod's
  state in colour, switch, settings, log), **Settings** (vehicle, updates, phone
  projection, hook map, about), **Car Report** and **Logs** (HMG-API's own log, kept
  since the start).
- **Car Report:** checks how HMG-API fits the car and maps it from its own software on
  the spot; saved to a USB stick, with a QR code that opens a filled-in GitHub issue.
  Model-level facts only, logs scrubbed. Since beta 2 it also holds the head unit's
  network: the stock firewall (its service, script, rules and counters), routes,
  and the packets the firewall dropped (device addresses removed).
- **Asked first:** the first time a mod uses a car command, HMG-API asks you on screen
  (once per mod and command; giving back never asks).
- **Mods are signed by their author.** Updates must come from the same author key
  as the installed mod, so nobody can install over someone else's mod. A mod from
  an author not on HMG-API's trusted author list installs with a warning.
- **Nothing is hidden.** The plan lists, per mod, the apps it installs, the services
  it runs, the car readings and commands it uses, the buttons it binds, its side
  panel pages and its Android Auto settings. When two mods want the same thing (a
  button press, Android Auto's density), the plan says which one has it, and you can
  pick the other.
- **Wireless CarPlay and Android Auto** are now a mod, with the same features as
  Leafia 1.x (turn-by-turn in the cluster and HUD, the CarPlay Bluetooth bridge,
  extra audio formats), and no app or library file replaced: the changes are made in
  memory as the apps load.
- **OTA updates** stay blocked by default while HMG-API is installed (USB and dealer
  updates still work), so an update can't silently remove it.
- **Startup notice:** a switch in the app (off by default) lets a mod dismiss the
  notice shown at start. It says what it skips.
- **With a NEMESIS package that does the same** (since beta 3): files a package
  changed and then put back when it was uninstalled no longer stop HMG-API ("changed
  outside HMG-API"): a mod's line edits go on the file as found, and an exact file
  that is stock again is built on again; a file no mod wants any more is let go. A
  file a mod adds that a package already added names the package to uninstall
  first. NEMESIS packages' own backup names (`featurexml.<onebin>.cfg`, `perm.*`,
  `*.absent`) are recognized.
- **The NEMESIS download** (since beta 3) is laid out like NEMESIS's own packages:
  unzipped onto a stick, its `NEMESIS.data` (`type=install`) installs
  `packages/hmgapi.zip`. Before, it was the bare package, which NEMESIS's USB handler
  reports as invalid when it's unzipped onto a stick, and doesn't see as a zip copied
  onto one.
- **NEMESIS descriptor 1.1** (since beta 4): `version=1.1` (the descriptor's),
  `type`, `package-version` (dotted numbers: a beta of 1.0.0 is 0.99.<beta>),
  `short-desc`, `detailed-desc`, `os` (the tested US EV6 260507), `models`, `depends`, as
  NEMESIS's Package Store takes them. `hmgapi-<v>-nemesis-package.zip` is the package
  itself, for the store and `nemesis install`.
- **Opening the app without a tile** (since beta 5): NEMESIS Manager's **Launch** for
  HMG-API (and `hmgapid sh 'sh /data/hmgapi/open.sh'` over adb) opens the app,
  installing it first from the copy HMG-API keeps if it's missing. The tile and ★
  choice come from the hooks, so on a car without a hook map this was the missing way
  in (and to its Car Report). An app install that fails with Android up is tried again
  at the next restart, with a message on screen, instead of only a line in the log.
- **Install and uninstall scripts** (since beta 4): the installer checks the hook maps
  with the rest of the payload, before anything changes (it used to find them
  missing after `/system` was already changed); NEMESIS's preuninstall only takes an
  update for one when an HMG-API package is staged and the command isn't an
  uninstall (a stage left by an interrupted install made an uninstall do nothing);
  the uninstaller starts hmgapid if it isn't running, since restoring the mods'
  changes goes through it.
- **Hook maps lost when unzipping** (since beta 4): the payload had a file `MAPS` next
  to the folder `maps`. On FAT/exFAT sticks, Windows and macOS those are the same name,
  so one was lost when the download was unzipped there, and HMG-API installed without
  its hook maps (hooks off) or stopped on the MANIFEST check. The file is now
  `MAPS.md5`; the installer stops with a clear message if the maps folder is missing;
  every download is checked for names that clash without case or that FAT can't hold.
- **The network keeper** leaves another tool's rules ahead of HMG-API's if they can't
  drop anything (accept, return, log, mark), instead of moving itself back to the top
  every 10 s.

### For mod authors

A mod is a folder with a `mod.json`; everything below is declared there, shown to the
owner in the plan, and undone when the mod is turned off or removed. Reference:
`docs/mod-reference.md`. An existing package can be offered as a mod as well, side by
side with it: `tools/mod-draft.py` drafts its `mod.json`.

- **Hooks:** Java methods in any app, by stable hook point names mapped per car
  (`core/hookpoints`, `maps/`), never by an app's obfuscated names. Native library
  patches in memory (`hooks.native`).
- **Your own APK** (`apps`): installed, turned off and removed with the mod.
- **Services:** scripts or `app_process` jars, run as root, system or your app's own
  user; restarted with backoff, logged, stopped when the mod is turned off.
- **The client library** (`hmgapi-client.jar`): your app and services ask HMG-API
  for their settings, services, logs, USB folders, readings and commands, without
  root.
- **Vehicle readings** (18, by name: gear, speed, steering angle, pedals, 12V and HV
  battery, conditioning, charging, odometer, seat heat, guide lines), read through
  the car's own services and shared between mods.
- **Vehicle commands**, each reviewed and guarded: charge door (park only), seat
  heat, wheel heat, battery conditioning, ambient light (one mod at a time), parking
  guide lines, rear camera turns, startup notice (owner's switch).
- **Buttons** (`input`): 40 head unit, steering wheel and overhead buttons. A held
  button is a mod's shortcut (the car's hold gives way; a short press still reaches
  the car); a short press can be watched or taken; knobs report every click. Star
  keys: the short press stays the owner's pick in the car's menu, the hold a mod's.
- **Side panel pages:** a standard Android app widget from your app, in the
  right-hand split screen.
- **Phone projection:** Android Auto density, sensors (no tap limit, EV sensor
  types), fuel and connector types; events for Android Auto and CarPlay sessions,
  guidance and navigation messages.
- **Network** (since beta 2): firewall rules (filter, nat, mangle; any chain),
  routes, routing rules and `net.*` sysctls, declared in `mod.json` and kept in place
  by HMG-API's network keeper even when the stock firewall reloads. Rules go in
  HMG-API's own chains, checked before the car's; hotspot-only rules need nothing
  more, anything that can reach the car's own network needs the owner's
  `overrides.allow_network`. Found on the Ioniq 6, whose firewall allows the CarPlay
  ports only on the wired link.
- **Not while driving:** your app follows the car's own driving restriction and
  popup.
- **USB folders, tiles with day and night icons, the car's notices.**
- **Shared files and settings:** feature flags, the Wi-Fi client, `media_codecs.xml`
  by codec name, list settings (enabled keyboards), and an install-time probe for
  decisions made on the unit.
- **MICOM watch** (owners and developers): records the frames the head unit gets
  from the car's MICOM, for mapping new readings with the community CAN projects
  (`docs/micom-map.md`). Listening only; identity never reaches the log.
- **Community mod catalogue:** one pull request (`tools/catalog.py add`) lists your mod
  on the wiki's Community mods page, linking your repository and your own signed
  release zip, and puts your author key on the trusted list. CI downloads the zip and
  checks its SHA-256 and signature; an entry's author key can't change.

### Compared with Leafia 1.x

- One mod per feature, installable together, instead of one package for all.
- No replaced app or library files: hooks and in-memory patches.
- Settings change without reinstalling; most changes apply without a restart.
- `stepofspec` is never changed.
