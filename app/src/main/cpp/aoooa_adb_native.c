#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#define ANDROID_PUBKEY_MODULUS_SIZE 256
#define ANDROID_PUBKEY_MODULUS_SIZE_WORDS 64
#define ANDROID_PUBKEY_ENCODED_SIZE 524

// ADB 协议版本：0x01000001 起发送端不再填写 data_check（AOSP adb.h A_VERSION_SKIP_CHECKSUM）
#define A_VERSION_SKIP_CHECKSUM 0x01000001u

// AOSP 标准小端序命令常量 (按 Little-Endian 写入后对应 ASCII: 'C','N','X','N')
#define A_CNXN 0x4e584e43 // 字节: 43 4E 58 4E "CNXN"

// 头结构与 AOSP apacket 的 amessage 布局一致：24 字节全 uint32
typedef struct {
    uint32_t command;
    uint32_t arg0;
    uint32_t arg1;
    uint32_t data_length;
    uint32_t data_check;
    uint32_t magic;
} AdbHeader;

// 布局被直接按字节序列化，必须与 AOSP 结构体完全对齐
_Static_assert(sizeof(AdbHeader) == 24, "AdbHeader must be 24 bytes");

static uint32_t calculate_checksum(const uint8_t* data, size_t len) {
    uint32_t sum = 0;
    for (size_t i = 0; i < len; i++) {
        sum += data[i];
    }
    return sum;
}

JNIEXPORT jbyteArray JNICALL
Java_com_aoooa_adb_native_AdbNative_buildCnxnPacket(
    JNIEnv *env,
    jobject thiz,
    jint version,
    jint max_payload,
    jstring banner_jstr
) {
    // banner 由调用方传入（含 features 列表），adbd 据此协商扩展能力，不可硬编码
    const char *banner = (*env)->GetStringUTFChars(env, banner_jstr, NULL);
    if (banner == NULL) {
        return NULL;
    }

    // payload 必须包含结尾 NUL，长度按 strlen + 1 计算
    size_t payload_len = strlen(banner) + 1;
    size_t total_len = sizeof(AdbHeader) + payload_len;

    jbyteArray result = NULL;
    uint8_t *packet = (uint8_t *)malloc(total_len);
    if (packet == NULL) {
        (*env)->ReleaseStringUTFChars(env, banner_jstr, banner);
        return NULL;
    }

    AdbHeader *hdr = (AdbHeader *)packet;
    hdr->command = A_CNXN;
    hdr->arg0 = (uint32_t)version;
    hdr->arg1 = (uint32_t)max_payload;
    hdr->data_length = (uint32_t)payload_len;
    // 协议版本 >= A_VERSION_SKIP_CHECKSUM 时该字段必须为 0
    hdr->data_check = ((uint32_t)version >= A_VERSION_SKIP_CHECKSUM)
                          ? 0u
                          : calculate_checksum((const uint8_t *)banner, payload_len);
    hdr->magic = A_CNXN ^ 0xFFFFFFFF;

    memcpy(packet + sizeof(AdbHeader), banner, payload_len);

    result = (*env)->NewByteArray(env, (jsize)total_len);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)total_len, (const jbyte *)packet);
    }

    free(packet);
    (*env)->ReleaseStringUTFChars(env, banner_jstr, banner);
    return result;
}

/**
 * ADB 认证公钥由 Kotlin 侧 AdbCrypto 以 Base64(524B RSAPublicKey) 形式生成，
 * 该结构含 Montgomery 参数 n0inv / rr，用纯 C 重算易出错且无调用方，故不在本层提供。
 */

/**
 * 无线配对由 Kotlin 侧 AdbPairing 实现（TLS 1.3 + EKM 绑定 + SPAKE2 + AES-128-GCM
 * 加密 PeerInfo）。配对帧为 6 字节头（version=1 / type / payload 长度 htonl），
 * 且必须在 TLS 之上进行，无法由单次明文 socket 发送完成，故本层不提供配对实现。
 */
