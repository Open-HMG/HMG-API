/*
 * hmgapi-hook zygote wrapper: installed as /system/bin/app_process, runs the
 * stock binary (kept as app_process.hmgapi-stock) with the same arguments.
 *
 * For the zygote only (argv has --zygote), it adds LD_PRELOAD=libhmgapi_hook.so
 * when all of these hold, so the hook runtime loads into every app:
 *   /data/hmgapi/hook/enable exists      (step 2 creates it)
 *   /data/hmgapi/hook/disable is absent  (manual off switch)
 *   /system/lib/libhmgapi_hook.so exists
 *   /data/hmgapi/hook/tripped is absent  (the boot guard hasn't turned them off)
 *   boot guard: fewer than MAXSTARTS (3) earlier starts in this kernel boot, and
 *   fewer than MAXBOOTS (6) earlier starts in a row that didn't boot
 * Boot guard: /data/hmgapi/hook/starts counts the starts in a row that didn't boot
 * (reach the home screen), across restarts; /data/hmgapi/hook/boot holds the
 * kernel's boot_id and every start within that boot, booted or not. On 4.4 a
 * system_server crash kills the zygote and init starts it again in the same boot,
 * so a crash loop from a hook, early or late (a phone connecting, CarPlay
 * starting, an hour into a drive), shows there: the 4th start in one boot runs
 * without hooks. A QuickBoot suspend and wake is the same boot with no new start.
 * A head unit restarted or switched off before it booted (installs, applies, the
 * car turned off) is a new boot: those count only toward MAXBOOTS, which still
 * stops a loop of full restarts, and a boot that hangs. With no boot_id, every
 * start counts as the same boot (MAXSTARTS overall).
 * When either limit is reached the wrapper writes hook/tripped ("boot S B" or
 * "row S B": which limit, the two counts) and from then on starts without hooks,
 * counting nothing, until the owner resets it (the app, a reinstall, or removing
 * hook/tripped, hook/boot and writing 0 to hook/starts).
 * Fails closed: a counter that can't be read or written in full (to a .tmp file,
 * fsync, rename) means no hooks for this start.
 * Booted: with hooks on, a watcher (double-forked, so init adopts it and the zygote
 * never sees it exit) polls every POLL_MS. Once this zygote has a system_server
 * child, a launcher child (LAUNCHER) that has been up UP_MS, and
 * sys.boot_completed is 1 (getprop), the start booted: it writes 0 to starts and
 * exits. hook/boot stays: it goes only with a new kernel boot. No system_server
 * after HANG_MS means the zygote hung before forking it (a mod's zygoteInit): the
 * watcher kills the zygote, init starts it again, and that start counts. Not booted
 * after GIVEUP_MS: the watcher exits and the start stays counted.
 * Stock ARM app_process re-execs /system/bin/app_process once with
 * NO_ADDR_COMPAT_LAYOUT_FIXUP=1 (ADDR_COMPAT_LAYOUT personality), so the zygote
 * passes through here twice per start. Only the first pass counts and sets the
 * preload; the second passes the environment through (LD_PRELOAD included).
 * hmgapid: every zygote start (first pass, hooks on or off) also starts
 * /system/bin/hmgapid daemon, double-forked, so the root daemon runs even when a
 * boot script lost HMG-API's hook (reinstalling NEMESIS rewrites
 * install-recovery.sh). hmgapid is single-instance (its socket bind): a second
 * start, here or from the boot hook, just exits.
 * Every start writes one line to the kernel log (tag "hmgapi-hook").
 * If the exec fails (the stock copy lost or damaged on /system: a copy that never
 * reached storage before a power loss), the install's backup of the stock binary,
 * /data/hmgapi/stock/system/bin/app_process (its md5 checked against stock.txt when
 * it was taken), is run instead, so Android still starts and an install or
 * uninstall can put /system right. It's mode 600 (the install's umask 077), so it's
 * made 700 first; it must start like an ELF binary. Where SELinux refuses an exec
 * from /data (an Enforcing unit) that fails too, and this returns 127 as before.
 * Beyond that there is nothing left to fall back to, so this file stays as small
 * as possible.
 *
 * Builds freestanding for ARM (no libc, raw syscalls). AGPL-3.0-or-later.
 */
typedef unsigned long ul;

#ifndef STOCK
#define STOCK "/system/bin/app_process.hmgapi-stock"
#endif
#ifndef KMSG
#define KMSG "/dev/kmsg"
#endif
#ifndef STOCKBAK
#define STOCKBAK "/data/hmgapi/stock/system/bin/app_process"
#endif
#ifndef HOOKDIR
#define HOOKDIR "/data/hmgapi/hook"
#endif
#ifndef HOOKLIB
#define HOOKLIB "/system/lib/libhmgapi_hook.so"
#endif
#ifndef HMGAPID
#define HMGAPID "/system/bin/hmgapid"
#endif
#ifndef BOOTID
#define BOOTID "/proc/sys/kernel/random/boot_id"
#endif
#ifndef GETPROP
#define GETPROP "/system/bin/getprop"
#endif
#ifndef PROC
#define PROC "/proc"
#endif
#ifndef SYSTEM_SERVER
#define SYSTEM_SERVER "system_server"
#endif
#ifndef LAUNCHER
#define LAUNCHER "com.android.launcher3"   /* every Gen5W build mapped so far (maps/base/launcher.json) */
#endif
#define MAXSTARTS 3   /* in one kernel boot */
#define MAXBOOTS 6    /* in a row, across restarts */
/* the watcher's times, counted in its polls (so a busy unit only makes them longer) */
#ifndef POLL_MS
#define POLL_MS 2000
#endif
#ifndef UP_MS
#define UP_MS 30000       /* the launcher up this long (and the boot completed): booted */
#endif
#ifndef HANG_MS
#define HANG_MS 120000    /* no system_server by then: hung (a normal start forks it within ~3 s) */
#endif
#ifndef GIVEUP_MS
#define GIVEUP_MS 1800000 /* not booted in 30 minutes: stop watching, the start stays counted */
#endif
#define MAXENV 256

/* EABI system call numbers only (CLAUDE.md): the head units answer -ENOSYS to OABI ones */
static long sys4(long n, long a, long b, long c, long d)
{
    register long r7 asm("r7") = n; register long r0 asm("r0") = a;
    register long r1 asm("r1") = b; register long r2 asm("r2") = c;
    register long r3 asm("r3") = d;
    asm volatile("svc 0" : "+r"(r0) : "r"(r7), "r"(r1), "r"(r2), "r"(r3) : "memory");
    return r0;
}
static long sys3(long n, long a, long b, long c) { return sys4(n, a, b, c, 0); }
static long xopen(const char *p, int fl, int m) { return sys3(5, (long)p, fl, m); }
static long xread(int fd, void *b, ul n) { return sys3(3, fd, (long)b, n); }
static long xwrite(int fd, const void *b, ul n) { return sys3(4, fd, (long)b, n); }
static void xclose(int fd) { sys3(6, fd, 0, 0); }
static long xexecve(const char *p, char **av, char **ev) { return sys3(11, (long)p, (long)av, (long)ev); }
static long xfork(void) { return sys3(2, 0, 0, 0); }
static long xgetpid(void) { return sys3(20, 0, 0, 0); }
static void xexit(int c) { sys3(1, c, 0, 0); for (;;) ; }
static void xsleep_ms(long ms) { long ts[2] = { ms / 1000, (ms % 1000) * 1000000 }; sys3(162, (long)ts, 0, 0); }
static void xunlink(const char *p) { sys3(10, (long)p, 0, 0); }
static long xchmod(const char *p, long mode) { return sys3(15, (long)p, mode, 0); }
static long xrename(const char *a, const char *b) { return sys3(38, (long)a, (long)b, 0); }
static long xfsync(int fd) { return sys3(118, fd, 0, 0); }
static long xclose_rc(int fd) { return sys3(6, fd, 0, 0); }
static void xwait(long pid) { sys4(114, pid, 0, 0, 0); }   /* wait4(pid, NULL, 0, NULL) */
static void xkill(long pid, int sig) { sys3(37, pid, sig, 0); }
#define O_RDONLY 0
#define O_WRONLY 1
#define O_WR 0x241   /* O_WRONLY|O_CREAT|O_TRUNC */
#define O_DIRECTORY 0x4000   /* ARM */
#define ENOENT 2

static ul slen(const char *s) { ul n = 0; while (s[n]) n++; return n; }
static int seq(const char *a, const char *b) { while (*a && *a == *b) a++, b++; return *a == *b; }
static int spre(const char *s, const char *p) { while (*p && *s == *p) s++, p++; return !*p; }
static int exists(const char *p) { long fd = xopen(p, O_RDONLY, 0); if (fd < 0) return 0; xclose(fd); return 1; }
static int is_elf(const char *p)
{
    char m[4]; long fd = xopen(p, O_RDONLY, 0), n;
    if (fd < 0) return 0;
    n = xread(fd, m, 4);
    xclose(fd);
    return n == 4 && m[0] == 0x7f && m[1] == 'E' && m[2] == 'L' && m[3] == 'F';
}

static char line[160];
static ul lpos;
static void put(const char *s) { while (*s && lpos < sizeof line - 2) line[lpos++] = *s++; }
static void putn(long v)
{
    char t[12]; int i = 0;
    if (v < 0) { put("-"); v = -v; }
    do t[i++] = '0' + v % 10; while ((v /= 10) && i < 11);
    while (i) { char c[2] = { t[--i], 0 }; put(c); }
}
static void flush(void)
{
    long fd = xopen(KMSG, O_WRONLY, 0);
    line[lpos++] = '\n';
    if (fd >= 0) { xwrite(fd, line, lpos); xclose(fd); }
    lpos = 0;
}

/* A counter file: its number (0 when the file is absent), or -1 when it can't be read
   or doesn't start with a number: fail closed. len gets the bytes read. */
static long readcount(const char *path, char *buf, ul size, long *len)
{
    long fd = xopen(path, O_RDONLY, 0), n, v = 0, i;
    *len = 0;
    if (fd == -ENOENT) return 0;
    if (fd < 0) return -1;
    n = xread(fd, buf, size);
    xclose(fd);
    if (n <= 0) return -1;
    *len = n;
    if (buf[0] < '0' || buf[0] > '9') return -1;
    for (i = 0; i < n && buf[i] >= '0' && buf[i] <= '9' && v < 100000000; i++) v = v * 10 + (buf[i] - '0');
    return v;
}
static void putnum(char *t, int *k, long v)
{
    char d[12]; int i = 0;
    do d[i++] = '0' + v % 10; while ((v /= 10) && i < 11);
    while (i) t[(*k)++] = d[--i];
}

/* Writes n bytes to path, whole or not at all: to tmp, fsync, then rename over path
   (a power cut leaves the old file or the new one, never a short one). 0, or -1. */
static int put_file(const char *path, const char *tmp, const char *s, long n)
{
    long fd = xopen(tmp, O_WR, 0600), r;
    if (fd < 0) return -1;
    r = xwrite(fd, s, n);
    if (r == n && xfsync(fd) < 0) r = -1;
    if (xclose_rc(fd) < 0) r = -1;
    if (r != n || xrename(tmp, path) < 0) { xunlink(tmp); return -1; }
    fd = xopen(HOOKDIR, O_RDONLY | O_DIRECTORY, 0);   /* the rename itself to disk: best effort */
    if (fd >= 0) { xfsync(fd); xclose(fd); }
    return 0;
}

/* Bumps both counters (in a row: starts; this boot: boot). Returns the in-a-row count
   before this start, or -1 when a counter can't be read or written in full; *inboot gets
   this boot's count before this start (the in-a-row one when there's no boot_id). */
static long bump_starts(long *inboot)
{
    static char b[16], id[48], saved[96];
    long fd, v, idn = 0, sn = 0, i, k;
    int same, w;
    v = readcount(HOOKDIR "/starts", b, sizeof b, &sn);
    if (v < 0) return -1;
    fd = xopen(BOOTID, O_RDONLY, 0);
    if (fd >= 0) {
        idn = xread(fd, id, sizeof id - 1);
        xclose(fd);
        if (idn < 0) idn = 0;
        while (idn > 0 && (id[idn - 1] == '\n' || id[idn - 1] == ' ')) idn--;
    }
    sn = 0;
    fd = xopen(HOOKDIR "/boot", O_RDONLY, 0);
    if (fd >= 0) {
        sn = xread(fd, saved, sizeof saved - 1);
        xclose(fd);
        if (sn < 0) return -1;
    } else if (fd != -ENOENT) return -1;
    saved[sn] = 0;
    /* hook/boot: "<boot_id> <count>" */
    same = idn > 0 && sn > idn && saved[idn] == ' ';
    for (i = 0; same && i < idn; i++) if (saved[i] != id[i]) same = 0;
    if (idn == 0) *inboot = v;
    else if (same) { *inboot = 0; for (k = idn + 1; k < sn && saved[k] >= '0' && saved[k] <= '9' && *inboot < 100000000; k++) *inboot = *inboot * 10 + (saved[k] - '0'); }
    else *inboot = 0;
    { char t[16]; int n = 0; putnum(t, &n, v + 1); t[n++] = '\n';
      if (put_file(HOOKDIR "/starts", HOOKDIR "/starts.tmp", t, n) < 0) return -1; }
    if (idn > 0) {
        w = 0;
        for (i = 0; i < idn && w < (int)sizeof saved - 14; i++) saved[w++] = id[i];
        saved[w++] = ' ';
        putnum(saved, &w, *inboot + 1);
        saved[w++] = '\n';
        if (put_file(HOOKDIR "/boot", HOOKDIR "/boot.tmp", saved, w) < 0) return -1;
    }
    return v;
}

/* the guard tripped: "boot|row <starts> <this boot>", kept until the owner resets it */
static void trip(const char *why, long starts, long inboot)
{
    char t[40]; int n = 0;
    while (*why) t[n++] = *why++;
    t[n++] = ' '; putnum(t, &n, starts); t[n++] = ' '; putnum(t, &n, inboot); t[n++] = '\n';
    if (put_file(HOOKDIR "/tripped", HOOKDIR "/tripped.tmp", t, n) < 0) put(" (can't write tripped)");
}

static int procpath(char *p, long pid, const char *what)
{
    const char *s = PROC "/";
    int i = 0, k = 0; char t[12];
    while (*s) p[i++] = *s++;
    do t[k++] = '0' + pid % 10; while ((pid /= 10) && k < 11);
    while (k) p[i++] = t[--k];
    p[i++] = '/';
    while (*what) p[i++] = *what++;
    p[i] = 0;
    return i;
}
static int alive(long pid)
{
    char p[64], c;
    long fd, n;
    procpath(p, pid, "cmdline");
    fd = xopen(p, O_RDONLY, 0);
    if (fd < 0) return 0;
    n = xread(fd, &c, 1);     /* a zombie has an empty cmdline */
    xclose(fd);
    return n == 1;
}
/* pid's parent (from /proc/<pid>/stat, after the name's last ')'), or -1 */
static long ppid_of(long pid)
{
    static char b[256];
    char p[64];
    long fd, n, i, j, v = 0;
    procpath(p, pid, "stat");
    fd = xopen(p, O_RDONLY, 0);
    if (fd < 0) return -1;
    n = xread(fd, b, sizeof b - 1);
    xclose(fd);
    if (n <= 0) return -1;
    for (i = n - 1; i > 0 && b[i] != ')'; i--) ;
    if (i == 0) return -1;
    for (j = i + 1; j < n && b[j] == ' '; j++) ;   /* ") S ppid" */
    for (; j < n && b[j] != ' '; j++) ;
    for (; j < n && b[j] == ' '; j++) ;
    if (j >= n || b[j] < '0' || b[j] > '9') return -1;
    for (; j < n && b[j] >= '0' && b[j] <= '9'; j++) v = v * 10 + (b[j] - '0');
    return v;
}
/* pid is a child of parent named name (its cmdline's first string) */
static int child_named(long pid, long parent, const char *name)
{
    static char b[128];
    char p[64];
    long fd, n;
    if (ppid_of(pid) != parent) return 0;
    procpath(p, pid, "cmdline");
    fd = xopen(p, O_RDONLY, 0);
    if (fd < 0) return 0;
    n = xread(fd, b, sizeof b - 1);
    xclose(fd);
    if (n <= 0) return 0;
    b[n] = 0;
    return seq(b, name);
}
/* a child of parent named name, or 0 */
static long find_child(long parent, const char *name)
{
    static char d[4096];
    long fd = xopen(PROC, O_RDONLY | O_DIRECTORY, 0), n, off, pid, found = 0;
    if (fd < 0) return 0;
    while (!found && (n = sys3(217, fd, (long)d, sizeof d)) > 0) {   /* getdents64 */
        for (off = 0; off < n && !found; off += *(unsigned short *)(d + off + 16)) {
            const char *nm = d + off + 19;
            pid = 0;
            while (*nm >= '0' && *nm <= '9') pid = pid * 10 + (*nm++ - '0');
            if (*nm || pid <= 0) continue;
            if (child_named(pid, parent, name)) found = pid;
        }
    }
    xclose(fd);
    return found;
}
/* sys.boot_completed is 1 (getprop's answer: no libc here to read the property area) */
static int boot_completed(char **ev)
{
    static char *av[] = { GETPROP, "sys.boot_completed", 0 };
    int p[2];
    char c[4];
    long pid, n = 0;
    if (sys3(42, (long)p, 0, 0) < 0) return 0;   /* pipe */
    pid = xfork();
    if (pid == 0) {
        sys3(63, p[1], 1, 0);                    /* dup2 to stdout */
        xclose(p[0]); xclose(p[1]);
        xexecve(GETPROP, av, ev);
        xexit(127);
    }
    xclose(p[1]);
    if (pid > 0) n = xread(p[0], c, sizeof c);
    xclose(p[0]);
    if (pid > 0) xwait(pid);
    return n >= 1 && c[0] == '1' && (n == 1 || c[1] == '\n');
}

static void reset_starts(void)
{
    if (put_file(HOOKDIR "/starts", HOOKDIR "/starts.w", "0\n", 2) < 0) put(", can't write starts");
}

/* Double fork: the middle child exits at once and is reaped here, before the
   exec, so the zygote never gets a SIGCHLD for it. The watcher's own children
   (getprop) are its own to reap. */
static void start_watcher(char **ev)
{
    long zpid = xgetpid(), pid = xfork();
    if (pid == 0) {
        if (xfork() == 0) {
            long t, ss = 0, la = 0, since = 0, fd;
            sys3(66, 0, 0, 0);                 /* setsid */
            /* none of the zygote's fds (its socket from init, a caller's pipe): /dev/null on 0-2 */
            for (fd = 3; fd < 1024; fd++) xclose(fd);
            fd = xopen("/dev/null", 2, 0);     /* O_RDWR */
            if (fd >= 0) { sys3(63, fd, 0, 0); sys3(63, fd, 1, 0); sys3(63, fd, 2, 0); if (fd > 2) xclose(fd); }
            for (t = POLL_MS; ; t += POLL_MS) {
                xsleep_ms(POLL_MS);
                if (!alive(zpid)) xexit(0);    /* it died: this start stays counted */
                if (t > GIVEUP_MS) {
                    put("<4>hmgapi-hook: zygote "); putn(zpid); put(" not booted in "); putn(GIVEUP_MS / 1000);
                    put(" s, counted as a failed start"); flush();
                    xexit(0);
                }
                if (!ss) ss = find_child(zpid, SYSTEM_SERVER);
                if (!ss) {
                    if (t < HANG_MS) continue;
                    put("<3>hmgapi-hook: zygote "); putn(zpid); put(" has no " SYSTEM_SERVER " after "); putn(t / 1000);
                    put(" s (hung before it: a mod's zygoteInit?), stopping it; the next start counts"); flush();
                    xkill(zpid, 9);
                    xexit(0);
                }
                if (la && !child_named(la, zpid, LAUNCHER)) la = 0;   /* it died: from 0 again */
                if (!la) {
                    la = find_child(zpid, LAUNCHER);
                    since = t;
                    continue;
                }
                if (t - since < UP_MS || !boot_completed(ev)) continue;
                put("<6>hmgapi-hook: zygote "); putn(zpid); put(" booted ("); put(LAUNCHER); put(" up ");
                putn((t - since) / 1000); put(" s, boot completed) at "); putn(t / 1000); put(" s, starts reset");
                reset_starts();
                flush();
                xexit(0);
            }
        }
        xexit(0);
    }
    if (pid > 0) xwait(pid);
}

/* hmgapid daemon, double-forked like the watcher (it detaches by itself too) */
static void start_hmgapid(char **ev)
{
    long pid = xfork();
    if (pid == 0) {
        if (xfork() == 0) {
            static char *av[] = { HMGAPID, "daemon", 0 };
            sys3(66, 0, 0, 0);                 /* setsid */
            xexecve(HMGAPID, av, ev);
        }
        xexit(0);
    }
    if (pid > 0) xwait(pid);
}

static char *env[MAXENV];
static char preload[] = "LD_PRELOAD=" HOOKLIB;

int main(int argc, char **argv, char **envp)
{
    int i, n = 0, zygote = 0, use = 0, reexec = 0;
    long starts = -1, inboot = 0, rc;

    for (i = 1; i < argc; i++)
        if (seq(argv[i], "--zygote")) zygote = 1;
    for (i = 0; envp[i]; i++)
        if (spre(envp[i], "NO_ADDR_COMPAT_LAYOUT_FIXUP=")) reexec = 1;

    put("<6>hmgapi-hook: ");
    put(zygote ? "zygote" : "app_process");
    if (reexec) put(" (re-exec)");
    else if (zygote && exists(HOOKDIR "/enable")) {
        if (exists(HOOKDIR "/disable")) put(", hooks off (disable file)");
        else if (!exists(HOOKLIB)) put(", hooks off (no " HOOKLIB ")");
        else if (exists(HOOKDIR "/tripped")) put(", hooks off (boot guard tripped earlier: hook/tripped)");
        else {
            starts = bump_starts(&inboot);
            if (starts < 0) put(", hooks off (can't read or write the boot guard's counters)");
            else if (inboot >= MAXSTARTS || starts >= MAXBOOTS) {
                put(", hooks off (boot guard, starts="); putn(starts); put(", this boot "); putn(inboot); put(")");
                trip(inboot >= MAXSTARTS ? "boot" : "row", starts, inboot);
            }
            else { use = 1; put(", hooks on, start "); putn(starts + 1); put(" (this boot "); putn(inboot + 1); put(")"); }
        }
    }

    for (i = 0; envp[i] && n < MAXENV - 2; i++)
        if (!(use && spre(envp[i], "LD_PRELOAD="))) env[n++] = envp[i];
    env[n] = 0;

    if (zygote && !reexec && exists(HMGAPID)) put(", hmgapid");
    put(", exec " STOCK);
    flush();
    if (use) start_watcher(env);   /* getprop gets the environment without the preload */
    if (use) { env[n++] = preload; env[n] = 0; }
    if (zygote && !reexec && exists(HMGAPID)) start_hmgapid(envp);
    rc = xexecve(STOCK, argv, env);
    put("<3>hmgapi-hook: exec " STOCK " failed, errno ");
    putn(-rc);
    if (!is_elf(STOCKBAK)) { put(", and no stock backup"); flush(); return 127; }
    put(", exec " STOCKBAK " (the stock backup)");
    flush();
    xchmod(STOCKBAK, 0700);
    rc = xexecve(STOCKBAK, argv, env);
    put("<3>hmgapi-hook: exec " STOCKBAK " failed, errno ");
    putn(-rc);
    flush();
    (void)slen;
    return 127;
}

__attribute__((naked, noreturn)) void _start(void)
{
    asm volatile("ldr r0,[sp]\n add r1,sp,#4\n add r2,r1,r0,lsl #2\n add r2,r2,#4\n"
                 "bl main\n mov r7,#1\n svc 0\n");
}
