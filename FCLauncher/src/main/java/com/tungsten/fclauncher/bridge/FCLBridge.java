package com.tungsten.fclauncher.bridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.tungsten.fclauncher.keycodes.AndroidKeycodeMap;
import com.tungsten.fclauncher.keycodes.LwjglGlfwKeycode;
import com.tungsten.fclauncher.utils.FCLPath;

import org.libsdl.app.FCLSDLInputBridge;
import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.io.Serializable;

public class FCLBridge implements Serializable {
    private static final String TAG = "FCLBridge";
    private static final int SDL_LAUNCH_FAILURE_EXIT_CODE = -1;

    public static boolean FORCE_RESOLUTION = false;
    public static float FORCE_RESOLUTION_SCALE = -1;
    public static int FORCE_RESOLUTION_WIDTH = 1920;
    public static int FORCE_RESOLUTION_HEIGHT = 1080;
    public static int FORCE_RESOLUTION_START_SIZE = -1;

    public static final int DEFAULT_WIDTH = 1280;
    public static final int DEFAULT_HEIGHT = 720;

    public static final int HIT_RESULT_TYPE_UNKNOWN = 0;
    public static final int HIT_RESULT_TYPE_MISS = 1;
    public static final int HIT_RESULT_TYPE_BLOCK = 2;
    public static final int HIT_RESULT_TYPE_ENTITY = 3;

    public static final int INJECTOR_MODE_ENABLE = 1;
    public static final int INJECTOR_MODE_DISABLE = 0;

    public static final int KeyPress = 2;
    public static final int KeyRelease = 3;
    public static final int ButtonPress = 4;
    public static final int ButtonRelease = 5;
    public static final int MotionNotify = 6;
    public static final int KeyChar = 7;
    public static final int ConfigureNotify = 22;
    public static final int FCLMessage = 37;

    public static final int Button1 = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_1;
    public static final int Button2 = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_2;
    public static final int Button3 = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_3;
    public static final int Button4 = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_4;
    public static final int Button5 = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_5;
    public static final int Button6 = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_6;
    public static final int Button7 = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_7;

    public static final int CursorEnabled = 1;
    public static final int CursorDisabled = 0;

    private static volatile boolean activeSDL3;

    private FCLBridgeCallback callback;

    private double scaleFactor = 1f;
    private String controller = "Default";
    private String gameDir;
    private String logPath;
    private String renderer;
    private String java;
    private Surface surface;
    private SurfaceHolder surfaceHolder;
    private boolean surfaceDestroyed;
    private Handler handler;
    private Thread thread;
    private SurfaceTexture surfaceTexture;
    private String modSummary;
    private boolean hasTouchController = false;
    private boolean usesSDL3 = false;
    private transient boolean hasSDLRelativeMouseMode;
    private transient boolean sdlRelativeMouseMode;
    private transient volatile boolean sdlExecutionRunning;
    private transient volatile boolean sdlExitReported;

    static {
        System.loadLibrary("fcl");
        System.loadLibrary("pojavexec_awt");
    }

    public FCLBridge() {
    }

    public static native void nativeClipboardReceived(String data, String mimeTypeSub);

    public native int[] renderAWTScreenFrame();

    public native void nativeSendData(int type, int i1, int i2, int i3, int i4);

    public native void nativeMoveWindow(int x, int y);

    public native int redirectStdio(String path);

    public native int chdir(String path);

    public native void setenv(String key, String value);

    public native long dlopen(String path);

    public native void setLdLibraryPath(String path);

    public native void setupExitTrap(FCLBridge bridge);

    public native void refreshHitResultType();

    public native void setFCLBridge(FCLBridge fclBridge);

    public void setThread(Thread thread) {
        this.thread = thread;
    }

    public Thread getThread() {
        return thread;
    }

    public SurfaceTexture getSurfaceTexture() {
        return surfaceTexture;
    }

    public void setSurfaceTexture(SurfaceTexture surfaceTexture) {
        this.surfaceTexture = surfaceTexture;
    }

    public void setSurfaceHolder(@Nullable SurfaceHolder surfaceHolder) {
        this.surfaceHolder = surfaceHolder;
    }

    public void resizeSurface(int width, int height) {
        if (surfaceHolder != null) {
            surfaceHolder.setFixedSize(width, height);
            return;
        }
        if (surfaceTexture != null) {
            surfaceTexture.setDefaultBufferSize(width, height);
        }
    }

    public void attachSurface(@NonNull Surface surface) {
        this.surface = surface;
        surfaceDestroyed = false;
        if (gameDir != null) {
            if (!usesSDL3) {
                CallbackBridge.setupBridgeWindow(surface);
            }
        } else {
            handleWindow();
        }
    }

    public FCLBridgeCallback getCallback() {
        return callback;
    }

    public void execute(Surface surface, FCLBridgeCallback callback) {
        activeSDL3 = false;
        this.handler = new Handler(Looper.getMainLooper());
        this.callback = callback;
        this.surface = surface;
        setFCLBridge(this);
        CallbackBridge.setFCLBridge(this);
        receiveLog("==================== Before Start ====================\n");
        receiveLog("invoke redirectStdio\n");
        int errorCode = redirectStdio(getLogPath());
        if (errorCode != 0) {
            receiveLog("Can't exec redirectStdio! Error code: " + errorCode + "\n");
        }
        receiveLog("invoke setLogPipeReady\n");
        // set graphic output and event pipe
        if (surface != null) {
            handleWindow();
        }
        receiveLog("invoke setEventPipe\n");

        // start
        if (thread != null) {
            thread.start();
        }
    }

    /**
     * Runs the prepared JVM task synchronously on SDLActivity's SDL thread.
     * SDL owns the native window and its lifecycle, so this path deliberately
     * does not initialize the GLFW callback bridge or attach a GLFW window.
     */
    public void executeSDL(FCLBridgeCallback callback) {
        usesSDL3 = true;
        activeSDL3 = true;
        hasSDLRelativeMouseMode = false;
        sdlExitReported = false;
        this.callback = callback;
        Throwable launchFailure = null;
        try {
            this.handler = new Handler(Looper.getMainLooper());
            setFCLBridge(this);
            FCLSDLInputBridge.reset();
            receiveLog("==================== Before Start ====================\n");
            receiveLog("invoke redirectStdio\n");
            int errorCode = redirectStdio(getLogPath());
            if (errorCode != 0) {
                receiveLog("Can't exec redirectStdio! Error code: " + errorCode + "\n");
            }
            receiveLog("invoke setLogPipeReady\n");
            receiveLog("invoke setEventPipe\n");

            if (thread == null) {
                throw new IllegalStateException("No JVM task was prepared for the SDL launch");
            }

            sdlExecutionRunning = true;
            try {
                // This must stay synchronous: SDL owns this thread and performs its
                // native main-thread cleanup only after SDLActivity.main() returns.
                thread.run();
                if (!sdlExitReported) {
                    throw new IllegalStateException(
                            "The SDL JVM task returned without reporting an exit code");
                }
            } finally {
                sdlExecutionRunning = false;
            }
        } catch (Throwable throwable) {
            // Do not let a JVM setup/runtime failure escape SDLMain.run(). Returning
            // normally from this method lets upstream SDL run nativeCleanupMainThread().
            sdlExecutionRunning = false;
            launchFailure = throwable;
        }

        if (launchFailure != null) {
            try {
                reportSDLLaunchFailure(launchFailure);
            } catch (Throwable reportingFailure) {
                // Even diagnostics must not escape back into upstream SDLMain.
                try {
                    Log.e(TAG, "Unable to report the SDL JVM failure", reportingFailure);
                } catch (Throwable ignored) {
                    // The only remaining priority is returning to SDL's cleanup path.
                }
            }
            if (!sdlExitReported) {
                try {
                    onExit(SDL_LAUNCH_FAILURE_EXIT_CODE);
                } catch (Throwable callbackFailure) {
                    // The callback belongs to the UI layer. It must not be allowed to skip
                    // SDL's native main-thread cleanup either.
                    try {
                        Log.e(TAG, "Unable to report the SDL JVM failure to the UI", callbackFailure);
                    } catch (Throwable ignored) {
                        // Continue returning to SDL's cleanup path.
                    }
                }
            }
        }
    }

    private void reportSDLLaunchFailure(Throwable throwable) {
        String message = "Unhandled failure while running the JVM on SDLThread";
        Log.e(TAG, message, throwable);
        try {
            receiveLog("\n" + message + "\n" + Log.getStackTraceString(throwable) + "\n");
        } catch (Throwable logFailure) {
            Log.e(TAG, "Unable to forward the SDL JVM failure to the game log", logFailure);
        }
    }

    public boolean isSDLExecutionRunning() {
        return sdlExecutionRunning;
    }

    public void pushEventMouseButton(int button, boolean press) {
        if (usesSDL3) {
            syncSDLCursorMode();
            switch (button) {
                case Button4:
                    if (press) {
                        FCLSDLInputBridge.sendMouseWheel(0.0f, 1.0f);
                    }
                    break;
                case Button5:
                    if (press) {
                        FCLSDLInputBridge.sendMouseWheel(0.0f, -1.0f);
                    }
                    break;
                default:
                    FCLSDLInputBridge.sendMouseButton(button, press);
                    break;
            }
            return;
        }

        switch (button) {
            case Button4:
                if (press) {
                    CallbackBridge.sendScroll(0, 1d);
                }
                break;
            case Button5:
                if (press) {
                    CallbackBridge.sendScroll(0, -1d);
                }
                break;
            default:
                CallbackBridge.sendMouseButton(button, press);
        }
    }

    public void pushEventPointer(int x, int y) {
        if (FORCE_RESOLUTION) {
            x = (int) ((x - FORCE_RESOLUTION_START_SIZE) / FORCE_RESOLUTION_SCALE);
            y = (int) (y / FORCE_RESOLUTION_SCALE);
        }
        if (usesSDL3) {
            syncSDLCursorMode();
            FCLSDLInputBridge.sendMousePosition(x, y);
        } else {
            CallbackBridge.sendCursorPos(x, y);
        }
    }

    public void pushEventScroll(float horizontal, float vertical) {
        if (usesSDL3) {
            syncSDLCursorMode();
            FCLSDLInputBridge.sendMouseWheel(horizontal, vertical);
        } else {
            CallbackBridge.sendScroll(horizontal, vertical);
        }
    }

    public void pushEventPointer(float x, float y) {
        if (usesSDL3) {
            syncSDLCursorMode();
            FCLSDLInputBridge.sendMousePosition(x, y);
        } else {
            CallbackBridge.sendCursorPos(x, y);
        }
    }

    public void pushEventKey(int keyCode, int keyChar, boolean press) {
        if (usesSDL3) {
            syncSDLCursorMode();
            FCLSDLInputBridge.sendKey(AndroidKeycodeMap.convertFCLKeycode(keyCode), press);
        } else {
            CallbackBridge.sendKeycode(keyCode, (char) keyChar, 0, CallbackBridge.getCurrentMods(), press);
        }
    }

    public void pushEventChar(char keyChar) {
        pushEventText(String.valueOf(keyChar));
    }

    public void pushEventText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (usesSDL3) {
            syncSDLCursorMode();
            FCLSDLInputBridge.sendText(text);
        } else {
            for (int i = 0; i < text.length(); i++) {
                CallbackBridge.sendChar(text.charAt(i), 0);
            }
        }
    }

    public void pushEventWindow(int width, int height) {
        if (!usesSDL3) {
            CallbackBridge.sendUpdateWindowSize(width, height);
        }
    }

    // FCLBridge callbacks
    public void onExit(int code) {
        sdlExitReported = true;
        if (callback != null) {
            callback.onLog("\nOpenJDK exited with code : " + code + "\n");
            callback.onExit(code);
        }
    }

    public void setHitResultType(int type) {
        if (callback != null) {
            callback.onHitResultTypeChange(type);
        }
    }

    public void setCursorMode(int mode) {
        if (callback != null) {
            callback.onCursorModeChange(mode);
        }
    }

    public synchronized int syncSDLCursorMode() {
        boolean relative = FCLSDLInputBridge.isRelativeMouseMode();
        if (!hasSDLRelativeMouseMode || relative != sdlRelativeMouseMode) {
            hasSDLRelativeMouseMode = true;
            sdlRelativeMouseMode = relative;
            setCursorMode(relative ? CursorDisabled : CursorEnabled);
        }
        return relative ? CursorDisabled : CursorEnabled;
    }

    public boolean isSDLTextInputReady() {
        return usesSDL3 && FCLSDLInputBridge.isTextInputReady();
    }

    public void setPrimaryClipString(String string) {
        ClipboardManager clipboard = (ClipboardManager) FCLPath.CONTEXT.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("FCL Clipboard", string);
        clipboard.setPrimaryClip(clip);
    }

    public String getPrimaryClipString() {
        ClipboardManager clipboard = (ClipboardManager) FCLPath.CONTEXT.getSystemService(Context.CLIPBOARD_SERVICE);
        if (!clipboard.hasPrimaryClip()) {
            return null;
        }
        ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
        return item.getText().toString();
    }

    private static OpenFolderCallback folderCallback = null;

    public static void setOpenFolderCallback(OpenFolderCallback callback) {
        folderCallback = callback;
    }

    public static void openLink(final String link) {
        Context context = FCLPath.CONTEXT;
        ((Activity) context).runOnUiThread(() -> {
            try {
                String targetLink = link;
                if (link.startsWith("file:")) {
                    targetLink = link.replaceFirst("^file:/+", "/");
                    if (targetLink.endsWith("/")) {
                        folderCallback.onBrowse(targetLink);
                        return;
                    }
                }
                Intent intent = new Intent(Intent.ACTION_VIEW);
                Uri uri;
                if (targetLink.startsWith("http")) {
                    uri = Uri.parse(targetLink);
                } else {
                    uri = FileProvider.getUriForFile(context, ((Activity) context).getApplication().getPackageName() + ".provider", new File(targetLink));
                }
                intent.setDataAndType(uri, "*/*");
                context.startActivity(Intent.createChooser(intent, ""));
            } catch (Exception e) {
                Log.e("openLink error", "link:" + link + " err:" + e.toString());
            }
        });
    }

    public static void querySystemClipboard() {
        Context context = FCLPath.CONTEXT;
        ClipboardManager clipboard = (ClipboardManager) FCLPath.CONTEXT.getSystemService(Context.CLIPBOARD_SERVICE);
        ((Activity) context).runOnUiThread(() -> {
            ClipData clipData = clipboard.getPrimaryClip();
            if (clipData == null) {
                nativeClipboardReceived(null, null);
                return;
            }
            ClipData.Item firstClipItem = clipData.getItemAt(0);
            //TODO: coerce to HTML if the clip item is styled
            CharSequence clipItemText = firstClipItem.getText();
            if (clipItemText == null) {
                nativeClipboardReceived(null, null);
                return;
            }
            nativeClipboardReceived(clipItemText.toString(), "plain");
        });
    }

    public static void putClipboardData(String data, String mimeType) {
        Context context = FCLPath.CONTEXT;
        ((Activity) context).runOnUiThread(() -> {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clipData = null;
            switch (mimeType) {
                case "text/plain":
                    clipData = ClipData.newPlainText("AWT Paste", data);
                    break;
                case "text/html":
                    clipData = ClipData.newHtmlText("AWT Paste", data, data);
            }
            if (clipData != null) clipboard.setPrimaryClip(clipData);
        });
    }

    public void setScaleFactor(double scaleFactor) {
        this.scaleFactor = scaleFactor;
    }

    public double getScaleFactor() {
        return scaleFactor;
    }

    public void setController(String controller) {
        this.controller = controller;
    }

    public String getController() {
        return controller;
    }

    public void setGameDir(String gameDir) {
        this.gameDir = gameDir;
    }

    @Nullable
    public String getGameDir() {
        return gameDir;
    }

    public void setRenderer(String renderer) {
        this.renderer = renderer;
    }

    @Nullable
    public String getRenderer() {
        return renderer;
    }

    public void setJava(String java) {
        this.java = java;
    }

    public String getJava() {
        return java;
    }

    public void setSurfaceDestroyed(boolean surfaceDestroyed) {
        this.surfaceDestroyed = surfaceDestroyed;
    }

    public boolean isSurfaceDestroyed() {
        return surfaceDestroyed;
    }

    @NonNull
    public String getLogPath() {
        return logPath;
    }

    public void setLogPath(String logPath) {
        this.logPath = logPath;
    }

    public void receiveLog(String log) {
        if (callback != null) {
            callback.onLog(log);
        }
    }

    private void handleWindow() {
        if (gameDir != null) {
            receiveLog("invoke setFCLNativeWindow\n");
            CallbackBridge.setupBridgeWindow(surface);
        } else {
            receiveLog("start Android AWT Renderer thread\n");
            Thread canvasThread = new Thread(() -> {
                Canvas canvas;
                Bitmap rgbArrayBitmap = Bitmap.createBitmap(DEFAULT_WIDTH, DEFAULT_HEIGHT, Bitmap.Config.ARGB_8888);
                Paint paint = new Paint();
                try {
                    while (!surfaceDestroyed && surface.isValid()) {
                        canvas = surface.lockCanvas(null);
                        canvas.drawRGB(0, 0, 0);
                        int[] rgbArray = renderAWTScreenFrame();
                        if (rgbArray != null) {
                            canvas.save();
                            rgbArrayBitmap.setPixels(rgbArray, 0, DEFAULT_WIDTH, 0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT);
                            canvas.drawBitmap(rgbArrayBitmap, 0, 0, paint);
                            canvas.restore();
                        }
                        surface.unlockCanvasAndPost(canvas);
                    }
                } catch (Throwable throwable) {
                    handler.post(() -> receiveLog(throwable + "\n"));
                }
                rgbArrayBitmap.recycle();
                surface.release();
            }, "AndroidAWTRenderer");
            canvasThread.start();
        }
    }

    public static int getFps() {
        return activeSDL3 ? 0 : CallbackBridge.getFps();
    }

    public String getModSummary() {
        return modSummary;
    }

    public void setModSummary(String modSummary) {
        this.modSummary = modSummary;
    }

    public boolean hasTouchController() {
        return hasTouchController;
    }

    public void setHasTouchController(boolean hasTouchController) {
        this.hasTouchController = hasTouchController;
    }

    public boolean isUseSDL3() {
        return usesSDL3;
    }

    public void setUseSDL3(boolean useSDL3) {
        this.usesSDL3 = useSDL3;
        activeSDL3 = useSDL3;
    }
}
