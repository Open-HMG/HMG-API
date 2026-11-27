#!/bin/sh
# Off-car test of the zygote wrapper under qemu-arm (binfmt): argument passthrough,
# the preload switch and the boot guard.
set -e
cd "$(dirname "$0")"
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
cat > "$T/stock" <<'S'
#!/bin/sh
echo "ARGS:$*"
echo "PRELOAD:${LD_PRELOAD:-none}"
[ -n "$STAY" ] && sleep "$STAY"
exit 0
S
chmod +x "$T/stock"
: > "$T/lib.so"
mkdir -p "$T/hook"
echo 5e1d0c34-aaaa-4bbb-8ccc-000000000001 > "$T/boot_id"   # one kernel boot, until a test changes it
. ../../tools/env.sh
clang $ARM_STATIC -DGUARD_DELAY=1 -DSTOCK="\"$T/stock\"" -DSTOCKBAK="\"$T/stockbak\"" -DKMSG="\"$T/kmsg\"" -DHOOKDIR="\"$T/hook\"" -DHOOKLIB="\"$T/lib.so\"" -DHMGAPID="\"$T/hmgapid\"" -DBOOTID="\"$T/boot_id\"" \
  -o "$T/wrap" zygote.c
fail=0
check() {  # name, expected-substring, actual
    case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted '$2' in: $3"; fail=1 ;; esac
}
run() { : > "$T/kmsg"; LD_PRELOAD=old.so "$T/wrap" "$@" 2>/dev/null; }

out=$(run -Xzygote /system/bin --zygote --start-system-server)
check "passes arguments" "ARGS:-Xzygote /system/bin --zygote --start-system-server" "$out"
check "no enable file: keeps env" "PRELOAD:old.so" "$out"
check "kernel log line" "hmgapi-hook: zygote, exec $T/stock" "$(cat "$T/kmsg")"

touch "$T/hook/enable"
out=$(run --zygote)
check "enabled: preload set" "PRELOAD:$T/lib.so" "$out"
check "start counter" "1" "$(cat "$T/hook/starts")"
check "log says on" "hooks on, start 1" "$(cat "$T/kmsg")"

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
echo 1 > "$T/hook/starts"; echo "$(cat "$T/boot_id") 1" > "$T/hook/boot"   # one earlier start, in this boot

out=$(run /system/bin com.android.commands.am.Am)
check "not zygote: no preload" "PRELOAD:old.so" "$out"

run --zygote >/dev/null; run --zygote >/dev/null
out=$(run --zygote)
check "boot guard after 3 starts" "PRELOAD:old.so" "$out"
check "log says boot guard" "boot guard, starts=3" "$(cat "$T/kmsg")"

echo 0 > "$T/hook/starts"; rm -f "$T/hook/boot"; touch "$T/hook/disable"
out=$(run --zygote)
check "disable file" "PRELOAD:old.so" "$out"
rm "$T/hook/disable"; rm "$T/lib.so"
out=$(run --zygote)
check "missing lib" "hooks off (no $T/lib.so)" "$(cat "$T/kmsg")"

# boot guard reset by the watcher: zygote alive past GUARD_DELAY -> starts=0
: > "$T/lib.so"
echo 0 > "$T/hook/starts"; rm -f "$T/hook/boot"
: > "$T/kmsg"; STAY=3 LD_PRELOAD=old.so "$T/wrap" --zygote >/dev/null 2>&1 &
zp=$!
sleep 0.4
check "watcher: counted" "1" "$(cat "$T/hook/starts")"
sleep 1.4
check "watcher: reset while zygote alive" "0" "$(cat "$T/hook/starts")"
wait $zp
check "watcher: logged" "boot guard reset" "$(cat "$T/kmsg")"
check "watcher: this boot's count gone too" no "$([ -e "$T/hook/boot" ] && echo yes || echo no)"
echo 0 > "$T/hook/starts"

# the boot guard tells a crash loop (one kernel boot) from restarts (a new boot_id each)
id() { echo "5e1d0c34-aaaa-4bbb-8ccc-00000000000$1" > "$T/boot_id"; }
echo 0 > "$T/hook/starts"; rm -f "$T/hook/boot"; id 1
run --zygote >/dev/null; run --zygote >/dev/null; run --zygote >/dev/null
out=$(run --zygote)
check "crash loop in one boot: off after 3" "PRELOAD:old.so" "$out"
check "... logged with this boot's count" "boot guard, starts=3, this boot 3" "$(cat "$T/kmsg")"
check "... this boot's count kept" "5e1d0c34-aaaa-4bbb-8ccc-000000000001 4" "$(cat "$T/hook/boot")"
echo 0 > "$T/hook/starts"; rm -f "$T/hook/boot"
for n in 1 2 3 4 5 6; do id $n; out=$(run --zygote); done
check "6 short restarts (6 boots): hooks still on" "PRELOAD:$T/lib.so" "$out"
check "... logged as the first start of its boot" "hooks on, start 6 (this boot 1)" "$(cat "$T/kmsg")"
id 7; out=$(run --zygote)
check "the 7th in a row: off (a restart loop)" "PRELOAD:old.so" "$out"
check "... logged" "boot guard, starts=6, this boot 0" "$(cat "$T/kmsg")"
echo 0 > "$T/hook/starts"; rm -f "$T/hook/boot" "$T/boot_id"
run --zygote >/dev/null; run --zygote >/dev/null; run --zygote >/dev/null
out=$(run --zygote)
check "no boot_id: every start counts, off after 3 as before" "PRELOAD:old.so" "$out"
check "... no boot file written" no "$([ -e "$T/hook/boot" ] && echo yes || echo no)"
echo 0 > "$T/hook/starts"
: > "$T/kmsg"; STAY= LD_PRELOAD=old.so "$T/wrap" --zygote >/dev/null 2>&1
sleep 2
check "watcher: no reset after zygote died" "1" "$(cat "$T/hook/starts")"

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
check "... both failures logged" "2" "$(grep -c 'failed, errno' "$T/kmsg")"
check "... the backup's with its errno" "exec $T/stockbak failed, errno" "$(cat "$T/kmsg")"
exit $fail
