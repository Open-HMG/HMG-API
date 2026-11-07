#!/system/bin/sh
# NEMESIS postinstall (full root, cwd /data/nemesis/apps/hmgapi): HMG-API's own
# installer on the payload in this package. Install and update alike.
# A package HMG-API's NEMESIS sync made (a "registered" marker, no payload) only lists an
# HMG-API that's already installed: nothing to do.
umask 077
if [ "$HMGAPI_NEMESIS_SYNC" = 1 ]; then echo "listed by HMG-API: nothing to install"; exit 0; fi
if [ ! -d ./payload ]; then
    if [ -f "${ROOT:-}/data/hmgapi/installed.txt" ]; then echo "HMG-API is installed: listed"; exit 0; fi
    echo "this package only lists HMG-API, and HMG-API isn't installed: install HMG-API's own download"
    exit 1
fi
export HMGAPI_FROM_NEMESIS=1   # install.sh: NEMESIS is listing it already, no NEMESIS sync
# HMGAPI/FORCE on a USB stick: install even if app_process isn't a known stock build (own risk)
for d in /storage/usb0 /storage/usb1 /storage/usb2; do [ -f "$d/HMGAPI/FORCE" ] && export HMGAPI_FORCE=1; done
exec sh ./payload/install.sh "$PWD/payload"
