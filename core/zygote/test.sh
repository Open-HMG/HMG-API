#!/bin/sh
# Off-car test of the zygote wrapper under qemu-arm (binfmt): argument passthrough,
# the preload switch and the boot guard (counting, its watcher, failing closed).
set -e
cd "$(dirname "$0")"
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
# The stock app_process stand-in. As the "zygote" (the wrapper execs it, so it keeps the
# wrapper's pid, which the watcher watches) it can fork the children the watcher looks for:
#   SS=s      a system_server after s seconds (none: unset)
#   LA=s      then a launcher after s more seconds, living LALIVE seconds (default 30)
#   LALOOP=n  then n launchers in turn, each living 0.3 s (a home screen that crash-loops)
#   STAY=s    the zygote lives s seconds, then exits (crashes)
cat > "$T/stock" <<S
#!/bin/sh
echo "ARGS:\$*"
echo "PRELOAD:\${LD_PRELOAD:-none}"
if [ -n "\$SS" ]; then
    (sleep "\$SS"; exec "$T/system_server" 30) </dev/null >/dev/null 2>&1 &
    if [ -n "\$LA" ]; then (sleep "\$SS"; sleep "\$LA"; exec "$T/launcher" "\${LALIVE:-30}") </dev/null >/dev/null 2>&1 & fi
    if [ -n "\$LALOOP" ]; then sleep "\$SS"; i=0; while [ \$i -lt "\$LALOOP" ]; do "$T/launcher" 0.3; i=\$((i + 1)); done; fi
fi
[ -n "\$STAY" ] && sleep "\$STAY"
exit 0
S
chmod +x "$T/stock"
# system_server and the launcher: a native program named by its path (its cmdline), a child
# of the stand-in; getprop answers what $T/boot_completed holds
printf '#include <stdlib.h>\n#include <time.h>\nint main(int c, char **v) { double s = c > 1 ? atof(v[1]) : 1; struct timespec t = { (time_t)s, (long)((s - (long)s) * 1e9) }; nanosleep(&t, 0); return 0; }\n' > "$T/nap.c"
cc -O2 -o "$T/system_server" "$T/nap.c"
cp "$T/system_server" "$T/launcher"
printf '#!/bin/sh\ncat %s/boot_completed 2>/dev/null || echo\n' "$T" > "$T/getprop"; chmod +x "$T/getprop"
echo 1 > "$T/boot_completed"
: > "$T/lib.so"
mkdir -p "$T/hook"
echo 5e1d0c34-aaaa-4bbb-8ccc-000000000001 > "$T/boot_id"   # one kernel boot, until a test changes it
. ../../tools/env.sh
# the watcher's times, in ms: poll 0.2 s, booted after the launcher's 0.6 s, hung with no
# system_server at 1 s, given up at 3 s
clang $ARM_STATIC -DPOLL_MS=200 -DUP_MS=600 -DHANG_MS=1000 -DGIVEUP_MS=3000 \
  -DSTOCK="\"$T/stock\"" -DSTOCKBAK="\"$T/stockbak\"" -DKMSG="\"$T/kmsg\"" -DHOOKDIR="\"$T/hook\"" -DHOOKLIB="\"$T/lib.so\"" -DHMGAPID="\"$T/hmgapid\"" -DBOOTID="\"$T/boot_id\"" \
  -DGETPROP="\"$T/getprop\"" -DSYSTEM_SERVER="\"$T/system_server\"" -DLAUNCHER="\"$T/launcher\"" \
  -o "$T/wrap" zygote.c
fail=0
check() {  # name, expected-substring, actual
    case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted '$2' in: $3"; fail=1 ;; esac
}
run() { : > "$T/kmsg"; LD_PRELOAD=old.so "$T/wrap" "$@" 2>/dev/null; }
fresh() { rm -f "$T/hook/starts" "$T/hook/boot" "$T/hook/tripped" "$T/hook"/*.tmp "$T/hook/starts.w"; echo 0 > "$T/hook/starts"; }
there() { [ -e "$1" ] && echo yes || echo no; }
id() { echo "5e1d0c34-aaaa-4bbb-8ccc-00000000000$1" > "$T/boot_id"; }

out=$(run -Xzygote /system/bin --zygote --start-system-server)
check "passes arguments" "ARGS:-Xzygote /system/bin --zygote --start-system-server" "$out"
check "no enable file: keeps env" "PRELOAD:old.so" "$out"
check "kernel log line" "hmgapi-hook: zygote, exec $T/stock" "$(cat "$T/kmsg")"

touch "$T/hook/enable"
out=$(run --zygote)
check "enabled: preload set" "PRELOAD:$T/lib.so" "$out"
check "start counter" "1" "$(cat "$T/hook/starts")"
check "log says on" "hooks on, start 1" "$(cat "$T/kmsg")"
check "counters written through .tmp, none left" "no no" "$(there "$T/hook/starts.tmp") $(there "$T/hook/boot.tmp")"

: > "$T/kmsg"; out=$(NO_ADDR_COMPAT_LAYOUT_FIXUP=1 LD_PRELOAD="$T/lib.so" "$T/wrap" --zygote 2>/dev/null)
check "re-exec: preload kept" "PRELOAD:$T/lib.so" "$out"
check "re-exec: not counted" "1" "$(cat "$T/hook/starts")"
check "re-exec: logged" "zygote (re-exec), exec" "$(cat "$T/kmsg")"

# hmgapid: started by every zygote start (not the re-exec, not other app_process runs)
printf '#!/bin/sh\necho "$*" >> %s/hmgapid.ran\n' "$T" > "$T/hmgapid"; chmod +x "$T/hmgapid"
run --zygote >/dev/null; sleep 0.3
check "zygote starts hmgapid daemon" "daemon" "$(cat "$T/hmgapid.ran" 2>/dev/null)"
check "... and logs it" ", hmgapid, exec" "$(cat "$T/kmsg")"
: > "$T/hmgapid.ran"
NO_ADDR_COMPAT_LAYOUT_FIXUP=1 "$T/wrap" --zygote >/dev/null 2>&1; "$T/wrap" /system/bin com.android.commands.am.Am >/dev/null 2>&1; sleep 0.3
check "re-exec and plain app_process don't" "" "$(cat "$T/hmgapid.ran")"
rm "$T/hmgapid" "$T/hmgapid.ran"

out=$(run /system/bin com.android.commands.am.Am)
check "not zygote: no preload" "PRELOAD:old.so" "$out"

fresh; echo 1 > "$T/hook/starts"; echo "$(cat "$T/boot_id") 1" > "$T/hook/boot"   # one earlier start, in this boot
run --zygote >/dev/null; run --zygote >/dev/null
out=$(run --zygote)
check "boot guard after 3 starts" "PRELOAD:old.so" "$out"
check "log says boot guard" "boot guard, starts=3" "$(cat "$T/kmsg")"
check "... and it trips: which limit, the counts" "boot 3 3" "$(cat "$T/hook/tripped" 2>/dev/null)"

fresh; touch "$T/hook/disable"
out=$(run --zygote)
check "disable file" "PRELOAD:old.so" "$out"
rm "$T/hook/disable"; rm "$T/lib.so"
out=$(run --zygote)
check "missing lib" "hooks off (no $T/lib.so)" "$(cat "$T/kmsg")"
: > "$T/lib.so"

# ---- the watcher: a start counts until Android booted (system_server, the launcher up
# UP_MS, sys.boot_completed 1); hook/boot stays for the whole kernel boot
fresh
: > "$T/kmsg"; SS=0.1 LA=0.1 STAY=3 LD_PRELOAD=old.so "$T/wrap" --zygote >/dev/null 2>&1 &
zp=$!
sleep 0.4
check "watcher: counted" "1" "$(cat "$T/hook/starts")"
sleep 1.6
check "watcher: reset once Android booted" "0" "$(cat "$T/hook/starts")"
wait $zp
check "watcher: logged" "booted ($T/launcher up 0 s, boot completed)" "$(sed 's/up [0-9]* s/up 0 s/' "$T/kmsg")"
check "watcher: this boot's count kept" "$(cat "$T/boot_id") 1" "$(cat "$T/hook/boot" 2>/dev/null)"

# the review's late crash: Android booted each time, then died (STAY), in one kernel boot
fresh
for n in 1 2 3; do
    SS=0.1 LA=0.1 STAY=2 LD_PRELOAD=old.so "$T/wrap" --zygote >/dev/null 2>&1; sleep 0.3
done
check "late crashes: each booted, so the row count is back to 0" "0" "$(cat "$T/hook/starts")"
out=$(run --zygote)
check "late crashes: 3 starts in one boot, the 4th has no hooks" "PRELOAD:old.so" "$out"
check "... logged" "boot guard, starts=0, this boot 3" "$(cat "$T/kmsg")"
check "... tripped" "boot 0 3" "$(cat "$T/hook/tripped" 2>/dev/null)"

# tripped stays: a new kernel boot doesn't lift it, and nothing more is counted
id 2; out=$(run --zygote)
check "tripped: still off in a new boot" "PRELOAD:old.so" "$out"
check "... logged" "hooks off (boot guard tripped earlier" "$(cat "$T/kmsg")"
check "... not counted" "5e1d0c34-aaaa-4bbb-8ccc-000000000001 4" "$(cat "$T/hook/boot")"
fresh; out=$(run --zygote)
check "reset (starts 0, no boot, no tripped): on again" "PRELOAD:$T/lib.so" "$out"
id 1

# not booted: no reset, the start stays counted
fresh; echo 0 > "$T/boot_completed"
SS=0.1 LA=0.1 STAY=1.8 LD_PRELOAD=old.so "$T/wrap" --zygote >/dev/null 2>&1; sleep 0.3
check "boot never completed: still counted" "1" "$(cat "$T/hook/starts")"
echo 1 > "$T/boot_completed"
fresh
SS=0.1 LALOOP=5 LD_PRELOAD=old.so "$T/wrap" --zygote >/dev/null 2>&1; sleep 0.3
check "launcher crash-looping (never up 0.6 s): still counted" "1" "$(cat "$T/hook/starts")"
fresh
SS=0.1 LA=0.1 LALIVE=0.3 STAY=1.8 LD_PRELOAD=old.so "$T/wrap" --zygote >/dev/null 2>&1; sleep 0.3
check "launcher died before it was up 0.6 s: still counted" "1" "$(cat "$T/hook/starts")"
fresh
: > "$T/kmsg"; SS=0.1 STAY=4.5 LD_PRELOAD=old.so "$T/wrap" --zygote >/dev/null 2>&1; sleep 0.3
check "no launcher at all: given up at GIVEUP_MS, still counted" "1" "$(cat "$T/hook/starts")"
check "... logged" "not booted in 3 s, counted as a failed start" "$(cat "$T/kmsg")"
fresh
: > "$T/kmsg"; STAY= LD_PRELOAD=old.so "$T/wrap" --zygote >/dev/null 2>&1
sleep 1
check "watcher: no reset after zygote died" "1" "$(cat "$T/hook/starts")"

# a hang before system_server (a mod's zygoteInit): stopped at HANG_MS, so it counts in this boot
fresh
: > "$T/kmsg"; s=$(date +%s)
set +e; STAY=6 LD_PRELOAD=old.so "$T/wrap" --zygote >/dev/null 2>&1; rc=$?; set -e
check "hang: the watcher stops the zygote (SIGKILL)" "137" "$rc"
check "... soon after HANG_MS, not when it would have ended" "yes" "$([ $(($(date +%s) - s)) -lt 4 ] && echo yes || echo no)"
check "... logged" "has no $T/system_server after 1 s (hung before it" "$(cat "$T/kmsg")"
check "... counted" "1" "$(cat "$T/hook/starts")"
for n in 2 3; do set +e; STAY=6 LD_PRELOAD=old.so "$T/wrap" --zygote >/dev/null 2>&1; set -e; done
out=$(run --zygote)
check "3 hangs in one boot: the 4th start has no hooks (Android starts)" "PRELOAD:old.so" "$out"
check "... tripped" "boot 3 3" "$(cat "$T/hook/tripped" 2>/dev/null)"

# ---- the boot guard tells a crash loop (one kernel boot) from restarts (a new boot_id each)
fresh; id 1
run --zygote >/dev/null; run --zygote >/dev/null; run --zygote >/dev/null
out=$(run --zygote)
check "crash loop in one boot: off after 3" "PRELOAD:old.so" "$out"
check "... logged with this boot's count" "boot guard, starts=3, this boot 3" "$(cat "$T/kmsg")"
check "... this boot's count kept" "5e1d0c34-aaaa-4bbb-8ccc-000000000001 4" "$(cat "$T/hook/boot")"
fresh
for n in 1 2 3 4 5 6; do id $n; out=$(run --zygote); done
check "6 short restarts (6 boots): hooks still on" "PRELOAD:$T/lib.so" "$out"
check "... logged as the first start of its boot" "hooks on, start 6 (this boot 1)" "$(cat "$T/kmsg")"
id 7; out=$(run --zygote)
check "the 7th in a row: off (a restart loop, or 6 boots that hung)" "PRELOAD:old.so" "$out"
check "... logged" "boot guard, starts=6, this boot 0" "$(cat "$T/kmsg")"
check "... tripped" "row 6 0" "$(cat "$T/hook/tripped" 2>/dev/null)"
fresh; rm -f "$T/boot_id"
run --zygote >/dev/null; run --zygote >/dev/null; run --zygote >/dev/null
out=$(run --zygote)
check "no boot_id: every start counts, off after 3 as before" "PRELOAD:old.so" "$out"
check "... no boot file written" no "$(there "$T/hook/boot")"
id 1

# ---- fail closed: a counter that can't be read or written in full means no hooks
for f in starts.tmp boot.tmp; do
    fresh; ln -s /dev/full "$T/hook/$f"
    out=$(run --zygote)
    check "write error ($f on a full disk): no hooks" "PRELOAD:old.so" "$out"
    check "... logged" "hooks off (can't read or write the boot guard's counters)" "$(cat "$T/kmsg")"
done
check "... the half-written temp file removed" no "$(there "$T/hook/boot.tmp")"
fresh; ln -sf /dev/full "$T/hook/starts"; ln -sf /dev/full "$T/hook/boot"
n=0; on=0
while [ $n -lt 10 ]; do out=$(run --zygote); case "$out" in *"PRELOAD:$T/lib.so"*) on=$((on + 1)) ;; esac; n=$((n + 1)); done
check "the review's case (counters on /dev/full, 10 starts): hooks on none" "0" "$on"
fresh; rm "$T/hook/starts"; mkdir "$T/hook/starts"
out=$(run --zygote)
check "starts unreadable (a directory): no hooks" "PRELOAD:old.so" "$out"
rmdir "$T/hook/starts"; fresh; printf 'x\n' > "$T/hook/starts"
out=$(run --zygote)
check "starts not a number: no hooks" "PRELOAD:old.so" "$out"
fresh; : > "$T/hook/starts"
out=$(run --zygote)
check "starts empty: no hooks" "PRELOAD:old.so" "$out"
fresh; rm "$T/hook/starts"
out=$(run --zygote)
check "starts absent: from 0, hooks on" "PRELOAD:$T/lib.so" "$out"
check "... written with a newline" "1" "$(cat "$T/hook/starts")"
fresh

# the stock copy lost or damaged: the install's stock backup runs instead (mode 600, made 700)
# the backup: an ARM binary standing in for stock (the wrapper built again, running the script
# that prints its arguments)
cp "$T/stock" "$T/stock.sh"
clang $ARM_STATIC -DSTOCK="\"$T/stock.sh\"" -DSTOCKBAK="\"$T/none\"" -DKMSG="\"$T/kmsg.bak\"" -DHOOKDIR="\"$T/hook\"" -DHOOKLIB="\"$T/lib.so\"" -DHMGAPID="\"$T/hmgapid\"" -DBOOTID="\"$T/boot_id\"" \
  -o "$T/stockbak" zygote.c
chmod 600 "$T/stockbak"
mv "$T/stock" "$T/stock.away"; printf 'short' > "$T/stock"; chmod 755 "$T/stock"   # cut short: no binary, not a script
: > "$T/kmsg"; out=$(LD_PRELOAD=old.so "$T/wrap" -Xzygote --zygote 2>/dev/null)
check "damaged stock copy: the stock backup runs, with the same arguments" "ARGS:-Xzygote --zygote" "$out"
check "... logged, with the errno" "failed, errno 8, exec $T/stockbak (the stock backup)" "$(cat "$T/kmsg")"
check "... the backup made executable (it's 600 after the install)" "-rwx------" "$(ls -l "$T/stockbak" | cut -c1-10)"
rm "$T/stock"
: > "$T/kmsg"; out=$(LD_PRELOAD=old.so "$T/wrap" --zygote 2>/dev/null)
check "stock copy gone: the stock backup runs" "ARGS:--zygote" "$out"
printf 'not an ELF' > "$T/stockbak"
: > "$T/kmsg"; set +e; "$T/wrap" --zygote 2>/dev/null; rc=$?; set -e
check "backup isn't a binary: not run, exits 127" "127" "$rc"
check "... logged" "failed, errno 2, and no stock backup" "$(cat "$T/kmsg")"
rm "$T/stockbak"
: > "$T/kmsg"; set +e; "$T/wrap" --zygote 2>/dev/null; rc=$?; set -e
check "exec failure exits 127" "127" "$rc"
check "exec failure logged" "failed, errno 2, and no stock backup" "$(cat "$T/kmsg")"
{ printf '\177ELF'; printf 'garbage'; } > "$T/stockbak"; chmod 600 "$T/stockbak"
: > "$T/kmsg"; set +e; "$T/wrap" --zygote 2>/dev/null; rc=$?; set -e
check "a backup that won't exec either: exits 127" "127" "$rc"
# (each log line goes to the start of $T/kmsg, a plain file here, so only the last is left)
check "... the backup's failure logged, with its errno" "exec $T/stockbak failed, errno" "$(cat "$T/kmsg")"
exit $fail
