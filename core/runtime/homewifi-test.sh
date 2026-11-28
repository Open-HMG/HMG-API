#!/bin/sh
# Off-car test of home Wi-Fi and mods kept off the car's cellular link (HomeWifi):
# what the engine makes of the owner's switch, mod.json "wifi": {"home": true} and
# overrides.allow_cellular (state/mods-net.txt, service-gids.txt, the keepers' lines,
# the plan), then the network keeper's mods section (net.sh) and the home Wi-Fi keeper
# (wifi.sh) against fake tools that keep their state in files.
# UNIT_SH: the shell to run the keepers with (mksh, the unit's, when it's installed).
set -e
cd "$(dirname "$0")/../.."
case "$UNIT_SH" in *busybox*) UNIT_SH= ;; esac
UNIT_SH=${UNIT_SH:-$(command -v mksh || command -v bash)}
CP=$(mktemp -d)
sh tools/engine-classes.sh "$CP"
javac --release 8 -cp "$CP" -d "$CP" core/runtime/test/hmgapi/engine/WifiApTest.java
# WifiAp.crc's chown system:wifi: a stand-in that fails only for a "chownfail" path (the test's failure case)
CB=$(mktemp -d)
printf '#!/bin/sh\ncase "$*" in *chownfail*) echo "chown: not permitted" >&2; exit 1 ;; esac\nexit 0\n' > "$CB/chown"; chmod +x "$CB/chown"
PATH=$CB:$PATH java -cp "$CP" hmgapi.engine.WifiApTest
rm -rf "$CB"
T=$(mktemp -d)
cleanup() { r=$?; [ $r != 0 ] && [ -z "$done" ] && echo "FAIL  stopped (exit $r) after: ${last:-the start}"; [ -f "$T/fake/fw/wpa.sleep" ] && kill "$(cat "$T/fake/fw/wpa.sleep")" 2>/dev/null || true; rm -rf "$T" "$CP"; }
trap cleanup EXIT
R=$T/root
D=$R/data/hmgapi
fail=0
check() { last=$1; if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { last=$1; case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
hasnt() { last=$1; case "$3" in *"$2"*) echo "FAIL  $1: [$2] in [$3]"; fail=1 ;; *) echo "ok    $1" ;; esac; }
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 HMGAPI_PRODUCT=CVEV.USA.0000.V015 java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }

mkdir -p "$D/mods/dl" "$D/mods/quiet" "$D/mods/cell" "$D/settings" "$D/state"
echo '{"id": "dl", "version": "1.0.0", "wifi": {"home": true}, "apps": [{"package": "com.example.dl", "apk": "dl.apk"}]}' > "$D/mods/dl/mod.json"
echo '{"id": "quiet", "version": "1.0.0", "apps": [{"package": "com.example.quiet", "apk": "q.apk"}]}' > "$D/mods/quiet/mod.json"
echo '{"id": "cell", "version": "1.0.0", "wifi": {"home": true}}' > "$D/mods/cell/mod.json"
: > "$D/mods/dl/dl.apk"; : > "$D/mods/quiet/q.apk"

# ---- the engine: home Wi-Fi off (the default)
eng publish-state
check "publish runs" 0 $rc
check "every mod kept off cellular" "block cell
block dl
block quiet" "$(cat "$D/state/mods-net.txt")"
check "their services' groups" "cell 2950
dl 2950
quiet 2950" "$(cat "$D/state/service-gids.txt")"
has "a mod that asks, home Wi-Fi off: told" "dl: asks for home Wi-Fi, which is off" "$out"
has "the network keeper runs for it" "hmgapi net " "$(cat "$D/state/services.txt")"
hasnt "no home Wi-Fi keeper while it's off" "hmgapi wifi " "$(cat "$D/state/services.txt")"

# ---- the owner's switch on (the app's file), one mod allowed cellular
mkdir -p "$D/settings/allow"; : > "$D/settings/allow/home_wifi"
echo '{"overrides": {"allow_cellular": ["cell"]}}' > "$D/settings/hmgapi.json"
eng publish-state
check "home for the mod that asked, block for the one that didn't, allow for the override" "allow cell
home dl
block quiet
homewifi on" "$(cat "$D/state/mods-net.txt")"
check "groups: none for the allowed one, home and plain" "cell 0
dl 2951
quiet 2950" "$(cat "$D/state/service-gids.txt")"
has "allowing cellular warns" "WARNING: cell: may use the car's cellular link (overrides.allow_cellular): at your own risk" "$out"
has "the home Wi-Fi keeper runs, from boot" "hmgapi wifi root boot yes script /data/hmgapi/state/wifi.sh" "$(cat "$D/state/services.txt")"
check "its script written" yes "$([ -s "$D/state/wifi.sh" ] && echo yes || echo no)"
eng plan
has "the plan: home Wi-Fi on" "home Wi-Fi  on" "$out"
has "the plan: home Wi-Fi only for the mod that asked" "network  home Wi-Fi only (no cellular) [dl]" "$out"
has "the plan: the allowed one" "may use cellular (overrides.allow_cellular, at your own risk) [cell]" "$out"
has "the plan: the car's own Wi-Fi client feature, HMG-API's own" "android.hardware.wifi.xml [hmgapi]" "$out"
has "the vehicle service runs for it (parked, power)" "hmgapi vehicle " "$(cat "$D/state/services.txt")"
echo '{"id": "bad", "version": "1.0.0", "wifi": {"home": "yes"}}' > "$D/mods/cell/mod.json"
eng plan
has "a bad \"wifi\" ask refused" "\"home\": true}" "$out"
echo '{"id": "cell", "version": "1.0.0", "wifi": {"home": true}}' > "$D/mods/cell/mod.json"

# ---- the network keeper's mods section, against fake tools
F=$T/fake
mkdir -p "$F/bin" "$F/fw"
cat > "$F/bin/iptables" <<'S'
#!/bin/bash
# a fake iptables: one file per chain ($FW/<v>/<table>/<chain>), rules as given
v=4; [ "${0##*/}" = ip6tables ] && v=6
t=filter; a=()
while [ $# -gt 0 ]; do if [ "$1" = -t ]; then t=$2; shift 2; else a+=("$1"); shift; fi; done
set -- "${a[@]}"
[ $v = 6 ] && [ $t = nat ] && { echo "ip6tables: can't initialize table nat" >&2; exit 3; }
d=$FW/$v/$t; mkdir -p "$d"
stock() { case $1 in INPUT|OUTPUT|FORWARD|PREROUTING|POSTROUTING) return 0 ;; esac; return 1; }
op=$1 c=$2; shift 2
f=$d/$c
case $op in -A|-I|-D|-F|-N|-X) echo "ipt$v $t $op $c $*" >> "$FW/order.log" ;; esac
if ! stock "$c" && [ "$op" != -N ] && [ ! -f "$f" ]; then echo "iptables: No chain/target/match by that name." >&2; exit 1; fi
case $op in
    -S) if stock "$c"; then echo "-P $c ACCEPT"; else echo "-N $c"; fi
        [ -f "$f" ] && while IFS= read -r l; do echo "-A $c $l"; done < "$f"; exit 0 ;;
    -N) [ -f "$f" ] && { echo "iptables: Chain already exists." >&2; exit 1; }; : > "$f" ;;
    -F) : > "$f" ;;
    -X) grep -qx -- "-j $c" "$d"/* 2>/dev/null && { echo "iptables: Too many links." >&2; exit 1; }; rm -f "$f" ;;
    -C) grep -qx -- "$*" "$f" 2>/dev/null ;;
    -D) grep -qx -- "$*" "$f" 2>/dev/null || exit 1
        awk -v r="$*" '!done && $0 == r { done = 1; next } { print }' "$f" > "$f.new"; mv "$f.new" "$f" ;;
    -I) shift; { echo "$*"; cat "$f" 2>/dev/null; } > "$f.new"; mv "$f.new" "$f" ;;
    -A) echo "$*" >> "$f" ;;
esac
S
cp "$F/bin/iptables" "$F/bin/ip6tables"
cat > "$F/bin/ip" <<'S'
#!/bin/bash
# a fake ip: routes as lines in ip4.route ("<route> table <t>"; main has no "table"), rules as "<prio>:\t<rest>"
v=4; case $1 in -4) shift ;; -6) v=6; shift ;; esac
o=$1 c=$2; shift 2
f=$FW/ip$v.$o; touch "$f"
rkey() {   # "<table> <destination> <metric>" of a route line
    set -- $1; case $1 in unicast|unreachable|blackhole|prohibit|throw|local) shift ;; esac
    dst=$1; [ "$dst" = default ] && dst=0.0.0.0/0; case $dst in */*) ;; *) dst=$dst/32 ;; esac
    m=0 tb=main; while [ $# -gt 0 ]; do case $1 in metric|priority) m=$2 ;; table) tb=$2 ;; esac; shift; done
    echo "$tb $dst $m"
}
case $o.$c in
    route.show) if [ "$1" = dev ]; then grep -- " dev $2 .*table main\$" "$f" | sed 's/ table main$//' || true
        elif [ "$1" = table ]; then grep -- " table $2\$" "$f" | sed "s/ table $2\$//" || true; else grep -- " table main\$" "$f" || true; fi ;;
    route.replace|route.add) n=$#; if [ "${@:$((n-1)):1}" = table ]; then tb=${!n}; set -- "${@:1:$((n-2))}"; else tb=main; fi
        # as the kernel: a route is its table, destination and metric; its type (unreachable, ...) and
        # the rest don't count. replace swaps the one with that key; add fails if there is one
        k=$(rkey "$* table $tb")
        while IFS= read -r l; do [ "$(rkey "$l")" = "$k" ] && found=1 || echo "$l"; done < "$f" > "$f.new"
        if [ $c = add ] && [ -n "$found" ]; then rm "$f.new"; echo "RTNETLINK answers: File exists" >&2; exit 2; fi
        echo "ip $c $* table $tb" >> "$FW/order.log"
        mv "$f.new" "$f"; echo "$* table $tb" >> "$f" ;;
    route.del) grep -q -e "^$1 .*dev $3 .*table main\$" -e "^$1 dev $3 .*table main\$" "$f" || exit 2
        awk -v r="$1" -v d="$3" '!x && $1 == r && index($0, " dev " d " ") { x = 1; next } { print }' "$f" > "$f.new"; mv "$f.new" "$f" ;;
    route.flush) grep -v -- " table $2\$" "$f" > "$f.new"; mv "$f.new" "$f" ;;
    route.get) [ "$2" = oif ] && grep -q "^10\.1\." <<< "$1" && echo "$1 dev $3" ;;
    addr.flush) : ;;
    rule.show) echo "0:	from all lookup local"; cat "$f" ;;
    rule.add) p=$2; shift 2; printf '%s:\t%s\n' "$p" "$*" >> "$f"; echo "ip rule add $p $*" >> "$FW/order.log" ;;
    rule.del) grep -q "^$2:" "$f" || exit 2; grep -v "^$2:" "$f" > "$f.new"; mv "$f.new" "$f" ;;
esac
S
cat > "$F/bin/getprop" <<'S'
#!/bin/sh
cat "$FW/props/$1" 2>/dev/null
S
cat > "$F/bin/setprop" <<'S'
#!/bin/sh
mkdir -p "$FW/props"; printf '%s' "$2" > "$FW/props/$1"
S
printf '#!/bin/sh\necho "$*" >> "$FW/ndc.log"\n' > "$F/bin/ndc"
cat > "$F/bin/service" <<'S'
#!/bin/sh
# toolbox's service: Android's Wi-Fi service is there once $FW/wifisvc is
[ "$1 $2" = "check wifi" ] || exit 1
if [ -f "$FW/wifisvc" ]; then echo "Service wifi: found"; else echo "Service wifi: not found"; fi
S
chmod +x "$F/bin/"*
export FW=$F/fw
keeper() { HMGAPI_PACKAGES=$F/packages.list PATH=$F/bin:$PATH $UNIT_SH "$D/state/net.sh" "$@" 2>&1; }
ipt() { PATH=$F/bin:$PATH iptables "$@"; }
printf 'com.example.dl 10071 0 /data/data/com.example.dl default 3003\ncom.example.quiet 10072 0 /data/data/com.example.quiet default 3003\n' > "$F/packages.list"
printf 'com.example.dl dl\ncom.example.quiet quiet\n' > "$D/state/apps.txt"

: > "$F/fw/order.log"
log=$(keeper once)
# the order it's built in: no moment with the IPv4 home REJECTs gone and nothing else in their place
at() { grep -n -m1 -F -- "$1" "$F/fw/order.log" | cut -d: -f1; }
cell=$(at "ipt4 filter -I OUTPUT 1 -j HMGAPI_CELL")
check "built first: the post-reroute block, the mark, masquerade, table 95, the fwmark rule" yes "$(for l in "ipt4 mangle -A HMGAPI_HOMECELL -o rmnet+" "ipt4 mangle -I POSTROUTING 1 -j HMGAPI_HOMECELL" "ipt4 mangle -I OUTPUT 1 -j HMGAPI_HOME" "ipt4 nat -I POSTROUTING 1 -j HMGAPI_HOMEOUT" "ip add unreachable default table 95" "ip rule add 900 fwmark"; do a=$(at "$l"); [ -n "$a" ] && [ "$a" -lt "$cell" ] || { echo "no: $l"; exit; }; done; echo yes)"
check "... the cellular chain filled before OUTPUT jumps to it" yes "$([ "$(at "ipt4 filter -A HMGAPI_CELL -o rmnet+ -m owner --uid-owner 10072")" -lt "$cell" ] && echo yes || echo no)"
check "... marks before the jump to them" yes "$([ "$(at "ipt4 mangle -A HMGAPI_HOME -m owner --uid-owner 10071")" -lt "$(at "ipt4 mangle -I OUTPUT 1 -j HMGAPI_HOME")" ] && echo yes || echo no)"
has "mods kept off cellular" "mods kept off cellular: 2 app(s) and every mod service, home Wi-Fi routing in" "$log"
check "the cellular chain first in OUTPUT" "-A OUTPUT -j HMGAPI_CELL" "$(ipt -S OUTPUT | grep -m1 '^-A')"
check "its IPv4 rules: the plain group and the app that didn't ask (home ones: after the reroute)" "-o rmnet+ -m owner --gid-owner 2950 -j REJECT
-o rmnet+ -m owner --uid-owner 10072 -j REJECT" "$(cat "$F/fw/4/filter/HMGAPI_CELL")"
check "IPv6: all four (never routed to home Wi-Fi)" 4 "$(grep -c REJECT "$F/fw/6/filter/HMGAPI_CELL")"
check "home Wi-Fi traffic never out on mobile data, checked after the reroute" "-o rmnet+ -m mark --mark 0x2951 -j DROP" "$(cat "$F/fw/4/mangle/HMGAPI_HOMECELL")"
check "... first in POSTROUTING" "-j HMGAPI_HOMECELL" "$(head -1 "$F/fw/4/mangle/POSTROUTING")"
check "only the mod that asked marked for home Wi-Fi (and the home group)" "-m owner --gid-owner 2951 -j MARK --set-mark 0x2951
-m owner --uid-owner 10071 -j MARK --set-mark 0x2951" "$(cat "$F/fw/4/mangle/HMGAPI_HOME")"
check "masqueraded on wlan0 (home Wi-Fi: the car's own client)" "-o wlan0 -m mark --mark 0x2951 -j MASQUERADE" "$(cat "$F/fw/4/nat/HMGAPI_HOMEOUT")"
has "marked traffic to table 95" "900:	fwmark 0x2951 table 95" "$(cat "$F/fw/ip4.rule")"
check "both mods' apps look names up through wlan0, never cellular DNS" "resolver setifaceforuidrange wlan0 10071 10071
resolver setifaceforuidrange wlan0 10072 10072" "$(cat "$F/fw/ndc.log")"
check "all there: a check is quiet" "" "$(keeper once)"
ipt -t mangle -F HMGAPI_HOMECELL   # the jump left, the DROP in it gone
has "the post-reroute DROP gone (its jump still there): put back" "mods' cellular block put back" "$(keeper once)"
check "... the DROP again" "-o rmnet+ -m mark --mark 0x2951 -j DROP" "$(cat "$F/fw/4/mangle/HMGAPI_HOMECELL")"
ipt -I OUTPUT 1 -o rmnet+ -j ACCEPT   # the stock firewall puts an accept first
has "moved: put back first" "mods' cellular block put back" "$(keeper once)"
check "... first again" "-A OUTPUT -j HMGAPI_CELL" "$(ipt -S OUTPUT | grep -m1 '^-A')"
printf 'com.example.dl 10091 0 /data/data/com.example.dl default 3003\ncom.example.quiet 10072 0 /data/data/com.example.quiet default 3003\n' > "$F/packages.list"
keeper once >/dev/null
has "a reinstalled app's new uid" "--uid-owner 10091 -j REJECT" "$(cat "$F/fw/6/filter/HMGAPI_CELL")"
has "... marked with it" "--uid-owner 10091 -j MARK" "$(cat "$F/fw/4/mangle/HMGAPI_HOME")"
hasnt "... the old one gone" "10071" "$(cat "$F/fw/6/filter/HMGAPI_CELL" "$F/fw/4/mangle/HMGAPI_HOME")"
rm "$D/settings/allow/home_wifi"
eng publish-state
keeper once >/dev/null
check "home Wi-Fi off: no marks" no "$([ -f "$F/fw/4/mangle/HMGAPI_HOME" ] && echo yes || echo no)"
hasnt "... no routing rule" "900:" "$(cat "$F/fw/ip4.rule")"
check "... still off cellular: all four refused in OUTPUT again" 4 "$(grep -c REJECT "$F/fw/4/filter/HMGAPI_CELL")"
check "... no post-reroute block (nothing is marked)" no "$([ -f "$F/fw/4/mangle/HMGAPI_HOMECELL" ] && echo yes || echo no)"
FW=$F/fw PATH=$F/bin:$PATH ip route replace unreachable default table 95
keeper down >/dev/null
hasnt "down: the cellular chain gone" "HMGAPI_CELL" "$(ipt -S OUTPUT)"
check "... table 95 empty (the uninstaller; wifi.sh's, in case it couldn't)" "" "$(grep 'table 95' "$F/fw/ip4.route" || true)"
: > "$D/settings/allow/home_wifi"
eng publish-state

# ---- the home Wi-Fi keeper, against fake tools
X=$T/unit
mkdir -p "$X/data/misc/wifi/sockets" "$X/data/hmgapi/public" "$X/proc/sys/kernel/random" "$X/proc/sys/net/ipv4/conf/wlan0"
echo boot-1 > "$X/proc/sys/kernel/random/boot_id"
echo "1000.00 2000.00" > "$X/proc/uptime"
cat > "$F/bin/hmgapid" <<'S'
#!/bin/bash
case $1 in
    vehicle) v=$(cat "$FW/veh.$2" 2>/dev/null); [ -n "$v" ] && echo "$2 $v"; exit 0 ;;
    wpa) c=$3; echo "$c" >> "$FW/wpa.log"
        [ -f "$FW/client" ] || exit 1   # the car's client isn't running: no socket
        case $c in
            STATUS) cat "$FW/wpa.status" ;;
            SIGNAL_POLL) echo "RSSI=-61" ;;
            SCAN_RESULTS) printf 'bssid / frequency / signal level / flags / ssid\naa:aa\t2437\t-61\t[WPA2-PSK-CCMP][ESS]\tWillows\nbb:bb\t5180\t-70\t[WPA2-PSK-CCMP][ESS]\tOther\n' ;;
            *) echo OK ;;
        esac ;;
esac
S
cat > "$F/bin/app_process" <<'S'
#!/bin/bash
# WifiAp: the hotspot ($FW/ap: its mode) and the car's own client ($FW/client)
echo "app_process $*" >> "$FW/apps.log"
case "$3 $4" in
    "stop ") if [ -f "$FW/ap" ]; then cat "$FW/ap"; rm "$FW/ap"; else echo 0; fi ;;
    "start "*) echo "$4" > "$FW/ap"; echo "hotspot on" ;;
    "ensure-projection ")
        if [ "$(cat "$FW/props/init.svc.wpa_supplicant")" = running ]; then
            echo "the car's own Wi-Fi client is on or set to restore, left alone"
        elif [ -f "$FW/ap" ]; then
            echo "already up"
        else
            echo 2 > "$FW/ap"; echo "up in mode 2"
        fi ;;
    "client on") : > "$FW/client"; echo running > "$FW/props/init.svc.wpa_supplicant"; echo "client on" ;;
    "client off") rm -f "$FW/client"; echo stopped > "$FW/props/init.svc.wpa_supplicant"; echo "client off" ;;
    "crc "*) echo crc >> "$FW/crc.log"
        [ -f "$FW/crc.fail" ] && { echo "checksum not written: chown system:wifi: exit 1"; exit 1; }
        echo 01234567 ;;
esac
S
printf '#!/bin/sh\n:\n' > "$F/bin/chown"
chmod +x "$F/bin/"*
mkdir -p "$F/fw/props" "$X/data/hmgapi/state"
echo 2 > "$F/fw/ap"   # the projection hotspot is up
: > "$F/fw/wifisvc"   # Android's Wi-Fi service is up
cp "$D/state/wifi.txt" "$X/data/hmgapi/state/wifi.txt"
check "the keeper's asks: home Wi-Fi only" "home" "$(cat "$X/data/hmgapi/state/wifi.txt")"
# the keeper's loop replaced by N keeps
sed -e 's/^while :; do$/for _k in $(seq 1 ${KEEPS:-1}); do keep; done; exit 0/' "$D/state/wifi.sh" > "$D/state/wifi.sh.n"
wk() { r=0; HMGAPI_TEST_ROOT=$X KEEPS=${1:-1} PATH=$F/bin:$PATH $UNIT_SH "$D/state/wifi.sh.n" 2>&1 || r=$?
    [ $r = 0 ] || { echo "FAIL  the keeper exited $r, after: $last" >&2; fail=1; }; }
verb() { HMGAPI_TEST_ROOT=$X PATH=$F/bin:$PATH $UNIT_SH "$D/state/wifi.sh" "$@" 2>&1; }
pub() { cat "$X/data/hmgapi/public/wifi.json" 2>/dev/null; }
state() { pub | sed 's/.*"state": "\([a-z]*\)".*/\1/'; }
NET=$X/data/misc/wifi/wpa_supplicant.conf

echo on > "$F/fw/veh.power"; echo 0 > "$F/fw/veh.parked"
wk >/dev/null
check "the car on: waits for parked" waiting "$(state)"
check "... the hotspot left as it is" 2 "$(cat "$F/fw/ap")"
echo delay_mode > "$F/fw/veh.power"; echo 1 > "$F/fw/veh.parked"
wk >/dev/null
check "parked with no saved networks: nothing switched" nonetworks "$(state)"
check "... the hotspot still up" 2 "$(cat "$F/fw/ap")"

# the app's verbs, on Android's own config
cat > "$NET" <<'C'
ctrl_interface=wlan0
update_config=1
p2p_disabled=1

network={
	ssid="Willows"
	psk="secret-not-shown"
	key_mgmt=WPA-PSK
}
C
check "saved: the names" "Willows" "$(verb saved)"
out=$(HMGAPI_WIFI_PSK=short verb join "Cascadia" || true)
check "join: a key under 8 characters refused" "a Wi-Fi key is 8 to 63 characters" "$out"
out=$(HMGAPI_WIFI_PSK='has"quote' verb join "Cascadia" || true)
has "join: a key that could break the config refused" "can't be used" "$out"
out=$(HMGAPI_WIFI_PSK= verb join 'Bad"Name' || true)
has "join: a name that could break the config refused" "can't be used" "$out"
: > "$F/fw/crc.log"
check "join: saved" "saved: Cascadia" "$(HMGAPI_WIFI_PSK=cascadia-key verb join Cascadia)"
check "... the checksum the car's Wi-Fi library checks, updated" crc "$(cat "$F/fw/crc.log")"
check "... both now" "Willows
Cascadia" "$(verb saved)"
has "... Android's own settings kept" "p2p_disabled=1" "$(cat "$NET")"
has "... its key in the config, nothing printed" 'psk="cascadia-key"' "$(cat "$NET")"
HMGAPI_WIFI_PSK=new-willows-key verb join Willows >/dev/null
check "join again: replaced, not doubled" 1 "$(grep -c 'ssid="Willows"' "$NET")"
has "... with its new key" 'psk="new-willows-key"' "$(cat "$NET")"
check "an open network" "saved: Cafe" "$(HMGAPI_WIFI_PSK= verb join Cafe)"
check "forget" "forgotten: Cafe" "$(verb forget Cafe)"
check "... gone, the others kept" "Cascadia
Willows" "$(verb saved | sort)"
check "seen: nothing before home Wi-Fi was on" "" "$(verb seen)"
: > "$F/fw/crc.fail"
out=$(HMGAPI_WIFI_PSK= verb join Cafe)
has "join: a checksum that can't be written is said" "the car's Wi-Fi checksum not updated, so its Wi-Fi client may not start: checksum not written: chown" "$out"
has "... the network still saved" "saved: Cafe" "$out"
verb forget Cafe >/dev/null; rm "$F/fw/crc.fail"
mv "$NET" "$NET.keep"; mkdir -p "$X/system/etc/wifi"; printf 'update_config=1\nctrl_interface=wlan0\npmf=1\n' > "$X/system/etc/wifi/wpa_supplicant.conf"
# put_net's chown says whether a config was already there: join writes nothing of its own before put_net
printf '#!/bin/sh\n[ -n "$NETCHK" ] && { [ -e "$NETCHK" ] && echo there || echo none; } >> "$FW/net.before"\nexit 0\n' > "$F/bin/chown"
: > "$F/fw/net.before"
NETCHK=$NET HMGAPI_WIFI_PSK=first-net-key verb join First >/dev/null
check "join with no config yet: no config of ours before put_net" none "$(cat "$F/fw/net.before")"
printf '#!/bin/sh\n:\n' > "$F/bin/chown"
has "join with no config yet: started from the car's template" "pmf=1" "$(cat "$NET")"
has "... with the network" 'ssid="First"' "$(cat "$NET")"
check "... the template's lines first, as the car's library writes them" "update_config=1" "$(sed -n 1p "$NET")"
check "... Android's mode" "-rw-rw----" "$(ls -l "$NET" | cut -c1-10)"
mv "$NET.keep" "$NET"

# parked: the hotspot off, the car's client on
echo "wpa_state=SCANNING" > "$F/fw/wpa.status"
: > "$F/fw/apps.log"
: > "$F/fw/crc.fail"
log=$(wk)
rm "$F/fw/crc.fail"
has "parked: switched at once" "the car off: the hotspot off, the car's Wi-Fi client on" "$log"
has "... the checksum checked before the client goes on" crc "$(cat "$F/fw/crc.log")"
has "... a checksum that can't be written: in the keeper's log, not just \"searching\"" "the car's Wi-Fi checksum not updated" "$log"
check "... the hotspot off" no "$([ -f "$F/fw/ap" ] && echo yes || echo no)"
has "... through Android's Wi-Fi service" "hmgapi.engine.WifiAp client on" "$(cat "$F/fw/apps.log")"
check "... looking" searching "$(state)"
out=$(HMGAPI_WIFI_PSK=some-new-key verb join Other || true)
has "while parked and on: networks wait for the car to be on" "add networks while the car is on" "$out"

# joined: the car's own traffic on mobile data, the mods that asked on home Wi-Fi
printf 'wpa_state=COMPLETED\nfreq=2437\n' > "$F/fw/wpa.status"
FW=$F/fw PATH=$F/bin:$PATH ip route replace default via 10.4.0.1 dev wlan0     # Android's, when Wi-Fi is default
FW=$F/fw PATH=$F/bin:$PATH ip route replace 10.4.0.0/16 dev wlan0 proto kernel
printf 10.10.10.100 > "$F/fw/props/dhcp.wlan0.dns1"
: > "$F/fw/ndc.log"
log=$(wk)
has "up" "home Wi-Fi up: the mods that asked go through it; the car stays on mobile data" "$log"
check "... the update check started, once" "update check" "$(cat "$X/data/hmgapi/wifi/update-check.test")"
has "... after wifi.json says up (the engine reads it to know it's online)" '"state": "up"' "$(cat "$X/data/hmgapi/wifi/update-check.pub")"
mkdir -p "$X/proc/sys/net/ipv4/conf/wlan0"; wk >/dev/null
check "... not again while still up" 1 "$(grep -c . "$X/data/hmgapi/wifi/update-check.test")"
check "status: up, signal, channel, no network name" '{"state": "up", "rssi": -61, "freq": 2437}' "$(pub)"
hasnt "the car's default route back off Wi-Fi" "default via 10.4.0.1 dev wlan0 table main" "$(cat "$F/fw/ip4.route")"
has "table 95: Wi-Fi's default" "default via 10.4.0.1 dev wlan0 table 95" "$(cat "$F/fw/ip4.route")"
has "table 95: the subnet" "10.4.0.0/16 dev wlan0 table 95" "$(cat "$F/fw/ip4.route")"
has "the car's own lookups on its carrier's DNS" "resolver setdefaultif rmnet0" "$(cat "$F/fw/ndc.log")"
has "wlan0's DNS servers for the mods" "resolver setifdns wlan0  10.10.10.100" "$(cat "$F/fw/ndc.log")"
has "a DNS server off the subnet: through the gateway" "10.10.10.100/32 via 10.4.0.1 dev wlan0 metric 1000" "$(cat "$F/fw/ip4.route")"
check "mods' services' DNS to the home router's" "-m owner --gid-owner 2951 -p udp --dport 53 -j DNAT --to-destination 10.10.10.100
-m owner --gid-owner 2951 -p tcp --dport 53 -j DNAT --to-destination 10.10.10.100" "$(cat "$F/fw/4/nat/HMGAPI_HOMEDNS")"
check "services' lookups (their resolver asks 127.0.0.1) may come back in through wlan0: the DNS server's answers only" "-i wlan0 -s 10.10.10.100/32 -d 127.0.0.0/8 -p udp --sport 53 -m state --state ESTABLISHED -j RETURN
-i wlan0 -s 10.10.10.100/32 -d 127.0.0.0/8 -p tcp --sport 53 -m state --state ESTABLISHED -j RETURN
-i wlan0 -d 127.0.0.0/8 -j DROP" "$(cat "$F/fw/4/filter/HMGAPI_HOMELO")"
check "... first in INPUT" "-j HMGAPI_HOMELO" "$(head -1 "$F/fw/4/filter/INPUT")"
check "... route_localnet on, behind it" 1 "$(cat "$X/proc/sys/net/ipv4/conf/wlan0/route_localnet")"
check "table 95: Wi-Fi's default replaced the unreachable one (one default, as the kernel keeps)" "default via 10.4.0.1 dev wlan0" "$(FW=$F/fw PATH=$F/bin:$PATH ip route show table 95 | grep default)"
# the stock firewall restarts (iptrules: INPUT flushed, its whitelist jump back), the lease the same
: > "$F/fw/4/filter/INPUT"; ipt -N WLIST_IN 2>/dev/null || true; ipt -A INPUT -j WLIST_IN
log=$(wk)
has "the stock firewall reloaded, the lease unchanged: the guard put back" "home Wi-Fi's 127.x guard put back" "$log"
check "... first in INPUT again" "-j HMGAPI_HOMELO" "$(head -1 "$F/fw/4/filter/INPUT")"
check "... the stock jump kept, after it" "-j WLIST_IN" "$(sed -n 2p "$F/fw/4/filter/INPUT")"
check "... route_localnet still on" 1 "$(cat "$X/proc/sys/net/ipv4/conf/wlan0/route_localnet")"
ipt -I INPUT 1 -j WLIST_IN   # the stock jump put first, ours still there
wk >/dev/null
check "not first any more: moved back first" "-j HMGAPI_HOMELO" "$(head -1 "$F/fw/4/filter/INPUT")"
check "... once" 1 "$(grep -c HMGAPI_HOMELO "$F/fw/4/filter/INPUT")"
check "all in place: the next pass is quiet" "" "$(wk)"
ipt -F INPUT; ipt -N HMGAPI_BROKEN 2>/dev/null; printf '#!/bin/bash\ncase "$*" in *"-I INPUT 1 -j HMGAPI_HOMELO"*) exit 1 ;; esac\nexec "$(dirname "$0")/iptables.real" "$@"\n' > "$F/bin/iptables.wrap"
mv "$F/bin/iptables" "$F/bin/iptables.real"; mv "$F/bin/iptables.wrap" "$F/bin/iptables"; chmod +x "$F/bin/iptables"
log=$(wk)
has "the guard can't go in: said" "home Wi-Fi's 127.x guard can't be put in" "$log"
check "... route_localnet off" 0 "$(cat "$X/proc/sys/net/ipv4/conf/wlan0/route_localnet")"
mv "$F/bin/iptables.real" "$F/bin/iptables"
wk >/dev/null
check "... and on once it can" 1 "$(cat "$X/proc/sys/net/ipv4/conf/wlan0/route_localnet")"
has "what it saw, for the app" "2437	-61	[WPA2-PSK-CCMP][ESS]	Willows" "$(verb seen)"
hasnt "the key never in the log" "secret" "$log"
FW=$F/fw PATH=$F/bin:$PATH ip route replace default via 10.4.0.1 dev wlan0   # Android puts it back (a renewal)
wk >/dev/null
hasnt "... taken out again" "default via 10.4.0.1 dev wlan0 table main" "$(cat "$F/fw/ip4.route")"

# the car on again
echo on > "$F/fw/veh.power"; echo 0 > "$F/fw/veh.parked"
: > "$F/fw/apps.log"
log=$(wk)
has "the car on: back" "back to the car's Wi-Fi (the car is on): the car's Wi-Fi client off, the hotspot back" "$log"
check "... the projection hotspot, in its mode" 2 "$(cat "$F/fw/ap")"
check "... client off, then the hotspot" "client off
start 2" "$(grep -o 'client off\|start [12]' "$F/fw/apps.log")"
check "... table 95: unreachable, never the main table's mobile data" "unreachable default table 95" "$(grep 'table 95' "$F/fw/ip4.route" || true)"
check "... route_localnet off again" 0 "$(cat "$X/proc/sys/net/ipv4/conf/wlan0/route_localnet")"
check "... the 127.x guard gone" no "$([ -f "$F/fw/4/filter/HMGAPI_HOMELO" ] && echo yes || echo no)"
check "... waiting again" waiting "$(state)"

# the car off without parked (the screen goes dark with it): 30 s, then home Wi-Fi
echo adm > "$F/fw/veh.power"; echo 0 > "$F/fw/veh.parked"
echo "2000.00 2000.00" > "$X/proc/uptime"
wk >/dev/null
check "the car just off: not yet (a stop for a moment)" 2 "$(cat "$F/fw/ap")"
echo "2020.00 2000.00" > "$X/proc/uptime"
wk >/dev/null
check "... 20 s: not yet" 2 "$(cat "$F/fw/ap")"
echo on > "$F/fw/veh.power"; wk >/dev/null; echo adm > "$F/fw/veh.power"
echo "2040.00 2000.00" > "$X/proc/uptime"
wk >/dev/null
check "on and off again: the 30 s start over" 2 "$(cat "$F/fw/ap")"
echo "2071.00 2000.00" > "$X/proc/uptime"
has "off 30 s: switched" "the car off: the hotspot off" "$(wk)"
check "... the hotspot off" no "$([ -f "$F/fw/ap" ] && echo yes || echo no)"
echo on > "$F/fw/veh.power"
has "on again: back at once" "back to the car's Wi-Fi (the car is on): the car's Wi-Fi client off, the hotspot back" "$(wk)"

# on request (the app's "Connect now"; later the updater): in P, with the car on
echo on > "$F/fw/veh.power"; echo 0 > "$F/fw/veh.parked"; echo 0 > "$F/fw/veh.gear.park"
wk >/dev/null
out=$(verb now 5 || true)
has "now: refused out of P" "shift to P first" "$out"
echo 1 > "$F/fw/veh.gear.park"
check "now: minutes checked" "minutes: 1 to 240, or 0 for no time limit" "$(verb now 999 || true)"
has "now: in P" "home Wi-Fi on for 5 minutes" "$(verb now 5)"
has "... switched on the next check" "on request: the hotspot off, the car's Wi-Fi client on" "$(wk)"
check "... the hotspot off" no "$([ -f "$F/fw/ap" ] && echo yes || echo no)"
wk >/dev/null
check "... stays while the car is on, in P" no "$([ -f "$F/fw/ap" ] && echo yes || echo no)"
echo 0 > "$F/fw/veh.gear.park"
has "out of P: back at once" "out of P: the car's Wi-Fi client off, the hotspot back" "$(wk)"
check "... the hotspot" 2 "$(cat "$F/fw/ap")"
echo 1 > "$F/fw/veh.gear.park"
verb now 1 >/dev/null; wk >/dev/null
verb back >/dev/null
has "back: the hotspot again" "the car's Wi-Fi client off, the hotspot back" "$(wk)"
verb now 1 >/dev/null; wk >/dev/null
echo "2400.00 2000.00" > "$X/proc/uptime"
has "the time up: back" "the car's Wi-Fi client off, the hotspot back" "$(wk)"
check "... the request gone" no "$([ -f "$X/data/hmgapi/wifi/manual" ] && echo yes || echo no)"
has "now 030: thirty minutes, not octal" "home Wi-Fi on for 30 minutes" "$(verb now 030)"
check "... ends 30 minutes on" "$(($(read -r u r < "$X/proc/uptime"; echo ${u%.*}) + 1800))" "$(read -r b m < "$X/data/hmgapi/wifi/manual"; echo "$m")"
verb back >/dev/null; wk >/dev/null
has "now 0: no time limit" "home Wi-Fi on until you shift out of P" "$(verb now 0)"
has "... switched on" "on request: the hotspot off, the car's Wi-Fi client on" "$(wk)"
echo "99999.00 2000.00" > "$X/proc/uptime"
wk >/dev/null
check "... still on hours later, in P" no "$([ -f "$F/fw/ap" ] && echo yes || echo no)"
echo 0 > "$F/fw/veh.gear.park"
has "... out of P: back" "out of P: the car's Wi-Fi client off, the hotspot back" "$(wk)"
check "... the request gone" no "$([ -f "$X/data/hmgapi/wifi/manual" ] && echo yes || echo no)"
echo 1 > "$F/fw/veh.gear.park"
echo "2400.00 2000.00" > "$X/proc/uptime"
mv "$NET" "$NET.keep"; check "now with no saved networks: refused" "no saved networks: add one first" "$(verb now 5 || true)"; mv "$NET.keep" "$NET"

# a restart while parked: Android remembers Wi-Fi on; the hotspot comes back first
echo delay_mode > "$F/fw/veh.power"; echo 1 > "$F/fw/veh.parked"
wk >/dev/null
echo boot-2 > "$X/proc/sys/kernel/random/boot_id"; echo on > "$F/fw/veh.power"; echo 0 > "$F/fw/veh.parked"
has "after a restart: HMG-API's client from before put back to the hotspot" "after a restart while home Wi-Fi was on" "$(wk)"
check "... the hotspot" 2 "$(cat "$F/fw/ap")"

# the car's own Wi-Fi settings, not HMG-API: left alone
echo running > "$F/fw/props/init.svc.wpa_supplicant"
wk >/dev/null
check "the car's own client (its settings): left alone" stock "$(state)"
echo stopped > "$F/fw/props/init.svc.wpa_supplicant"

# home Wi-Fi turned off while parked and on: the hotspot back
echo delay_mode > "$F/fw/veh.power"; echo 1 > "$F/fw/veh.parked"
wk >/dev/null
: > "$F/fw/apps.log"
verb down >/dev/null
check "down while parked: the hotspot back" 2 "$(cat "$F/fw/ap")"
check "... the client off" no "$([ -f "$F/fw/client" ] && echo yes || echo no)"
check "... table 95's unreachable default out too (home Wi-Fi off, the uninstaller)" "" "$(grep 'table 95' "$F/fw/ip4.route" || true)"

# ---- the projection AP ("wifi": {"projection": true}): the engine
rm -f "$D/settings/allow/home_wifi" "$D/settings/hmgapi.json"
mkdir -p "$D/mods/aa"
echo '{"id": "aa", "version": "1.0.0", "wifi": {"projection": true}}' > "$D/mods/aa/mod.json"
eng publish-state
check "publish with a projection ask runs" 0 $rc
check "the keeper's asks: the projection AP for aa, no home Wi-Fi" "projection aa" "$(cat "$D/state/wifi.txt")"
has "the keeper runs without home Wi-Fi, from boot" "hmgapi wifi root boot yes script /data/hmgapi/state/wifi.sh" "$(cat "$D/state/services.txt")"
has "... the vehicle service runs anyway (the gear check)" "hmgapi vehicle " "$(cat "$D/state/services.txt")"
eng plan
has "the plan: the projection AP" "wifi     the projection AP up from boot, once each boot, unless a Wi-Fi client is on [aa]" "$out"
has "the plan: the hotspot feature comes with it (the chip powered)" "com.hkmc.software.wifihotspot" "$out"
has "the plan: home Wi-Fi still off" "home Wi-Fi  off" "$out"
mkdir -p "$D/mods/bad-wifi"
echo '{"id": "bad-wifi", "version": "1.0.0", "wifi": {"projection": true, "hotspot": "yes"}}' > "$D/mods/bad-wifi/mod.json"
eng publish-state
check "a malformed Wi-Fi request doesn't break state publication" 0 $rc
check "only the valid projection request is published" "projection aa" "$(cat "$D/state/wifi.txt")"
rm -rf "$D/mods/bad-wifi"

# ---- the projection AP: the keeper
cp "$D/state/wifi.txt" "$X/data/hmgapi/state/wifi.txt"
echo boot-3 > "$X/proc/sys/kernel/random/boot_id"
rm -f "$F/fw/ap" "$F/fw/wifisvc" "$X/data/hmgapi/wifi/mode"
echo stopped > "$F/fw/props/init.svc.wpa_supplicant"
: > "$F/fw/apps.log"
wk >/dev/null
check "from boot, no Wi-Fi service yet: nothing tried" "" "$(cat "$F/fw/apps.log")"
: > "$F/fw/wifisvc"
log=$(wk)
has "the Wi-Fi service there: the projection AP up" "projection AP: up in mode 2 (requested by aa)" "$log"
check "... in mode 2 (connectivity)" 2 "$(cat "$F/fw/ap")"
check "... home Wi-Fi off in the status" off "$(state)"
rm "$F/fw/ap"; : > "$F/fw/apps.log"
wk >/dev/null
check "... once a boot: the car turning it off later is left alone" "" "$(cat "$F/fw/apps.log")"
echo boot-4 > "$X/proc/sys/kernel/random/boot_id"
echo 1 > "$F/fw/ap"
has "a new boot, the plain hotspot already up: left as it is" "projection AP: already up" "$(wk)"
check "... still the plain one" 1 "$(cat "$F/fw/ap")"
echo boot-5 > "$X/proc/sys/kernel/random/boot_id"
rm "$F/fw/ap"; echo running > "$F/fw/props/init.svc.wpa_supplicant"
has "the car's own Wi-Fi client on: left alone" "the car's own Wi-Fi client is on or set to restore, left alone" "$(wk)"
check "... no AP" no "$([ -f "$F/fw/ap" ] && echo yes || echo no)"
echo stopped > "$F/fw/props/init.svc.wpa_supplicant"
echo boot-6 > "$X/proc/sys/kernel/random/boot_id"
mv "$F/bin/app_process" "$F/bin/app_process.ok"
printf '#!/bin/sh\necho "app_process $*" >> "$FW/apps.log"; echo "projection AP didn\x27t come on"; exit 1\n' > "$F/bin/app_process"; chmod +x "$F/bin/app_process"
has "it doesn't come on: said once" "projection AP: not up yet, trying again" "$(wk)"
hasnt "... and not again" "not up yet" "$(wk)"
mv "$F/bin/app_process.ok" "$F/bin/app_process"
has "... tried again, up" "projection AP: up in mode 2 (requested by aa)" "$(wk)"
# QuickBoot: the same boot resumes; the keeper's sleep took far longer than asked (/proc/uptime counts
# the suspend). A fake sleep moves the clock: by what it was asked, or by 400 s more (a suspend).
mkdir -p "$F/sleepbin"
printf '#!/bin/sh\necho "$1" >> "$FW/sleeps"; read -r u r < "%s/proc/uptime"; u=${u%%.*}; echo "$((u + $1 + ${SUSPEND:-0})).00 0" > "%s/proc/uptime"\n' "$X" "$X" > "$F/sleepbin/sleep"; chmod +x "$F/sleepbin/sleep"
sed -e 's/^while :; do$/for _k in $(seq 1 ${KEEPS:-1}); do keep; tick; done; keep; exit 0/' "$D/state/wifi.sh" > "$D/state/wifi.sh.t"
wt() { r=0; HMGAPI_TEST_ROOT=$X FW=$F/fw KEEPS=${1:-1} SUSPEND=${SUSPEND:-0} PATH=$F/sleepbin:$F/bin:$PATH $UNIT_SH "$D/state/wifi.sh.t" 2>&1 || r=$?
    [ $r = 0 ] || { echo "FAIL  the keeper exited $r, after: $last" >&2; fail=1; }; }
rm "$F/fw/ap"; : > "$F/fw/apps.log"
log=$(wt)
hasnt "a plain sleep, the AP off since: still the car's choice" "projection AP" "$log"
check "... not started" no "$([ -f "$F/fw/ap" ] && echo yes || echo no)"
SUSPEND=400; log=$(wt); SUSPEND=0
has "back from a QuickBoot suspend: said" "back from a suspend" "$log"
has "... the projection AP up again" "projection AP: up in mode 2 (requested by aa)" "$log"
check "... in mode 2" 2 "$(cat "$F/fw/ap")"
echo boot-6b > "$X/proc/sys/kernel/random/boot_id"
rm "$F/fw/ap"; : > "$F/fw/sleeps"
mv "$F/bin/app_process" "$F/bin/app_process.ok"
printf '#!/bin/sh\necho "app_process $*" >> "$FW/apps.log"; echo "projection AP didn\x27t come on"; exit 1\n' > "$F/bin/app_process"; chmod +x "$F/bin/app_process"
wt 31 >/dev/null
check "it keeps not coming on: every 2 s for about a minute" 2 "$(sed -n 29p "$F/fw/sleeps")"
check "... then every 30 s" 30 "$(sed -n 30p "$F/fw/sleeps")"
mv "$F/bin/app_process.ok" "$F/bin/app_process"
has "... and up once it can" "projection AP: up in mode 2 (requested by aa)" "$(wt)"
# with home Wi-Fi on too: home Wi-Fi has the radio while parked, and brings the AP back itself
: > "$D/settings/allow/home_wifi"
eng publish-state
check "both asks" "home
projection aa" "$(cat "$D/state/wifi.txt")"
cp "$D/state/wifi.txt" "$X/data/hmgapi/state/wifi.txt"
echo boot-7 > "$X/proc/sys/kernel/random/boot_id"
echo delay_mode > "$F/fw/veh.power"; echo 1 > "$F/fw/veh.parked"
echo 2 > "$F/fw/ap"
log=$(wk 2)
has "parked: home Wi-Fi switches as before" "the car off: the hotspot off, the car's Wi-Fi client on" "$log"
check "... the AP stays off while the client is on" no "$([ -f "$F/fw/ap" ] && echo yes || echo no)"
echo on > "$F/fw/veh.power"; echo 0 > "$F/fw/veh.parked"
has "the car on: the AP back (home Wi-Fi's)" "the hotspot back" "$(wk)"
check "... mode 2" 2 "$(cat "$F/fw/ap")"
rm -rf "$D/mods/aa" "$D/settings/allow/home_wifi"
eng publish-state
check "no asks: no keeper" "" "$(grep 'hmgapi wifi ' "$D/state/services.txt" || true)"
check "... its asks file empty" "" "$(cat "$D/state/wifi.txt")"

done=1
[ $fail = 0 ] && echo "all home Wi-Fi checks passed"
exit $fail
