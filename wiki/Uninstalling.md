# Uninstalling

Uninstalling puts every file and setting HMG-API or its mods changed back to the
stock bytes, and removes the mods' apps.

- **USB stick:** add an empty file named `UNINSTALL` to the `HMGAPI` folder of the
  install stick and use the Engineering Mode route (`UNINSTALL-PURGE` also deletes
  HMG-API's data: mods, settings, backups).
- **NEMESIS:** uninstall "HMG-API" in NEMESIS Manager.
- **adb:** `./hmgapi-adb.sh uninstall` (`--purge` also deletes HMG-API's data).

Restart the head unit afterwards.

**No screen** (stuck on the boot animation, no home screen): the USB and NEMESIS routes
need the car's screen, adb doesn't. With adb on (turn it on while things work: the
[adb mod](Community-mods) or NEMESIS's Enable ADB), `./hmgapi-adb.sh uninstall` from the
adb zip works with Android down, or run the uninstaller it uses directly:

```
adb shell "hmgapid sh 'sh /data/hmgapi/uninstall.sh'"
```

then `adb shell "hmgapid reboot"`. The files go back to stock either way; the steps that
need Android (removing the HMG-API app and turning the mods' apps off, the ★ keys back to
None) are skipped while it's down. Before uninstalling, try safe mode, which turns every
mod off without removing anything: [Troubleshooting](Troubleshooting#stuck-on-the-boot-animation-no-home-screen-or-an-app-keeps-crashing).

**After a firmware update:** the update already put the system files back to stock,
and the uninstall leaves the new firmware's files as they are. Settings mods changed
stay as they are then; to put those back too, install HMG-API again first, then
uninstall it. See [Troubleshooting](Troubleshooting#after-a-firmware-update-hmg-apis-system-files-are-gone-or-uninstall-fails).
