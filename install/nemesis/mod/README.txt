@NAME@ @VERSION@ (a HMG-API mod) - install with NEMESIS
=======================================================

Needs HMG-API on the head unit first: NEMESIS Manager says so if it isn't, and
offers to install it when it can find it (on the stick, then online).

1. Unzip this file onto the top level of a USB stick (FAT32 or exFAT), so the
   stick has NEMESIS.data and the packages folder at its root. Only one NEMESIS
   package on the stick at a time.
2. Plug the stick into the car's USB port once Android is up, and confirm the
   install of "@NAME@".
3. Restart the head unit if it says so, and take the stick out.

HMG-API installs the mod and applies it, as its app does. Updating the same way
keeps the mod's settings. A mod that doesn't list your car isn't installed; to
install it anyway, at your own risk, add an empty file HMGAPI/ANY-CAR to the stick.

Uninstall from NEMESIS Manager (everything the mod changed goes back to stock), or:
    nemesis uninstall @PACKAGE_ID@
Removing the mod in the HMG-API app works too; then uninstall this package as well.
