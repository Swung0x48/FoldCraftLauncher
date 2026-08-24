#include "fcl_bridge.h"

#include <android/log.h>
#include <dlfcn.h>

#define FCL_SDL_TEXT_TAG "FCLSDLText"

// SDL_SendKeyboardText is intentionally an internal, hidden SDL symbol. This
// source is compiled into libSDL3.so so the call stays within the same DSO.
extern void SDL_SendKeyboardText(const char *text);

static void fcl_sdl_send_keyboard_text(const char *utf8_text) {
    SDL_SendKeyboardText(utf8_text);
}

__attribute__((constructor))
static void register_fcl_sdl_text_sender(void) {
    // FCLBridge loads libfcl through the VM before SDLActivity loads SDL3.
    // NOLOAD preserves that ownership and makes a load-order regression fall
    // back to upstream text input instead of silently loading a second way.
    void *fcl_handle = dlopen("libfcl.so", RTLD_NOW | RTLD_LOCAL | RTLD_NOLOAD);
    if (fcl_handle == NULL) {
        __android_log_print(ANDROID_LOG_WARN, FCL_SDL_TEXT_TAG,
                            "Unable to open libfcl.so: %s", dlerror());
        return;
    }

    dlerror();
    typedef void (*FCLSDLKeyboardTextSetter)(FCLSDLKeyboardTextSender sender);
    FCLSDLKeyboardTextSetter setter = NULL;
    void *setter_symbol = dlsym(fcl_handle, "fclSetSDLKeyboardTextSender");
    const char *symbol_error = dlerror();
    if (symbol_error == NULL) {
        *(void **)(&setter) = setter_symbol;
    }

    if (setter != NULL) {
        setter(fcl_sdl_send_keyboard_text);
    } else {
        __android_log_print(ANDROID_LOG_WARN, FCL_SDL_TEXT_TAG,
                            "Unable to resolve fclSetSDLKeyboardTextSender: %s",
                            symbol_error != NULL ? symbol_error : "unknown error");
    }

    // Keep the handle for the process lifetime because libfcl now owns a
    // function pointer into this SDL DSO. This constructor runs only once.
}
