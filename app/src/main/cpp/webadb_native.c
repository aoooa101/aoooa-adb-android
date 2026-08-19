#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#define ANDROID_PUBKEY_MODULUS_SIZE 256
#define ANDROID_PUBKEY_MODULUS_SIZE_WORDS 64
#define ANDROID_PUBKEY_ENCODED_SIZE 524

// ADB 命令定义
#define A_CNXN 0x4e58434e
#define A_AUTH 0x48545541

// AOSP RSAPublicKey 标准内存结构
typedef struct {
    uint32_t modulus_size_words; // 64
    uint32_t n0inv;              // -1 / N[0] mod 2^32
    uint8_t modulus[ANDROID_PUBKEY_MODULUS_SIZE]; // 小端模式
    uint8_t rr[ANDROID_PUBKEY_MODULUS_SIZE];      // R^2 mod N
    uint32_t exponent;           // 公钥指数 (65537)
} RSAPublicKey;

// ADB 24字节Header头结构 (Little-Endian)
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

JNIEXPORT jbyteArray JNICALL
Java_com_aoooa_webadb_native_WebAdbNative_buildCnxnPacket(
    JNIEnv *env,
    jobject thiz,
    jint version,
    jint max_payload,
    jstring banner_jstr
) {
    const char *banner = (*env)->GetStringUTFChars(env, banner_jstr, NULL);
    size_t payload_len = strlen(banner);

    size_t total_len = sizeof(AdbHeader) + payload_len;
    uint8_t *packet = (uint8_t *)malloc(total_len);
    if (!packet) {
        (*env)->ReleaseStringUTFChars(env, banner_jstr, banner);
        return NULL;
    }

    AdbHeader *hdr = (AdbHeader *)packet;
    hdr->command = A_CNXN;
    hdr->arg0 = (uint32_t)version;
    hdr->arg1 = (uint32_t)max_payload;
    hdr->data_length = (uint32_t)payload_len;
    hdr->data_check = calculate_checksum((const uint8_t *)banner, payload_len);
    hdr->magic = A_CNXN ^ 0xFFFFFFFF;

    memcpy(packet + sizeof(AdbHeader), banner, payload_len);

    (*env)->ReleaseStringUTFChars(env, banner_jstr, banner);

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

    // 将大端/Java BigInteger 字节转化为 256 字节的小端模式 (Little-Endian)
    // Java getByteArray() 通常是 Big-Endian 且可能有前导 0 字节
    int src_start = 0;
    while (src_start < len && mod_bytes[src_start] == 0) {
        src_start++;
    }
    int valid_len = len - src_start;

    for (int i = 0; i < valid_len && i < ANDROID_PUBKEY_MODULUS_SIZE; i++) {
        // 大端转小端：源数组倒序放入 destination
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
