/* libhmgapi_fetch.so: one HTTPS request for HMG-API's engine (hmgapi.engine.NativeFetch): the
 * catalogue, mod packages and HMG-API releases, over home Wi-Fi only (Catalog).
 *
 * Android 4.4's Java TLS shares no cipher suite with GitHub, but the unit's own libssl.so
 * (OpenSSL 1.0.1) has TLS 1.2 with ECDHE and AES-GCM. This opens a TCP connection, does the
 * handshake with that library, sends the request bytes Java made and writes every response
 * byte (headers and body) to a file, up to a cap. Java parses the HTTP.
 *
 * The server's certificate is NOT checked here: the unit's 2013 root store doesn't know GitHub's
 * roots and its clock isn't reliable. Nothing fetched is trusted because of where it came from:
 * a package must have the SHA-256 its catalogue entry names, and the engine checks its signature
 * (the installed mod's author key; HMG-API's release key for HMG-API itself) and refuses a
 * downgrade. A hostile network can make a check find nothing, not get code installed.
 *
 * The socket carries home Wi-Fi's mark (SO_MARK 0x2951: table 95, HomeWifi), so it goes out on home
 * Wi-Fi or not at all (table 95 holds an unreachable default while home Wi-Fi is down), never
 * on the car's mobile data. Root only (the engine is).
 *
 * Built with no libc (as the loader): everything comes through dlopen/dlsym from the unit's
 * libc.so and libssl.so. On a computer (-DHOST, the off-car tests) it uses the system's headers
 * and an OpenSSL 1.1+/3 library, which is why each OpenSSL call has an old and a new name.
 * First written as a mod (an update-check proof of concept), run on an Ioniq 5 US 260507.
 */
#ifdef HOST
#include <jni.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <netdb.h>
#include <string.h>
#include <sys/socket.h>
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
#define AF_INET 2
#define SOCK_STREAM 1
#define O_WRONLY 01
#define O_CREAT 0100
#define O_TRUNC 01000
static void *libc_handle;
#define LIBC_SYM(name) dlsym(libc_handle, #name)
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

/* error codes (negative), so Java can say which step failed */
enum { E_LIB = -1, E_SYM = -2, E_RESOLVE = -3, E_CONNECT = -4, E_CTX = -5, E_HANDSHAKE = -6, E_SEND = -7,
       E_FILE = -8, E_TOO_BIG = -9, E_READ = -10, E_ARGS = -11 };

#define SSL_CTRL_OPTIONS 32
#define SSL_CTRL_SET_TLSEXT_HOSTNAME 55
#define SSL_OP_NO_SSLv2 0x01000000L
#define SSL_OP_NO_SSLv3 0x02000000L

/* host is "name" or "name@address": the name goes in the TLS server name, and the connection goes
 * to the address when there is one (Java's own lookup, DnsLookup: the unit's resolver can't be
 * used by a mod's service on home Wi-Fi, see there) */
static int fetch(const char *ssl_lib, const char *c_lib, const char *host_arg, const char *port,
                 const char *request, int request_len, const char *out_path, int max_bytes) {
    char host[256];
    const char *addr = host_arg;
    int hn = 0;
    while (host_arg[hn] && host_arg[hn] != '@' && hn < (int) sizeof host - 1) { host[hn] = host_arg[hn]; hn++; }
    host[hn] = 0;
    if (host_arg[hn] == '@') addr = host_arg + hn + 1;
    else if (host_arg[hn]) return E_ARGS;
    else addr = host;
    void *ssl_h = dlopen(ssl_lib, RTLD_NOW);
    if (!ssl_h) return E_LIB;
#ifndef HOST
    libc_handle = dlopen(c_lib, RTLD_NOW);
    if (!libc_handle) return E_LIB;
#else
    (void) c_lib;
#endif
    int (*p_getaddrinfo)(const char *, const char *, const struct addrinfo *, struct addrinfo **) = LIBC_SYM(getaddrinfo);
    void (*p_freeaddrinfo)(struct addrinfo *) = LIBC_SYM(freeaddrinfo);
    int (*p_socket)(int, int, int) = LIBC_SYM(socket);
    int (*p_connect)(int, const struct sockaddr *, sock_len) = LIBC_SYM(connect);
    int (*p_setsockopt)(int, int, int, const void *, sock_len) = LIBC_SYM(setsockopt);
    int (*p_close)(int) = LIBC_SYM(close);
    int (*p_open)(const char *, int, ...) = LIBC_SYM(open);
    long (*p_write)(int, const void *, unsigned long) = LIBC_SYM(write);
    if (!p_getaddrinfo || !p_freeaddrinfo || !p_socket || !p_connect || !p_close || !p_open || !p_write) return E_SYM;

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
    int (*p_shutdown)(void *) = dlsym(ssl_h, "SSL_shutdown");
    if ((!p_init_old && !p_init_new) || !p_method || !p_ctx_new || !p_ctx_free || !p_new || !p_free || !p_ctrl
            || !p_set_fd || !p_ssl_connect || !p_ssl_write || !p_ssl_read) return E_SYM;

    struct addrinfo hints, *res = 0, *a;
    memset(&hints, 0, sizeof hints);
    hints.ai_family = AF_INET;          /* home Wi-Fi is IPv4 only for mods */
    hints.ai_socktype = SOCK_STREAM;
    if (p_getaddrinfo(addr, port, &hints, &res) != 0 || !res) return E_RESOLVE;
    int fd = -1;
    for (a = res; a; a = a->ai_next) {
        fd = p_socket(a->ai_family, a->ai_socktype, a->ai_protocol);
        if (fd < 0) continue;
#ifndef HOST
        /* home Wi-Fi's mark on the socket itself, so the connection's route is table 95's from the
         * start (home Wi-Fi, or unreachable): never the main table's mobile data */
        if (p_setsockopt) {
            unsigned int mark = 0x2951;
            p_setsockopt(fd, 1 /* SOL_SOCKET */, 36 /* SO_MARK */, &mark, sizeof mark);
        }
#endif
        if (p_connect(fd, a->ai_addr, a->ai_addrlen) == 0) break;
        p_close(fd);
        fd = -1;
    }
    p_freeaddrinfo(res);
    if (fd < 0) return E_CONNECT;

    int rc;
    void *ctx = 0, *ssl = 0;
    int out = -1;
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
    out = p_open(out_path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (out < 0) { rc = E_FILE; goto done; }
    static char buf[16384];
    int total = 0;
    for (;;) {
        int n = p_ssl_read(ssl, buf, sizeof buf);
        if (n <= 0) break;                 /* the server closes after the response (Connection: close) */
        if (total + n > max_bytes) { rc = E_TOO_BIG; goto done; }
        int w = 0;
        while (w < n) {
            long k = p_write(out, buf + w, (unsigned long) (n - w));
            if (k <= 0) { rc = E_FILE; goto done; }
            w += (int) k;
        }
        total += n;
    }
    rc = total > 0 ? total : E_READ;
done:
    if (out >= 0) p_close(out);
    if (ssl) { if (p_shutdown) p_shutdown(ssl); p_free(ssl); }
    if (ctx) p_ctx_free(ctx);
    p_close(fd);
    return rc;
}

JNIEXPORT jint JNICALL Java_hmgapi_engine_NativeFetch_fetch(JNIEnv *env, jclass cls, jstring ssl_lib, jstring c_lib,
        jstring host, jstring port, jbyteArray request, jstring out_path, jint max_bytes) {
    (void) cls;
    if (!ssl_lib || !c_lib || !host || !port || !request || !out_path || max_bytes <= 0) return E_ARGS;
    const char *s = (*env)->GetStringUTFChars(env, ssl_lib, 0), *c = (*env)->GetStringUTFChars(env, c_lib, 0);
    const char *h = (*env)->GetStringUTFChars(env, host, 0), *p = (*env)->GetStringUTFChars(env, port, 0);
    const char *o = (*env)->GetStringUTFChars(env, out_path, 0);
    jsize len = (*env)->GetArrayLength(env, request);
    jbyte *req = (*env)->GetByteArrayElements(env, request, 0);
    int rc = (s && c && h && p && o && req) ? fetch(s, c, h, p, (const char *) req, (int) len, o, (int) max_bytes) : E_ARGS;
    if (req) (*env)->ReleaseByteArrayElements(env, request, req, JNI_ABORT);
    if (o) (*env)->ReleaseStringUTFChars(env, out_path, o);
    if (p) (*env)->ReleaseStringUTFChars(env, port, p);
    if (h) (*env)->ReleaseStringUTFChars(env, host, h);
    if (c) (*env)->ReleaseStringUTFChars(env, c_lib, c);
    if (s) (*env)->ReleaseStringUTFChars(env, ssl_lib, s);
    return rc;
}
