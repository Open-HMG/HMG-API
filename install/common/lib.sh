# HMG-API installer: shared functions, sourced by install/uninstall on the head unit.
# mksh-safe: the unit has no sed, head, tail, wc or awk. ROOT prefixes paths in tests.
ROOT=${ROOT:-}
HOOK_BEGIN="# HMGAPI_HOOK begin"
HOOK_END="# HMGAPI_HOOK end"

# install.sh's and uninstall.sh's: a line to the screen and $LOG; an md5; give up (putting
# /system back to read-only first when the script set REMOUNTED; each script has its own remount)
log() { echo "$1"; echo "$1" >> "$LOG" 2>/dev/null; }
sum() { set -- $(md5 "$1" 2>/dev/null); echo "$1"; }

# NEMESIS 1.17's LivePatch puts its own shell script at /system/bin/app_process (its watchdog
# writes it again on every boot, LivePatch package or not) and runs app_process.real, which
# holds whatever binary it found there first: stock, or HMG-API's wrapper. Under that script
# HMG-API's wrapper goes into app_process.real and the uninstall gives the stock binary back
# there; app_process itself stays NEMESIS's. Writing app_process instead lasts one boot, and
# an uninstall that did would leave .real running a wrapper whose stock copy is gone: no zygote.
nemesis_livepatch() { is_livepatch_script "$ROOT/system/bin/app_process"; }
# a file that is NEMESIS 1.17's LivePatch script (its first two lines; native/livepatch.c
# is_wrapper), wherever it is: never a stock binary
is_livepatch_script() {
    local a= b=
    { read -r a; read -r b; } 2>/dev/null < "$1"   # 2> first: a file that isn't there says nothing
    [ "$a" = "#!/system/bin/sh" ] || return 1
    case "$b" in "# NEMESIS LivePatch"*) return 0 ;; esac
    return 1
}
# is_binary FILE: an ELF binary by its first four bytes: not empty, not a script, not missing. A
# stock app_process is one. A copy that was cut short still starts like one, so a copy is also
# checked by md5 against what it was made from.
is_binary() {
    [ -s "$1" ] || return 1
    case "$(dd if="$1" bs=4 count=1 2>/dev/null)" in ?ELF) return 0 ;; esac
    return 1
}
# is_wrapper FILE: an HMG-API zygote wrapper, never stock: every version's names the stock copy it
# runs. $WRAP, when set: the md5 of the one this release installs (install.sh) or the one that is
# installed (uninstall.sh, from installed.txt).
is_wrapper() { { [ -n "$WRAP" ] && [ "$(sum "$1")" = "$WRAP" ]; } || grep -q app_process.hmgapi-stock "$1" 2>/dev/null; }
# not_stock FILE: can't be a stock app_process: an HMG-API wrapper, NEMESIS 1.17's LivePatch
# script, or anything else that isn't a binary (another tool's script, an empty file, no file).
# install.sh and uninstall.sh both ask this, so neither keeps nor puts back what the other refuses.
not_stock() { is_wrapper "$1" || is_livepatch_script "$1" || ! is_binary "$1"; }
# the zygote binaries HMG-API wraps and gives back (/system paths, space-separated): app_process
# unless it's NEMESIS's script, and app_process.real whenever it's there. NEMESIS's watchdog runs
# .real from its next boot whatever app_process holds now: an older HMG-API's update or uninstall
# wrote over NEMESIS's script and left the wrapper before it in .real (#396). Empty: NEMESIS's
# script with no .real.
zygote_files() {
    local z=
    nemesis_livepatch || z=/system/bin/app_process
    [ -f "$ROOT/system/bin/app_process.real" ] && z="${z:+$z }/system/bin/app_process.real"
    echo "$z"
}
# what a refusal for an app_process build HMG-API hasn't checked asks of the owner (install.sh,
# uninstall.sh; the USB installer's toast says the same in short)
ASK_BUILD="HMG-API only takes app_process builds it has checked, and this one isn't checked yet. Please open an issue at https://github.com/Open-HMG/HMG-API/issues/new/choose with this line, your car and its head unit software version (e.g. 260507)"
REMOUNTED=
fail() {
    log "FAIL: $1"
    [ -n "$REMOUNTED" ] && remount ro >/dev/null 2>&1
    exit 1
}

# The boot hook: `hmgapid boot`, which returns at once (the boot script goes straight on
# to NEMESIS's start) and, detached, starts hmgapid and runs /data/hmgapi/boot.sh
# (HMG-API's boot steps) once per boot.
hook_block() {
    echo "$HOOK_BEGIN (HMG-API: starts hmgapid. HMG-API's uninstall removes this block.)"
    echo 'if [ -x /system/bin/hmgapid ]; then /system/bin/hmgapid boot; fi'
    echo "$HOOK_END"
}

# copy <file> without the HMG-API block to stdout
hook_strip() {
    _in=0
    while IFS= read -r _l || [ -n "$_l" ]; do
        case "$_l" in
            "$HOOK_BEGIN"*) _in=1; continue ;;
            "$HOOK_END"*) _in=0; continue ;;
        esac
        [ $_in = 1 ] || echo "$_l"
    done < "$1"
}

# hook_add <file> top|end: put the block after the first line (top: the file may
# end with "exit 0", as NEMESIS's install-recovery.sh does) or at the end. A
# missing file is created (and marked, so hook_remove deletes it again).
hook_add() {
    _f=$ROOT$1
    _t=$_f.hmgapi-new
    if [ ! -f "$_f" ]; then
        { echo "#!/system/bin/sh"; echo "# HMGAPI_CREATED"; hook_block; echo "exit 0"; } > "$_t" || return 1
        chmod 755 "$_t"
    else
        hook_strip "$_f" > "$_t.body" || return 1
        if [ "$2" = top ]; then
            _first=1
            while IFS= read -r _l || [ -n "$_l" ]; do
                echo "$_l"
                if [ $_first = 1 ]; then hook_block; _first=0; fi
            done < "$_t.body" > "$_t"
        else
            { cat "$_t.body"; hook_block; } > "$_t"
        fi
        rm -f "$_t.body"
        chmod 755 "$_t"
        [ -x "$_f" ] || chmod 644 "$_t"
    fi
    mv "$_t" "$_f"
}

# hook_restore <file> <backup>: the backup's own bytes back, when the file is still that
# backup plus HMG-API's block and nothing else. hook_strip writes each line again through
# the shell, which can change bytes (a last line with no newline gets one, and a shell's
# echo may expand a backslash), so a stripped file is only close to stock; the backup
# taken at install is stock exactly. To see that nothing else changed the file since,
# both are stripped until another pass changes nothing, then compared: the file has been
# through the shell more often than the backup (once per install or update, and again
# here), and a pass isn't always the same as two (an echo that expands "\\\\" leaves
# "\\", and the next one "\"). 1: not the same (something else rewrote it, e.g. a NEMESIS
# install; no backup; no block), and nothing was changed: hook_remove then.
hook_settle() {   # <in> <out>: <in> stripped until it stays as it is
    cp "$1" "$2" || return 1
    _n=0
    while [ $_n -lt 8 ]; do
        hook_strip "$2" > "$2.next" || { rm -f "$2.next"; return 1; }
        if [ "$(sum "$2.next")" = "$(sum "$2")" ]; then rm -f "$2.next"; return 0; fi
        mv "$2.next" "$2" || return 1
        _n=$((_n + 1))
    done
    rm -f "$2.next"
    return 1
}
hook_restore() {
    _f=$ROOT$1
    _b=$2
    [ -f "$_f" ] && [ -s "$_b" ] && [ ! -f "$_b.absent" ] || return 1
    grep -q "^$HOOK_BEGIN" "$_f" || return 1
    grep -q "^$HOOK_BEGIN" "$_b" && return 1
    hook_settle "$_f" "$_b.now" && hook_settle "$_b" "$_b.then" || { rm -f "$_b.now" "$_b.then"; return 1; }
    _now=$(sum "$_b.now")
    _then=$(sum "$_b.then")
    rm -f "$_b.now" "$_b.then"
    [ -n "$_now" ] && [ "$_now" = "$_then" ] || return 1
    cp "$_b" "$_f.hmgapi-new" || return 1
    chmod 755 "$_f.hmgapi-new"
    [ -x "$_f" ] || chmod 644 "$_f.hmgapi-new"
    [ "$(sum "$_f.hmgapi-new")" = "$(sum "$_b")" ] || { rm -f "$_f.hmgapi-new"; return 1; }
    mv "$_f.hmgapi-new" "$_f"
}

# hook_remove <file>: take the block out; delete the file if HMG-API created it
hook_remove() {
    _f=$ROOT$1
    [ -f "$_f" ] || return 0
    if grep -q "^# HMGAPI_CREATED" "$_f"; then
        rm -f "$_f"
        return
    fi
    grep -q "^$HOOK_BEGIN" "$_f" || return 0
    hook_strip "$_f" > "$_f.hmgapi-new" || return 1
    chmod 755 "$_f.hmgapi-new"
    [ -x "$_f" ] || chmod 644 "$_f.hmgapi-new"
    mv "$_f.hmgapi-new" "$_f"
}
