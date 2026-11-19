# HMG-API installer: shared functions, sourced by install/uninstall on the head unit.
# mksh-safe: the unit has no sed, head, tail, wc or awk. ROOT prefixes paths in tests.
ROOT=${ROOT:-}
HOOK_BEGIN="# HMGAPI_HOOK begin"
HOOK_END="# HMGAPI_HOOK end"

# install.sh's and uninstall.sh's: a line to the screen and $LOG; an md5; give up (putting
# /system back to read-only first when the script set REMOUNTED; each script has its own remount)
log() { echo "$1"; echo "$1" >> "$LOG" 2>/dev/null; }
sum() { set -- $(md5 "$1" 2>/dev/null); echo "$1"; }
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

# SELinux. Most of these units boot with it disabled (no selinuxfs); some newer cars may
# boot Enforcing. sel_mode: enforcing, permissive or disabled, now.
SEL=/sys/fs/selinux/enforce
sel_mode() {
    case "$(cat "$ROOT$SEL" 2>/dev/null)" in
        1) echo enforcing ;;
        0) echo permissive ;;
        *) echo disabled ;;
    esac
}

# The boot steps' part (boot.sh): the owner's Permissive option, if the zygote wrapper
# couldn't set it (its note says what it found); then what this boot's mode is for the app
# and the Car Report (public/selinux.txt). Nothing is saved or put back: a boot without the
# option (or without HMG-API) is stock again.
selinux_boot() {
    _d=$ROOT/data/hmgapi _n=$ROOT/dev/.hmgapi-selinux
    if [ "$(sel_mode)" = enforcing ] && [ -f "$_d/settings/allow/selinux_permissive" ]; then
        case "$(cat "$_n" 2>/dev/null)" in
            *permissive*) ;;   # set by the wrapper, and the owner made it Enforcing again since: leave it
            *)
                if echo 0 > "$ROOT$SEL" 2>/dev/null; then
                    echo "enforcing permissive boot.sh" > "$_n"
                    echo "SELinux: Permissive (set by boot.sh)"
                else
                    echo "enforcing failed boot.sh" > "$_n"
                    echo "SELinux: Enforcing, can't set Permissive"
                fi ;;
        esac
    fi
    sel_publish
}
# public/selinux.txt: the mode now and the start's note, for the app's Settings → System
sel_publish() {
    { echo "mode $(sel_mode)"; echo "boot $(cat "$ROOT/dev/.hmgapi-selinux" 2>/dev/null)"; } > "$ROOT/data/hmgapi/public/selinux.txt" \
        && chmod 644 "$ROOT/data/hmgapi/public/selinux.txt"
}

# File labels (SELinux contexts). With SELinux on, the zygote only starts from a file
# labelled like the stock app_process (zygote_exec), and a copied file gets its folder's
# label. label_of <file>: the label, or nothing (SELinux off, no label); label_set <label> <file>;
# label_fix <label> <file>: label_set unless it's already that. Full paths (ROOT included).
label_of() {
    [ -e "$ROOT$SEL" ] || return 0
    for _w in $(ls -Z "$1" 2>/dev/null); do
        case "$_w" in *:object_r:*) echo "$_w"; return 0 ;; esac
    done
}
label_set() {
    [ -n "$1" ] && [ -e "$ROOT$SEL" ] || return 0
    chcon "$1" "$2"
}
label_fix() {
    [ -n "$1" ] && [ "$(label_of "$2")" != "$1" ] || return 0
    label_set "$1" "$2"
}
