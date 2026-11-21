#include <jni.h>
#include <stdint.h>

#define A_CNXN 0x4e584e43u
#define INITIAL_MAX_PAYLOAD 4096

static void put_le32(uint8_t *dst, uint32_t value) {
    for (unsigned i = 0; i < 4; ++i) dst[i] = (uint8_t)(value >> (8 * i));
}

JNIEXPORT jbyteArray JNICALL
Java_com_aoooa_adb_native_AdbNative_buildCnxnPacket(JNIEnv *env, jobject self, jint version,
                                                    jint max_payload, jbyteArray payload) {
    (void)self;
    jsize size = (*env)->GetArrayLength(env, payload);
    if (size > INITIAL_MAX_PAYLOAD) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"), "CNXN banner exceeds 4096 bytes");
        return NULL;
    }
    uint8_t packet[24 + INITIAL_MAX_PAYLOAD];
    (*env)->GetByteArrayRegion(env, payload, 0, size, (jbyte *)(packet + 24));
    if ((*env)->ExceptionCheck(env)) return NULL;
    uint32_t sum = 0;
    for (jsize i = 0; i < size; ++i) sum += packet[24 + i];
    put_le32(packet, A_CNXN);
    put_le32(packet + 4, (uint32_t)version);
    put_le32(packet + 8, (uint32_t)max_payload);
    put_le32(packet + 12, (uint32_t)size);
    put_le32(packet + 16, sum);
    put_le32(packet + 20, ~A_CNXN);
    jbyteArray result = (*env)->NewByteArray(env, 24 + size);
    if (result != NULL) (*env)->SetByteArrayRegion(env, result, 0, 24 + size, (const jbyte *)packet);
    return result;
}
