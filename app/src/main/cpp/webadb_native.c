#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#define ANDROID_PUBKEY_MODULUS_SIZE 256
#define ANDROID_PUBKEY_MODULUS_SIZE_WORDS 64
#define ANDROID_PUBKEY_ENCODED_SIZE 524

// ADB 命令定义 (Little-Endian)
#define A_CNXN 0x4e58434e
#define A_AUTH 0x48545541

// AOSP RSAPublicKey 标准内存结构 (524 字节)
typedef struct {
    uint32_t modulus_size_words; // 64
    uint32_t n0inv;              // -1 / N[0] mod 2^32
    uint8_t modulus[ANDROID_PUBKEY_MODULUS_SIZE]; // 模数小端数组
    uint8_t rr[ANDROID_PUBKEY_MODULUS_SIZE];      // R^2 mod N
    uint32_t exponent;           // 公钥指数 (65537)
} RSAPublicKey;

// ADB 24字节 Header 头结构 (Little-Endian)
typedef struct {
    uint32_t command;
    uint32_t arg0;
    uint32_t arg1;
    uint32_t data_length;
    uint32_t data_check;
    uint32_t magic;
} AdbHeader;

// 计算 ADB Checksum (payload 字节累加和 & 0xFFFFFFFF)
static uint32_t calculate_checksum(const uint8_t* data, size_t len) {
    uint32_t sum = 0;
    for (size_t i = 0; i < len; i++) {
        sum += data[i];
    }
    return sum;
}

// 标准 7 字节 ASCII Banner: "host::\0" (彻底避开 JNI Modified-UTF8 C0 80 陷阱)
static const uint8_t STANDARD_BANNER[] = {'h', 'o', 's', 't', ':', ':', '\0'};

JNIEXPORT jbyteArray JNICALL
Java_com_aoooa_webadb_native_WebAdbNative_buildCnxnPacket(
    JNIEnv *env,
    jobject thiz,
    jint version,
    jint max_payload,
    jstring banner_jstr
) {
    // 强制使用纯正 7 字节 ASCII "host::\0"，防止 JNI 将 \0 编码为 C0 80 乱码
    const uint8_t *payload = STANDARD_BANNER;
    size_t payload_len = sizeof(STANDARD_BANNER); // 7 字节

    size_t total_len = sizeof(AdbHeader) + payload_len; // 24 + 7 = 31 字节
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

    key.modulus_size_words = ANDROID_PUBKEY_MODULUS_SIZE_WORDS; // 64
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
