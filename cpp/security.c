#include <jni.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <signal.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <android/log.h>

#define TAG "cipher_sec"

// ─── ROOT CHECK ──────────────────────────────────────────────────────────────

static const char *ROOT_PATHS[] = {
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/su",
        "/data/local/su",
        "/data/local/bin/su",
        "/data/local/xbin/su",
        "/system/bin/failsafe/su",
        "/system/xbin/busybox",
        "/magisk/.core/bin/su",
        NULL
};

static int check_root_paths() {
    struct stat st;
    for (int i = 0; ROOT_PATHS[i]; i++) {
        if (stat(ROOT_PATHS[i], &st) == 0) return 1;
    }
    return 0;
}

static int check_build_tags() {
    // reads /proc/version for test-keys signature
    FILE *f = fopen("/proc/version", "r");
    if (!f) return 0;
    char buf[256];
    fgets(buf, sizeof(buf), f);
    fclose(f);
    return strstr(buf, "test-keys") != NULL;
}

// ─── FRIDA CHECK ─────────────────────────────────────────────────────────────

static const char *FRIDA_SIGNATURES[] = {
        "frida",
        "gum-js-loop",
        "gmain",
        "linjector",
        "frida-agent",
        "frida-helper",
        NULL
};

static int check_frida_maps() {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return 0;
    char line[512];
    while (fgets(line, sizeof(line), f)) {
        for (int i = 0; FRIDA_SIGNATURES[i]; i++) {
            if (strstr(line, FRIDA_SIGNATURES[i])) {
                fclose(f);
                return 1;
            }
        }
    }
    fclose(f);
    return 0;
}

static int check_frida_port() {
    // tries to connect to frida default ports
    int ports[] = {27042, 27043, 27044, 27045, 0};
    for (int i = 0; ports[i]; i++) {
        int sock = socket(AF_INET, SOCK_STREAM, 0);
        if (sock < 0) continue;
        struct sockaddr_in addr;
        memset(&addr, 0, sizeof(addr));
        addr.sin_family = AF_INET;
        addr.sin_port = htons(ports[i]);
        addr.sin_addr.s_addr = htonl(0x7F000001); // 127.0.0.1
        struct timeval tv = {0, 50000}; // 50ms timeout
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
        int result = connect(sock, (struct sockaddr *)&addr, sizeof(addr));
        close(sock);
        if (result == 0) return 1;
    }
    return 0;
}

static int check_frida_threads() {
    // reads /proc/self/task and checks thread names for frida signatures
    FILE *f = fopen("/proc/self/status", "r");
    if (!f) return 0;
    char line[256];
    while (fgets(line, sizeof(line), f)) {
        for (int i = 0; FRIDA_SIGNATURES[i]; i++) {
            if (strstr(line, FRIDA_SIGNATURES[i])) {
                fclose(f);
                return 1;
            }
        }
    }
    fclose(f);
    return 0;
}

// ─── DEBUGGER CHECK ──────────────────────────────────────────────────────────

static int check_debugger() {
    FILE *f = fopen("/proc/self/status", "r");
    if (!f) return 0;
    char line[256];
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            int pid = atoi(line + 10);
            fclose(f);
            return pid != 0; // non-zero means a debugger is attached
        }
    }
    fclose(f);
    return 0;
}

// ─── EMULATOR CHECK ──────────────────────────────────────────────────────────

static int check_emulator() {
    // checks qemu driver which is present on all AVDs
    struct stat st;
    return stat("/dev/socket/qemud", &st) == 0
           || stat("/dev/qemu_pipe", &st) == 0
           || stat("/dev/goldfish_pipe", &st) == 0;
}

// ─── INTEGRITY CHECK (certificate SHA-256) ───────────────────────────────────

// Your fingerprint split across two halves — makes it harder to find with strings
static const char CERT_PART1[] = "2E3FA2A8FCF9AAAFF808";
static const char CERT_PART2[] = "5C72443D2DE7C8CC3B04CCC84A8EBFBFAF116C82E668";

static int check_signature(JNIEnv *env, jobject ctx) {
    // reconstruct expected hash at runtime
    char expected[65];
    snprintf(expected, sizeof(expected), "%s%s", CERT_PART1, CERT_PART2);

    // call PackageManager from native via JNI
    jclass ctx_class = (*env)->GetObjectClass(env, ctx);
    jmethodID getPM = (*env)->GetMethodID(env, ctx_class,
                                          "getPackageManager", "()Landroid/content/pm/PackageManager;");
    jobject pm = (*env)->CallObjectMethod(env, ctx, getPM);

    jmethodID getPkgName = (*env)->GetMethodID(env, ctx_class,
                                               "getPackageName", "()Ljava/lang/String;");
    jstring pkgName = (*env)->CallObjectMethod(env, ctx, getPkgName);

    jclass pm_class = (*env)->GetObjectClass(env, pm);
    jmethodID getPkgInfo = (*env)->GetMethodID(env, pm_class,
                                               "getPackageInfo",
                                               "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");

    // GET_SIGNING_CERTIFICATES = 134217728 (0x8000000)
    jobject pkgInfo = (*env)->CallObjectMethod(env, pm, getPkgInfo, pkgName, 0x8000000);
    if (!pkgInfo) return 0;

    jclass pi_class = (*env)->GetObjectClass(env, pkgInfo);
    jfieldID sigInfoField = (*env)->GetFieldID(env, pi_class,
                                               "signingInfo", "Landroid/content/pm/SigningInfo;");
    jobject signingInfo = (*env)->GetObjectField(env, pkgInfo, sigInfoField);
    if (!signingInfo) return 0;

    jclass si_class = (*env)->GetObjectClass(env, signingInfo);
    jmethodID getSigners = (*env)->GetMethodID(env, si_class,
                                               "getApkContentsSigners", "()[Landroid/content/pm/Signature;");
    jobjectArray sigs = (*env)->CallObjectMethod(env, signingInfo, getSigners);
    if (!sigs) return 0;

    jsize count = (*env)->GetArrayLength(env, sigs);

    // use MessageDigest via JNI to SHA-256 each signature
    jclass md_class = (*env)->FindClass(env, "java/security/MessageDigest");
    jmethodID getInstance = (*env)->GetStaticMethodID(env, md_class,
                                                      "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    jstring algo = (*env)->NewStringUTF(env, "SHA-256");

    for (jsize i = 0; i < count; i++) {
        jobject sig = (*env)->GetObjectArrayElement(env, sigs, i);
        jclass sig_class = (*env)->GetObjectClass(env, sig);
        jmethodID toBytes = (*env)->GetMethodID(env, sig_class, "toByteArray", "()[B");
        jbyteArray sigBytes = (*env)->CallObjectMethod(env, sig, toBytes);

        jobject md = (*env)->CallStaticObjectMethod(env, md_class, getInstance, algo);
        jmethodID digest = (*env)->GetMethodID(env, md_class, "digest", "([B)[B");
        jbyteArray hash = (*env)->CallObjectMethod(env, md, digest, sigBytes);

        jsize len = (*env)->GetArrayLength(env, hash);
        jbyte *bytes = (*env)->GetByteArrayElements(env, hash, NULL);

        // convert to hex
        char hex[65] = {0};
        for (int j = 0; j < len && j < 32; j++) {
            snprintf(hex + j * 2, 3, "%02X", (unsigned char)bytes[j]);
        }
        (*env)->ReleaseByteArrayElements(env, hash, bytes, JNI_ABORT);

        if (strcmp(hex, expected) == 0) return 1;
    }
    return 0;
}

// ─── KILL ────────────────────────────────────────────────────────────────────

static void hard_kill() {
    raise(SIGKILL); // can't be caught or hooked from Java layer
}

// ─── JNI EXPORTS ─────────────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_hardcode_cipher_SecurityCheck_nativeIsSafe(JNIEnv *env, jclass cls, jobject ctx) {

    if (check_root_paths())    { hard_kill(); return JNI_FALSE; }
    if (check_build_tags())    { hard_kill(); return JNI_FALSE; }
    if (check_debugger())      { hard_kill(); return JNI_FALSE; }
    if (check_emulator())      { hard_kill(); return JNI_FALSE; }
    if (check_frida_maps())    { hard_kill(); return JNI_FALSE; }
    if (check_frida_port())    { hard_kill(); return JNI_FALSE; }
    if (check_frida_threads()) { hard_kill(); return JNI_FALSE; }
    if (!check_signature(env, ctx)) { hard_kill(); return JNI_FALSE; }

    return JNI_TRUE;
}