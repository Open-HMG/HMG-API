HMG-API @VERSION@ - install with NEMESIS
=======================================

Needs NEMESIS 1.16 or newer (its Package Store).

1. Unzip this file onto the top level of a USB stick (FAT32 or exFAT), so the
   stick has NEMESIS.data and the packages folder at its root. Nothing else
   NEMESIS-related on the stick.
2. Plug the stick into the car's USB port once Android is up. NEMESIS Manager
   opens its Package Store on this stick's list: install "HMG-API", and any of
   the mods you want (a mod on its own installs HMG-API first).
3. Restart the head unit when it says so, and take the stick out.

NEMESIS won't install HMG-API while HMG Leafia Plus (wireless.carplay.aa),
carplay.session or voicehandler.vr-gates is installed: uninstall those first. The wireless CarPlay + Android Auto mod can't go
with wireless.projection or projection.tbt, the adb mod not with adb.tcp.

Updating the same way keeps HMG-API's mods. Uninstall from NEMESIS Manager, or:
    nemesis uninstall hmgapi
From a shell with NEMESIS's root (the stick in the first USB port):
    nemesis install /storage/usb0/packages/hmgapi.zip
