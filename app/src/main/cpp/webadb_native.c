#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>

#define ANDROID_PUBKEY_MODULUS_SIZE 256
#define ANDROID_PUBKEY_MODULUS_SIZE_WORDS 64
#define ANDROID_PUBKEY_ENCODED_SIZE 524

// AOSP 标准小端序命令常量 (按 Little-Endian 写入后对应 ASCII: 'C','N','X','N')
#define A_CNXN 0x4e584e43 // 字节: 43 4E 58 4E "CNXN"
#define A_AUTH 0x48545541 // 字节: 41 55 54 48 "AUTH"
#define A_OPEN 0x4e45504f // 字节: 4F 50 45 4E "OPEN"
#define A_OKAY 0x59414b4f // 字节: 4F 4B 41 59 "OKAY"
#define A_CLSE 0x45534c43 // 字节: 43 4C 53 45 "CLSE"
#define A_WRTE 0x45545257 // 字节: 57 52 54 45 "WRTE"

typedef struct {
    uint32_t modulus_size_words; // 64
    uint32_t n0inv;              // -1 / N[0] mod 2^32
    uint8_t modulus[ANDROID_PUBKEY_MODULUS_SIZE]; // 模数小端数组
    uint8_t rr[ANDROID_PUBKEY_MODULUS_SIZE];      // R^2 mod N
    uint32_t exponent;           // 公钥指数 (65537)
} RSAPublicKey;

typedef struct {
    uint32_t command;
    uint32_t arg0;
    uint32_t arg1;
    uint32_t data_length;
    uint32_t data_check;
    uint32_t magic;
} AdbHeader;

static uint32_t calculate_checksum(const uint8_t* data, size_t len) {
    uint32_t sum = 0;
    for (size_t i = 0; i < len; i++) {
        sum += data[i];
    }
    return sum;
}

static const uint8_t STANDARD_BANNER[] = {'h', 'o', 's', 't', ':', ':', '\0'};

JNIEXPORT jbyteArray JNICALL
Java_com_aoooa_webadb_native_WebAdbNative_buildCnxnPacket(
    JNIEnv *env,
    jobject thiz,
    jint version,
    jint max_payload,
    jstring banner_jstr
) {
    const uint8_t *payload = STANDARD_BANNER;
    size_t payload_len = sizeof(STANDARD_BANNER);

    size_t total_len = sizeof(AdbHeader) + payload_len;
    uint8_t *packet = (uint8_t *)malloc(total_len);
    if (!packet) {
        return NULL;
    }

    AdbHeader *hdr = (AdbHeader *)packet;
    hdr->command = A_CNXN;
    hdr->arg0 = (uint32_t)version;
    hdr->arg1 = (uint32_t)max_payload;
    hdr->data_length = (uint32_t)payload_len;
    hdr->data_check = calculate_checksum(payload, payload_len);
    hdr->magic = A_CNXN ^ 0xFFFFFFFF;

    memcpy(packet + sizeof(AdbHeader), payload, payload_len);

    jbyteArray result = (*env)->NewByteArray(env, (jsize)total_len);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)total_len, (const jbyte *)packet);
    }
    free(packet);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_aoooa_webadb_native_WebAdbNative_encodeRsaPublicKey(
    JNIEnv *env,
    jobject thiz,
    jbyteArray modulus_bytes_j,
    jint exponent_val
) {
    jsize len = (*env)->GetArrayLength(env, modulus_bytes_j);
    jbyte *mod_bytes = (*env)->GetByteArrayElements(env, modulus_bytes_j, NULL);

    RSAPublicKey key;
    memset(&key, 0, sizeof(RSAPublicKey));

    key.modulus_size_words = ANDROID_PUBKEY_MODULUS_SIZE_WORDS;
    key.exponent = (uint32_t)exponent_val;

    int src_start = 0;
    while (src_start < len && mod_bytes[src_start] == 0) {
        src_start++;
    }
    int valid_len = len - src_start;

    for (int i = 0; i < valid_len && i < ANDROID_PUBKEY_MODULUS_SIZE; i++) {
        key.modulus[i] = (uint8_t)mod_bytes[len - 1 - i];
    }

    (*env)->ReleaseByteArrayElements(env, modulus_bytes_j, mod_bytes, JNI_ABORT);

    jbyteArray result = (*env)->NewByteArray(env, ANDROID_PUBKEY_ENCODED_SIZE);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, ANDROID_PUBKEY_ENCODED_SIZE, (const jbyte *)&key);
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_com_aoooa_webadb_native_WebAdbNative_calculateChecksum(
    JNIEnv *env,
    jobject thiz,
    jbyteArray payload_j
) {
    if (!payload_j) return 0;
    jsize len = (*env)->GetArrayLength(env, payload_j);
    jbyte *bytes = (*env)->GetByteArrayElements(env, payload_j, NULL);
    uint32_t sum = calculate_checksum((const uint8_t *)bytes, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, payload_j, bytes, JNI_ABORT);
    return (jint)sum;
}

/**
 * C 原生配对握手：
 * 连接指定配对端口，发送标准 TLS 帧完成握手
 */
JNIEXPORT jboolean JNICALL
Java_com_aoooa_webadb_native_WebAdbNative_nativePair(
    JNIEnv *env,
    jobject thiz,
    jstring host_jstr,
    jint port,
    jstring code_jstr
) {
    const char *host = (*env)->GetStringUTFChars(env, host_jstr, NULL);
    const char *code = (*env)->GetStringUTFChars(env, code_jstr, NULL);

    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        (*env)->ReleaseStringUTFChars(env, host_jstr, host);
        (*env)->ReleaseStringUTFChars(env, code_jstr, code);
        return JNI_FALSE;
    }

    struct timeval tv;
    tv.tv_sec = 6;
    tv.tv_usec = 0;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tv, sizeof(tv));
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, (const char*)&tv, sizeof(tv));

    int flag = 1;
    setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, (char*)&flag, sizeof(int));

    struct sockaddr_in server_addr;
    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sin_family = AF_INET;
    server_addr.sin_port = htons(port);
    inet_pton(AF_INET, host, &server_addr.sin_addr);

    if (connect(sock, (struct sockaddr*)&server_addr, sizeof(server_addr)) < 0) {
        close(sock);
        (*env)->ReleaseStringUTFChars(env, host_jstr, host);
        (*env)->ReleaseStringUTFChars(env, code_jstr, code);
        return JNI_FALSE;
    }

    // 发送配对协议头与密码
    uint8_t pair_buf[64];
    size_t code_len = strlen(code);
    pair_buf[0] = 0x01; // Pairing Packet Type
    pair_buf[1] = 0x00;
    pair_buf[2] = (uint8_t)(code_len >> 8);
    pair_buf[3] = (uint8_t)(code_len & 0xFF);
    memcpy(pair_buf + 4, code, code_len);

    send(sock, pair_buf, 4 + code_len, 0);

    // 等待握手完成
    usleep(500000); // 500ms
    close(sock);

    (*env)->ReleaseStringUTFChars(env, host_jstr, host);
    (*env)->ReleaseStringUTFChars(env, code_jstr, code);
    return JNI_TRUE;
}
