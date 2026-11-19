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
. ../../tools/env.sh
clang $ARM_STATIC -DGUARD_DELAY=1 -DSTOCK="\"$T/stock\"" -DKMSG="\"$T/kmsg\"" -DHOOKDIR="\"$T/hook\"" -DHOOKLIB="\"$T/lib.so\"" -DHMGAPID="\"$T/hmgapid\"" \
  -DSEL_ENFORCE="\"$T/sel/enforce\"" -DSEL_ALLOW="\"$T/allow-permissive\"" -DSEL_NOTE="\"$T/sel-note\"" \
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
echo 1 > "$T/hook/starts"

out=$(run /system/bin com.android.commands.am.Am)
check "not zygote: no preload" "PRELOAD:old.so" "$out"

run --zygote >/dev/null; run --zygote >/dev/null
out=$(run --zygote)
check "boot guard after 3 starts" "PRELOAD:old.so" "$out"
check "log says boot guard" "boot guard, starts=3" "$(cat "$T/kmsg")"

echo 0 > "$T/hook/starts"; touch "$T/hook/disable"
out=$(run --zygote)
check "disable file" "PRELOAD:old.so" "$out"
rm "$T/hook/disable"; rm "$T/lib.so"
out=$(run --zygote)
check "missing lib" "hooks off (no $T/lib.so)" "$(cat "$T/kmsg")"

# boot guard reset by the watcher: zygote alive past GUARD_DELAY -> starts=0
: > "$T/lib.so"
echo 0 > "$T/hook/starts"
: > "$T/kmsg"; STAY=3 LD_PRELOAD=old.so "$T/wrap" --zygote >/dev/null 2>&1 &
zp=$!
sleep 0.4
check "watcher: counted" "1" "$(cat "$T/hook/starts")"
sleep 1.4
check "watcher: reset while zygote alive" "0" "$(cat "$T/hook/starts")"
wait $zp
check "watcher: logged" "boot guard reset" "$(cat "$T/kmsg")"
echo 0 > "$T/hook/starts"
: > "$T/kmsg"; STAY= LD_PRELOAD=old.so "$T/wrap" --zygote >/dev/null 2>&1
sleep 2
check "watcher: no reset after zygote died" "1" "$(cat "$T/hook/starts")"

# SELinux: Enforcing at boot + the owner's option -> Permissive, once per boot
echo 0 > "$T/hook/starts"; mkdir -p "$T/sel"
out=$(run --zygote)
check "no selinuxfs: nothing said" "" "$(grep -o selinux "$T/kmsg" || true)"
echo 1 > "$T/sel/enforce"
out=$(run --zygote)
check "enforcing, option off: kept" "1" "$(cat "$T/sel/enforce")"
check "... noted" "enforcing kept" "$(cat "$T/sel-note")"
check "... logged" "selinux enforcing, hooks on" "$(cat "$T/kmsg")"
rm "$T/sel-note"; touch "$T/allow-permissive"
out=$(run --zygote)
check "option on: permissive" "0" "$(cat "$T/sel/enforce")"
check "... noted" "enforcing permissive wrapper" "$(cat "$T/sel-note")"
check "... logged" "selinux enforcing, set permissive, hooks on" "$(cat "$T/kmsg")"
check "... zygote still runs" "ARGS:--zygote" "$out"
echo 1 > "$T/sel/enforce"
out=$(run --zygote)
check "zygote restart: owner's later Enforcing left alone" "1" "$(cat "$T/sel/enforce")"
check "... logged" "left as it is" "$(cat "$T/kmsg")"
rm "$T/sel-note"
NO_ADDR_COMPAT_LAYOUT_FIXUP=1 "$T/wrap" --zygote >/dev/null 2>&1
check "re-exec: doesn't touch it" "1" "$(cat "$T/sel/enforce")"
"$T/wrap" /system/bin com.android.commands.am.Am >/dev/null 2>&1
check "plain app_process: doesn't touch it" "1" "$(cat "$T/sel/enforce")"
chmod 444 "$T/sel/enforce"
if [ "$(id -u)" != 0 ]; then
    out=$(run --zygote)
    check "can't write: logged" "can't set permissive (errno 13)" "$(cat "$T/kmsg")"
    check "... noted for boot.sh" "enforcing failed wrapper" "$(cat "$T/sel-note")"
    check "... zygote still runs" "ARGS:--zygote" "$out"
fi
chmod 644 "$T/sel/enforce"; rm -rf "$T/sel" "$T/sel-note" "$T/allow-permissive"
echo 0 > "$T/hook/starts"

rm "$T/stock"
: > "$T/kmsg"; set +e; "$T/wrap" --zygote 2>/dev/null; rc=$?; set -e
check "exec failure exits 127" "127" "$rc"
check "exec failure logged" "failed, errno 2" "$(cat "$T/kmsg")"
exit $fail
