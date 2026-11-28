#!/bin/sh
# Off-car test of safe mode (Safe): `engine safe on|off|status`, and what the engine
# publishes while /data/hmgapi/SAFE is there: no mod's services (HMG-API's own stay),
# apps, network rules, hooks or vehicle readings; the public state says so; turning it
# off brings every mod back and resets the boot guard's counters. Also the mods'
# service priority (state/service-priority.txt) and the log keeper's per-mod limit.
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d)
sh tools/engine-classes.sh "$CP"
T=$(mktemp -d)
trap 'rm -rf "$T" "$CP"' EXIT
R=$T/root
D=$R/data/hmgapi
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
hasnt() { case "$3" in *"$2"*) echo "FAIL  $1: [$2] in [$3]"; fail=1 ;; *) echo "ok    $1" ;; esac; }
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 HMGAPI_PRODUCT=CEEV.USA.0000.V015 java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
actions() { cat "$R/actions.log" 2>/dev/null; : > "$R/actions.log"; }

mkdir -p "$D/mods/cam/app" "$D/mods/cam/svc" "$D/mods/cp" "$D/hook" "$R/data/system"
: > "$R/data/system/packages.list"
echo apk > "$D/mods/cam/app/Cam.apk"
echo 'exec sleep 1' > "$D/mods/cam/svc/rec.sh"
echo 'exec sleep 1' > "$D/mods/cam/svc/ui.sh"
cat > "$D/mods/cam/mod.json" <<'J'
{"id": "cam", "version": "1.0.0",
 "apps": [{"apk": "app/Cam.apk", "package": "dev.example.cam"}],
 "services": [{"name": "rec", "script": "svc/rec.sh", "start": "boot"},
              {"name": "ui", "script": "svc/ui.sh", "priority": "normal"},
              {"name": "odd", "script": "svc/ui.sh", "priority": "high"}],
 "vehicle": {"read": ["speed"]},
 "hooks": {"jar": "hooks.jar", "class": "dev.example.cam.Hooks"}}
J
cat > "$D/mods/cp/mod.json" <<'J'
{"id": "cp", "version": "1.0.0",
 "network": {"rules": [{"chain": "input", "in": "hotspot", "proto": "tcp", "ports": [7000], "action": "accept"}]}}
J
# the adb mod's own rule (Open-HMG/hmgapi-adb): adb over the hotspot, on cars whose firewall drops it
mkdir -p "$D/mods/adb" "$D/settings"
cat > "$D/mods/adb/mod.json" <<'J'
{"id": "adb", "version": "1.0.1",
 "network": {"rules": [{"chain": "input", "in": "hotspot", "proto": "tcp", "ports": [5555], "action": "accept", "when": "settings.tcp"},
                       {"chain": "input", "in": "hotspot", "proto": "tcp", "ports": [5555, 8080], "action": "accept"},
                       {"chain": "input", "in": "hotspot", "proto": "udp", "ports": [5555], "action": "accept"}]}}
J
echo '{"tcp": true}' > "$D/settings/adb.json"
export HMGAPI_PROPS="persist.adb.tcp.port=5555"   # adbd listens on the network

# ---- off: as usual
eng safe
check "status: off" "safe mode: off" "$out"
eng publish-state
has "mods' services listed" "cam rec root boot yes script svc/rec.sh" "$(cat $D/state/services.txt)"
check "priority: only the one that asked for normal" "cam ui normal" "$(cat $D/state/service-priority.txt)"
has "an unknown priority is refused" "cam: service odd: priority must be low (the default) or normal" "$out"
has "network rules wanted" "7000" "$(cat $D/state/net.rules)"
check "apps.txt" "dev.example.cam cam" "$(cat $D/state/apps.txt)"
check "vehicle readings" "cam speed" "$(cat $D/state/vehicle.txt)"
eng plan
has "plan: normal priority shown" "service  cam/ui as root, at android, restarted if it stops, normal priority [cam]" "$out"
hasnt "plan: no safe-mode line" "safe mode is on" "$out"
eng apps
has "app installed" "dev.example.cam installed (cam)" "$out"
: > "$D/state/net.applied"   # the keeper put the rules in
actions >/dev/null

# ---- on
printf '3\n' > "$D/hook/starts"; echo "id 3" > "$D/hook/boot"   # the boot guard had tripped
eng safe on
check "safe on: exit 0" 0 $rc
has "safe on: says what's off" "safe mode on: no mod's hooks (from the next restart), services, apps or network rules (adb stays reachable); HMG-API and its app stay on" "$out"
has "... and how to turn it off" "hmgapid engine safe off" "$out"
has "... asks for a restart (the hooks)" "restart the head unit" "$out"
check "the file, with why" "owner" "$(cat $D/SAFE)"
hasnt "no mod's service" "cam " "$(cat $D/state/services.txt)"
has "HMG-API's log keeper stays" "hmgapi log root boot yes" "$(cat $D/state/services.txt)"
has "the network keeper stays, to take the rules out" "hmgapi net root boot yes" "$(cat $D/state/services.txt)"
hasnt "the mods' network rules out" "7000" "$(cat $D/state/net.rules 2>/dev/null)"
has "... but the adb mod's rule to adbd's port stays (adb is how a stuck unit is mended)" "--dport 5555 -j ACCEPT" "$(cat $D/state/net.rules)"
hasnt "... only that one: not a rule with more ports" "8080" "$(cat $D/state/net.rules)"
hasnt "... nor udp" "-p udp" "$(cat $D/state/net.rules)"
check "no mod's app has access" "" "$(cat $D/state/apps.txt)"
check "no readings" "" "$(cat $D/state/vehicle.txt)"
check "no priority lines" "" "$(cat $D/state/service-priority.txt)"
hasnt "no vehicle service for mods" "hmgapi vehicle" "$(cat $D/state/services.txt)"
has "public state: safe" '"safe": true' "$(cat $D/public/state.json)"
has "public state: why" '"safe_why": "owner"' "$(cat $D/public/state.json)"
has "public state: the mod off" '"enabled": false' "$(cat $D/public/state.json)"
hasnt "public state: no mod on" '"enabled": true' "$(cat $D/public/state.json)"
has "published, saying safe mode" "0 of 3 mods on (safe mode)" "$out"
# adb over TCP off (no port), or the mod's own switch off: nothing kept
# export, then back: VAR= eng (a function) leaves VAR set after it in bash 3.2's sh (macOS)
export HMGAPI_PROPS=; eng publish-state; export HMGAPI_PROPS="persist.adb.tcp.port=5555"
check "adb over TCP off: no rule kept" "" "$(cat $D/state/net.rules 2>/dev/null)"
eng publish-state
has "adb over TCP on again: kept again" "--dport 5555" "$(cat $D/state/net.rules)"
echo '{"tcp": false}' > "$D/settings/adb.json"
eng publish-state
check "the adb mod's switch off: none either" "" "$(cat $D/state/net.rules 2>/dev/null)"
echo '{"tcp": true}' > "$D/settings/adb.json"
: > "$D/mods/adb/.disabled"
eng publish-state
check "the adb mod turned off: none either" "" "$(cat $D/state/net.rules 2>/dev/null)"
rm "$D/mods/adb/.disabled"
eng publish-state
check "the boot guard's counters stay (until off)" "3" "$(cat $D/hook/starts)"
has "the apps' processes hear it" "broadcast hmgapi.intent.action.STATE_CHANGED" "$(actions)"
eng apps
has "mod's app turned off" "dev.example.cam turned off (cam is off; its data stays)" "$out"
check "pm disable" "pm disable dev.example.cam" "$(actions)"
eng safe
has "status: on, by the owner" "safe mode: on (turned on by the owner)" "$out"
eng plan
has "plan: says safe mode is on" "safe mode is on (turned on by the owner)" "$out"
hasnt "plan: no mod's service" "service  cam/" "$out"
eng status
has "status: safe mode line" "safe mode on (turned on by the owner)" "$out"
eng safe on
check "on again: fine" 0 $rc
hasnt "on again: no restart asked (nothing changed)" "restart the head unit" "$out"
eng safe sideways
check "a bad argument: usage" 2 $rc
has "... says so" "usage: engine safe [on | off]" "$out"

# the file by hand (hmgapid sh ': > /data/hmgapi/SAFE'): the same, with no reason
rm "$D/SAFE"; : > "$D/SAFE"
eng safe
has "by hand: on" "safe mode: on (turned on by hand" "$out"
# the zygote wrapper's boot guard
echo "boot guard" > "$D/SAFE"
eng safe
has "boot guard: explained" "the boot guard turned it on" "$out"
eng publish-state
has "boot guard: in the public state" '"safe_why": "boot guard"' "$(cat $D/public/state.json)"

# ---- off
eng safe off
check "safe off: exit 0" 0 $rc
has "safe off: says so" "safe mode off: the mods run again" "$out"
check "the file is gone" "no" "$([ -e $D/SAFE ] && echo yes || echo no)"
check "boot guard counters reset" "0" "$(cat $D/hook/starts)"
check "... and this boot's count gone" "no" "$([ -e $D/hook/boot ] && echo yes || echo no)"
has "services back" "cam rec root boot yes script svc/rec.sh" "$(cat $D/state/services.txt)"
has "rules back" "7000" "$(cat $D/state/net.rules)"
hasnt "public state: no safe flag" '"safe"' "$(cat $D/public/state.json)"
eng apps
has "app on again" "dev.example.cam turned on (cam)" "$out"
eng safe off
check "off again: fine" 0 $rc

# ---- an engine run in progress: the file changes at once, the state follows later
mkdir -p "$T/lockhold"
cat > "$T/lockhold/Hold.java" <<'J'
public class Hold {
    public static void main(String[] a) throws Exception {
        java.io.RandomAccessFile f = new java.io.RandomAccessFile(a[0], "rw");
        f.getChannel().lock();
        new java.io.File(a[1]).createNewFile();
        Thread.sleep(20000);
    }
}
J
javac -d "$T/lockhold" "$T/lockhold/Hold.java"
java -cp "$T/lockhold" Hold "$D/state/engine.lock" "$T/locked" & HOLD=$!
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do [ -e "$T/locked" ] && break; sleep 0.25; done
s=$(date +%s); eng safe on; took=$(( $(date +%s) - s ))
kill $HOLD 2>/dev/null; wait $HOLD 2>/dev/null || true
check "busy engine: safe on still works" 0 $rc
check "... the file is there" "owner" "$(cat $D/SAFE)"
has "... says the state follows" "the state follows when the engine run in progress has finished" "$out"
hasnt "... and doesn't say to try again" "try again" "$out"
[ "$took" -lt 10 ] && echo "ok    ... without waiting for the lock" || { echo "FAIL  safe on waited ${took}s for the lock"; fail=1; }
eng safe off

# ---- the log keeper: each mod's lines up to its limit a minute, HMG-API's own always
cat > "$T/KeeperTest.java" <<'J'
package hmgapi.engine;
public class KeeperTest {
    public static void main(String[] a) {
        LogKeeper.Throttle t = new LogKeeper.Throttle(3);
        StringBuilder b = new StringBuilder();
        long now = 0;
        for (int i = 0; i < 6; i++) for (String s : t.line("11-27 10:00:00.000 I/HMG-API(  42): [spam] line " + i, now)) b.append(s).append('\n');
        for (String s : t.line("11-27 10:00:01.000 I/HMG-API(  42): mods: 2 launcher entries", now)) b.append(s).append('\n');
        for (String s : t.line("11-27 10:00:01.000 I/HMG-API(  43): [other] one", now)) b.append(s).append('\n');
        now += LogKeeper.WINDOW;
        for (String s : t.line("11-27 10:01:01.000 I/HMG-API(  42): [spam] later", now)) b.append(s).append('\n');
        System.out.print(b);
    }
}
J
javac -cp "$CP" -d "$T" "$T/KeeperTest.java"
k=$(java -cp "$CP:$T" hmgapi.engine.KeeperTest)
has "keeper: a mod's first lines kept" "[spam] line 2" "$k"
hasnt "keeper: past its limit, left out" "[spam] line 3" "$k"
has "keeper: one line says so" "HMG-API log keeper: [spam] logs more than 3 lines a minute" "$k"
check "keeper: said once" 1 "$(printf '%s\n' "$k" | grep -c 'logs more than')"
has "keeper: HMG-API's own line kept" "mods: 2 launcher entries" "$k"
has "keeper: another mod kept" "[other] one" "$k"
has "keeper: next minute, the count" "HMG-API log keeper: [spam] 3 lines left out" "$k"
has "keeper: next minute, kept again" "[spam] later" "$k"

exit $fail
