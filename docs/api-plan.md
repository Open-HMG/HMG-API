# HMG-API: what community mods need

Fifteen community projects for Gen5W head units, read for what each one changes and
which car services it calls. None of them is made into a mod here: this is what
HMG-API offers so that any of them can also be offered as a mod, if its author
wants, and so all of them can be installed together.

Rule for every API below: use what the car already offers (the `automotive`
framework's managers, Android, the projection apps' own callbacks), mapped per car
like the hook points (`maps/<make>/<model>/<region>/<firmware>/`), and settle every
clash between mods in one place: two mods never fight over a file, a key, a light or
a camera.

## The projects and what they need

| Project | What it does | How it does it today | Needs from HMG-API |
|---|---|---|---|
| AA DPI (eliotwass) | Android Auto layout density; optional tap-limit removal | rewrites GPM's dex (density literals, speed sensor) | **Projection**: AA video density, AA sensor list |
| SmartTube (KramerTech) | YouTube app, park only | NEMESIS package with its own APK, a watcher for the codec package | **Apps** (own APK), **requires** (exists), **Driving**: park-only policy and the car's "not while driving" notice |
| KeyBORD (ntwm787) | Hacker's Keyboard as the system keyboard, and back | APK + settings commands | **Apps**, **Settings** (default and enabled keyboards, put back on removal) |
| Codec Patch (KramerTech) | Working video decoders for third-party players | edits `/system/etc/media_codecs.xml`, adds decoder libraries, probes at install | **Config merge** for `media_codecs.xml` (by codec name), `add` (exists), **install-time step** (probe) |
| Ambient Sound (nmz787) | Microphone-driven ambient light | system-uid worker calling `ISystemService` 77/78 | **Vehicle**: ambient light (lease), **Services** (worker), **Apps**, **Tiles** |
| Steering Arcade (nmz787) | Game driven by steering, pedal, brake | reads `tm_hscan_service` every 100 ms | **Vehicle** readings (shared poller), **Apps**, **Tiles** |
| Launchpad (paytondevs) | Manual battery preconditioning; TUNE-hold launcher | root `app_process` daemon on `/dev/input`; `HEVService` 62/65/66 | **Input** (TUNE hold), **Vehicle**: battery conditioning, **Services**, **Apps** |
| EGMP AA EV (willemcvu) | EV routing in Maps over AA, automatic preconditioning, EV logs | 3 patches on the AA library + GPM dex changes; HEV readings | **Projection**: AA sensors (type 23, fuel and connector types, raw sensor batches), navigation events; **Native patches** (exists); **Vehicle**; **USB** folders |
| Factory82 (armandodollia) | Dismisses the startup confirmation | watcher calling `com.hkmc.factory` 82 | **Vehicle**: startup notice dismiss; **Boot events** |
| Rear Dashcam (KramerTech) | Records the backup camera to USB | root helpers: camera, display overlay alpha, `micom.pgs` guide lines; mute ×4 | **Camera** (hand-off to the stock reverse view), **Vehicle**: guide lines, gear; **Input** (mute ×4); **Services** (root, raw I/O); **USB** |
| Cold-start Heat (eclipseempire) | Heated wheel and seats when it's cold | `micom.usm` 213/214 | **Vehicle**: outside temperature, seat and wheel heat; **Boot events** |
| Tilepad (KramerTech) | Grid launcher on TUNE hold | Launchpad's daemon; Wi-Fi client toggle; reboot | **Input**, **Wi-Fi**, **reboot** (exists for HMG-API's app), **Apps** |
| Phone Assistant PTT (dzzzy90) | Voice button opens Siri/Google | replaces a framework odex (`ModeKeyDispatcher` delay) | **Input**: re-route the voice button (a hook, no file) |
| Battery Watchdog (zpoon) | 12V battery warnings and history | `tm_hscan_service` 137/214; toasts; USB export | **Vehicle** readings, **UI** (notices), **USB**, **Services** |
| Charge Port Button (eclipseempire) | MAP ×3 opens the charge door, park only | `MAP_SHORTCUT` broadcast; `micom.bcm` 84, `micom.system` 36 | **Input** (MAP ×3), **Vehicle**: charge door (park only), gear |

## Coverage today

What each project could use from HMG-API now, and what it still does on its own.
"Own" isn't wrong: it keeps working as a mod service; it just isn't settled with
other mods yet.

| Project | Covered now | Still its own (until the step that covers it) |
|---|---|---|
| AA DPI | density, speed sensor (tap limit) | |
| SmartTube | APK, `not_while_driving`, `requires`; codecs through Codec Patch's merge | |
| KeyBORD | APK, default keyboard, enabled-keyboard list merged per entry | |
| Codec Patch | `add` files, `codecs` merge by name, install-time probe | its regression check after install (its own probe can repeat it) |
| Ambient Sound | APK, worker service, tile with day/night icons, ambient light (lease) | |
| Steering Arcade | APK, tile, readings: steering, pedal, brake | |
| Launchpad | APK, daemon service, readings, TUNE hold, conditioning | |
| EGMP AA EV | native patches, USB folder, readings, conditioning (auto-start), sensor 23, fuel and connector types, navigation messages (apps and hook code), the raw sensor-batch sender | its library recipes (native patches stay in mods); the energy model itself; outside temperature, range |
| Factory82 | watcher as a service, startup notice (behind the owner's switch) | |
| Rear Dashcam | root helpers as services, USB folder, readings: gear, mute ×4, guide lines, rear camera turns | its display-layer helper stays a root service (raw display registers) |
| Cold-start Heat | service, seat and wheel heat | outside temperature |
| Tilepad | APK, TUNE hold, Wi-Fi client | |
| Phone Assistant PTT | voice button (`ptt` hold/short, replace) | starting the phone's assistant itself (its own code, or step 4) |
| Battery Watchdog | service, notices, USB folder, readings: 12V, health | |
| Charge Port Button | APK, readings: gear, MAP ×3, charge door | |

## Where they would clash today, and how HMG-API settles it

| Clash | Settled by |
|---|---|
| GPM: AA DPI, EGMP and the Wireless mod each replace `HKMC_GPM`'s dex | hooks and Projection settings: nobody replaces GPM |
| AA library: Wireless (turn-by-turn) and EGMP (sensors) patch the same library | native patches in memory, non-overlapping regions (checked when applied) |
| TUNE hold: Launchpad and Tilepad both open on it | Input: one binding, one owner; the owner picks in HMG-API's settings (like the ★ key) |
| Battery conditioning: Launchpad (manual) and EGMP (automatic) | Vehicle: one conditioning state; each request is logged with the mod that made it, and a manual stop wins over an automatic start |
| Ambient light: Ambient Sound and any other light mod | Vehicle lease: one holder at a time; the previous colour and brightness come back when it lets go |
| Rear camera: Dashcam and the stock reverse view | Camera: the stock view always wins; the holder gets "give it back" and "you can have it again" |
| USB drive in the port: Dashcam keeps one in, which can stall wireless Android Auto | core (moves out of the Wireless mod): a drive never starts wired AA |
| Wi-Fi: Tilepad turns the client on; Wireless needs the hotspot | Wi-Fi: requests for hotspot and client are combined (same channel); the owner sees who asked for what |
| Framework file: PTT replaces a framework odex | Input re-route through a hook: no file |
| `media_codecs.xml`: Codec Patch edits it; others may later | Config merge: edits by codec name; two mods editing the same codec are refused with the reason |

## The APIs

Each is a core class (`hmgapi.hook.*`) for hook code and the same calls for a mod's
own app through the **client** (below). A mod declares what it uses in `mod.json`,
so the plan shows it and the owner sees it.

### 1. Apps: a mod's own APK

`mod.json`: `"apps": [{"apk": "app/SmartTube.apk", "package": "com.liskovsoft.smarttube"}]`.
Installed with the signature workaround (`sys.boot_completed` 0 for the `pm` call,
deferred while Android is down, as HMG-API's own app), kept with its data on a mod
update, removed with the mod. An app id already installed some other way is
reported, not overwritten, unless the owner says so.

### 2. Services: a mod's background processes

`"services": [{"name": "knob", "dex": "svc.jar", "class": "x.KnobDaemon", "uid": "root|system|app", "start": "boot|android", "restart": true}]`
(or `"script"`). Run and watched by `hmgapid`, logged into the mod's log, stopped
when the mod is turned off, started again when it's turned on, no restart needed.
Raw device access (Dashcam's display overlay) is a root service declared as such;
the plan says so.

### 3. Client: calling HMG-API from a mod's app

A small jar (`hmgapi-client.jar`, compile against it) that talks to `hmgapid` over
its socket. `hmgapid` knows the caller's app id (peer uid), finds the mod that owns
that app, and allows only what that mod declared:
`HmgApi c = HmgApi.connect(context); c.vehicle().read("hv.soc");`.
This is what lets a plain app (SmartTube, Steering Arcade, Launchpad) do these things
without root of its own.

### 4. Vehicle: readings and commands, by name

Built on the `automotive` framework's managers (`BCMManager`, `HEVManager`,
`USMManager`, `micom.ISystemService`, `hscan`, `regulation`, `pgs`, `factory`), mapped
per car as framework points, never raw transaction numbers in mods.

- **Readings** (any mod that declares them): gear and park, speed, steering angle,
  accelerator, brake pressure, HV state of charge and health, 12V level, outside
  temperature, battery temperature, charging state, conditioning state, seat and
  wheel heat state. One poller per reading for the whole unit, at the fastest rate
  any mod asked for; subscribers get changes.
- **Commands**: each one is named, reviewed on its own before it's offered
  (project rule: no own CAN frames; commands through the stock services, each
  reviewed first), and carries its guard: charge door (park only), seat heat
  driver/passenger level, wheel heat, battery conditioning start/stop, ambient
  light colour/brightness (lease), parking guide lines on/off, startup notice
  dismiss. A mod asks for a command by name in `mod.json`; the owner sees the list
  when installing.

Commands to review first (from these projects): `BCMManager`/`micom.bcm` 84
(charge door), `USMManager` front seat heat steps / `micom.usm` 213-214 (seat and
wheel heat), `HEVManager.setBatteryConditioning` / `HEVService` 62/65/66,
`USMManager.setAmbientLight` / `ISystemService` 77/78, `micom.pgs` 33/59 (guide
lines), `com.hkmc.factory` 82 (startup notice).

### 5. Input: buttons and the knob

Bindings, declared: `"input": [{"key": "tune", "press": "hold", "action": "open", "component": "..."}]`,
`{"key": "custom", "press": "hold"}`, `{"key": "mute", "press": "short"}`,
`{"key": "ptt", "press": "short", "route": "phone_assistant"}`. One reader in the
system (hooks on the framework's key dispatch, not `/dev/input` per mod), patterns
(short, hold, N presses) detected once, the stock action kept unless the binding
replaces it. Two mods on the same binding: the owner picks.

### 6. Projection: CarPlay and Android Auto

- Sessions: wired/wireless, started/ended, navigation owner (car or phone).
- Turn-by-turn feed for both phones (the data `AaTurns`/`CpTurns` already build).
- Navigation events: route active, destination, next stop (EGMP's preconditioning).
- Android Auto settings: video density (one value; two mods asking differently is a
  conflict), sensors offered to the phone (remove speed, add type 23), fuel and
  connector types, a raw sensor-batch sender (EGMP's energy model). Built on GPM
  hook points (`VideoSink.nearestVideoConfiguration`, `SensorSource`). EGMP's three
  AA library sites stay in its own recipes, not core ones: HMG-API never owns a native
  patch (the maintainers' decision on #44), so a new library build is the mod's update.

### 7. Driving: park-only apps

`"driving": {"park_only": ["com.liskovsoft.smarttube"]}`: HMG-API watches the gear
and closes the app with the car's own "not available while driving" notice when
leaving park, as the stock apps do.

### 8. UI, tiles, USB, boot

- Notices: the car's toast and popup (`com.hkmc.intent.action.TOAST`), from any mod.
- Tiles: the menu tiles that exist now, plus day/night icons (Ambient Sound, Steering
  Arcade, Tilepad).
- USB: a mod names a folder (`egmp-aa-ev-logs`, `Dashcam`, `BatteryWatchdog`); HMG-API
  tells it when a drive with that folder is in.
- Boot events: Android up, screen on (Factory82, Cold-start Heat).
- Side panel pages (owner's request, 2026-10-30): `provides.panel` app widgets from a
  mod's own app, shown in the right-hand split-screen panel. *Done (off-car; maps for
  the EV6): the panel (`RightScreenHome`) has 23 fixed page slots; HMG-API takes slot
  22, Mobis' unused test page, by setting its never-set field and answering its
  always-off switch only inside the two methods that build that slot, and hosts the
  widgets there (AppWidgetHost; the panel runs as system). The car's split-screen
  editor (`ThemeSettings`) lists the page; Settings keeps id 22 while a mod has a
  page; HMG-API's uninstall takes it out of the lists. Example:
  `mods/examples/side-panel`.*

### 9. Settings and config files

- Android settings (`secure`, `system`, `global`): the existing `setting` change type,
  plus list values (enabled keyboards) merged per entry.
- `media_codecs.xml`: edits by codec name (remove, add, order), merged between mods.
- Install-time steps: a mod's own check (Codec Patch's probe) run by the engine, with
  its result in the plan.

### 10. Wi-Fi, iAP2, Bluetooth, Features (from the Wireless mod)

Hotspot and client requests combined; iAP2 listeners and sessions; Bluetooth SPP;
Hyundai feature flags with shared use. These come out of the Wireless mod into core.

## Order

1. **Apps, Services, Client, UI notices, Tiles icons, USB folders.** Every project
   needs at least one; nothing else works without the client. *Done (off-car tests;
   not yet on a car): `mod.json` `apps`, `services`, `usb`, launcher `icon`/`icon_night`;
   `hmgapi-client.jar`; `hmgapi.hook.Notices`; `tools/mod-draft.py` and
   `mod-reference.md` for authors.*
2. **Vehicle readings, Driving (park only)**, then each **Vehicle command** after its
   own review (`vehicle-commands.md`). *Readings and "not while driving" done
   (off-car tests): 15 readings through the car's system, HEV, regulation and
   telematics CAN interfaces, mapped by method name (`framework.json`), served by
   HMG-API's vehicle service through `hmgapid vehicle` to what each mod declared;
   `not_while_driving` uses the car's own driving restriction and popup instead of
   a gear watcher. Commands done (owner's decisions 2026-10-30: 1-6 as proposed,
   7 behind the owner's switch): `vehicle-do`, guards in `vehicle/Commands`
   (37 off-car checks). Not yet: outside temperature.*
3. **Input** bindings and re-routes. *Done (off-car tests; mapped on the EV6): 40
   named buttons (head unit, steering wheel, overhead), presses short/hold/down
   (multi-press dropped after the car test: it delays every press or lets the car
   act on each; hold always takes the car's hold), keep or replace, settled between mods with the owner's pick; one watcher
   in system_server at the car's own key entry
   (`ModeKeyDispatcher.interceptKeyFromAndroid`, -1 uses a key up; a held-back
   press is handed back by calling it again). Press logic tested with a fake clock
   (`keys-test.sh`).*
4. **Projection** (Android Auto settings and sensors, sessions, navigation events) and
   **Features**. *Android Auto done (off-car; mapped on the EV6): `"projection"`
   density, sensors (remove speed, add types), fuel and connector types, events
   (connected, disconnected, navigation focus, guidance, navigation messages 0x8006
   and 0x8007 from the Wireless mod's library patch, for apps and hook code); through
   hooks on GPM's `VideoSink.nearestVideoConfiguration` and `SensorSource.init`. Raw
   sensor batches (`"aa_raw"`, `ProjectionHooks.sendRaw`) and types above 21, where the
   asking mod's own library recipe is in place. Not yet: CarPlay events, Features.*
5. **Camera, Wi-Fi, iAP2, Bluetooth, config merges.** *Done (off-car): shared
   `feature_flags` (the Wireless mod's two flags moved to it), `"wifi": {"client":
   true}`, `codecs` changes merged by codec name, list settings merged per entry,
   install-time `probe`, `rear_camera` turns. Not done: iAP2 and Bluetooth stay in
   the Wireless mod (no other project needs them yet).*

Every step ends with the APIs in the catalogue, maps for every car, off-car tests,
and an example in `mods/examples` that uses them.
