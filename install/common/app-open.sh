#!/system/bin/sh
# HMG-API: open the HMG-API app, installing it first if it isn't there (an install
# that failed, or the app removed). Installed as /data/hmgapi/open.sh; run as root by
# NEMESIS Manager's Launch (the package's [app.0], bin/open.sh) or over adb:
#   adb shell "hmgapid sh 'sh /data/hmgapi/open.sh'"
# Needs no hooks, so it opens the app on a car without a hook map too (there the
# launcher tile and the star key choice, which the hooks add, aren't there).
# The app's copy: /data/hmgapi/HmgApi.apk (kept by install.sh). pm takes HMG-API's
# signature only while sys.boot_completed is 0 (OEM allowlist): set for the pm call only.
D=${ROOT:-}/data/hmgapi
LOG=$D/install.log
# Output only, no toasts of its own: NEMESIS Manager shows a launch script's output as a
# toast (pkg.c cmd_pkg_launch), and over adb it's printed.
say() { echo "HMG-API: $1"; }
[ "$(getprop sys.boot_completed)" = 1 ] || { say "Android isn't up yet: try again in a minute"; exit 1; }
case "$(pm path hmgapi.app 2>/dev/null)" in
    package:*) ;;
    *)
        A=$D/HmgApi.apk
        [ -f "$A" ] || { say "the app isn't on this unit and there's no copy to install: install HMG-API again"; exit 1; }
        setprop sys.boot_completed 0
        r=$(pm install -r "$A" 2>&1)
        setprop sys.boot_completed 1
        case "$r" in
            *Success*)
                rm -f "$D/public/HmgApi.apk"
                echo "installed the HMG-API app (open.sh)" >> "$LOG"
                say "installed the app" ;;
            *)
                echo "WARNING: the HMG-API app didn't install (open.sh): $r" >> "$LOG"
                say "the app didn't install: $r"
                exit 1 ;;
        esac ;;
esac
am start -n hmgapi.app/.MainActivity >/dev/null 2>&1 || { say "couldn't open the app"; exit 1; }
