package com.tungsten.fcl.activity;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.tungsten.fcl.R;
import com.tungsten.fcl.control.GameMenu;
import com.tungsten.fcl.control.JarExecutorMenu;
import com.tungsten.fcl.control.MenuCallback;
import com.tungsten.fcl.control.MenuType;
import com.tungsten.fcl.control.view.MenuView;
import com.tungsten.fcl.game.sdl.SdlBridge;
import com.mio.flite.FliteTts;
import com.tungsten.fcl.setting.GameOption;
import com.tungsten.fcl.terracotta.Terracotta;
import com.mio.util.AndroidUtilKt;
import com.tungsten.fclauncher.bridge.FCLBridge;
import com.tungsten.fclauncher.keycodes.FCLKeycodes;
import com.tungsten.fclauncher.keycodes.LwjglGlfwKeycode;
import com.tungsten.fclcore.util.Logging;
import com.tungsten.fcllibrary.component.FCLActivity;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;
import org.lwjgl.glfw.CallbackBridge;

import java.util.Objects;
import java.util.logging.Level;

public class JVMActivity extends FCLActivity implements SurfaceHolder.Callback, TextureView.SurfaceTextureListener {

    private SurfaceView surfaceView;
    private TextureView textureView;
    private View renderView;

    private MenuCallback menu;
    private static MenuType menuType;
    private static FCLBridge fclBridge;
    private static boolean useTextureView = false;
    private boolean isTranslated = false;
    private static boolean isRunning = false;
    private long volumeDownTime = 0;
    private int textureOutput = 0;

    public static void setFCLBridge(FCLBridge fclBridge, MenuType menuType) {
        setFCLBridge(fclBridge, menuType, false);
    }

    public static void setFCLBridge(FCLBridge fclBridge, MenuType menuType, boolean useTextureView) {
        JVMActivity.fclBridge = fclBridge;
        JVMActivity.menuType = menuType;
        JVMActivity.useTextureView = useTextureView;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_jvm);
        if (menuType == null || fclBridge == null) {
            Logging.LOG.log(Level.WARNING, "Failed to get ControllerType or FCLBridge, task canceled.");
            return;
        }

        menu = menuType == MenuType.GAME ? new GameMenu() : new JarExecutorMenu();
        menu.setup(this, fclBridge);
        textureView = findViewById(R.id.texture_view);
        surfaceView = findViewById(R.id.surface_view);
        renderView = useTextureView ? textureView : surfaceView;
        ((ViewGroup) renderView.getParent()).removeView(useTextureView ? surfaceView : textureView);
        textureView.setVisibility(useTextureView ? View.VISIBLE : View.GONE);
        surfaceView.setVisibility(useTextureView ? View.GONE : View.VISIBLE);
        if (useTextureView) {
            textureView.setSurfaceTextureListener(this);
        } else {
            surfaceView.getHolder().addCallback(this);
        }
        if (FCLBridge.FORCE_RESOLUTION) {
            ViewGroup.LayoutParams params = renderView.getLayoutParams();
            FCLBridge.FORCE_RESOLUTION_SCALE = (float) AndroidUtilKt.getScreenHeight() / FCLBridge.FORCE_RESOLUTION_HEIGHT;
            params.width = (int) (FCLBridge.FORCE_RESOLUTION_WIDTH * FCLBridge.FORCE_RESOLUTION_SCALE);
            params.height = (int) (FCLBridge.FORCE_RESOLUTION_HEIGHT * FCLBridge.FORCE_RESOLUTION_SCALE);
            FCLBridge.FORCE_RESOLUTION_START_SIZE = (AndroidUtilKt.getScreenWidth() - params.width) / 2;
            renderView.setLayoutParams(params);
            renderView.setX(FCLBridge.FORCE_RESOLUTION_START_SIZE);
        }

        addContentView(menu.getLayout(), new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (menuType == MenuType.GAME && ((GameMenu) menu).getMenuSetting().isDisableSoftKeyAdjust()) {
                return;
            }
            int screenHeight = getWindow().getDecorView().getHeight();
            Rect rect = new Rect();
            getWindow().getDecorView().getWindowVisibleDisplayFrame(rect);
            if (screenHeight * 2 / 3 > rect.bottom) {
                renderView.setTranslationY(rect.bottom - screenHeight);
                isTranslated = true;
            } else if (isTranslated) {
                isTranslated = false;
                renderView.setTranslationY(0);
            }
        });
    }

    /**
     * 请求系统将屏幕切换到设备支持的最高刷新率，避免游戏帧率被系统限制在自选的较低刷新档位
     *
     * 参考 MinecraftGLSurface（https://github.com/AngelAuraMC/Amethyst-Android/blob/v3_openjdk/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MinecraftGLSurface.java）
     */
    private void voteMaxDisplayRefreshRate(Surface surface) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        float maxRefreshRate = 120f;
        for (float rate : getDisplay().getMode().getAlternativeRefreshRates()) {
            maxRefreshRate = Math.max(maxRefreshRate, rate);
        }
        surface.setFrameRate(
                maxRefreshRate,
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
        );
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        if (menu == null || fclBridge == null) {
            return;
        }

        menu.onGraphicOutput();
        menu.getInput().initExternalController(menuType == MenuType.GAME ? surfaceView : menu.getLayout());
        fclBridge.setSurfaceDestroyed(false);
        fclBridge.setSurfaceHolder(holder);

        Surface nativeSurface = holder.getSurface();
        voteMaxDisplayRefreshRate(nativeSurface);
        SdlBridge.prepareSurface(this, nativeSurface, (ViewGroup) surfaceView.getParent(), surfaceView);

        if (isRunning) {
            fclBridge.attachSurface(holder.getSurface());
            resizeSurface(surfaceView.getWidth(), surfaceView.getHeight());
            return;
        }

        isRunning = true;
        Logging.LOG.log(Level.INFO, "surface ready, start jvm now!");
        int[] size = getSurfaceSize(surfaceView.getWidth(), surfaceView.getHeight());
        if (menuType == MenuType.GAME) {
            GameOption gameOption = new GameOption(Objects.requireNonNull(menu.getBridge()).getGameDir());
            gameOption.set("fullscreen", "false");
            gameOption.set("overrideWidth", String.valueOf(size[0]));
            gameOption.set("overrideHeight", String.valueOf(size[1]));
            gameOption.save();
        }
        fclBridge.resizeSurface(size[0], size[1]);
        CallbackBridge.windowWidth = size[0];
        CallbackBridge.windowHeight = size[1];
        fclBridge.execute(nativeSurface, menu.getCallbackBridge());
        syncWindowSize(size[0], size[1]);
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        if (fclBridge == null) {
            return;
        }
        fclBridge.setSurfaceHolder(holder);
        // Scale from the VIEW size, never from the reported surface size. setFixedSize()
        // drives this callback, and the width/height it reports are the buffer dimensions we
        // just asked for - already scaled. Feeding those back through getSurfaceSize()
        // multiplies by scaleFactor again on every callback, so any scale below 1.0 collapses
        // the surface geometrically (0.4 measured: 3044 -> 1217 -> 486 -> ... -> 1) and the
        // screen flickers through garbage sizes. The view size is the fixed point.
        resizeSurface(surfaceView.getWidth(), surfaceView.getHeight());
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        if (fclBridge != null) {
            fclBridge.setSurfaceDestroyed(true);
            fclBridge.setSurfaceHolder(null);
        }
        notifySdlSurfaceDestroyed();
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
        if (menu == null || fclBridge == null) {
            return;
        }

        fclBridge.setSurfaceDestroyed(false);
        Surface nativeSurface = new Surface(surfaceTexture);
        voteMaxDisplayRefreshRate(nativeSurface);

        if (isRunning) {
            fclBridge.setSurfaceTexture(surfaceTexture);
            fclBridge.attachSurface(nativeSurface);
            SdlBridge.prepareSurface(this, nativeSurface, (ViewGroup) textureView.getParent(), textureView);
            resizeTexture(width, height);
            menu.onGraphicOutput();
            return;
        }

        isRunning = true;
        Logging.LOG.log(Level.INFO, "texture ready, start jvm now!");
        int[] size = getSurfaceSize(width, height);
        if (menuType == MenuType.GAME) {
            menu.getInput().initExternalController(textureView);
            GameOption gameOption = new GameOption(Objects.requireNonNull(menu.getBridge()).getGameDir());
            gameOption.set("fullscreen", "false");
            gameOption.set("overrideWidth", String.valueOf(size[0]));
            gameOption.set("overrideHeight", String.valueOf(size[1]));
            gameOption.save();
        }
        surfaceTexture.setDefaultBufferSize(size[0], size[1]);
        CallbackBridge.windowWidth = size[0];
        CallbackBridge.windowHeight = size[1];
        SdlBridge.prepareSurface(this, nativeSurface, (ViewGroup) textureView.getParent(), textureView);
        fclBridge.execute(nativeSurface, menu.getCallbackBridge());
        fclBridge.setSurfaceTexture(surfaceTexture);
        syncWindowSize(size[0], size[1]);
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
        resizeTexture(width, height);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
        if (fclBridge != null) {
            fclBridge.setSurfaceDestroyed(true);
            fclBridge.setSurfaceTexture(null);
        }
        notifySdlSurfaceDestroyed();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
        if (menu == null) {
            return;
        }
        if (textureOutput == 1) {
            menu.onGraphicOutput();
            textureOutput++;
        }
        if (textureOutput < 1) {
            textureOutput++;
        }
    }

    private int[] getSurfaceSize(int width, int height) {
        int targetWidth = menuType == MenuType.GAME
                ? (int) ((width + ((GameMenu) menu).getMenuSetting().getCursorOffset()) * fclBridge.getScaleFactor())
                : FCLBridge.DEFAULT_WIDTH;
        int targetHeight = menuType == MenuType.GAME
                ? (int) (height * fclBridge.getScaleFactor())
                : FCLBridge.DEFAULT_HEIGHT;
        if (FCLBridge.FORCE_RESOLUTION) {
            targetWidth = FCLBridge.FORCE_RESOLUTION_WIDTH;
            targetHeight = FCLBridge.FORCE_RESOLUTION_HEIGHT;
        }
        return new int[]{targetWidth, targetHeight};
    }

    private void resizeSurface(int width, int height) {
        if (menu == null || fclBridge == null) {
            return;
        }
        int[] size = getSurfaceSize(width, height);
        fclBridge.resizeSurface(size[0], size[1]);
        syncWindowSize(size[0], size[1]);
    }

    private void resizeTexture(int width, int height) {
        if (menu == null || fclBridge == null || textureView == null || textureView.getSurfaceTexture() == null) {
            return;
        }
        int[] size = getSurfaceSize(width, height);
        textureView.getSurfaceTexture().setDefaultBufferSize(size[0], size[1]);
        syncWindowSize(size[0], size[1]);
    }

    private void syncWindowSize(int width, int height) {
        CallbackBridge.windowWidth = width;
        CallbackBridge.windowHeight = height;
        if (SdlBridge.getSdlEnabled()) {
            SDLSurface sdlSurface = SDLActivity.getSDLSurface();
            if (sdlSurface != null) {
                sdlSurface.surfaceChanged();
                sdlSurface.nativeResize(width, height);
            }
        }
        fclBridge.pushEventWindow(width, height);
    }

    private void notifySdlSurfaceDestroyed() {
        if (SdlBridge.getSdlEnabled() && SDLActivity.getSDLSurface() != null) {
            SDLActivity.getSDLSurface().surfaceDestroyed();
        }
    }

    @Override
    protected void onPause() {
        if (menu != null) {
            menu.onPause();
        }
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 0);
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 0);
        super.onPause();
    }

    @Override
    protected void onResume() {
        if (menu != null) {
            menu.onResume();
        }
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 1);
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 1);
        super.onResume();
    }

    @Override
    protected void onStart() {
        super.onStart();
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 1);
    }

    @Override
    protected void onStop() {
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 0);
        super.onStop();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean handleEvent = true;
        if (menu != null && menuType == MenuType.GAME) {
            if (!(handleEvent = menu.getInput().handleKeyEvent(event))) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && !((GameMenu) menu).getTouchCharInput().isEnabled()) {
                    if (event.getAction() != KeyEvent.ACTION_UP) {
                        return true;
                    }
                    menu.getInput().sendKeyEvent(FCLKeycodes.KEY_ESC, true);
                    menu.getInput().sendKeyEvent(FCLKeycodes.KEY_ESC, false);
                    return true;
                } else if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
                    MenuView menuView = ((GameMenu) menu).getMenuView();
                    if (menuView.getAlpha() == 0 || menuView.getVisibility() == View.INVISIBLE) {
                        DrawerLayout drawerLayout = (DrawerLayout) menu.getLayout();
                        if (drawerLayout.isDrawerOpen(GravityCompat.START) || drawerLayout.isDrawerOpen(GravityCompat.END)) {
                            if (event.getAction() == KeyEvent.ACTION_UP) {
                                drawerLayout.closeDrawers();
                                volumeDownTime = System.currentTimeMillis();
                            }
                        } else if (System.currentTimeMillis() - volumeDownTime > 800) {
                            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                                return true;
                            }
                            drawerLayout.openDrawer(GravityCompat.START, true);
                            drawerLayout.openDrawer(GravityCompat.END, true);
                        } else {
                            volumeDownTime = System.currentTimeMillis();
                        }
                    }
                }
            }
        }
        return handleEvent;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (menu != null && menuType == MenuType.GAME && menu.getInput().handleGenericMotionEvent(event)) {
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        if (useTextureView && textureView != null && textureView.getSurfaceTexture() != null) {
            textureView.post(() -> resizeTexture(textureView.getWidth(), textureView.getHeight()));
        } else if (surfaceView != null) {
            surfaceView.post(() -> resizeSurface(surfaceView.getWidth(), surfaceView.getHeight()));
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (useTextureView && textureView != null && textureView.getSurfaceTexture() != null) {
            textureView.post(() -> resizeTexture(textureView.getWidth(), textureView.getHeight()));
        } else if (surfaceView != null) {
            surfaceView.post(() -> resizeSurface(surfaceView.getWidth(), surfaceView.getHeight()));
        }
    }

    @Override
    protected void onDestroy() {
        Terracotta.setWaiting(this, true);
        CallbackBridge.resetInputState();
        SdlBridge.reset();
        FliteTts.shutdown();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, hasFocus ? 1 : 0);
        if (!hasFocus) {
            CallbackBridge.resetInputState();
        }
    }

    /**
     * SDL 会在窗口创建时按窗口宽高动态请求方向，可能切到 sensorPortrait，
     * 此处强制锁定横向（跟随传感器），保证游戏画面方向一致
     */
    @Override
    public void setRequestedOrientation(int requestedOrientation) {
        super.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    /**
     * SDL 原生层（Android_JNI_ShowMessageBox）会在宿主对象的运行时类上按名
     * 查找 messageboxShowMessageBox；本宿主非 SDLActivity 子类，必须桥接到
     * SDLActivity 的静态实现，否则查找失败会带着 pending 异常触发 JniAbort
     */
    @Keep
    public int messageboxShowMessageBox(int flags, String title, String message,
                                        int[] buttonFlags, int[] buttonIds,
                                        String[] buttonTexts, int[] colors) {
        return SDLActivity.messageboxShowMessageBox(this, flags, title, message,
                buttonFlags, buttonIds, buttonTexts, colors);
    }
}
