package com.tungsten.fcl.control;

import android.app.Activity;
import android.view.View;

import androidx.annotation.Nullable;

import com.mio.ui.view.CursorView;
import com.tungsten.fclauncher.bridge.FCLBridge;
import com.tungsten.fclauncher.bridge.FCLBridgeCallback;
import com.tungsten.fcllibrary.component.view.FCLImageView;

public interface MenuCallback {

    void setup(Activity activity, FCLBridge fclBridge);

    View getLayout();

    @Nullable
    FCLBridge getBridge();

    FCLBridgeCallback getCallbackBridge();

    FCLInput getInput();

    CursorView getCursor();

    int getCursorMode();

    void onPause();

    void onResume();

    void onGraphicOutput();

    void onCursorModeChange(int mode);

    void onLog(String log);

    void onExit(int exitCode);

}
