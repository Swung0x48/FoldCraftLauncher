#include <SDL3/SDL.h>

#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

#define LOG_TAG "FCLSDLWindow"

typedef SDL_Window *(SDLCALL *SDL_CreateWindowFn)(const char *title,
                                                   int width,
                                                   int height,
                                                   SDL_WindowFlags flags);
typedef void (SDLCALL *SDL_DestroyWindowFn)(SDL_Window *window);

static pthread_once_t sdl_symbols_once = PTHREAD_ONCE_INIT;
static pthread_mutex_t window_mutex = PTHREAD_MUTEX_INITIALIZER;
static void *sdl_handle;
static SDL_CreateWindowFn real_create_window;
static SDL_DestroyWindowFn real_destroy_window;
static SDL_Window *renderpearl_window;
static unsigned int renderpearl_window_references;
static bool renderpearl_window_handed_off;

static void resolve_sdl_symbols(void)
{
    // SDLJVMActivity loads libSDL3 before LWJGL opens this shim. Opening the
    // SONAME again gives us an unambiguous handle to the upstream functions
    // while also making libSDL3 a real dependency of this DSO.
    sdl_handle = dlopen("libSDL3.so", RTLD_NOW | RTLD_LOCAL);
    if (sdl_handle) {
        real_create_window = (SDL_CreateWindowFn)dlsym(sdl_handle, "SDL_CreateWindow");
        real_destroy_window = (SDL_DestroyWindowFn)dlsym(sdl_handle, "SDL_DestroyWindow");
    }

    if (!real_create_window || !real_destroy_window) {
        __android_log_print(ANDROID_LOG_ERROR,
                            LOG_TAG,
                            "Unable to resolve upstream SDL window functions: %s",
                            dlerror());
    }
}

static bool is_renderpearl_utility_window(const char *title, SDL_WindowFlags flags)
{
    static const char utility_title[] =
            "Minecraft - RenderPearl OpenGL Hidden Utility Window";
    const SDL_WindowFlags required_flags = SDL_WINDOW_OPENGL |
                                           SDL_WINDOW_HIDDEN |
                                           SDL_WINDOW_UTILITY |
                                           SDL_WINDOW_NOT_FOCUSABLE;

    const char *video_driver = SDL_GetCurrentVideoDriver();
    return video_driver && strcmp(video_driver, "android") == 0 &&
           title && strcmp(title, utility_title) == 0 &&
           (flags & required_flags) == required_flags;
}

// LWJGL resolves SDL symbols from this DSO. Symbols not defined here are found
// in the DT_NEEDED libSDL3 dependency; only the two functions that need Android
// single-window semantics are interposed.
__attribute__((visibility("default")))
SDL_Window *SDLCALL SDL_CreateWindow(const char *title,
                                     int width,
                                     int height,
                                     SDL_WindowFlags flags)
{
    pthread_once(&sdl_symbols_once, resolve_sdl_symbols);
    if (!real_create_window) {
        return NULL;
    }

    if (is_renderpearl_utility_window(title, flags)) {
        // Android has one Activity surface. Create RenderPearl's first window
        // with the same mutable/HiDPI properties its later visible window asks
        // for, while keeping it hidden until device probing completes.
        SDL_WindowFlags normalized_flags =
                (flags | SDL_WINDOW_RESIZABLE | SDL_WINDOW_HIGH_PIXEL_DENSITY) &
                ~(SDL_WINDOW_UTILITY | SDL_WINDOW_NOT_FOCUSABLE);
        SDL_Window *window = real_create_window(title, width, height, normalized_flags);

        if (window) {
            pthread_mutex_lock(&window_mutex);
            renderpearl_window = window;
            renderpearl_window_references = 1;
            renderpearl_window_handed_off = false;
            pthread_mutex_unlock(&window_mutex);

            __android_log_print(ANDROID_LOG_INFO,
                                LOG_TAG,
                                "Created reusable RenderPearl SDL window (flags=0x%llx)",
                                (unsigned long long)normalized_flags);
        }
        return window;
    }

    pthread_mutex_lock(&window_mutex);
    SDL_Window *window = renderpearl_window;
    const char *video_driver = SDL_GetCurrentVideoDriver();
    bool can_reuse = video_driver && strcmp(video_driver, "android") == 0 &&
                     window && !renderpearl_window_handed_off &&
                     (flags & SDL_WINDOW_OPENGL) && !(flags & SDL_WINDOW_HIDDEN);
    if (can_reuse) {
        renderpearl_window_handed_off = true;
        renderpearl_window_references = 2;
    }
    pthread_mutex_unlock(&window_mutex);

    if (!can_reuse) {
        return real_create_window(title, width, height, flags);
    }

    // Android_CreateWindow already replaces the requested utility dimensions
    // with the Activity surface size. Promote that same SDL_Window instead of
    // asking the upstream Android backend for an impossible second window.
    SDL_SetWindowTitle(window, title);
    SDL_SetWindowResizable(window, (flags & SDL_WINDOW_RESIZABLE) != 0);
    SDL_SetWindowFocusable(window, true);
    SDL_ShowWindow(window);

    __android_log_print(ANDROID_LOG_INFO,
                        LOG_TAG,
                        "Reused RenderPearl utility window as the Minecraft window");
    return window;
}

__attribute__((visibility("default")))
void SDLCALL SDL_DestroyWindow(SDL_Window *window)
{
    pthread_once(&sdl_symbols_once, resolve_sdl_symbols);
    if (!real_destroy_window) {
        return;
    }

    pthread_mutex_lock(&window_mutex);
    if (window == renderpearl_window && renderpearl_window_references > 1) {
        --renderpearl_window_references;
        pthread_mutex_unlock(&window_mutex);
        __android_log_print(ANDROID_LOG_INFO,
                            LOG_TAG,
                            "Deferred shared RenderPearl SDL window destruction");
        return;
    }

    if (window == renderpearl_window) {
        renderpearl_window = NULL;
        renderpearl_window_references = 0;
        renderpearl_window_handed_off = false;
    }
    pthread_mutex_unlock(&window_mutex);

    real_destroy_window(window);
}
