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
# rolled back (apps may already have loaded the files): in the background, once the car
# is parked (restart_when_parked), never right after power-on.
# ROOT prefixes every path, for off-car tests only (HMGAPI_PARK_POLL, _TRIES and _HOLD too).
umask 077
ROOT=${ROOT:-}
if [ -z "$ROOT" ]; then
    # what hmgapid gives its commands (the caller's environment is init's or NEMESIS's)
    export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
    export LD_LIBRARY_PATH=/vendor/lib:/system/lib
    unset LD_PRELOAD
fi
D=$ROOT/data/hmgapi
H=$ROOT/system/bin/hmgapid
echo "$(date) boot"
rm -f $D/public/restart-needed   # a restart the last boot was waiting for: this start is it

# The restart after a rollback, once the car is parked. A restart takes the reverse camera's
# picture away for about a minute (Android shows it on these units), and right after power-on,
# when this runs, the driver may be about to reverse: in P isn't enough then. So it waits, in
# the background, for HMG-API's parked state (the owner's rule: off, in P, the driver got out),
# or in P with the car off; on a car whose power status can't be read, in P for two minutes in
# a row. hmgapid's reboot checks P again. Meanwhile public/restart-needed tells the app (its
# Restart needed pill). With no gear reading for 10 minutes (the vehicle service never came up,
# or the car's map has no gear point), no restart: the notice stays, and the app's Restart asks
# the owner (the warning plus override). A QuickBoot suspend doesn't end the wait.
restart_when_parked() {
    local poll=${HMGAPI_PARK_POLL:-10} tries=${HMGAPI_PARK_TRIES:-60} hold=${HMGAPI_PARK_HOLD:-120} unread=0 inp=0 g p k
    echo "rolled back at $(date): restart once the car is parked" > $D/public/restart-needed
    chmod 644 $D/public/restart-needed
    while :; do
        g=-; p=-; k=-
        set -- $($H vehicle gear.park power parked 2>/dev/null)
        while [ $# -ge 2 ]; do
            case $1 in gear.park) g=$2 ;; power) p=$2 ;; parked) k=$2 ;; esac
            shift 2
        done
        case $g in
            1) unread=0; inp=$((inp + 1)) ;;
            0) unread=0; inp=0 ;;
            *) unread=$((unread + 1)); inp=0
               if [ $unread -ge $tries ]; then
                   echo "$(date) no gear reading: not restarting (the app asks the owner)"
                   echo "rolled back at boot; the gear can't be read, so the restart was left to the owner" > $D/public/restart-needed
                   return 0
               fi ;;
        esac
        if [ "$k" = 1 ] || { [ "$g" = 1 ] && [ "$p" != on ] && [ "$p" != - ]; } \
            || { [ "$g" = 1 ] && [ "$p" = - ] && [ $((inp * poll)) -ge $hold ]; }; then
            echo "$(date) parked (gear.park $g, power $p, parked $k): restarting"
            $H reboot && return 0
            echo "$(date) hmgapid didn't restart: waiting again"
        fi
        sleep $poll
    done
}
# Already detached (own session, output to boot.log), so the background jobs start from
# here: through `hmgapid sh` they'd hold its output pipe (mksh's saved fds), and this with it.
if [ -s $D/state/binds.txt ]; then $H binds > $D/binds.log 2>&1 & fi
if [ -f $D/public/HmgApi.apk ]; then sh $D/app-pending.sh </dev/null >/dev/null 2>&1 & fi
if [ -f $D/hook-check.sh ]; then sh $D/hook-check.sh </dev/null >/dev/null 2>&1 & fi
if [ -x $ROOT/system/xbin/nemesis ]; then (sleep 120; $H engine nemesis-sync) </dev/null >$D/nemesis-sync.log 2>&1 & fi
# the points HMG-API's map lacks only because it's older than them, mapped and checked on this
# car (state/local-map.json): only when the map, the catalogue or the stock files changed since,
# so after an HMG-API install or update. Used from the next start. The owner's switch skips it.
if [ -d $D/maps ] && [ ! -f $D/settings/allow/no_local_points ]; then (sleep 90; $H engine map-car --local --if-needed) </dev/null >$D/map-local.log 2>&1 & fi
# the journal: an apply cut off, or a trial (the apps it stopped are started below, once Android is up)
if [ -f $D/state/journal.json ]; then
    # Restart only after a recover that finished (it takes the journal away). One that
    # failed part-way has "rolled back" in its log too and keeps the journal: restarting
    # on that would come straight back here, at every start. It's tried again at the
    # next start instead, and `hmgapid engine recover` says what's wrong.
    if $H engine recover > $D/recover.log 2>&1; then
        if grep -q "rolled back" $D/recover.log || grep -q "trial undone" $D/recover.log; then
            echo "$(date) recover rolled something back: restarting once the car is parked"
            restart_when_parked </dev/null &   # its lines go to boot.log too (this script's output, a file)
        fi
    else
        echo "$(date) recover didn't finish (see $D/recover.log): not restarting, tried again at the next start"
    fi
fi
# Apps an apply stopped (pm disable) while their files changed and never started again: a power
# cut or a killed engine in the middle. Android keeps them off across restarts (GPM, DiPO, iAP2,
# navigation...). pm needs Android up, so once it is, and only with the files put back (the
# engine keeps them off while an unfinished apply's journal is there).
if [ -s $D/state/held.txt ]; then
    (n=0; while [ "$(getprop sys.boot_completed)" != 1 ] && [ $n -lt 300 ]; do sleep 2; n=$((n + 1)); done
     $H engine release-held) </dev/null >$D/held.log 2>&1 &
fi
echo "$(date) boot done"
