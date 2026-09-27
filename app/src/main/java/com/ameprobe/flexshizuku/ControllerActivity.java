package com.ameprobe.flexshizuku;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import rikka.shizuku.Shizuku;

public final class ControllerActivity extends Activity {
    private static final int REQUEST_SHIZUKU = 42;
    private TextView status;
    private IControllerService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable statusPoll = new Runnable() {
        @Override public void run() {
            if (service != null) {
                try { setStatus("Controller: " + service.getStatus()); }
                catch (RemoteException ignored) { service = null; }
            }
            handler.postDelayed(this, 1000);
        }
    };

    private final Shizuku.UserServiceArgs serviceArgs = new Shizuku.UserServiceArgs(
            new ComponentName("com.ameprobe.flexshizuku", ControllerUserService.class.getName()))
            .daemon(true).processNameSuffix("controller").debuggable(true).version(1).tag("flex-controller");

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IControllerService.Stub.asInterface(binder);
            setStatus("Shizuku connected. Ready.");
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            setStatus("Shizuku service lost. Session stopped safely.");
        }
    };

    private final Shizuku.OnBinderReceivedListener binderReceived = this::ensurePermission;
    private final Shizuku.OnBinderDeadListener binderDead = () -> {
        service = null;
        setStatus("Shizuku binder died. Samsung state should restore automatically.");
    };
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (code, result) -> {
        if (code == REQUEST_SHIZUKU && result == PackageManager.PERMISSION_GRANTED) bindController();
        else setStatus("Shizuku permission denied.");
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(64, 64, 64, 64);
        root.setBackgroundColor(Color.BLACK);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(18f);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(-1, 0, 1f));

        Button start = new Button(this);
        start.setText("START FLEXWINDOW");
        start.setOnClickListener(v -> call(true));
        root.addView(start, new LinearLayout.LayoutParams(-1, 150));

        Button stop = new Button(this);
        stop.setText("STOP AND RESTORE");
        stop.setOnClickListener(v -> call(false));
        root.addView(stop, new LinearLayout.LayoutParams(-1, 150));
        setContentView(root);

        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionResult);
        handler.post(statusPoll);
        setStatus("Waiting for Shizuku…");
    }

    private void ensurePermission() {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bindController();
        else if (!Shizuku.shouldShowRequestPermissionRationale()) Shizuku.requestPermission(REQUEST_SHIZUKU);
        else setStatus("Shizuku permission was denied permanently.");
    }

    private void bindController() {
        setStatus("Binding shell controller…");
        Shizuku.bindUserService(serviceArgs, connection);
    }

    private void call(boolean start) {
        if (service == null) { setStatus("Controller is not connected."); return; }
        try {
            if (start) service.startController(); else service.stopController();
            setStatus(start ? "Controller started. You may switch apps." : "Stopped; Samsung state restored.");
        } catch (RemoteException error) {
            setStatus("Controller call failed: " + error.getMessage());
        }
    }

    private void setStatus(String text) { runOnUiThread(() -> status.setText(text)); }

    @Override protected void onDestroy() {
        handler.removeCallbacks(statusPoll);
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        Shizuku.removeRequestPermissionResultListener(permissionResult);
        super.onDestroy();
    }
}
