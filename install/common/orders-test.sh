#!/bin/sh
# Every order of events on a made-up head unit (tools/zygote-orders.py): this tree's install.sh
# and uninstall.sh, with older releases' from their git tags, NEMESIS 1.17 installed and
# uninstalled, restarts, firmware installs and factory resets, and installs with a copy cut off.
# None may end with a zygote that can't start because of what this tree's scripts did (#397).
# By default 3 events deep (where #397's K5 is) and the cut-off pass from four starting points with
# the installer killed at the copy: a minute or two. ORDERS_FULL=1, before a release: 7 deep, every
# starting point, the copy failing as well as the installer killed (half an hour on 8 CPUs).
# ORDERS_DEPTH sets the depth by itself.
# Needs the release tags (git fetch --tags): skips without them. No build.
cd "$(dirname "$0")/../.."
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
Z="python3 tools/zygote-orders.py"
D=${ORDERS_DEPTH:-${ORDERS_FULL:+7}}; D=${D:-3}

out=$($Z --stock known --order restart 2>&1); rc=$?
if [ $rc = 3 ]; then echo "skip  $out"; exit 0; fi
has "a unit as shipped starts" "starts  a build stock.txt knows: restart" "$out"

# the checker sees what it is for: #397's K5 with rc.9's own scripts, and the first #397 (an older
# uninstall under NEMESIS's script)
out=$($Z --stock known --order "nemesis force-install@v1.0.2 restart install@v1.1.0-rc.9" 2>&1)
has "NEMESIS, a forced 1.0.2, rc.9's update: dead (wrapper and script run each other)" "app_process.hmgapi-stock (nemesis-script) -> app_process.real (wrapper): a loop" "$out"
out=$($Z --stock known --order "install@v1.0.2 nemesis uninstall" 2>&1)
has "1.0.2, NEMESIS, 1.0.2's uninstall: dead (the wrapper's stock copy is gone)" "app_process.hmgapi-stock (none): can't run" "$out"
# ... and this tree's scripts on the same units
out=$($Z --stock known --order "nemesis force-install@v1.0.2 restart install restart uninstall restart" 2>&1)
has "NEMESIS, a forced 1.0.2, this install, this uninstall: starts" "starts  a build stock.txt knows: nemesis > force-install@v1.0.2 > restart > install > restart > uninstall > restart" "$out"
out=$($Z --stock known --order "nemesis force-install@v1.0.2 restart nemesis-uninstall install uninstall" 2>&1)
has "... with NEMESIS uninstalled in between: starts, nothing refused" "starts  a build stock.txt knows: nemesis > force-install@v1.0.2 > restart > nemesis-uninstall > install > uninstall" "$out"
out=$($Z --stock known --order "install@v1.0.2 nemesis uninstall install restart" 2>&1)
has "1.0.2's uninstall under NEMESIS, then this install before the restart: starts" "starts  a build stock.txt knows: install@v1.0.2 > nemesis > uninstall > install > restart" "$out"

out=$($Z --pass orders --depth "$D" 2>&1); rc=$?
check "no order of $D events ends dead by this tree's install or uninstall" 0 $rc
[ $rc = 0 ] || echo "$out"
if [ -n "$ORDERS_FULL" ]; then out=$($Z --pass cut-off 2>&1); rc=$?; else out=$($Z --pass cut-off --cut kill --quick 2>&1); rc=$?; fi
check "an install with a copy cut off, the install again, each uninstall: none ends dead" 0 $rc
[ $rc = 0 ] || echo "$out"
exit $fail
