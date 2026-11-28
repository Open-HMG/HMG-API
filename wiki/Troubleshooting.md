# Troubleshooting

## Stuck on the boot animation, no home screen, or an app keeps crashing

Most of what follows works without the car's screen, over adb. **Turn adb on before you
need it**, while everything works: the [adb mod](Community-mods) (or NEMESIS's Enable ADB),
then check from a computer that `adb shell hmgapid ping` answers `ok`. adb keeps working
when Android doesn't start (adbd runs before it), and it doesn't ask for keys. While adb
over USB is on, the USB port is in computer mode and doesn't read sticks.

**1. Safe mode: every mod off, nothing removed.** No mod's hooks, services (the ones that
start at boot too), apps or network rules; HMG-API and its app stay on, so you can turn
it off again. adb stays reachable: the adb mod's rule for adb over the hotspot is the one
network rule kept. The mods' changes to system files stay as they are.

```
adb shell "hmgapid engine safe on"
```
```
adb shell "hmgapid reboot"
```

Services, apps and network rules stop within seconds; the hooks after the restart. If the
engine can't run at all, the file does the same:
`adb shell "hmgapid sh ': > /data/hmgapi/SAFE'"`. With the screen: HMG-API → Settings →
Safe mode. HMG-API also turns safe mode on by itself when Android fails to start several
times in a row (see [The head unit restarted over and over](#the-head-unit-restarted-over-and-over)).

If the head unit starts normally in safe mode, a mod is the cause: turn off the one you
added or changed last (Mods tab, or `adb shell "hmgapid disable <id>"`), then turn safe
mode off and restart:

```
adb shell "hmgapid engine safe off"
```

Turning it off also resets the boot guard's counters, so HMG-API's hooks get another try.

**2. Only the hooks off** (HMG-API's own too: no tile, no ★ choice, no update block):

```
adb shell "hmgapid sh ': > /data/hmgapi/hook/disable'"
```

then restart. Back on: `adb shell "hmgapid sh 'rm /data/hmgapi/hook/disable'"`. This
leaves mods' services, apps and network rules running; use safe mode first.

**`hmgapid: not running`?** HMG-API's daemon didn't start. Use NEMESIS's root instead (a
copy of its tool, so it goes through NEMESIS's daemon):

```
adb shell "cp /system/xbin/nemesis /data/local/tmp/nemesis-d"
```
```
adb shell "/data/local/tmp/nemesis-d root -c ': > /data/hmgapi/SAFE'"
```

**A USB stick can't do this.** The stick is mounted by Android (its storage service asks
for the mount once Android is up), so a file on it would only be seen after Android has
started: too late for a head unit stuck on the boot animation. The steering-wheel keys are read by
Android too. Without adb, the boot guard is what turns safe mode on.

**`/data` full.** Apps stop, settings don't save, the car's own apps crash. See how full it is:

```
adb shell df /data
```
```
adb shell "hmgapid sh 'du -s /data/hmgapi/* /data/hmgapi/logs/*'"
```

HMG-API keeps each mod service's log at 256 KB (and a `.1`) while it runs, at most 256 KB
of output a minute, and its own log the same way. Releases before 1.1.0 only checked a log
when its service started, so a service that logged in a loop could fill `/data`. Stop it
(`adb shell "hmgapid disable <id>"`, or safe mode), then empty its log:
`adb shell "hmgapid sh ': > /data/hmgapi/logs/<id>/<name>.log; rm -f /data/hmgapi/logs/<id>/*.1'"`.
Car Reports and problem logs are in `/data/hmgapi/public/reports/`; `adb pull` them first if
you still need them.

**Back to stock.** Uninstall over adb, no screen needed: `./hmgapi-adb.sh uninstall` from
the adb zip, or what it runs: `adb shell "hmgapid sh 'sh /data/hmgapi/uninstall.sh'"`.
Every file HMG-API or its mods changed goes back to the stock bytes. A few steps need
Android up (the HMG-API app and the mods' apps out, the ★ keys back); with Android down
those are skipped. See [Uninstalling](Uninstalling).

## The HMG-API app isn't there, or there's no tile for it

- **No tile, no ★ choice:** those come from HMG-API's hooks, which need your car's hook
  map. Open the app from **NEMESIS Manager → My Packages → HMG-API → Launch**, or over
  adb: `adb shell "hmgapid sh 'sh /data/hmgapi/open.sh'"`.
- **The app didn't install** (a message says so; `/data/hmgapi/install.log` has the
  reason): restart the head unit and HMG-API tries again, or use **Launch** above,
  which installs it first and then opens it.

## After a firmware update: "HMG-API's system files are gone", or uninstall fails

A firmware update (Navigation Updater, or an update over the air) puts the head unit's
system files back to stock. HMG-API's own files and the mods' changes to system files
go with it; HMG-API's data (mods, settings) stays. If NEMESIS is gone too, install it
again first, or use the USB stick route.

- **To keep using HMG-API:** install it again, any route. It notices the update, drops
  its backups of the old firmware and starts from the new firmware's files; your mods
  apply again on them (each one's own checks say if it supports the new firmware).
- **To remove it:** uninstall it as usual ([Uninstalling](Uninstalling)).

1.0.2 and 1.1.0-beta.1 can't uninstall after a firmware update (NEMESIS says
"Operation failed"): install HMG-API 1.1.0 or newer over it first, then uninstall
it if you want to.

## The top bar says "Hooks off for this car"

HMG-API still works; only the parts of mods that change the car's own apps wait.
- **No hook map for this car or software:** make a [Car Report](Car-Report), or try
  Settings → Hook map → "Map this car from its files".
- **The map isn't checked on this car yet:** Settings → Hook map → "Check the map on
  this car", then restart.

## A mod is red

It doesn't run on your car; the Mods tab says why. Usually a hook point your car's
map doesn't have yet: make a [Car Report](Car-Report).

## A mod's car command does nothing

The first time a mod uses a car command, HMG-API asks you on screen. If you said
"Don't allow", change it in Settings → Vehicle → "Forget my answers".

## The head unit restarted over and over

When Android fails to start three times during one boot with HMG-API's hooks (a
hook crashing the system), or the head unit restarts six times in a row less than
two minutes after starting, HMG-API's boot guard turns **safe mode** on by itself: no
mod runs, and HMG-API's own hooks (its tile, its ★ choice) get one more try. The top bar
says "Safe mode: mods off". If Android still fails to start in safe mode, the boot guard
turns HMG-API's hooks off too, and the app says the hooks aren't running (open it from
NEMESIS Manager → My Packages → HMG-API → Launch).

Turn off or remove the mod you added or changed last, then turn safe mode off (Settings
→ Safe mode) and restart; that resets the boot guard's counters too. Over adb:

```
adb shell "hmgapid engine safe off"
```

Releases before 1.1.0 have no safe mode: reset the counter by reinstalling HMG-API, or
`adb shell "hmgapid sh 'echo 0 > /data/hmgapi/hook/starts; rm -f /data/hmgapi/hook/boot'"`.

## A mod isn't doing anything

- Is it switched on, and did you **Apply changes**?
- Look at the plan for a `problem` line about it.
- Its log: the Mods tab, or the Logs tab.

## Android Auto won't connect wirelessly with a USB stick in

A stick in the port can make the head unit start wired Android Auto and refuse
wireless until it times out. Take the stick out; if it stays stuck, restart the head
unit.

## NEMESIS Manager shows hmgapi/boot as "stopped"

That's normal. `hmgapi/boot` (Services) starts HMG-API when NEMESIS starts, then
exits; HMG-API keeps running in its own daemon. Start and Stop there change
nothing: HMG-API starts once per restart, from whichever comes first, this or its
own boot hook.

## NEMESIS says "daemon not running" for a minute or two after a restart

That was HMG-API 1.0.0 and 1.0.1 holding up NEMESIS's start. Update HMG-API to
1.0.2 or newer.

## Sending a report

Make a [Car Report](Car-Report) and open an issue ([Getting help](Getting-help)).
