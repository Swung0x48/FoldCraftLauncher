package com.tungsten.fclauncher.utils;

import android.content.Context;
import android.os.Environment;

import java.io.File;

public class FCLPath {

    private static final String[] BUNDLED_SDL3_LWJGL_VERSIONS = {"3.4.1", "3.4.2"};
    private static final String[] REQUIRED_SDL3_LWJGL_LIBRARIES = {
            "liblwjgl.so",
            "liblwjgl_opengl.so",
            "liblwjgl_stb.so",
            "liblwjgl_vma.so",
            "liblwjgl_tinyfd.so"
    };

    public static Context CONTEXT;

    public static String NATIVE_LIB_DIR;

    public static String LOG_DIR;
    public static String CACHE_DIR;

    public static String RUNTIME_DIR;
    public static String MOD_RUNTIME_DIR;
    public static String JAVA_8_PATH;
    public static String JAVA_17_PATH;
    public static String JAVA_21_PATH;
    public static String JAVA_25_PATH;
    public static String JAVA_PATH;
    public static String JNA_PATH;
    public static String LWJGL_DIR;
    public static String CACIOCAVALLO_8_DIR;
    public static String CACIOCAVALLO_17_DIR;

    public static String FILES_DIR;
    public static String PLUGIN_DIR;
    public static String BACKGROUND_DIR;
    public static String CONTROLLER_DIR;

    public static String PRIVATE_COMMON_DIR;
    public static String SHARED_COMMON_DIR = Environment.getExternalStorageDirectory().getAbsolutePath() + "/FCL/.minecraft";

    public static String AUTHLIB_INJECTOR_PATH;
    public static String LIB_PATCHER_PATH;
    public static String MIO_LAUNCH_WRAPPER;
    public static String LT_BACKGROUND_PATH;
    public static String DK_BACKGROUND_PATH;
    public static String LIVE_BACKGROUND_PATH;

    public static void loadPaths(Context context) {
        CONTEXT = context;

        NATIVE_LIB_DIR = context.getApplicationInfo().nativeLibraryDir;

        LOG_DIR = Environment.getExternalStorageDirectory().getAbsolutePath() + "/FCL/log";
        CACHE_DIR = context.getCacheDir() + "/fclauncher";

        RUNTIME_DIR = context.getDir("runtime", 0).getAbsolutePath();
        JAVA_PATH = RUNTIME_DIR + "/java";
        JAVA_8_PATH = RUNTIME_DIR + "/java/jre8";
        JAVA_17_PATH = RUNTIME_DIR + "/java/jre17";
        JAVA_21_PATH = RUNTIME_DIR + "/java/jre21";
        JAVA_25_PATH = RUNTIME_DIR + "/java/jre25";
        JNA_PATH = RUNTIME_DIR + "/jna";
        LWJGL_DIR = RUNTIME_DIR + "/lwjgl";
        CACIOCAVALLO_8_DIR = RUNTIME_DIR + "/caciocavallo";
        CACIOCAVALLO_17_DIR = RUNTIME_DIR + "/caciocavallo17";

        MOD_RUNTIME_DIR = context.getDir("runtime_mod", 0).getAbsolutePath();

        FILES_DIR = context.getFilesDir().getAbsolutePath();
        PLUGIN_DIR = FILES_DIR + "/plugins";
        BACKGROUND_DIR = FILES_DIR + "/background";
        CONTROLLER_DIR = Environment.getExternalStorageDirectory().getAbsolutePath() + "/FCL/control";
        File externalFilesDir = context.getExternalFilesDir(null);
        if (externalFilesDir == null) {
            externalFilesDir = new File(Environment.getExternalStorageDirectory(), "Android/data/" + context.getPackageName() + "/files");
        }
        PRIVATE_COMMON_DIR = new File(externalFilesDir, ".minecraft").getAbsolutePath();

        AUTHLIB_INJECTOR_PATH = PLUGIN_DIR + "/authlib-injector.jar";
        LIB_PATCHER_PATH = PLUGIN_DIR + "/MioLibPatcher.jar";
        MIO_LAUNCH_WRAPPER = PLUGIN_DIR + "/MioLaunchWrapper.jar";
        LT_BACKGROUND_PATH = BACKGROUND_DIR + "/lt.png";
        DK_BACKGROUND_PATH = BACKGROUND_DIR + "/dk.png";
        LIVE_BACKGROUND_PATH = BACKGROUND_DIR + "/live.mp4";

        init(LOG_DIR);
        init(CACHE_DIR);
        init(RUNTIME_DIR);
        init(MOD_RUNTIME_DIR);
        init(JAVA_8_PATH);
        init(JAVA_25_PATH);
        init(JAVA_17_PATH);
        init(JAVA_21_PATH);
        init(LWJGL_DIR);
        init(CACIOCAVALLO_8_DIR);
        init(CACIOCAVALLO_17_DIR);
        init(FILES_DIR);
        init(PLUGIN_DIR);
        init(BACKGROUND_DIR);
        init(CONTROLLER_DIR);
        init(PRIVATE_COMMON_DIR);
        init(SHARED_COMMON_DIR);
    }

    private static boolean init(String path) {
        if (!new File(path).exists()) {
            return new File(path).mkdirs();
        }
        return true;
    }

    public static String[] getBundledSDL3LWJGLVersions() {
        return BUNDLED_SDL3_LWJGL_VERSIONS.clone();
    }

    public static String[] getRequiredSDL3LWJGLLibraries() {
        return REQUIRED_SDL3_LWJGL_LIBRARIES.clone();
    }

    public static String getSDL3LWJGLNativeDir(String lwjglVersion) {
        boolean bundled = false;
        for (String version : BUNDLED_SDL3_LWJGL_VERSIONS) {
            if (version.equals(lwjglVersion)) {
                bundled = true;
                break;
            }
        }
        if (!bundled) {
            throw new IllegalArgumentException("Unsupported SDL LWJGL version: " + lwjglVersion);
        }
        String abi = Architecture.archAsAndroidAbi(Architecture.getProcessArchitecture());
        return new File(LWJGL_DIR, lwjglVersion + "/natives/" + abi).getAbsolutePath();
    }

}
