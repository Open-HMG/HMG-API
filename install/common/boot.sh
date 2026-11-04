#!/system/bin/sh
# HMG-API at boot, once hmgapid is up. Run by `hmgapid boot` (the boot hook's one line
# in install-recovery.sh and qb_runtime.sh, and HMG-API's NEMESIS service), detached
# from the boot script, and once per boot: the call that started hmgapid runs this,
# the others stop there. So nothing here holds a boot script or NEMESIS's start.
# Output: /data/hmgapi/boot.log.
#
# The app's installer if the app is still waiting; hook-check.sh, which puts the boot
# hook back in a boot script something else rewrote; with NEMESIS, two minutes later,
# engine nemesis-sync, so NEMESIS lists what HMG-API has installed. If an engine apply
# was cut off (or a trial is waiting), roll it back, and restart once if anything was
# rolled back (apps may already have loaded the files).
umask 077
D=/data/hmgapi
H=/system/bin/hmgapid
echo "$(date) boot"
if [ -s $D/state/binds.txt ]; then $H binds > $D/binds.log 2>&1 & fi
if [ -f $D/public/HmgApi.apk ]; then $H sh "sh $D/app-pending.sh </dev/null >/dev/null 2>&1 &" >/dev/null 2>&1; fi
if [ -f $D/hook-check.sh ]; then $H sh "sh $D/hook-check.sh </dev/null >/dev/null 2>&1 &" >/dev/null 2>&1; fi
if [ -x /system/xbin/nemesis ]; then $H sh "(sleep 120; $H engine nemesis-sync) </dev/null >$D/nemesis-sync.log 2>&1 &" >/dev/null 2>&1; fi
if [ -f $D/state/journal.json ]; then
    $H engine recover > $D/recover.log 2>&1
    if grep -q "rolled back" $D/recover.log || grep -q "trial undone" $D/recover.log; then
        echo "$(date) recover rolled something back: restarting"
        $H reboot
    fi
fi
echo "$(date) boot done"
