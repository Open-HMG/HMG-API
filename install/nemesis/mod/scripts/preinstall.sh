#!/system/bin/sh
# NEMESIS preinstall for a HMG-API mod package (full root, cwd the staged package).
# A failure here stops the install before NEMESIS copies or registers anything.
# depends="hmgapi" already needs HMG-API's NEMESIS package; this also catches an
# HMG-API install that didn't finish, or one done over adb or the DSP OTA.
if [ ! -x "${ROOT:-}/system/bin/hmgapid" ] || [ ! -f "${ROOT:-}/data/hmgapi/uninstall.sh" ]; then
    echo "HMG-API isn't installed on this unit: install HMG-API first, then this mod"
    exit 1
fi
[ -f mod/package.zip ] && [ -f mod/id ] || { echo "not a HMG-API mod package (no mod/package.zip)"; exit 1; }
exit 0
