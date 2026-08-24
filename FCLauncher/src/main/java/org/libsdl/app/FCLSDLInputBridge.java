package org.libsdl.app;

import android.view.MotionEvent;
import android.view.ViewGroup;

/**
 * Adapts FCL's synthetic input to SDL's unmodified Android JNI entry points.
 *
 * <p>The native {@code Android_OnMouse} implementation expects the complete
 * Android mouse button bitmask on every down/up event, rather than the button
 * which changed. FCL only reports individual button transitions, so this
 * class owns that bitmask.</p>
 */
public final class FCLSDLInputBridge {
    private static int mouseButtonState;
    private static float mouseX;
    private static float mouseY;
    private static boolean hasMousePosition;
    private static boolean textBridgeInstalled;

    private static native boolean nativeInstallTextBridge();

    private FCLSDLInputBridge() {
    }

    /** Resets state which belongs to a single SDL game run. */
    public static synchronized void reset() {
        mouseButtonState = 0;
        mouseX = 0.0f;
        mouseY = 0.0f;
        hasMousePosition = false;
    }

    /**
     * Replaces only SDLInputConnection.nativeCommitText with FCL's standard
     * UTF-8 encoder. A failed installation remains retryable and leaves SDL's
     * upstream native method registered.
     */
    public static synchronized boolean installTextBridge() {
        if (!textBridgeInstalled) {
            textBridgeInstalled = nativeInstallTextBridge();
        }
        return textBridgeInstalled;
    }

    /** Sends an Android KeyEvent keycode to SDL. */
    public static void sendKey(int androidKeycode, boolean pressed) {
        if (pressed) {
            SDLActivity.onNativeKeyDown(androidKeycode);
        } else {
            SDLActivity.onNativeKeyUp(androidKeycode);
        }
    }

    /**
     * Sends FCL's absolute pointer position.
     *
     * <p>FCL keeps an accumulated pointer position even while the game has
     * captured the mouse. In SDL relative mode this method converts that
     * accumulated position back to a delta, matching the coordinates emitted
     * by SDL's mainline Android motion listener.</p>
     */
    public static synchronized void sendMousePosition(float x, float y) {
        boolean relative = isRelativeMouseMode();
        float eventX = x;
        float eventY = y;

        if (relative) {
            if (hasMousePosition) {
                eventX -= mouseX;
                eventY -= mouseY;
            } else {
                // Establish a baseline instead of turning the first absolute
                // coordinate into a large camera movement.
                eventX = 0.0f;
                eventY = 0.0f;
            }
        }

        mouseX = x;
        mouseY = y;
        hasMousePosition = true;
        SDLActivity.onNativeMouse(mouseButtonState, MotionEvent.ACTION_MOVE,
                eventX, eventY, relative);
    }

    /** Sends an already-relative mouse delta to SDL. */
    public static synchronized void sendRelativeMouseMotion(float deltaX, float deltaY) {
        SDLActivity.onNativeMouse(mouseButtonState, MotionEvent.ACTION_MOVE,
                deltaX, deltaY, true);
    }

    /**
     * Sends a zero-based FCL/GLFW mouse button transition.
     *
     * <p>The first five GLFW button numbers have the same bit positions as
     * Android's PRIMARY, SECONDARY, TERTIARY, BACK and FORWARD buttons.</p>
     */
    public static synchronized void sendMouseButton(int button, boolean pressed) {
        int changedButton = toAndroidButtonMask(button);
        if (changedButton == 0) {
            return;
        }

        int newState = pressed
                ? mouseButtonState | changedButton
                : mouseButtonState & ~changedButton;
        if (newState == mouseButtonState) {
            return;
        }
        mouseButtonState = newState;

        boolean relative = isRelativeMouseMode();
        float eventX = relative || !hasMousePosition ? 0.0f : mouseX;
        float eventY = relative || !hasMousePosition ? 0.0f : mouseY;
        SDLActivity.onNativeMouse(mouseButtonState,
                pressed ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP,
                eventX, eventY, relative);
    }

    /** Sends an Android-style horizontal/vertical wheel amount to SDL. */
    public static synchronized void sendMouseWheel(float horizontal, float vertical) {
        SDLActivity.onNativeMouse(mouseButtonState, MotionEvent.ACTION_SCROLL,
                horizontal, vertical, false);
    }

    /** Sends arbitrary Unicode text through SDL's mainline input connection. */
    public static void sendText(String text) {
        if (text == null || text.isEmpty() || "\u0000".equals(text)) {
            return;
        }
        SDLInputConnection.nativeCommitText(text, 0);
    }

    public static boolean isRelativeMouseMode() {
        return SDLActivity.getMotionListener().inRelativeMode();
    }

    /** Returns whether SDL's mainline Android text editor currently owns focus. */
    public static boolean isTextInputReady() {
        SDLDummyEdit textEdit = SDLActivity.mTextEdit;
        if (textEdit == null || !textEdit.hasFocus()) {
            return false;
        }
        ViewGroup.LayoutParams layoutParams = textEdit.getLayoutParams();
        return layoutParams != null && layoutParams.width > 0 && layoutParams.height > 0;
    }

    private static int toAndroidButtonMask(int button) {
        if (button < 0 || button > 4) {
            return 0;
        }
        return 1 << button;
    }
}
