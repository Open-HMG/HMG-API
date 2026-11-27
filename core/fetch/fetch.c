/* libhmgapi_fetch.so: one HTTPS request, or one DNS query, for HMG-API's engine (hmgapi.engine.NativeFetch):
 * the catalogue, mod packages and HMG-API releases, over home Wi-Fi only (Catalog).
 *
 * Android 4.4's Java TLS shares no cipher suite with GitHub, but the unit's own libssl.so
 * (OpenSSL 1.0.1) has TLS 1.2 with ECDHE and AES-GCM. This opens a TCP connection, does the
 * handshake with that library, sends the request bytes Java made and writes every response
 * byte (headers and body) to a file, up to a cap. Java parses the HTTP. The DNS query (Java's
 * bytes, DnsLookup) goes out the same way, over UDP, so it is held to home Wi-Fi too.
 *
 * The server's certificate is NOT checked here: the unit's 2013 root store doesn't know GitHub's
 * roots and its clock isn't reliable. Nothing fetched is trusted because of where it came from:
 * a package must have the SHA-256 its catalogue entry names, and the engine checks its signature
 * (the installed mod's author key; HMG-API's release key for HMG-API itself) and refuses a
 * downgrade. A hostile network can make a check find nothing, not get code installed.
 *
 * Every socket carries home Wi-Fi's mark (SO_MARK 0x2951: table 95, HomeWifi) and is bound to
 * wlan0 (SO_BINDTODEVICE), or isn't used at all: a socket that can't have both is an error. The
 * mark makes its route table 95's (home Wi-Fi, or the unreachable default while home Wi-Fi is
 * down); the binding holds it to wlan0 even before the network keeper has added the fwmark rule
 * (priority 900), when the mark alone would leave it on the main table's mobile data. Root only
 * (the engine is). Each socket has send and receive timeouts (connect, the handshake and every
 * read and write), so a network that goes away mid-answer (route_down) ends the call.
 *
 * How the answer ended matters: a TLS close (close_notify) is a whole answer; anything else
 * (the connection dropped, a read error, a timeout) is E_CUT with what came written to the file,
 * which Java takes only if the HTTP framing says it is whole (Http).
 *
 * Built with no libc (as the loader): everything comes through dlopen/dlsym from the unit's
 * libc.so and libssl.so. On a computer (-DHOST, the off-car tests) it uses the system's headers
 * and an OpenSSL 1.1+/3 library, which is why each OpenSSL call has an old and a new name.
 * No static buffers: calls may come from several threads.
 * First written as a mod (an update-check proof of concept), run on an Ioniq 5 US 260507.
 */
#ifdef HOST
#include <jni.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <netdb.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
#define LIBC_SYM(name) ((void *) name)
typedef socklen_t sock_len;
#else
void *dlopen(const char *path, int flags);
void *dlsym(void *handle, const char *name);
#define RTLD_NOW 2
typedef unsigned int sock_len;
struct sockaddr;
struct addrinfo {            /* bionic, 32-bit ARM */
    int ai_flags, ai_family, ai_socktype, ai_protocol;
    sock_len ai_addrlen;
    char *ai_canonname;
    struct sockaddr *ai_addr;
    struct addrinfo *ai_next;
};
struct timeval { long tv_sec, tv_usec; };   /* 32-bit ARM: two 32-bit longs */
_Static_assert(sizeof(struct timeval) == 8, "ARM timeval");
#define AF_INET 2
#define SOCK_STREAM 1
#define SOCK_DGRAM 2
#define AI_NUMERICHOST 4
#define SOL_SOCKET 1
#define SO_RCVTIMEO 20
#define SO_SNDTIMEO 21
#define SO_BINDTODEVICE 25
#define SO_MARK 36
#define O_WRONLY 01
#define O_CREAT 0100
#define O_EXCL 0200
#define O_NOFOLLOW 0100000   /* ARM's value (x86's is 0400000) */
#define LIBC_SYM(name) dlsym(libc_h, #name)
/* the few JNI pieces used here, at their places in the standard function table (as jni.h has
 * them), so the unit's build needs no system headers (jni.h pulls in the build machine's libc) */
typedef int jint;
typedef jint jsize;
typedef signed char jbyte;
typedef unsigned char jboolean;
typedef void *jobject;
typedef jobject jclass, jstring, jbyteArray;
struct JNINativeInterface_ {
    void *before169[169];
    const char *(*GetStringUTFChars)(void *, jstring, jboolean *);          /* 169 */
    void (*ReleaseStringUTFChars)(void *, jstring, const char *);           /* 170 */
    jsize (*GetArrayLength)(void *, jbyteArray);                            /* 171 */
    void *before184[184 - 172];
    jbyte *(*GetByteArrayElements)(void *, jbyteArray, jboolean *);         /* 184 */
    void *before192[192 - 185];
    void (*ReleaseByteArrayElements)(void *, jbyteArray, jbyte *, jint);    /* 192 */
};
_Static_assert(__builtin_offsetof(struct JNINativeInterface_, GetStringUTFChars) == 169 * sizeof(void *), "JNI table");
_Static_assert(__builtin_offsetof(struct JNINativeInterface_, GetByteArrayElements) == 184 * sizeof(void *), "JNI table");
_Static_assert(__builtin_offsetof(struct JNINativeInterface_, ReleaseByteArrayElements) == 192 * sizeof(void *), "JNI table");
typedef const struct JNINativeInterface_ *JNIEnv;
#define JNIEXPORT __attribute__((visibility("default")))
#define JNICALL
#define JNI_ABORT 2
static void *memset(void *p, int c, unsigned long n) { unsigned char *b = p; while (n--) *b++ = (unsigned char) c; return p; }
#endif

/* error codes (negative), so Java can say which step failed (NativeFetch.why) */
enum { E_LIB = -1, E_SYM = -2, E_RESOLVE = -3, E_CONNECT = -4, E_CTX = -5, E_HANDSHAKE = -6, E_SEND = -7,
       E_FILE = -8, E_TOO_BIG = -9, E_READ = -10, E_ARGS = -11, E_ROUTE = -12, E_CUT = -13, E_SOCKET = -14 };

#define SSL_CTRL_OPTIONS 32
#define SSL_CTRL_SET_TLSEXT_HOSTNAME 55
#define SSL_OP_NO_SSLv2 0x01000000L
#define SSL_OP_NO_SSLv3 0x02000000L
#define SSL_ERROR_ZERO_RETURN 6
#define HOME_MARK 0x2951
#define HOME_IFACE "wlan0"
#define BUF 16384

/* the C library's functions this needs, looked up once per call */
struct libc {
    int (*getaddrinfo)(const char *, const char *, const struct addrinfo *, struct addrinfo **);
    void (*freeaddrinfo)(struct addrinfo *);
    int (*socket)(int, int, int);
    int (*connect)(int, const struct sockaddr *, sock_len);
    int (*setsockopt)(int, int, int, const void *, sock_len);
    int (*close)(int);
    int (*open)(const char *, int, ...);
    long (*write)(int, const void *, unsigned long);
    long (*read)(int, void *, unsigned long);
    void *(*malloc)(unsigned long);
    void (*free)(void *);
};

static int libc_load(struct libc *c, const char *c_lib) {
#ifndef HOST
    void *libc_h = dlopen(c_lib, RTLD_NOW);
    if (!libc_h) return E_LIB;
#else
    (void) c_lib;
#endif
    c->getaddrinfo = LIBC_SYM(getaddrinfo);
    c->freeaddrinfo = LIBC_SYM(freeaddrinfo);
    c->socket = LIBC_SYM(socket);
    c->connect = LIBC_SYM(connect);
    c->setsockopt = LIBC_SYM(setsockopt);
    c->close = LIBC_SYM(close);
    c->open = LIBC_SYM(open);
    c->write = LIBC_SYM(write);
    c->read = LIBC_SYM(read);
    c->malloc = LIBC_SYM(malloc);
    c->free = LIBC_SYM(free);
    if (!c->getaddrinfo || !c->freeaddrinfo || !c->socket || !c->connect || !c->setsockopt || !c->close || !c->open
            || !c->write || !c->read || !c->malloc || !c->free) return E_SYM;
    return 0;
}

/* a socket for a: timeouts set, and (on the unit) home Wi-Fi's mark and wlan0, or none at all */
static int home_socket(struct libc *c, const struct addrinfo *a, int timeout_ms) {
    int fd = c->socket(a->ai_family, a->ai_socktype, a->ai_protocol);
    if (fd < 0) return E_SOCKET;
    struct timeval tv;
    memset(&tv, 0, sizeof tv);
    tv.tv_sec = timeout_ms / 1000;
    tv.tv_usec = (timeout_ms % 1000) * 1000;
    if (c->setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv) != 0
            || c->setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv) != 0) {   /* SO_SNDTIMEO bounds connect too */
        c->close(fd);
        return E_SOCKET;
    }
#ifndef HOST
    unsigned int mark = HOME_MARK;
    static const char iface[] = HOME_IFACE;
    if (c->setsockopt(fd, SOL_SOCKET, SO_MARK, &mark, sizeof mark) != 0
            || c->setsockopt(fd, SOL_SOCKET, SO_BINDTODEVICE, iface, sizeof iface) != 0) {
        c->close(fd);
        return E_ROUTE;
    }
#endif
    return fd;
}

/* connected to the first of addr:port that answers; or a negative error */
static int home_connect(struct libc *c, const char *addr, const char *port, int type, int timeout_ms) {
    struct addrinfo hints, *res = 0, *a;
    memset(&hints, 0, sizeof hints);
    hints.ai_family = AF_INET;          /* home Wi-Fi is IPv4 only for mods */
    hints.ai_socktype = type;
    hints.ai_flags = AI_NUMERICHOST;    /* an address (DnsLookup's): never the system resolver, which uses mobile data */
    if (c->getaddrinfo(addr, port, &hints, &res) != 0 || !res) return E_RESOLVE;
    int fd = E_CONNECT;
    for (a = res; a; a = a->ai_next) {
        fd = home_socket(c, a, timeout_ms);
        if (fd < 0) break;              /* no socket held to home Wi-Fi: no connection */
        if (c->connect(fd, a->ai_addr, a->ai_addrlen) == 0) break;
        c->close(fd);
        fd = E_CONNECT;
    }
    c->freeaddrinfo(res);
    return fd;
}

/* the answer's file: new, ours (O_EXCL), never through a link */
static int open_new(struct libc *c, const char *path) {
    return c->open(path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0600);
}

static int write_all(struct libc *c, int fd, const char *p, int n) {
    int w = 0;
    while (w < n) {
        long k = c->write(fd, p + w, (unsigned long) (n - w));
        if (k <= 0) return -1;
        w += (int) k;
    }
    return 0;
}

/* host is "name@address": the name goes in the TLS server name, the connection goes to the
 * address (Java's own lookup, DnsLookup: the unit's resolver would ask the car's carrier DNS) */
static int fetch(const char *ssl_lib, const char *c_lib, const char *host_arg, const char *port,
                 const char *request, int request_len, const char *out_path, int max_bytes, int timeout_ms) {
    char host[256];
    int hn = 0;
    while (host_arg[hn] && host_arg[hn] != '@' && hn < (int) sizeof host - 1) { host[hn] = host_arg[hn]; hn++; }
    host[hn] = 0;
    if (host_arg[hn] != '@' || !host_arg[hn + 1]) return E_ARGS;
    const char *addr = host_arg + hn + 1;
    struct libc c;
    int rc = libc_load(&c, c_lib);
    if (rc) return rc;
    void *ssl_h = dlopen(ssl_lib, RTLD_NOW);
    if (!ssl_h) return E_LIB;

    /* OpenSSL 1.0.x names first (the unit), then 1.1+/3 (a computer) */
    int (*p_init_old)(void) = dlsym(ssl_h, "SSL_library_init");
    int (*p_init_new)(unsigned long long, const void *) = dlsym(ssl_h, "OPENSSL_init_ssl");
    const void *(*p_method)(void) = dlsym(ssl_h, "SSLv23_client_method");
    if (!p_method) p_method = dlsym(ssl_h, "TLS_client_method");
    void *(*p_ctx_new)(const void *) = dlsym(ssl_h, "SSL_CTX_new");
    void (*p_ctx_free)(void *) = dlsym(ssl_h, "SSL_CTX_free");
    long (*p_ctx_ctrl)(void *, int, long, void *) = dlsym(ssl_h, "SSL_CTX_ctrl");
    unsigned long (*p_ctx_set_options)(void *, unsigned long) = dlsym(ssl_h, "SSL_CTX_set_options");
    void *(*p_new)(void *) = dlsym(ssl_h, "SSL_new");
    void (*p_free)(void *) = dlsym(ssl_h, "SSL_free");
    long (*p_ctrl)(void *, int, long, void *) = dlsym(ssl_h, "SSL_ctrl");
    int (*p_set_fd)(void *, int) = dlsym(ssl_h, "SSL_set_fd");
    int (*p_ssl_connect)(void *) = dlsym(ssl_h, "SSL_connect");
    int (*p_ssl_write)(void *, const void *, int) = dlsym(ssl_h, "SSL_write");
    int (*p_ssl_read)(void *, void *, int) = dlsym(ssl_h, "SSL_read");
    int (*p_get_error)(const void *, int) = dlsym(ssl_h, "SSL_get_error");
    int (*p_shutdown)(void *) = dlsym(ssl_h, "SSL_shutdown");
    if ((!p_init_old && !p_init_new) || !p_method || !p_ctx_new || !p_ctx_free || !p_new || !p_free || !p_ctrl
            || !p_set_fd || !p_ssl_connect || !p_ssl_write || !p_ssl_read || !p_get_error) return E_SYM;

    int fd = home_connect(&c, addr, port, SOCK_STREAM, timeout_ms);
    if (fd < 0) return fd;
    void *ctx = 0, *ssl = 0;
    int out = -1, clean = 0;
    char *buf = 0;
    if (p_init_new) p_init_new(0, 0); else p_init_old();
    ctx = p_ctx_new(p_method());
    if (!ctx) { rc = E_CTX; goto done; }
    if (p_ctx_set_options) p_ctx_set_options(ctx, SSL_OP_NO_SSLv2 | SSL_OP_NO_SSLv3);
    else if (p_ctx_ctrl) p_ctx_ctrl(ctx, SSL_CTRL_OPTIONS, SSL_OP_NO_SSLv2 | SSL_OP_NO_SSLv3, 0);
    ssl = p_new(ctx);
    if (!ssl) { rc = E_CTX; goto done; }
    p_ctrl(ssl, SSL_CTRL_SET_TLSEXT_HOSTNAME, 0, (void *) host);   /* server name: GitHub's hosts need it */
    if (p_set_fd(ssl, fd) != 1 || p_ssl_connect(ssl) != 1) { rc = E_HANDSHAKE; goto done; }
    int sent = 0;
    while (sent < request_len) {
        int n = p_ssl_write(ssl, request + sent, request_len - sent);
        if (n <= 0) { rc = E_SEND; goto done; }
        sent += n;
    }
    buf = c.malloc(BUF);
    if (!buf) { rc = E_CTX; goto done; }
    out = open_new(&c, out_path);
    if (out < 0) { rc = E_FILE; goto done; }
    int total = 0;
    for (;;) {
        int n = p_ssl_read(ssl, buf, BUF);
        if (n <= 0) {
            /* the server's TLS close (Connection: close) ends a whole answer; a dropped connection,
             * a read error or a timeout doesn't: Java decides from the HTTP framing (E_CUT) */
            clean = p_get_error(ssl, n) == SSL_ERROR_ZERO_RETURN;
            break;
        }
        if (n > max_bytes - total) { rc = E_TOO_BIG; goto done; }
        if (write_all(&c, out, buf, n)) { rc = E_FILE; goto done; }
        total += n;
    }
    rc = total == 0 ? E_READ : clean ? total : E_CUT;
done:
    if (out >= 0) c.close(out);
    if (buf) c.free(buf);
    if (ssl) { if (clean && p_shutdown) p_shutdown(ssl); p_free(ssl); }   /* no shutdown after an error (OpenSSL) */
    if (ctx) p_ctx_free(ctx);
    c.close(fd);
    return rc;
}

/* one UDP query to server:port (DnsLookup's bytes) on a socket held to home Wi-Fi; the answer to out_path */
static int dns(const char *c_lib, const char *server, const char *port, const char *query, int query_len,
               const char *out_path, int timeout_ms) {
    struct libc c;
    int rc = libc_load(&c, c_lib);
    if (rc) return rc;
    int fd = home_connect(&c, server, port, SOCK_DGRAM, timeout_ms);
    if (fd < 0) return fd;
    char *buf = c.malloc(1500);
    int out = -1;
    if (!buf) { rc = E_CTX; goto done; }
    if (c.write(fd, query, (unsigned long) query_len) != query_len) { rc = E_SEND; goto done; }
    long n = c.read(fd, buf, 1500);
    if (n <= 0) { rc = E_READ; goto done; }
    out = open_new(&c, out_path);
    if (out < 0 || write_all(&c, out, buf, (int) n)) { rc = E_FILE; goto done; }
    rc = (int) n;
done:
    if (out >= 0) c.close(out);
    if (buf) c.free(buf);
    c.close(fd);
    return rc;
}

JNIEXPORT jint JNICALL Java_hmgapi_engine_NativeFetch_fetch(JNIEnv *env, jclass cls, jstring ssl_lib, jstring c_lib,
        jstring host, jstring port, jbyteArray request, jstring out_path, jint max_bytes, jint timeout_ms) {
    (void) cls;
    if (!ssl_lib || !c_lib || !host || !port || !request || !out_path || max_bytes <= 0 || timeout_ms <= 0) return E_ARGS;
    const char *s = (*env)->GetStringUTFChars(env, ssl_lib, 0), *c = (*env)->GetStringUTFChars(env, c_lib, 0);
    const char *h = (*env)->GetStringUTFChars(env, host, 0), *p = (*env)->GetStringUTFChars(env, port, 0);
    const char *o = (*env)->GetStringUTFChars(env, out_path, 0);
    jsize len = (*env)->GetArrayLength(env, request);
    jbyte *req = (*env)->GetByteArrayElements(env, request, 0);
    int rc = (s && c && h && p && o && req) ? fetch(s, c, h, p, (const char *) req, (int) len, o, (int) max_bytes, (int) timeout_ms) : E_ARGS;
    if (req) (*env)->ReleaseByteArrayElements(env, request, req, JNI_ABORT);
    if (o) (*env)->ReleaseStringUTFChars(env, out_path, o);
    if (p) (*env)->ReleaseStringUTFChars(env, port, p);
    if (h) (*env)->ReleaseStringUTFChars(env, host, h);
    if (c) (*env)->ReleaseStringUTFChars(env, c_lib, c);
    if (s) (*env)->ReleaseStringUTFChars(env, ssl_lib, s);
    return rc;
}

JNIEXPORT jint JNICALL Java_hmgapi_engine_NativeFetch_dns(JNIEnv *env, jclass cls, jstring c_lib, jstring server,
        jstring port, jbyteArray query, jstring out_path, jint timeout_ms) {
    (void) cls;
    if (!c_lib || !server || !port || !query || !out_path || timeout_ms <= 0) return E_ARGS;
    const char *c = (*env)->GetStringUTFChars(env, c_lib, 0), *sv = (*env)->GetStringUTFChars(env, server, 0);
    const char *p = (*env)->GetStringUTFChars(env, port, 0), *o = (*env)->GetStringUTFChars(env, out_path, 0);
    jsize len = (*env)->GetArrayLength(env, query);
    jbyte *q = (*env)->GetByteArrayElements(env, query, 0);
    int rc = (c && sv && p && o && q) ? dns(c, sv, p, (const char *) q, (int) len, o, (int) timeout_ms) : E_ARGS;
    if (q) (*env)->ReleaseByteArrayElements(env, query, q, JNI_ABORT);
    if (o) (*env)->ReleaseStringUTFChars(env, out_path, o);
    if (p) (*env)->ReleaseStringUTFChars(env, port, p);
    if (sv) (*env)->ReleaseStringUTFChars(env, server, sv);
    if (c) (*env)->ReleaseStringUTFChars(env, c_lib, c);
    return rc;
}
