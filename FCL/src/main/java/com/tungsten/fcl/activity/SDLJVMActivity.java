package com.tungsten.fcl.activity;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.mio.util.DisplayUtil;
import com.tungsten.fcl.control.GameMenu;
import com.tungsten.fcl.control.MenuCallback;
import com.tungsten.fcl.control.MenuType;
import com.tungsten.fcl.control.view.MenuView;
import com.tungsten.fcl.setting.GameOption;
import com.tungsten.fcl.terracotta.Terracotta;
import com.tungsten.fcl.util.AndroidUtils;
import com.tungsten.fclauncher.bridge.FCLBridge;
import com.tungsten.fclauncher.bridge.OpenFolderCallback;
import com.tungsten.fclauncher.keycodes.FCLKeycodes;
import com.tungsten.fclcore.util.Logging;
import com.tungsten.fcllibrary.browser.FileBrowser;
import com.tungsten.fcllibrary.browser.options.LibMode;
import com.tungsten.fcllibrary.component.theme.ThemeEngine;
import com.tungsten.fcllibrary.util.LocaleUtils;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.FCLSDLInputBridge;
import org.lwjgl.glfw.CallbackBridge;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * FCL's Minecraft host for versions using LWJGL's SDL3 bindings.
 *
 * <p>The SDL Java and native platform layers are kept upstream. This subclass only overlays
 * FCL's controls and runs the embedded JVM from SDL's own SDLThread entry point.</p>
 */
public final class SDLJVMActivity extends SDLActivity implements OpenFolderCallback, LifecycleOwner {
    private static final long SDL_SHUTDOWN_GRACE_MILLIS = 3000L;

    private static FCLBridge fclBridge;
    private static MenuType menuType;

    private MenuCallback menu;
    private boolean translated;
    private boolean backKeyDownHandled;
    private long volumeDownTime;
    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);

    public static void setFCLBridge(FCLBridge bridge, MenuType type) {
        // This method is invoked on Android's UI thread before the Activity starts.
        // Load the embedded-JVM launcher here so CallbackBridge's Choreographer is
        // never initialized later on SDLThread, which intentionally has no Looper.
        CallbackBridge.ensureNativeLauncherLoaded();
        fclBridge = bridge;
        menuType = type;
        bridge.setUseSDL3(true);
    }

    @Override
    protected String[] getLibraries() {
        // Minecraft is launched by the embedded JVM, not a libmain.so SDL_main entry point.
        return new String[]{"SDL3"};
    }

    @Override
    public void setOrientationBis(int width, int height, boolean resizable, String hint) {
        // Minecraft creates a resizable SDL window without an orientation hint. Upstream SDL
        // therefore selects FULL_USER, which can turn FCL's landscape controls and surface
        // portrait after a pause/resume. FCL's game UI is landscape-only, so keep the policy
        // declared by this Activity while leaving SDL's native lifecycle and SurfaceView intact.
        if (getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleUtils.setLanguage(base));
    }

    @Override
    public void setRequestedOrientation(int requestedOrientation) {
        // SDL normally follows an application's resizable-window orientation hint. FCL's
        // in-game control overlay is landscape-only, so allowing Minecraft to request
        // FULL_USER here rotates both the Surface and controls after showing the IME.
        super.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeEngine.getInstance().setupThemeEngine(this);
        super.onCreate(savedInstanceState);
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        if (mBrokenLibraries) {
            return;
        }

        DisplayUtil.updateWindowSize(this);
        FCLBridge.setOpenFolderCallback(this);
        if (menuType != MenuType.GAME || fclBridge == null) {
            Logging.LOG.log(Level.WARNING, "Missing game state for SDLJVMActivity; canceling launch.");
            finish();
            return;
        }

        try {
            if (!FCLSDLInputBridge.installTextBridge()) {
                Logging.LOG.log(Level.WARNING,
                        "SDL text bridge was not installed; retaining upstream text input.");
            }
        } catch (Throwable throwable) {
            // Text input for BMP characters must remain usable even when the
            // optional standard UTF-8 replacement cannot be installed.
            Logging.LOG.log(Level.WARNING,
                    "Failed to install the SDL text bridge; retaining upstream text input.",
                    throwable);
        }

        // FCL only keeps this holder so its runtime render-scale controls can call
        // setFixedSize(). SDLSurface remains the sole owner of native window events.
        fclBridge.setSurfaceHolder(mSurface.getHolder());

        menu = new GameMenu();
        menu.setup(this, fclBridge);
        mLayout.addView(menu.getLayout(), new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        menu.getInput().initExternalController(mSurface);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (((GameMenu) menu).getMenuSetting().isDisableSoftKeyAdjust()) {
                return;
            }
            int screenHeight = getWindow().getDecorView().getHeight();
            Rect visible = new Rect();
            getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
            if (screenHeight * 2 / 3 > visible.bottom) {
                mSurface.setTranslationY(visible.bottom - screenHeight);
                translated = true;
            } else if (translated) {
                translated = false;
                mSurface.setTranslationY(0);
            }
        });
    }

    @Override
    protected void main() {
        if (menu == null || fclBridge == null) {
            return;
        }

        int[] size = configureSurfaceForLaunch();
        GameOption gameOption = new GameOption(Objects.requireNonNull(menu.getBridge()).getGameDir());
        gameOption.set("fullscreen", "false");
        gameOption.set("overrideWidth", Integer.toString(size[0]));
        gameOption.set("overrideHeight", Integer.toString(size[1]));
        gameOption.save();

        runOnUiThread(menu::onGraphicOutput);
        Logging.LOG.log(Level.INFO, "SDL surface ready; starting the embedded JVM on SDLThread.");
        fclBridge.executeSDL(menu.getCallbackBridge());
    }

    /**
     * Apply FCL's render scale before LWJGL creates the SDL window. SDL's own SurfaceHolder
     * callback remains responsible for publishing the final native window and dimensions.
     */
    private int[] configureSurfaceForLaunch() {
        CountDownLatch configured = new CountDownLatch(1);
        AtomicReference<int[]> result = new AtomicReference<>();
        runOnUiThread(() -> {
            int width = Math.max(1, mSurface.getWidth());
            int height = Math.max(1, mSurface.getHeight());
            int[] target = getSurfaceSize(width, height);
            result.set(target);

            Rect frame = mSurface.getHolder().getSurfaceFrame();
            if (frame.width() == target[0] && frame.height() == target[1]) {
                configured.countDown();
                return;
            }

            SurfaceHolder.Callback callback = new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(@NonNull SurfaceHolder holder) {
                }

                @Override
                public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                    if (width == target[0] && height == target[1]) {
                        holder.removeCallback(this);
                        configured.countDown();
                    }
                }

                @Override
                public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                    holder.removeCallback(this);
                    configured.countDown();
                }
            };
            mSurface.getHolder().addCallback(callback);
            mSurface.getHolder().setFixedSize(target[0], target[1]);
            mSurface.postDelayed(configured::countDown, 2500);
        });

        try {
            configured.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int[] target = result.get();
        if (target != null) {
            return target;
        }
        return getSurfaceSize(AndroidUtils.getScreenWidth(), AndroidUtils.getScreenHeight());
    }

    private int[] getSurfaceSize(int width, int height) {
        int targetWidth = (int) ((width + ((GameMenu) menu).getMenuSetting().getCursorOffset())
                * fclBridge.getScaleFactor());
        int targetHeight = (int) (height * fclBridge.getScaleFactor());
        if (FCLBridge.FORCE_RESOLUTION) {
            targetWidth = FCLBridge.FORCE_RESOLUTION_WIDTH;
            targetHeight = FCLBridge.FORCE_RESOLUTION_HEIGHT;
        }
        return new int[]{Math.max(1, targetWidth), Math.max(1, targetHeight)};
    }

    @Override
    public void onBrowse(String path) {
        new FileBrowser.Builder(this)
                .setLibMode(LibMode.FILE_BROWSER)
                .setInitDir(path)
                .create()
                .browse(this);
    }

    @Override
    protected void onPause() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
        if (menu != null) {
            menu.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        if (menu != null) {
            menu.onResume();
        }
        super.onResume();
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
    }

    @Override
    protected void onStart() {
        super.onStart();
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START);
    }

    @Override
    protected void onStop() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
        super.onStop();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean handled = true;
        if (menu != null && menuType == MenuType.GAME) {
            if (!(handled = menu.getInput().handleKeyEvent(event))) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                        && !((GameMenu) menu).getTouchCharInput().isEnabled()) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        // Key repeats only update this state; one matching ACTION_UP emits
                        // exactly one press/release pair to the game.
                        backKeyDownHandled = true;
                        return true;
                    }
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        boolean shouldSendEscape = backKeyDownHandled;
                        backKeyDownHandled = false;
                        if (shouldSendEscape) {
                            sendEscapeToGame();
                        }
                    }
                    return true;
                } else if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN
                        || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
                    MenuView menuView = ((GameMenu) menu).getMenuView();
                    if (menuView.getAlpha() == 0 || menuView.getVisibility() == View.INVISIBLE) {
                        DrawerLayout drawer = (DrawerLayout) menu.getLayout();
                        if (drawer.isDrawerOpen(GravityCompat.START)
                                || drawer.isDrawerOpen(GravityCompat.END)) {
                            if (event.getAction() == KeyEvent.ACTION_UP) {
                                drawer.closeDrawers();
                                volumeDownTime = System.currentTimeMillis();
                            }
                        } else if (System.currentTimeMillis() - volumeDownTime > 800) {
                            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                                return true;
                            }
                            drawer.openDrawer(GravityCompat.START, true);
                            drawer.openDrawer(GravityCompat.END, true);
                        } else {
                            volumeDownTime = System.currentTimeMillis();
                        }
                    }
                }
            }
        }
        return handled || super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        backKeyDownHandled = false;
        if (!sendEscapeToGame()) {
            // Only retain upstream/system behavior when launch state is unavailable,
            // such as a broken SDL library dialog or a canceled launch.
            super.onBackPressed();
        }
    }

    private boolean sendEscapeToGame() {
        if (menu == null || menuType != MenuType.GAME) {
            return false;
        }
        menu.getInput().sendKeyEvent(FCLKeycodes.KEY_ESC, true);
        menu.getInput().sendKeyEvent(FCLKeycodes.KEY_ESC, false);
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (menu != null && menuType == MenuType.GAME
                && menu.getInput().handleGenericMotionEvent(event)) {
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        DisplayUtil.refreshDisplayMetrics(this);
    }

    @Override
    protected void onDestroy() {
        FCLBridge bridge = fclBridge;
        boolean hadSDLGame = bridge != null && menuType == MenuType.GAME && !mBrokenLibraries;
        if (bridge != null) {
            bridge.setSurfaceHolder(null);
        }
        Terracotta.setWaiting(this, true);
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);

        // SDL upstream tears down its native synchronization objects after a fixed
        // one-second join. Give Minecraft an earlier quit event and a bounded save/exit
        // grace period. If the JVM still owns SDL after that, terminating the current
        // game process is safer than entering upstream nativeQuit() and risking a UAF.
        Thread sdlThread = mSDLThread;
        if (!mBrokenLibraries && sdlThread != null && sdlThread.isAlive()) {
            try {
                nativeSendQuit();
                sdlThread.join(SDL_SHUTDOWN_GRACE_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable throwable) {
                Logging.LOG.log(Level.SEVERE, "Failed to request a safe SDL shutdown", throwable);
            }

            if (sdlThread.isAlive()) {
                Logging.LOG.log(Level.SEVERE,
                        "SDLThread did not stop within the FCL shutdown grace period; "
                                + "terminating the game process before SDL native teardown.");
                android.os.Process.killProcess(android.os.Process.myPid());
                return;
            }
        }

        super.onDestroy();
        menu = null;
        fclBridge = null;
        menuType = null;

        // Game execution lives in this process. Reclaim it only after upstream SDL has
        // completed nativeCleanupMainThread() and nativeQuit(). Crash UI runs in :crash.
        if (hadSDLGame) {
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycle;
    }
}
