package com.ameprobe.flexshizuku;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import rikka.shizuku.Shizuku;

public final class ControllerActivity extends Activity {
    private static final int REQUEST_SHIZUKU = 42;
    private static final int REQUEST_MEDIA = 43;
    static final String MEDIA_PREFS = "selected_media";
    static final String MEDIA_FILE = "flexwindow_media";
    static final String PREF_MEDIA_KIND = "kind";
    static final String PREF_MEDIA_NAME = "name";
    private TextView status;
    private TextView mediaLabel;
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
            .daemon(true).processNameSuffix("controller").debuggable(true).version(33).tag("flex-controller");

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

        mediaLabel = new TextView(this);
        mediaLabel.setTextColor(Color.LTGRAY);
        mediaLabel.setTextSize(14f);
        mediaLabel.setGravity(Gravity.CENTER);
        root.addView(mediaLabel, new LinearLayout.LayoutParams(-1, 80));

        Button chooseMedia = new Button(this);
        chooseMedia.setText("CHOOSE VIDEO OR GIF");
        chooseMedia.setOnClickListener(v -> chooseMedia());
        root.addView(chooseMedia, new LinearLayout.LayoutParams(-1, 130));

        Button useBundled = new Button(this);
        useBundled.setText("USE BUNDLED ANIMATION");
        useBundled.setOnClickListener(v -> clearSelectedMedia());
        root.addView(useBundled, new LinearLayout.LayoutParams(-1, 110));

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
        updateMediaLabel();
        setStatus("Waiting for Shizuku…");
    }

    private void chooseMedia() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[] { "video/*", "image/gif", "image/webp" });
        startActivityForResult(intent, REQUEST_MEDIA);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_MEDIA || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        String mime = getContentResolver().getType(uri);
        String name = displayName(uri);
        String lowerName = name.toLowerCase(java.util.Locale.ROOT);
        boolean animatedImage = "image/gif".equals(mime)
                || "image/webp".equals(mime)
                || lowerName.endsWith(".gif") || lowerName.endsWith(".webp");
        boolean video = (mime != null && mime.startsWith("video/"))
                || lowerName.endsWith(".mp4") || lowerName.endsWith(".m4v")
                || lowerName.endsWith(".webm") || lowerName.endsWith(".mkv");
        if (!animatedImage && !video) {
            setStatus("Unsupported file. Choose a video, animated GIF, or animated WebP.");
            return;
        }

        File destination = new File(getFilesDir(), MEDIA_FILE);
        File temporary = new File(getFilesDir(), MEDIA_FILE + ".tmp");
        try (InputStream input = getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(temporary)) {
            if (input == null) throw new IOException("Unable to open selected file");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
            Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            getSharedPreferences(MEDIA_PREFS, MODE_PRIVATE).edit()
                    .putString(PREF_MEDIA_KIND, animatedImage ? "image" : "video")
                    .putString(PREF_MEDIA_NAME, name)
                    .apply();
            updateMediaLabel();
            setStatus("Media saved. Stop and start FlexWindow to apply it.");
        } catch (IOException error) {
            temporary.delete();
            setStatus("Could not import media: " + error.getMessage());
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String value = cursor.getString(column);
                    if (value != null && !value.isEmpty()) return value;
                }
            }
        }
        String segment = uri.getLastPathSegment();
        return segment == null ? "Selected media" : segment;
    }

    private void clearSelectedMedia() {
        new File(getFilesDir(), MEDIA_FILE).delete();
        getSharedPreferences(MEDIA_PREFS, MODE_PRIVATE).edit().clear().apply();
        updateMediaLabel();
        setStatus("Bundled animation selected. Stop and start FlexWindow to apply it.");
    }

    private void updateMediaLabel() {
        String name = getSharedPreferences(MEDIA_PREFS, MODE_PRIVATE)
                .getString(PREF_MEDIA_NAME, null);
        mediaLabel.setText(name == null ? "Current media: bundled animation"
                : "Current media: " + name);
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
