#!/system/bin/sh
# NEMESIS postinstall for a HMG-API mod package (full root, cwd
# /data/nemesis/apps/hmgapi.<mod id>): installs the mod it carries (mod/package.zip,
# signed by its author) with HMG-API's own engine, then applies it, as the HMG-API
# app's Check USB updates and Apply changes do. Install and update alike: the engine
# keeps the owner's settings, keeps a mod turned off off, and takes an update only
# from the same author key as the installed mod.
# HMGAPI/ANY-CAR on a USB stick: install a mod that doesn't list this car (own risk).
umask 077
H=${ROOT:-}/system/bin/hmgapid
ID=$(cat mod/id)
say() { echo "HMG-API mod $ID: $1"; }
toast() {
    [ "$(getprop sys.boot_completed)" = 1 ] || return 0
    am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1
}
[ -x "$H" ] || { say "HMG-API isn't installed on this unit: install HMG-API first"; exit 1; }
"$H" ping >/dev/null 2>&1 || "$H" daemon >/dev/null 2>&1   # the engine runs through it
any=
for d in ${HMGAPI_USB_DIRS:-/storage/usb0 /storage/usb1 /storage/usb2}; do [ -f "$d/HMGAPI/ANY-CAR" ] && any=--any-car; done
new=yes
[ -d "${ROOT:-}/data/hmgapi/mods/$ID" ] && new=no
if ! "$H" engine install "$PWD/mod/package.zip" $any; then
    say "HMG-API didn't install it (see above): nothing changed. Uninstall this package in NEMESIS Manager."
    toast "HMG-API didn't install $ID: see NEMESIS's log"
    exit 1
fi
if ! r=$("$H" engine apply 2>&1); then
    echo "$r"
    if [ $new = yes ]; then
        "$H" engine remove "$ID" >/dev/null 2>&1
        say "HMG-API couldn't apply it (see above), so it's not installed: nothing changed. Uninstall this package in NEMESIS Manager."
    else
        say "the update is in HMG-API, but it couldn't apply it (see above): the HMG-API app shows the problem; Apply changes there once it's fixed."
    fi
    toast "HMG-API couldn't apply $ID: see the HMG-API app"
    exit 1
fi
echo "$r"
case $r in
    *"restart the head unit"*) say "installed: restart the head unit to use it"; toast "$ID installed: restart the head unit" ;;
    *) say "installed"; toast "$ID installed" ;;
esac
exit 0
