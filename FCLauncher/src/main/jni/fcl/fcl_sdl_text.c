#include "fcl_bridge.h"

#include <android/log.h>
#include <jni.h>
#include <stdbool.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>

#define FCL_SDL_TEXT_TAG "FCLSDLText"

static _Atomic(FCLSDLKeyboardTextSender) sdl_keyboard_text_sender;
static _Atomic bool sdl_text_bridge_installed;

static size_t encode_utf8_codepoint(uint32_t codepoint, char *output) {
    if (codepoint <= 0x7f) {
        output[0] = (char)codepoint;
        return 1;
    }
    if (codepoint <= 0x7ff) {
        output[0] = (char)(0xc0 | (codepoint >> 6));
        output[1] = (char)(0x80 | (codepoint & 0x3f));
        return 2;
    }
    if (codepoint <= 0xffff) {
        output[0] = (char)(0xe0 | (codepoint >> 12));
        output[1] = (char)(0x80 | ((codepoint >> 6) & 0x3f));
        output[2] = (char)(0x80 | (codepoint & 0x3f));
        return 3;
    }

    output[0] = (char)(0xf0 | (codepoint >> 18));
    output[1] = (char)(0x80 | ((codepoint >> 12) & 0x3f));
    output[2] = (char)(0x80 | ((codepoint >> 6) & 0x3f));
    output[3] = (char)(0x80 | (codepoint & 0x3f));
    return 4;
}

static void throw_out_of_memory(JNIEnv *env) {
    jclass error_class = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
    if (error_class != NULL) {
        (*env)->ThrowNew(env, error_class, "Unable to encode SDL text input");
        (*env)->DeleteLocalRef(env, error_class);
    }
}

static void JNICALL fcl_sdl_native_commit_text(
        JNIEnv *env, jclass clazz, jstring text, jint new_cursor_position) {
    (void)clazz;
    (void)new_cursor_position;

    if (text == NULL) {
        return;
    }

    FCLSDLKeyboardTextSender sender = atomic_load_explicit(
            &sdl_keyboard_text_sender, memory_order_acquire);
    if (sender == NULL) {
        return;
    }

    jsize utf16_length = (*env)->GetStringLength(env, text);
    if (utf16_length <= 0) {
        return;
    }
    if ((size_t)utf16_length > (SIZE_MAX - 1) / 3) {
        throw_out_of_memory(env);
        return;
    }

    const jchar *utf16 = (*env)->GetStringChars(env, text, NULL);
    if (utf16 == NULL) {
        return;
    }

    size_t capacity = (size_t)utf16_length * 3 + 1;
    char *utf8 = malloc(capacity);
    if (utf8 == NULL) {
        (*env)->ReleaseStringChars(env, text, utf16);
        throw_out_of_memory(env);
        return;
    }

    size_t output_length = 0;
    for (jsize index = 0; index < utf16_length; ++index) {
        uint32_t codepoint = utf16[index];
        if (codepoint >= 0xd800 && codepoint <= 0xdbff) {
            if (index + 1 < utf16_length) {
                uint32_t low_surrogate = utf16[index + 1];
                if (low_surrogate >= 0xdc00 && low_surrogate <= 0xdfff) {
                    codepoint = 0x10000
                            + ((codepoint - 0xd800) << 10)
                            + (low_surrogate - 0xdc00);
                    ++index;
                } else {
                    codepoint = 0xfffd;
                }
            } else {
                codepoint = 0xfffd;
            }
        } else if ((codepoint >= 0xdc00 && codepoint <= 0xdfff) || codepoint == 0) {
            // SDL text events are NUL-terminated. Keep the output valid UTF-8
            // without truncating the rest of an unexpected Java string.
            codepoint = 0xfffd;
        }

        output_length += encode_utf8_codepoint(codepoint, utf8 + output_length);
    }
    utf8[output_length] = '\0';

    (*env)->ReleaseStringChars(env, text, utf16);
    sender(utf8);
    free(utf8);
}

__attribute__((visibility("default")))
void fclSetSDLKeyboardTextSender(FCLSDLKeyboardTextSender sender) {
    atomic_store_explicit(&sdl_keyboard_text_sender, sender, memory_order_release);
}

JNIEXPORT jboolean JNICALL
Java_org_libsdl_app_FCLSDLInputBridge_nativeInstallTextBridge(
        JNIEnv *env, jclass clazz) {
    (void)clazz;

    if (atomic_load_explicit(&sdl_text_bridge_installed, memory_order_acquire)) {
        return JNI_TRUE;
    }
    if (atomic_load_explicit(&sdl_keyboard_text_sender, memory_order_acquire) == NULL) {
        __android_log_print(ANDROID_LOG_WARN, FCL_SDL_TEXT_TAG,
                            "SDL keyboard text sender is not available yet");
        return JNI_FALSE;
    }

    jclass input_connection = (*env)->FindClass(env, "org/libsdl/app/SDLInputConnection");
    if (input_connection == NULL) {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        __android_log_print(ANDROID_LOG_WARN, FCL_SDL_TEXT_TAG,
                            "Unable to find SDLInputConnection");
        return JNI_FALSE;
    }

    JNINativeMethod method = {
            "nativeCommitText",
            "(Ljava/lang/String;I)V",
            (void *)fcl_sdl_native_commit_text
    };
    jint result = (*env)->RegisterNatives(env, input_connection, &method, 1);
    (*env)->DeleteLocalRef(env, input_connection);
    if (result != JNI_OK) {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        __android_log_print(ANDROID_LOG_WARN, FCL_SDL_TEXT_TAG,
                            "Unable to replace SDLInputConnection.nativeCommitText");
        return JNI_FALSE;
    }

    atomic_store_explicit(&sdl_text_bridge_installed, true, memory_order_release);
    __android_log_print(ANDROID_LOG_INFO, FCL_SDL_TEXT_TAG,
                        "Installed standard UTF-8 SDL text bridge");
    return JNI_TRUE;
}
