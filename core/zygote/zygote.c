/*
 * hmgapi-hook zygote wrapper: installed as /system/bin/app_process, runs the
 * stock binary (kept as app_process.hmgapi-stock) with the same arguments.
 *
 * For the zygote only (argv has --zygote), it adds LD_PRELOAD=libhmgapi_hook.so
 * when all of these hold, so the hook runtime loads into every app:
 *   /data/hmgapi/hook/enable exists      (step 2 creates it)
 *   /data/hmgapi/hook/disable is absent  (manual off switch)
 *   /system/lib/libhmgapi_hook.so exists
 *   boot guard: fewer than MAXSTARTS (3) earlier starts in this kernel boot, and
 *   fewer than MAXBOOTS (6) in a row overall, that didn't last GUARD_DELAY
 * Boot guard: /data/hmgapi/hook/starts counts the starts in a row that didn't last,
 * across restarts; /data/hmgapi/hook/boot holds the kernel's boot_id and the count
 * within that boot. A crash loop from a hook happens within one boot (on 4.4 a
 * system_server crash kills the zygote and init starts it again), so 3 there turn
 * the hooks off. A head unit restarted or switched off within GUARD_DELAY of
 * starting (installs, applies, the car turned off) is a new boot: those count
 * only toward MAXBOOTS, which still stops a loop of full restarts. With no
 * boot_id, every start counts as the same boot (MAXSTARTS overall, as before).
 * Reset: with hooks on, a watcher (double-forked, so init adopts it and the
 * zygote never sees it exit) waits GUARD_DELAY seconds and, if this zygote pid is
 * still alive, writes 0 to starts and removes boot: the start worked.
 * Safe mode (SAFEFILE, /data/hmgapi/SAFE): the runtime loads no mod, hmgapid runs no
 * mod's service, the engine publishes every mod as off. The preload stays, so HMG-API's
 * own hooks (its tile, its ★ choice, the update block) still run. The boot guard
 * tripping with no SAFEFILE writes it ("boot guard") and gives the counters a fresh
 * start: HMG-API alone gets another try. Tripping again in safe mode: hooks off, as
 * before (until the owner turns safe mode off, which resets the counters).
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
 * If the exec fails there is nothing left to fall back to, so this file stays
 * as small as possible.
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
#ifndef SAFEFILE
#define SAFEFILE "/data/hmgapi/SAFE"
#endif
#define MAXSTARTS 3   /* in one kernel boot */
#define MAXBOOTS 6    /* in a row, across restarts */
#ifndef GUARD_DELAY
#define GUARD_DELAY 120
#endif
#define MAXENV 256

static long sys3(long n, long a, long b, long c)
{
    register long r7 asm("r7") = n; register long r0 asm("r0") = a;
    register long r1 asm("r1") = b; register long r2 asm("r2") = c;
    asm volatile("svc 0" : "+r"(r0) : "r"(r7), "r"(r1), "r"(r2) : "memory");
    return r0;
}
static long xopen(const char *p, int fl, int m) { return sys3(5, (long)p, fl, m); }
static long xread(int fd, void *b, ul n) { return sys3(3, fd, (long)b, n); }
static long xwrite(int fd, const void *b, ul n) { return sys3(4, fd, (long)b, n); }
static void xclose(int fd) { sys3(6, fd, 0, 0); }
static long xexecve(const char *p, char **av, char **ev) { return sys3(11, (long)p, (long)av, (long)ev); }
static long xfork(void) { return sys3(2, 0, 0, 0); }
static long xgetpid(void) { return sys3(20, 0, 0, 0); }
static void xexit(int c) { sys3(1, c, 0, 0); for (;;) ; }
static void xsleep(long s) { long ts[2] = { s, 0 }; sys3(162, (long)ts, 0, 0); }
static void xunlink(const char *p) { sys3(10, (long)p, 0, 0); }
#define O_RDONLY 0
#define O_WRONLY 1
#define O_WR 0x241   /* O_WRONLY|O_CREAT|O_TRUNC */

static ul slen(const char *s) { ul n = 0; while (s[n]) n++; return n; }
static int seq(const char *a, const char *b) { while (*a && *a == *b) a++, b++; return *a == *b; }
static int spre(const char *s, const char *p) { while (*p && *s == *p) s++, p++; return !*p; }
static int exists(const char *p) { long fd = xopen(p, O_RDONLY, 0); if (fd < 0) return 0; xclose(fd); return 1; }

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

/* the number at the start of a file, or 0 (absent, empty, not a number) */
static long readnum(const char *path, char *buf, ul size, long *len)
{
    long fd = xopen(path, O_RDONLY, 0), n = 0, v = 0, i;
    if (fd >= 0) {
        n = xread(fd, buf, size);
        xclose(fd);
        if (n < 0) n = 0;
    }
    if (len) *len = n;
    for (i = 0; i < n && buf[i] >= '0' && buf[i] <= '9'; i++) v = v * 10 + (buf[i] - '0');
    return v;
}
static void putnum(char *t, int *k, long v)
{
    char d[12]; int i = 0;
    do d[i++] = '0' + v % 10; while ((v /= 10) && i < 11);
    while (i) t[(*k)++] = d[--i];
}

/* Bumps both counters (in a row: starts; this boot: boot). Returns the in-a-row count
   before this start, or -1 when it can't be written; *inboot gets this boot's count
   before this start (the in-a-row one when there's no boot_id). */
static long bump_starts(long *inboot)
{
    static char b[16], id[48], saved[96];
    long fd, v, idn = 0, sn = 0, i, k;
    int same, w;
    v = readnum(HOOKDIR "/starts", b, sizeof b, 0);
    fd = xopen(BOOTID, O_RDONLY, 0);
    if (fd >= 0) {
        idn = xread(fd, id, sizeof id - 1);
        xclose(fd);
        if (idn < 0) idn = 0;
        while (idn > 0 && (id[idn - 1] == '\n' || id[idn - 1] == ' ')) idn--;
    }
    fd = xopen(HOOKDIR "/boot", O_RDONLY, 0);
    if (fd >= 0) {
        sn = xread(fd, saved, sizeof saved - 1);
        xclose(fd);
        if (sn < 0) sn = 0;
    }
    saved[sn] = 0;
    /* hook/boot: "<boot_id> <count>" */
    same = idn > 0 && sn > idn && saved[idn] == ' ';
    for (i = 0; same && i < idn; i++) if (saved[i] != id[i]) same = 0;
    if (idn == 0) *inboot = v;
    else if (same) { *inboot = 0; for (k = idn + 1; k < sn && saved[k] >= '0' && saved[k] <= '9'; k++) *inboot = *inboot * 10 + (saved[k] - '0'); }
    else *inboot = 0;
    fd = xopen(HOOKDIR "/starts", O_WR, 0600);
    if (fd < 0) return -1;
    { char t[12]; int n = 0; putnum(t, &n, v + 1); xwrite(fd, t, n); }
    xclose(fd);
    if (idn > 0) {
        fd = xopen(HOOKDIR "/boot", O_WR, 0600);
        if (fd >= 0) {
            w = 0;
            for (i = 0; i < idn && w < (int)sizeof saved - 14; i++) saved[w++] = id[i];
            saved[w++] = ' ';
            putnum(saved, &w, *inboot + 1);
            saved[w++] = '\n';
            xwrite(fd, saved, w);
            xclose(fd);
        }
    }
    return v;
}
/* safe mode on: SAFEFILE, saying why */
static int safe_on(void)
{
    long fd = xopen(SAFEFILE, O_WR, 0600);
    if (fd < 0) return 0;
    xwrite(fd, "boot guard\n", 11);
    xclose(fd);
    return 1;
}
static int alive(long pid)
{
    char p[24] = "/proc/", c;
    int i = 6, k = 0; char t[12]; long fd, n;
    do t[k++] = '0' + pid % 10; while ((pid /= 10) && k < 11);
    while (k) p[i++] = t[--k];
    p[i] = 0;
    { const char *s = "/cmdline"; while (*s) p[i++] = *s++; p[i] = 0; }
    fd = xopen(p, O_RDONLY, 0);
    if (fd < 0) return 0;
    n = xread(fd, &c, 1);     /* a zombie has an empty cmdline */
    xclose(fd);
    return n == 1;
}

/* Double fork: the middle child exits at once and is reaped here, before the
   exec, so the zygote never gets a SIGCHLD for it. */
static void start_watcher(void)
{
    long zpid = xgetpid(), pid = xfork();
    if (pid == 0) {
        if (xfork() == 0) {
            long fd;
            sys3(66, 0, 0, 0);                 /* setsid */
            xsleep(GUARD_DELAY);
            if (!alive(zpid)) xexit(0);
            fd = xopen(HOOKDIR "/starts", O_WR, 0600);
            if (fd >= 0) { xwrite(fd, "0", 1); xclose(fd); }
            xunlink(HOOKDIR "/boot");
            put("<6>hmgapi-hook: zygote "); putn(zpid); put(" up "); putn(GUARD_DELAY);
            put(" s, boot guard reset"); flush();
        }
        xexit(0);
    }
    if (pid > 0) sys3(114, pid, 0, 0);         /* wait4(pid, NULL, 0, NULL) */
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
    if (pid > 0) sys3(114, pid, 0, 0);
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
        else {
            int safe = exists(SAFEFILE);
            starts = bump_starts(&inboot);
            if (starts < 0) put(", hooks off (can't write starts)");
            else if ((inboot >= MAXSTARTS || starts >= MAXBOOTS) && safe) {
                put(", hooks off (boot guard, starts="); putn(starts); put(", this boot "); putn(inboot); put("; in safe mode)");
            }
            else if (inboot >= MAXSTARTS || starts >= MAXBOOTS) {
                put(", boot guard (starts="); putn(starts); put(", this boot "); putn(inboot); put(")");
                if (safe_on()) {   /* counters from 0 again, this start the first: HMG-API alone gets its try */
                    long fd = xopen(HOOKDIR "/starts", O_WR, 0600);
                    if (fd >= 0) { xwrite(fd, "0", 1); xclose(fd); }
                    xunlink(HOOKDIR "/boot");
                    starts = bump_starts(&inboot);
                }
                if (starts >= 0 && exists(SAFEFILE) && inboot < MAXSTARTS && starts < MAXBOOTS) {
                    use = 1; put(": safe mode on, hooks on without mods");
                }
                else put(": hooks off (can't write " SAFEFILE ")");
            }
            else {
                use = 1; put(", hooks on, start "); putn(starts + 1); put(" (this boot "); putn(inboot + 1); put(")");
                if (safe) put(", safe mode: no mods");
            }
        }
    }

    for (i = 0; envp[i] && n < MAXENV - 2; i++)
        if (!(use && spre(envp[i], "LD_PRELOAD="))) env[n++] = envp[i];
    if (use) env[n++] = preload;
    env[n] = 0;

    if (zygote && !reexec && exists(HMGAPID)) put(", hmgapid");
    put(", exec " STOCK);
    flush();
    if (use) start_watcher();
    if (zygote && !reexec && exists(HMGAPID)) start_hmgapid(envp);
    rc = xexecve(STOCK, argv, env);
    put("<3>hmgapi-hook: exec " STOCK " failed, errno ");
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
