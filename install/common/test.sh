#!/bin/sh
# Off-car test of install/common/lib.sh (the boot hook) against a fake root.
# UNIT_SH: the shell to run it with (an ARM busybox ash is closest to the unit's mksh).
set -e
cd "$(dirname "$0")"
SH=${UNIT_SH:-sh}
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
mkdir -p "$T/system/etc" "$T/bin" "$T/bak"
printf '#!/bin/sh\n[ -f "$1" ] || exit 1\nset -- $(md5sum "$1"); echo "$1 $2"\n' > "$T/bin/md5"; chmod +x "$T/bin/md5"   # the unit's md5
L() { env PATH="$T/bin:$PATH" ROOT="$T" $SH -c ". $PWD/lib.sh; $*"; }
LM() { L "$@"; }
printf '#!/system/bin/sh\n# NEMESIS\nnemesis watchdog\nexit 0\n' > "$T/system/etc/install-recovery.sh"; chmod 755 "$T/system/etc/install-recovery.sh"
printf 'echo qb stock\n# NEMESIS_QB_HOOK\nnemesis watchdog\n' > "$T/system/etc/qb_runtime.sh"
IR=$(md5sum < "$T/system/etc/install-recovery.sh"); QB=$(md5sum < "$T/system/etc/qb_runtime.sh")

L hook_add /system/etc/install-recovery.sh top
check "block after the first line" "# HMGAPI_HOOK begin (HMG-API: starts hmgapid. HMG-API's uninstall removes this block.)" "$(sed -n 2p "$T/system/etc/install-recovery.sh")"
check "still ends with exit 0" "exit 0" "$(tail -1 "$T/system/etc/install-recovery.sh")"
check "NEMESIS lines kept" "1" "$(grep -c 'nemesis watchdog' "$T/system/etc/install-recovery.sh")"
check "stays executable" "yes" "$([ -x "$T/system/etc/install-recovery.sh" ] && echo yes)"
L hook_add /system/etc/install-recovery.sh top
check "adding twice keeps one block" "1" "$(grep -c 'HMGAPI_HOOK begin' "$T/system/etc/install-recovery.sh")"
L hook_add /system/etc/qb_runtime.sh end
check "qb block at the end" "# HMGAPI_HOOK end" "$(tail -1 "$T/system/etc/qb_runtime.sh")"
check "qb not made executable" "no" "$([ -x "$T/system/etc/qb_runtime.sh" ] && echo yes || echo no)"
sh -n "$T/system/etc/install-recovery.sh" && echo "ok    hooked script parses" || { echo "FAIL  hooked script syntax"; fail=1; }

L hook_remove /system/etc/install-recovery.sh
L hook_remove /system/etc/qb_runtime.sh
check "install-recovery.sh back byte for byte" "$IR" "$(md5sum < "$T/system/etc/install-recovery.sh")"
check "qb_runtime.sh back byte for byte" "$QB" "$(md5sum < "$T/system/etc/qb_runtime.sh")"

rm "$T/system/etc/install-recovery.sh"
L hook_add /system/etc/install-recovery.sh top
check "created when missing" "yes" "$([ -x "$T/system/etc/install-recovery.sh" ] && grep -q HMGAPI_CREATED "$T/system/etc/install-recovery.sh" && echo yes)"
L hook_remove /system/etc/install-recovery.sh
check "created file removed again" "no" "$([ -e "$T/system/etc/install-recovery.sh" ] && echo yes || echo no)"

# every line written back as it is: echo expanded backslashes in both mksh (the unit) and dash,
# and "\c" ended the output (the line cut short, the next joined to it: an unclosed quote)
# (in $SH, and in mksh when it's installed: its print -r is what the unit uses)
bs_checks() {
    B=$T/system/etc/bs.sh
    printf '#!/system/bin/sh\nV="x\\cY"; echo ok\nP="a\\\\b"\nE="\\x1b[1;31m\\n\\t\\0101"\n  indented\\\n-n\n-e\nexit 0\n' > "$B"; chmod 755 "$B"
    BS=$(md5sum < "$B")
    L hook_add /system/etc/bs.sh top
    check "[$SH] backslashes: block in once" "1" "$(grep -c 'HMGAPI_HOOK begin' "$B")"
    check "[$SH] ... the file minus the block is the file as it was" "$BS" "$(grep -v HMGAPI_HOOK "$B" | grep -v 'hmgapid boot' | md5sum)"
    check "[$SH] ... the line with backslash-c whole" 'V="x\cY"; echo ok' "$(grep -F 'V="x' "$B")"
    sh -n "$B" && echo "ok    ... still parses" || { echo "FAIL  ... backslash script syntax"; fail=1; }
    L hook_add /system/etc/bs.sh top
    check "[$SH] ... adding again: one block, nothing else changed" "1|$BS" "$(grep -c 'HMGAPI_HOOK begin' "$B")|$(grep -v HMGAPI_HOOK "$B" | grep -v 'hmgapid boot' | md5sum)"
    L hook_remove /system/etc/bs.sh
    check "[$SH] ... hook_remove: back byte for byte" "$BS" "$(md5sum < "$B")"
    L hook_add /system/etc/bs.sh end
    check "[$SH] ... at the end too" "$BS" "$(grep -v HMGAPI_HOOK "$B" | grep -v 'hmgapid boot' | md5sum)"
    L hook_remove /system/etc/bs.sh
    check "[$SH] ... and back" "$BS" "$(md5sum < "$B")"
    printf 'A="\\\\"\nB="\\c"' > "$B"   # no newline at the end: the block goes after one
    L hook_add /system/etc/bs.sh end
    check "[$SH] no last newline: one added, nothing else changed" "$(printf 'A="\\\\"\nB="\\c"\n' | md5sum)" "$(grep -v HMGAPI_HOOK "$B" | grep -v 'hmgapid boot' | md5sum)"
    rm -f "$B"
    # nothing to write lines with that keeps them as they are: the file is left as it is
    printf 'echo qb stock\n' > "$B"; BS=$(md5sum < "$B")
    env PATH="$T/bin:$PATH" ROOT="$T" $SH -c ". $PWD/lib.sh; hook_line() { return 1; }; hook_add /system/etc/bs.sh end" && r=0 || r=$?
    check "[$SH] no faithful copy: hook_add fails, file untouched, no work files" "1|$BS|bs.sh" "$r|$(md5sum < "$B")|$(ls "$T/system/etc" | grep bs)"
    # a copy that changes a line (a shell's echo): caught before the file is replaced
    env PATH="$T/bin:$PATH" ROOT="$T" $SH -c ". $PWD/lib.sh; hook_line() { echo \"\$1x\"; }; hook_add /system/etc/bs.sh end" && r=0 || r=$?
    check "[$SH] a copy that changes lines: hook_add fails, file untouched" "1|$BS|bs.sh" "$r|$(md5sum < "$B")|$(ls "$T/system/etc" | grep bs)"
    rm -f "$B"
}
bs_checks
if [ "${SH##*/}" != mksh ] && command -v mksh >/dev/null 2>&1; then
    SH0=$SH; SH=mksh; bs_checks; SH=$SH0
else
    echo "skip  backslashes in mksh (no mksh here)"
fi

# hook_restore (the uninstall): the install's backup byte for byte, for a stock file the
# shell can't write back exactly (backslashes, as the EV6's qb_runtime.sh has; no newline
# at the end), as long as nothing else changed the file since
Q=$T/system/etc/qb_runtime.sh
printf 'RED="\\x1b[1;31m"\necho "a\\nb\\\\c"\nnemesis watchdog' > "$Q"
cp "$Q" "$T/bak/qb"; QS=$(md5sum < "$Q")
L hook_add /system/etc/qb_runtime.sh end
check "hook_restore: the block is in" "1" "$(grep -c 'HMGAPI_HOOK begin' "$Q")"
LM hook_restore /system/etc/qb_runtime.sh "$T/bak/qb" && r=0 || r=$?
check "hook_restore: backslashes and no last newline: the backup's bytes back" "0|$QS" "$r|$(md5sum < "$Q")"
check "... no work files left" "qb" "$(ls "$T/bak" | tr '\n' ' ' | sed 's/ $//')"
check "... mode kept (not executable)" "no" "$([ -x "$Q" ] && echo yes || echo no)"
L hook_add /system/etc/qb_runtime.sh end
echo "something else's line" >> "$Q"; QC=$(md5sum < "$Q")
LM hook_restore /system/etc/qb_runtime.sh "$T/bak/qb" && r=0 || r=$?
check "hook_restore: changed by something else since: left alone (hook_remove's)" "1|$QC" "$r|$(md5sum < "$Q")"
L hook_remove /system/etc/qb_runtime.sh
check "... hook_remove takes the block out, the other line kept" "0|1" "$(grep -c HMGAPI_HOOK "$Q" || true)|$(grep -c "something else's line" "$Q")"
LM hook_restore /system/etc/qb_runtime.sh "$T/bak/qb" && r=0 || r=$?
check "hook_restore: no block in the file: nothing to do" "1" "$r"
cp "$T/bak/qb" "$Q"; L hook_add /system/etc/qb_runtime.sh end
LM hook_restore /system/etc/qb_runtime.sh "$T/bak/none" && r=0 || r=$?
check "hook_restore: no backup: not restored" "1" "$r"
cp "$Q" "$T/bak/hooked"
LM hook_restore /system/etc/qb_runtime.sh "$T/bak/hooked" && r=0 || r=$?
check "hook_restore: a backup that has the block itself is never put back" "1" "$r"
: > "$T/bak/absent"; : > "$T/bak/absent.absent"
LM hook_restore /system/etc/qb_runtime.sh "$T/bak/absent" && r=0 || r=$?
check "hook_restore: the file wasn't there at install: not restored (hook_remove deletes it)" "1" "$r"
printf 'echo qb stock\n# NEMESIS_QB_HOOK\nnemesis watchdog\n' > "$Q"

# hook-check.sh at boot: a NEMESIS install or update rewrites install-recovery.sh as a whole
D=$T/data/hmgapi
mkdir -p "$D"; cp lib.sh "$D/"; echo "version 1.0.0" > "$D/installed.txt"
HC() { env PATH="$T/bin:$PATH" ROOT="$T" $SH "$PWD/hook-check.sh" 2>&1; }
printf '#!/system/bin/sh\n# NEMESIS\nnemesis watchdog\nexit 0\n' > "$T/system/etc/install-recovery.sh"; chmod 755 "$T/system/etc/install-recovery.sh"
L hook_add /system/etc/install-recovery.sh top
L hook_add /system/etc/qb_runtime.sh end
IRH=$(md5sum < "$T/system/etc/install-recovery.sh"); QBH=$(md5sum < "$T/system/etc/qb_runtime.sh")
o=$(HC)
check "hook-check: both hooks there: nothing changed, nothing said" "|$IRH|$QBH" "$o|$(md5sum < "$T/system/etc/install-recovery.sh")|$(md5sum < "$T/system/etc/qb_runtime.sh")"
printf '#!/system/bin/sh\n# NEMESIS\nnemesis watchdog\nexit 0\n' > "$T/system/etc/install-recovery.sh"   # what a NEMESIS update writes
o=$(HC)
check "hook-check: install-recovery.sh rewritten: the block put back after the first line" "# HMGAPI_HOOK begin (HMG-API: starts hmgapid. HMG-API's uninstall removes this block.)" "$(sed -n 2p "$T/system/etc/install-recovery.sh")"
check "... as HMG-API's install puts it (same bytes)" "$IRH" "$(md5sum < "$T/system/etc/install-recovery.sh")"
check "... qb_runtime.sh left alone" "$QBH" "$(md5sum < "$T/system/etc/qb_runtime.sh")"
check "... said in install.log" "1" "$(grep -c 'boot hook put back in /system/etc/install-recovery.sh' "$D/install.log")"
check "... no lock left" "no" "$([ -e "$D/state/hook-check.pid" ] && echo yes || echo no)"
L hook_remove /system/etc/qb_runtime.sh
HC > /dev/null
check "hook-check: qb_runtime.sh lost it: put back at the end" "$QBH" "$(md5sum < "$T/system/etc/qb_runtime.sh")"
printf '#!/system/bin/sh\n# NEMESIS\nnemesis watchdog\nexit 0\n' > "$T/system/etc/install-recovery.sh"
rm "$D/installed.txt"
HC > /dev/null
check "hook-check: HMG-API not installed: nothing put back" "0" "$(grep -c HMGAPI_HOOK "$T/system/etc/install-recovery.sh")"
echo "version 1.0.0" > "$D/installed.txt"
if [ -f /proc/self/cmdline ]; then   # hook-check tells one is running from /proc/<pid>/cmdline, as on the unit
    cp "$(command -v sleep)" "$T/hook-check"; "$T/hook-check" 30 & sp=$!   # stands in for one already running
    echo $sp > "$D/state/hook-check.pid"
    HC > /dev/null
    check "hook-check: another one running: this one does nothing" "0" "$(grep -c HMGAPI_HOOK "$T/system/etc/install-recovery.sh")"
    kill $sp 2>/dev/null; wait $sp 2>/dev/null || true
else
    echo "skip  hook-check: another one running (no /proc here)"
fi
echo 999999 > "$D/state/hook-check.pid"
HC > /dev/null
check "hook-check: a stale lock (after a restart) doesn't stop it" "1" "$(grep -c 'HMGAPI_HOOK begin' "$T/system/etc/install-recovery.sh")"

# gear_gate: an install or uninstall only starts in P (a running hmgapid's reading); with no
# reading, a warning and on
printf '#!/bin/sh\n[ "$*" = "vehicle gear.park" ] || exit 2\ncat "%s/gear.out" 2>/dev/null || { echo "the vehicle service isn'"'"'t running (yet)"; exit 69; }\n' "$T" > "$T/gear-hmgapid"
chmod +x "$T/gear-hmgapid"
G() { set +e; out=$(env ROOT="$T" LOG="$T/gear.log" HMGAPI_HMGAPID="$T/gear-hmgapid" $SH -c ". $PWD/lib.sh; gear_gate 'the install'; echo went on" 2>&1); rc=$?; set -e; }
echo "gear.park 1" > "$T/gear.out"; G
check "gear_gate: in P: on, the owner told to stay in P" "0|in P: keep the car in P until the install is done
went on" "$rc|$out"
echo "gear.park 0" > "$T/gear.out"; G
check "gear_gate: not in P: stops with 75, before anything" "75|0" "$rc|$(echo "$out" | grep -c 'went on')"
check "... says why" "1" "$(echo "$out" | grep -c "FAIL: the car isn't in P, and the install only starts in P")"
echo "gear.park -" > "$T/gear.out"; G
check "gear_gate: the car didn't answer: a warning, and on" "0|1|1" "$rc|$(echo "$out" | grep -c '^warning: the gear can')|$(echo "$out" | grep -c 'went on')"
rm -f "$T/gear.out"; G
check "gear_gate: no vehicle service: a warning, and on" "0|1" "$rc|$(echo "$out" | grep -c 'went on')"
set +e; out=$(env ROOT="$T" LOG="$T/gear.log" HMGAPI_HMGAPID="$T/missing" $SH -c ". $PWD/lib.sh; gear_gate 'the install'; echo went on" 2>&1); rc=$?; set -e
check "gear_gate: no hmgapid (a first install): a warning, and on" "0|1" "$rc|$(echo "$out" | grep -c 'went on')"
check "... in install.log too" "yes" "$(grep -q 'keep it in P until the install is done' "$T/gear.log" && echo yes || echo no)"
set +e; out=$(env ROOT="$T" LOG="$T/gear.log" $SH -c ". $PWD/lib.sh; gear_gate 'the install'; echo went on" 2>&1); rc=$?; set -e
check "gear_gate: off-car with no stand-in: no check" "0|went on" "$rc|$out"

# boot.sh: an apply that was cut off (or a trial) is rolled back, then one restart, in the
# background once the car is parked (never holding the boot). A recover that fails part-way
# keeps its journal, so restarting on it would loop.
mkdir -p "$T/system/bin" "$D/public"
cat > "$T/system/bin/hmgapid" <<EOF
#!/bin/sh
echo "\$*" >> "$T/hmgapid.calls"
case "\$1" in
    vehicle) cat "$T/vehicle.out" 2>/dev/null || echo "the vehicle service isn't running (yet)"; exit 0 ;;
    reboot) exit \$(cat "$T/reboot.rc" 2>/dev/null || echo 0) ;;
esac
[ "\$1 \$2" = "engine recover" ] || exit 0
cat "$T/recover.out"
exit \$(cat "$T/recover.rc")
EOF
chmod +x "$T/system/bin/hmgapid"
# the car: gear.park, power, parked (what hmgapid's vehicle reading answers)
car() { printf 'gear.park %s\npower %s\nparked %s\n' "$1" "$2" "$3" > "$T/vehicle.out"; }
BOOT() { rm -f "$T/hmgapid.calls"; : > "$D/state/journal.json"; printf '%s\n' "$1" > "$T/recover.out"; echo "$2" > "$T/recover.rc"
         env ROOT="$T" HMGAPI_PARK_POLL=1 HMGAPI_PARK_TRIES=${TRIES:-60} HMGAPI_PARK_HOLD=${HOLD:-120} $SH "$PWD/boot.sh" > "$T/boot.out" 2>&1; }
calls() { [ ! -f "$T/hmgapid.calls" ] || tr '\n' '|' < "$T/hmgapid.calls"; }
# the calls but the readings
once() { [ ! -f "$T/hmgapid.calls" ] || grep -v '^vehicle' "$T/hmgapid.calls" | tr '\n' '|'; }
# until the background wait has restarted or given up (15 s at most)
settled() {
    i=0
    while [ $i -lt 30 ]; do
        grep -q 'restarting$' "$T/boot.out" 2>/dev/null && grep -q '^reboot' "$T/hmgapid.calls" 2>/dev/null && return
        grep -q 'not restarting' "$T/boot.out" 2>/dev/null && return
        sleep 0.5; i=$((i + 1))
    done
}

car 1 logic_on 0
BOOT "rolled back /system/app/A.apk
unfinished apply rolled back" 0
settled
check "boot: an unfinished apply rolled back: one restart, with the car off in P" "engine recover|reboot|" "$(once)"
check "... after reading the car" "vehicle gear.park power parked" "$(grep -m1 '^vehicle' "$T/hmgapid.calls")"
check "... the app told a restart is coming" "yes" "$([ -f "$D/public/restart-needed" ] && echo yes || echo no)"
BOOT "trial undone" 0
settled
check "boot: a waiting trial undone: one restart" "engine recover|reboot|" "$(once)"

# driving (not in P): boot.sh ends at once, and the restart waits
car 0 on 0
t0=$(date +%s)
BOOT "unfinished apply rolled back" 0
check "boot: driving: boot.sh doesn't wait for the car" "yes" "$([ $(( $(date +%s) - t0 )) -lt 3 ] && echo yes || echo no)"
sleep 2
check "... no restart while not in P" "engine recover|" "$(once)"
# just switched on, in P: the driver may reverse next, so still no restart
car 1 on 0
sleep 2
check "... none in P with the car on either" "engine recover|" "$(once)"
car 1 on 1
settled
check "... the restart once HMG-API's parked state is on" "engine recover|reboot|" "$(once)"

# hmgapid refuses (shifted out of P in between): waits again, then restarts
car 1 logic_on 0
echo 75 > "$T/reboot.rc"
BOOT "unfinished apply rolled back" 0
i=0; while [ $i -lt 20 ] && ! grep -q "didn't restart" "$T/boot.out"; do sleep 0.5; i=$((i + 1)); done
echo 0 > "$T/reboot.rc"
i=0; while [ $i -lt 20 ] && [ "$(grep -c '^reboot' "$T/hmgapid.calls")" -lt 2 ]; do sleep 0.5; i=$((i + 1)); done
check "boot: hmgapid's reboot refused: tried again" "2" "$(grep -c '^reboot' "$T/hmgapid.calls")"
sleep 1

# no power status: in P for HMGAPI_PARK_HOLD seconds in a row
car 1 - 0
HOLD=2 BOOT "unfinished apply rolled back" 0
settled
check "boot: no power status: a restart once in P for a while" "engine recover|reboot|" "$(once)"

# no gear reading at all: no restart, and the app asks the owner
rm -f "$T/vehicle.out"
TRIES=2 BOOT "unfinished apply rolled back" 0
settled
check "boot: no gear reading: no restart" "engine recover|" "$(once)"
check "... the notice left for the app" "1" "$(grep -c 'left to the owner' "$D/public/restart-needed" 2>/dev/null)"
rm -f "$D/state/journal.json"
env ROOT="$T" $SH "$PWD/boot.sh" > /dev/null 2>&1
check "... gone at the next start" "no" "$([ -f "$D/public/restart-needed" ] && echo yes || echo no)"

BOOT "nothing to recover" 0
check "boot: nothing rolled back: no restart" "engine recover|" "$(once)"
BOOT "rolled back /system/app/A.apk
engine error: java.io.IOException: No space left on device" 1
check "boot: recover failed part-way (journal kept): no restart, or it would restart at every start" "engine recover|" "$(once)"
check "... and boot.log says so" "1" "$(grep -c "recover didn't finish" "$T/boot.out")"
rm -f "$D/state/journal.json"; rm -f "$T/hmgapid.calls"
env ROOT="$T" $SH "$PWD/boot.sh" > /dev/null 2>&1; wait
check "boot: no journal: recover isn't run" "" "$(calls)"
echo dev.example.gpm > "$D/state/held.txt"; printf 'enabled  dev.example.gpm (stopped by an apply that was cut off)\nnothing to recover\n' > "$T/recover.out"; echo 0 > "$T/recover.rc"
env ROOT="$T" $SH "$PWD/boot.sh" > /dev/null 2>&1
check "boot: apps a cut-off apply stopped, no journal: recover turns them on, no restart" "engine recover|" "$(calls)"
rm -f "$D/state/held.txt"
exit $fail
