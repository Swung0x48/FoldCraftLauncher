package com.tungsten.fclauncher;

import android.content.Context;
import android.os.Environment;

import com.mio.data.Renderer;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

public final class MobileGLTraceCapture {
    private static final String ROOT_NAME = "mobilegl-trace";
    private static final String ENABLE_FILE = "enable";
    private static final String WRAPPER_NAME = "egltrace.so";
    public static final int FIXTURE_WIDTH = 854;
    public static final int FIXTURE_HEIGHT = 480;

    private static File activeSessionDir;
    private static File activeWrapper;
    private static String activeRealGl;

    private MobileGLTraceCapture() {
    }

    public static File sharedRoot() {
        return new File(Environment.getExternalStorageDirectory(), "FCL/" + ROOT_NAME);
    }

    public static File appRoot(Context context) {
        return new File(context.getFilesDir(), ROOT_NAME);
    }

    public static boolean isMobileGLRenderer(Renderer renderer) {
        return renderer != null &&
                (renderer.isEqual(Renderer.ID_MOBILEGL) || renderer.isEqual(Renderer.ID_SIMPLEFPEWRAPPER));
    }

    public static boolean isEnabled(Context context, Renderer renderer) {
        if (!isMobileGLRenderer(renderer)) {
            return false;
        }
        return new File(sharedRoot(), ENABLE_FILE).isFile() || new File(appRoot(context), ENABLE_FILE).isFile();
    }

    public static String resolveGlPath(FCLConfig config) {
        Renderer renderer = config.getRenderer();
        if (!isEnabled(config.getContext(), renderer)) {
            return renderer == null ? "" : renderer.getGLPath();
        }
        File wrapper = prepareWrapper(config.getContext());
        if (wrapper == null) {
            return renderer.getGLPath();
        }
        activeWrapper = wrapper;
        activeRealGl = realGlPath(config.getContext(), renderer);
        return wrapper.getAbsolutePath();
    }

    public static void addEnv(FCLConfig config, HashMap<String, String> envMap) {
        Renderer renderer = config.getRenderer();
        if (!isEnabled(config.getContext(), renderer)) {
            return;
        }
        File wrapper = prepareWrapper(config.getContext());
        if (wrapper == null) {
            writeStatus("disabled", "missing egltrace.so; push it to " + new File(sharedRoot(), WRAPPER_NAME));
            return;
        }

        File session = createSessionDir(renderer);
        activeSessionDir = session;
        activeWrapper = wrapper;
        activeRealGl = realGlPath(config.getContext(), renderer);

        envMap.put("TRACE_FILE", new File(session, "full.trace").getAbsolutePath());
        envMap.put("TRACE_LIBGL", activeRealGl);
        envMap.put("FLUSH_EVERY_MS", "1000");
        envMap.put("LIBEGL_NAME", wrapper.getAbsolutePath());
        envMap.put("POJAVEXEC_EGL", wrapper.getAbsolutePath());
        envMap.put("MOBILEGL_TRACE_CAPTURE_DIR", session.getAbsolutePath());
        envMap.put("MOBILEGL_TRACE_CAPTURE_FRAME_FILE", new File(session, "capture-result.json").getAbsolutePath());
        envMap.put("MOBILEGL_TRACE_CAPTURE_REQUEST_FILE", new File(sharedRoot(), "capture-once.request").getAbsolutePath());
        envMap.put("MOBILEGL_TRACE_FIXTURE_WIDTH", String.valueOf(FIXTURE_WIDTH));
        envMap.put("MOBILEGL_TRACE_FIXTURE_HEIGHT", String.valueOf(FIXTURE_HEIGHT));

        writeJson(new File(session, "capture-status.json"),
                "{\n" +
                        "  \"status\": \"enabled\",\n" +
                        "  \"wrapper\": \"" + escape(wrapper.getAbsolutePath()) + "\",\n" +
                        "  \"traceLibGl\": \"" + escape(activeRealGl) + "\",\n" +
                        "  \"fixtureWidth\": " + FIXTURE_WIDTH + ",\n" +
                        "  \"fixtureHeight\": " + FIXTURE_HEIGHT + ",\n" +
                        "  \"traceFile\": \"" + escape(new File(session, "full.trace").getAbsolutePath()) + "\"\n" +
                        "}\n");
    }

    public static boolean requestOneShotCapture() {
        File root = sharedRoot();
        if (!root.exists() && !root.mkdirs()) {
            return false;
        }
        File request = new File(root, "capture-once.request");
        String content = "{\n  \"requestedAtMs\": " + System.currentTimeMillis() + "\n}\n";
        return writeJson(request, content);
    }

    public static File getActiveSessionDir() {
        return activeSessionDir;
    }

    private static File prepareWrapper(Context context) {
        File appRoot = appRoot(context);
        if (!appRoot.exists() && !appRoot.mkdirs()) {
            return null;
        }
        File appWrapper = new File(appRoot, WRAPPER_NAME);
        File sharedWrapper = new File(sharedRoot(), WRAPPER_NAME);
        if (sharedWrapper.isFile()) {
            try {
                Files.copy(sharedWrapper.toPath(), appWrapper.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                appWrapper.setReadable(true, false);
                appWrapper.setExecutable(true, true);
            } catch (IOException ignored) {
            }
        }
        return appWrapper.isFile() ? appWrapper : null;
    }

    private static String realGlPath(Context context, Renderer renderer) {
        String path = renderer.getGLPath();
        if (path.startsWith("/") || path.contains("/")) {
            return path;
        }
        return new File(context.getApplicationInfo().nativeLibraryDir, path).getAbsolutePath();
    }

    private static File createSessionDir(Renderer renderer) {
        File root = sharedRoot();
        if (!root.exists()) {
            root.mkdirs();
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String name = "capture-" + stamp + "-" + renderer.getName().replaceAll("[^A-Za-z0-9_.-]", "_");
        File session = new File(root, name);
        session.mkdirs();
        writeJson(new File(root, "latest-session.txt"), session.getAbsolutePath() + "\n");
        return session;
    }

    private static void writeStatus(String status, String message) {
        File root = sharedRoot();
        if (!root.exists()) {
            root.mkdirs();
        }
        writeJson(new File(root, "capture-status.json"),
                "{\n  \"status\": \"" + escape(status) + "\",\n  \"message\": \"" + escape(message) + "\"\n}\n");
    }

    private static boolean writeJson(File file, String content) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileWriter writer = new FileWriter(file, false)) {
                writer.write(content);
            }
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
