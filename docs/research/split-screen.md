# Docked split screen: research

Goal: when the right-hand split-screen panel (`com.mobis.splitscreen.right`,
`RightScreenHome.apk`) opens, the app area gets narrower (content pushed left), like a
docked side panel, instead of the panel drawing over the current screen.

Research only (2026-11-08): read from the stock trees, nothing changed on a car.
Questions that need the car are marked **Q** and collected with their adb commands at
the end.

**Short answer.** The stock firmware already has the docked mode, complete, in the
window manager policy. Mobis switches it off on these cars with one feature file,
`/system/etc/permissions/com.hkmc.software.splitscreen.floating.xml`. With that feature
off, the panel opens as a docked window, the policy shrinks the app area to 1280 px
(853 dp), and the window manager sends every app a narrower configuration. Apps that
can't take it (launcher, navigation, CarPlay) already ask the panel to keep them full
width. The projections are fixed-size per session. Stock has a setting that starts
CarPlay at 1280×720 and Android Auto at 1280×720 with the panel beside them, but they
can't be narrowed partway through a session.

## Cars checked

| Car | Tree | What was checked |
|---|---|---|
| US EV6 260507 | `~/hmg-private/stock/ev6-full` (+ `ev6dump/info/getprop.txt`) | everything below |
| CA EV6 260206 | `ev6-can-260206` | policy, WMS, `Feature`, panel, DiPO, GPM: same code as the US EV6 |
| CA Ioniq 5 260206 | `neev-can-260206` | same policy/WMS/`Feature`. Panel and DiPO classes differ as files, but the split methods (`bK`, `setContentView`, `bh`, `setFreezeSS`, `D`, `i`; DiPO `mU`, `mW`) are the same |
| US Ioniq 5 260507 | `neevold-usa-260507` (same build as `neev`; `neev-260507` only holds BtSettings) | same as the CA Ioniq 5 |
| US Ioniq 5 (2023+) 260903 | none: `maps/hyundai/neev23/usa/260903` is probed from the car | **not checked** (Q11) |

All four trees have `ro.device.splitscreen=left_right`, `ro.sf.lcd_density=240` and both
`com.hkmc.software.splitscreen.floating.xml` and `…splitscreen.fullsize.xml`. The
screen is 1920×720 px = 1280×480 dp. The panel is 640 px (644 with the ECO UI), so the
docked app area is 1280 px = 853 dp.

## 1. How the panel is shown today

**Process.** `com.mobis.splitscreen.right`, a persistent system-uid service. Its base
class (smali `k/r`, source `SplitScreenBase.java`, the `panel.*` points' owner) extends
`Service` and adds its own windows through `WindowManager.addView`.

**Two modes, picked once in `onCreate`** from `com.hkmc.Feature.hasFeature`:

| | Floating (all checked cars ship this) | Docked (stock code, switched off) |
|---|---|---|
| Feature | `com.hkmc.software.splitscreen.floating` present | absent |
| Window type | 2045 `TYPE_SPLIT_WINDOW_FLOATING` (Mobis type) | 2042 `TYPE_SPLIT_WINDOW_RIGHT` (Mobis type) |
| Size / position | 640×MATCH_PARENT, `x = 1920 − 640 = 1280`, gravity LEFT, flags `0x40368` (not focusable, not touch-modal, layout in screen / no limits, watch outside touch), translucent | MATCH_PARENT×MATCH_PARENT, window animations, private flag `0x40`. The policy forces its frame to `[1280,0][1920,720]` |
| Policy knows it? | No. `PhoneWindowManager.prepareAddWindowLw` registers only types 2041/2042 (`mSplitWindowLeft/Right`); 2045 is an ordinary overlay | Yes: `mSplitWindowRight` |
| On show/hide calls | `ExtendedWindowManager.setFloatingSplitScreenEnabled(bool)` (WMS only notifies "right OSD" listeners) | `ExtendedWindowManager.setSplitScreenEnabled(bool)` → WMS → policy (below) |
| `setFreezeSS` from apps | ignored ("mbUseFloatingWindow") | honoured |

(`fullsize` sets the panel's own full-screen flag and skips its `SplitSystemUiCheckerWindow`;
it doesn't affect the docking. **Q**: what it changes on screen.)

**Show/hide.** Requests reach the panel's handler through its binder interface
(`com.hkmc.split.manager.SplitScreenManager` in `automotive.jar` is the client apps
use) and its receiver (`SPLIT_BUTTON_SHOW/HIDE`, `SCREEN_ON/OFF`,
`SPLIT_SCREEN_DISABLED_SCREEN`, keyboard show/hide, connectivity). It also hides itself
for the rear camera and for screens apps put on its "button off" list
(`addBtnOffScreen`: DiPO, GPM, navigation, phone, media and others). One method does
both (`bh(boolean)`, log `setSplitSeenShow`). **Q1**: which user control sends the
request on the car.

**What tells others it's open:**

- broadcast `com.mobis.intent.action.SPLIT_SCREEN_SHOW` / `…_HIDE` (extra `ssByUser`),
  heard by about 30 stock apps;
- `Settings.System split_screen_status` = 1/0, written by the panel;
- `SplitScreenManager.SplitListener.onChangeOnOff(boolean)` callbacks;
- docked mode only: WMS `ISplitScreenStateListener` (`registerSplitScreenStateListener`).
  Every `android.app.Dialog` registers one and moves itself (`updateDialogPosition`).

In floating mode the window manager doesn't change anything. A few apps narrow
themselves on these signals: Radio's manual-tune screen and MediaGate set their own
`Configuration.screenWidthDp = 853` and `updateConfiguration` (MediaGate from
`onChangeOnOff`), and USB Music moves views by 1280. Everything else gets covered.

**Settings `SplitScreenUtils` (settings.split_screen) and the theme settings' split
item** only manage which pages the panel shows and in what order (`ss_list`,
`ss_setting`, `split_screen_on` per driver, `splitscreen_settings_pref`) plus the digital
frame images. Neither has a layout or mode setting.

## 2. Docked / resized mode already in the firmware

Yes, all of it. It's Mobis code in `android.policy.odex` and `services.odex`,
identical on the four checked cars:

1. `WindowManagerService.setSplitScreenEnabled(true)` → `PhoneWindowManager.setSplitScreenEnabled`
   → `showSplitScreen()`: `mSplitScreenStates = true`, redo layout.
2. The new configuration runs `WMS.setNewConfiguration` → `policy.updateSplitWindowState()`
   → after 300 ms (`PhoneWindowManager$23`, log "start genuine resize for split screen")
   `mSplitScreenResize = true` and layout again. Hiding is the same in reverse
   (`$24`, `hideLw` after the animation).
3. `beginLayoutLw` (only when `ro.device.splitscreen` = `left_right`): with
   `mSplitWindowRight` visible and `mSplitScreenResize`, it sets the dock, content,
   stable, system and current right edges to the panel's left edge (1280), log
   "Screen width is resized to 853dp by split screen". So apps' windows are laid out at
   0..1280.
4. `WMS.computeScreenConfigurationLocked` (same property) takes `screenWidthDp` from that
   content rect, so the global configuration becomes 853 dp: activities without
   `configChanges="screenSize"` are relaunched, and resources pick the narrow layouts.
5. **Opt-out per activity:** `SplitScreenManager.setFreezeSS(true, className)` makes the
   panel call `freezeResizingBySplitScreen(true)`. The policy then keeps the full 1920
   (log "Screen width is fixed to 1280dp by mFreezeResizing") and the panel overlays,
   as today. The panel clears the freeze when another activity resumes (`D()`, log
   "checkFreezeSS"). Stock callers: Launcher_WP3, navigation (`NaviApp`,
   `NaviLoadingActivity`), DiPO (`DipoActivity.onResume`), HomeSetting, ButtonSettings,
   Settings, GeneralSettings, SoundSettings, phone contacts, TApps.

Apps already built for the narrow width (US EV6 tree: `res/layout-w1280dp` for full
width beside a default layout for narrower): GeneralSettings, BtPhoneContacts,
BtSettings, HDData, MediaGate, ModemFoTAService, RadioNA, TAppSafetyAndSecurity,
TAppVRM, VehicleSetting, VoiceMemo, Weather, InCallUI, Settings_W (Climate has
`drawable-w1280dp`). The other trees hold only some apps; BtSettings and Settings_W have
the same qualifiers on all four. Launcher_WP3 has no narrow layout and freezes itself.

Also found, not needed here: a left split window (type 2041, 80 px) and top/bottom types.
No overscan or display-area setting is used for this; `persist.sys.display_*` and the
HDMI ones are about the external display.

## 3. What each kind of screen needs to get narrower

| Screen | In docked mode as stock wrote it | Gap |
|---|---|---|
| Apps with narrow layouts (list above) | resize and relayout by themselves | Floating-mode self-narrowing in Radio, MediaGate and USB Music must turn off, or it runs on top of the real resize (it shifts views by 640/1280 px). It's keyed on the same `floating` feature, so turning the feature off for them does it |
| Settings and GeneralSettings | have narrow layouts but call `setFreezeSS(p1, …)` with a flag. **Q**: when do they freeze? | — |
| Launcher_WP3 / HomeSetting | freeze → full width, panel overlays | Narrowing needs launcher hooks to stop the freeze and lay the tiles out for 1280 px (it has one 1920 layout). Large |
| Navigation (`HKMC_Navi_ExSL`) | `NaviApp` freezes on resume | **Q12**: does the native map redraw at 1280 if not frozen? |
| Other stock apps (no narrow layout, no freeze) | get 853 dp and stretch or clip what was drawn for 1280 dp | per app; **Q** |
| HMG-API's app (`core/app`) | no `configChanges`, so it's relaunched; layouts are weight-based (`Ui.K` scaling) and should reflow | keep the selected tab across the relaunch; check it at 853 dp off-car |
| Dialogs | move on `ISplitScreenStateListener` | none |
| CarPlay (DiPO) | `DipoActivity` always freezes | below |
| Android Auto (GPM) | no freeze found; the window would shrink while the phone's video stays 1920 wide | below |

### CarPlay (DiPO)

- The session size is fixed when the session is created
  (`CarPlayService.createCarPlay` → `TccCarPlayNativeMethod_Create`, one
  `CarPlayScreen` mode + pixel/physical sizes). Modes: 800×480, 960×540, 1280×720,
  1920×720 (30/60 fps), custom.
- What it sends today: `e/a/i.mU()` gives the width as the display width (1920) →
  `VideoStream_1920x720_60fps`. It gives **1280** (`VideoStream_1280x720_60fps`) when
  the projection split setting is on (`DipoService.fy()`, the "getPPBtnOffState" check)
  **and** the car has no wireless CarPlay (`isSupportWirelessCarPlay`: feature
  `com.hkmc.software.connectivity.carplay_wireless`). `DipoActivity.onResume` then sizes
  the surface 1280×720 and `setPPFullscreenMode(false)`. With the session at 1920, it's
  1920×720 full screen.
- Stock cars don't have `carplay_wireless`, so a wired session follows the setting. The
  post-install image (`ev6-sysimg`) has `carplay_wireless.xml` and
  `androidauto_wireless.xml` (the wireless mod), and with those DiPO always starts at 1920.
- Setting key (US/CA, `ro.product.opt.stepofspec` 105): `carplay_ss_osd` (`_dr1`/`_dr2` per
  driver profile); `phoneprojection_setting_use_split_screen` on other specs. Label in
  PhoneProjection: "Use Split Screen on Apple CarPlay Screen". **Q5**: shown on US cars?
- **View areas:** no. The native libraries (`libCarPlay*.so`) carry no
  `viewAreas`/`safeArea` keys (only `widthPixels`, `heightPixels`, `widthPhysical`,
  `maxFPS`, `primaryInputDevice`), so the phone can't be told about a smaller area
  partway through a session. Ways around it: start sessions at 1280 whenever the option
  is on (panel space always reserved), restart the session when the panel opens (seconds
  of black, audio drops: not recommended), or scale or crop the 1920 picture into
  1280 px (distorted, or the right third is lost; touch would need the same mapping,
  **Q10**).

### Android Auto (GPM)

- `GalConnectionSession.initGalReceiver` offers video configurations once per session
  through `VideoSink.nearestVideoConfiguration(width, height, density, realDensity, fps,
  depth)`, which picks the codec size and margins.
- What it sends today (wide screen, `stepofspec` ≥ 40): 1920×720 → `VIDEO_1920x1080`,
  width margin 0, height margin 360. It also offers 1280×480 and 800×465 lower down.
  With the projection split setting on (`d.by()`: `androidauto_ss_osd` on US/CA): 1240×720
  → `VIDEO_1280x720` with a 40 px width margin, plus 800×465. Wired and wireless both;
  no wireless check here.
- Label: "Use Split Screen on Android Auto Screen".
- **Insets:** this GPM's protocol has no UI-config/insets message. Its
  `VideoConfiguration` stops at field 9 (resolution, fps, width/height margin, density,
  decoder depth, viewing distance, pixel aspect, real density). Margins are fixed for the
  session. Changing them at runtime would mean adding newer protocol messages to a stock
  app, and whether phones honour them on this head unit is unknown: not recommended.
  Restarting the session or scaling has the same costs as for CarPlay.

## 4. Options

All three turn off the same feature in some form. They differ in what's allowed to narrow.

### Option 0: stock projection setting (no changes)

- **What:** turn on "Use Split Screen on Apple CarPlay / Android Auto Screen".
  CarPlay starts at 1280×720 (wired, and only without the wireless mod's
  `carplay_wireless` feature). AA starts at 1280×720 with a 40 px margin. The panel sits
  beside it.
- **/system, hooks:** none. **Back to stock:** turn the setting off.
- **Risk:** none from us. The panel space stays reserved for the whole session even
  while the panel is closed (**Q6**, **Q7**).
- **Extend it to wireless CarPlay:** a DiPO hook so `mU()` ignores the wireless feature
  (new point, e.g. `dipo.config.session_width` on `e/a/i.mU`). The wireless mod's
  repository has to agree; the mod is in `Open-HMG/hmgapi-wireless-carplay-aa`.
- Checked: code on all four cars; the UI is **not checked**.

### Option 1 (cheap): docked only for apps that already resize

- **What:** the panel runs in its stock docked mode, but freezing is the default:
  only activities on a "resizes" list (the narrow-layout apps above, HMG-API's app)
  get the 1280 px area. Everything else keeps today's overlay.
- **Hooks (no /system change):**
  - new framework point `framework.hkmc_feature.has_feature`
    (`com.hkmc.Feature.hasFeature(String)`, `framework2`, not obfuscated): answer
    `false` for `com.hkmc.software.splitscreen.floating`. `Feature` isn't preloaded
    (not in `preloaded-classes`), and each process reads the permission files itself, so
    the hook goes in every app process before `handleBindApplication` (like the
    `updates` option's `IoBridge` hook) and in system_server. The processes that matter:
    the panel, system_server (WMS, `Dialog`), RadioNA, MediaGate, USB Music and the other
    apps that check `floating` (BTAudio, NatureSound, HDData, BtPhoneContacts, SoundHound,
    TApps, VoiceMemo, InCallUI, Weather).
  - new panel points: `panel.base` (`SplitScreenBase`, the `Service` subclass with
    public `setFreezeSS(boolean, String)`) and `panel.base.check_freeze` (`D(String)`,
    found by its string "checkFreezeSS but resumed class is not registered"). After it
    runs, HMG-API calls `freezeResizingBySplitScreen(true)` unless the resumed activity's
    package is on the list.
  - Each new point has to be mapped for every car in `maps/`, so the 260903 Ioniq 5's
    files are needed (Q11).
- **Back to stock:** turn the option off and restart. The panel picks its mode once in
  `onCreate` (persistent process), and each app reads `Feature` once, so reboot.
  Nothing on `/system` to restore.
- **Variant with a file instead of the feature hook:** move
  `/system/etc/permissions/com.hkmc.software.splitscreen.floating.xml` away (md5 gate,
  one-time backup, uninstall puts the exact bytes back). That's simpler and covers
  everything, `PackageManager.hasSystemFeature` included, but it's a `/system` write
  and can't be switched per boot.
- **Risk:** medium. The docked path is complete in the code, but every checked car
  ships floating, so Mobis may never have tested it on these cars or this panel build.
  Each open and close changes the global configuration: the top app relaunches (state
  lost if it doesn't save it, a flicker, 300 ms delay). A panel crash loop wouldn't
  stop the car booting (it's an app; the zygote boot guard isn't involved), but the panel
  would be gone until the option is off.
- Checked: code on all four cars; nothing on a car (Q3, Q9).

### Option 2 (full): everything gets narrower

- **What:** Option 1 with no default freeze, plus:
  - stop the launcher's and HomeSetting's own `setFreezeSS(true)` and relayout the
    launcher for 1280 px (launcher hooks; large, Launcher_WP3 has one layout);
  - navigation unfrozen if Q12 shows the map copes; otherwise it stays on the list of
    exceptions;
  - CarPlay and Android Auto: no runtime resize exists (section 3). Pick one: (a)
    reserve the space for the whole session, i.e. Option 0 (plus the wireless DiPO hook);
    (b) restart the session when the panel opens; or (c) scale or crop the picture into
    1280 px with the touch mapping changed to match (DiPO `DipoActivity` and GPM surface
    hooks, new points). (a) is the only one without a visible cost.
- **/system:** none (hooks), or the same feature-file variant.
- **Back to stock:** as Option 1.
- **Risk:** high. Stock apps without narrow layouts at 853 dp, launcher work, and
  projection behaviour that can only be judged on a car.
- Checked: framework code on all four cars; app behaviour not at all.

**Suggested order:** check Option 0 and Q1–Q7 on the US EV6. Then run the
docked-path test (Q9), which needs a `/system` change done with the backup rules. If
docked mode works, build Option 1. Option 2 only for the screens that turn out fine.

## Questions for the car

Run as the shell user unless noted. The main log buffer only holds ~10 s, so for logs,
start streaming first and then do the action.

| # | Question | Command |
|---|---|---|
| Q1 | Which control opens and closes the panel, and what reaches it | `adb shell logcat -v time \| grep -i -E "setSplitSeenShow\|SPLIT_SCREEN_(SHOW\|HIDE)"` then open and close it |
| Q2 | Floating mode is what runs | `adb shell dumpsys window \| grep -E "mFloatingSplitScreenEnabled\|mRightOsdEnabled"` |
| Q3 | Panel window type and frame; app area while it's open (expect `ty=2045`, `[1280,0][1920,720]`, and `mContent` right edge 1920 today) | `adb shell dumpsys window windows \| grep -A12 com.mobis.splitscreen.right` and `adb shell dumpsys window policy \| grep -E "mDock=\|mContent=\|mStable="` |
| Q4 | Features as the system sees them | `adb shell pm list features \| grep -i -E "splitscreen\|wireless"` |
| Q5 | Projection split settings shown in the UI, and their values | Settings → Phone projection; `adb shell settings get system carplay_ss_osd` and `adb shell settings get system androidauto_ss_osd` |
| Q6 | CarPlay session size, setting off and on | `adb shell logcat -v time \| grep -E "mSetScreenWidth\|createCarPlay"` while CarPlay connects |
| Q7 | Android Auto video configurations offered | `adb shell logcat -v time \| grep -E "resolution: \|isSplitScreenEnabled"` while AA connects |
| Q8 | `fullsize`: what it changes on screen | compare a screenshot with the panel open: `adb shell screencap -p /sdcard/ss.png` and `adb pull /sdcard/ss.png ~/Downloads/` |
| Q9 | Docked path works (**writes /system**: needs the md5 gate and backup first; not done in this research) | `adb shell "hmgapid sh 'umask 077; mkdir -p /data/hmgapi/research && cp /system/etc/permissions/com.hkmc.software.splitscreen.floating.xml /data/hmgapi/research/ && nemesis remount rw && rm /system/etc/permissions/com.hkmc.software.splitscreen.floating.xml; nemesis remount ro'"`, reboot, open the panel, then Q3's policy command (expect right edge 1280) and `adb shell dumpsys window \| grep mCurConfiguration` (expect `w853dp`). Put it back with the reverse copy (`chmod 644`, owner root) and check its md5 against the stock tree |
| Q10 | DiPO touch coordinates are normalized (decides whether scaling CarPlay can keep touch right) | `adb shell logcat -v time \| grep -i -E "OnTouchScreen\|touch"` while touching CarPlay |
| Q11 | 2023+ Ioniq 5 (260903): same features and policy | `adb shell ls /system/etc/permissions \| grep splitscreen`, `adb shell md5 /system/framework/android.policy.odex /system/app/RightScreenHome.odex`, and pull those files privately to map them |
| Q12 | Navigation at 1280 px unfrozen | only after Q9: open navigation, open the panel, look at the map; `adb shell logcat -v time \| grep -i -E "setFreezeSS\|checkFreezeSS"` |
| — | Which Settings screens freeze themselves | `adb shell logcat -v time \| grep -i "setFreezeSS"` while moving around Settings with the panel open |
