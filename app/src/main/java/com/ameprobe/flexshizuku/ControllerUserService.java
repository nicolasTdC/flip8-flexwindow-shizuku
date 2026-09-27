package com.ameprobe.flexshizuku;

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;

public final class ControllerUserService extends IControllerService.Stub {
    private static final String TAG = "FlexShizukuService";
    private final Context context;
    private final Object stateManager;
    private final Class<?> requestClass;
    private final Class<?> callbackClass;
    private final Method requestStateMethod;
    private final Method cancelStateMethod;
    private final Executor direct = Runnable::run;
    private volatile boolean running;
    private volatile boolean requestActive;
    private volatile String status = "idle";
    private Thread worker;

    public ControllerUserService(Context context) throws Exception {
        this.context = context;
        this.stateManager = context.getSystemService("device_state");
        this.requestClass = Class.forName("android.hardware.devicestate.DeviceStateRequest");
        this.callbackClass = Class.forName("android.hardware.devicestate.DeviceStateRequest$Callback");
        this.requestStateMethod = stateManager.getClass().getMethod(
                "requestState", requestClass, Executor.class, callbackClass);
        this.cancelStateMethod = stateManager.getClass().getMethod("cancelStateRequest");
    }

    @Override public synchronized void startController() {
        if (running) {
            Log.i(TAG, "start ignored: already running; status=" + status);
            return;
        }
        running = true;
        Log.i(TAG, "controller start requested");
        worker = new Thread(this::loop, "flex-lifecycle");
        worker.start();
    }

    @Override public synchronized void stopController() {
        running = false;
        stopProofAndReset();
        status = "stopped";
    }

    @Override public String getStatus() { return status; }

    private void loop() {
        status = "running";
        String lastSnapshot = "";
        boolean batterySaverLatched = false;
        boolean batterySaverSuspensionApplied = false;
        long batterySaverOffSinceMs = -1L;
        try {
            while (running) {
                // A daemon UserService can outlive Shizuku's binder process on this
                // Samsung build. Treat the manager process as an independent
                // heartbeat so a lost Shizuku session cannot leave state 4 held.
                command("pidof", "shizuku_server");
                String device = command("dumpsys", "device_state");
                String power = command("dumpsys", "power");
                String policy = command("dumpsys", "window", "policy");
                String activities = command("dumpsys", "activity", "activities");
                int base = integerAfter(device, "mBaseState=Optional[DeviceState{identifier=");
                boolean awake = power.contains("mWakefulness=Awake");
                boolean batterySaverReported = "1".equals(
                        command("settings", "get", "global", "low_power").trim())
                        || power.contains("Battery Saver is currently: ON")
                        || power.contains("mSettingBatterySaverEnabled=true");
                long now = android.os.SystemClock.uptimeMillis();
                if (batterySaverReported) {
                    batterySaverLatched = true;
                    batterySaverOffSinceMs = -1L;
                } else if (batterySaverLatched) {
                    if (batterySaverOffSinceMs < 0L) batterySaverOffSinceMs = now;
                    else if (now - batterySaverOffSinceMs >= 3000L) {
                        batterySaverLatched = false;
                        batterySaverOffSinceMs = -1L;
                    }
                }
                boolean batterySaver = batterySaverLatched;
                boolean unlocked = policy.contains("interactiveState=INTERACTIVE_STATE_AWAKE")
                        && !policy.matches("(?s).*\\n\\s+showing=true\\s*\\n.*");
                boolean proofProcessAlive = processExists("com.ameprobe.flexshizuku:proof");
                boolean proofOnDisplay1 = proofProcessAlive
                        && proofPresentOnDisplay1(activities);
                // Samsung Camera/FlipShot can replace the secondary-display
                // task while it is foreground. Avoid fighting it, then recover
                // our disposable proof as soon as Camera leaves the foreground.
                boolean cameraForeground = cameraIsResumed(activities);
                // State 2 is Samsung's HALF_OPENED posture around 90 degrees.
                boolean eligible = (base == 2 || base == 3) && awake && unlocked;
                String snapshot = "base=" + base + " awake=" + awake
                        + " unlocked=" + unlocked + " batterySaver=" + batterySaver
                        + " camera=" + cameraForeground
                        + " proofDisplay1=" + proofOnDisplay1
                        + " proofProcess=" + proofProcessAlive
                        + " requestActive=" + requestActive;
                if (!snapshot.equals(lastSnapshot)) {
                    Log.i(TAG, snapshot);
                    lastSnapshot = snapshot;
                }

                if (batterySaver) {
                    if (!batterySaverSuspensionApplied) {
                        stopProofAndReset();
                        batterySaverSuspensionApplied = true;
                    }
                    status = "suspended for Battery Saver";
                    Thread.sleep(1000);
                    continue;
                }
                batterySaverSuspensionApplied = false;

                if (eligible && !proofOnDisplay1) {
                    if (cameraForeground) {
                        status = "paused for Camera; will recover when it closes";
                    } else {
                        // The proof activity may have been removed while its
                        // process remains alive. Reissue state 4 and relaunch
                        // whenever it is no longer the display-1 foreground.
                        if (requestActive) cancelRequest();
                        requestConcurrent();
                        command("am", "start", "--display", "1", "-n",
                                "com.ameprobe.flexshizuku/.ProofActivity");
                        status = "active on display 1";
                        Log.i(TAG, status);
                    }
                } else if (!eligible && (requestActive || proofOnDisplay1)) {
                    stopProofAndReset();
                    status = "suspended";
                }
                Thread.sleep(1000);
            }
        } catch (Throwable error) {
            status = "error: " + error.getClass().getSimpleName() + ": " + error.getMessage();
            Log.e(TAG, "controller failed", error);
        } finally {
            Log.i(TAG, "controller cleanup: " + status);
            stopProofAndReset();
            running = false;
            // Daemon UserServices are not guaranteed to die with Shizuku on
            // this firmware. Exit after cleanup so restarts cannot accumulate
            // idle shell processes or retain a stale binder endpoint.
            System.exit(0);
        }
    }

    private void requestConcurrent() throws Exception {
        Object builder = requestClass.getMethod("newBuilder", int.class).invoke(null, 4);
        Object request = builder.getClass().getMethod("build").invoke(builder);
        Object callback = Proxy.newProxyInstance(
                callbackClass.getClassLoader(),
                new Class<?>[] { callbackClass },
                (proxy, method, args) -> null);
        requestStateMethod.invoke(stateManager, request, direct, callback);
        requestActive = true;
    }

    private void cancelRequest() {
        if (!requestActive) return;
        try { cancelStateMethod.invoke(stateManager); } catch (Throwable ignored) {}
        requestActive = false;
    }

    private void stopProofAndReset() {
        cancelRequest();
        try {
            command("am", "broadcast", "-a", "com.ameprobe.flexshizuku.STOP_PROOF",
                    "-n", "com.ameprobe.flexshizuku/.ProofStopReceiver");
        } catch (Throwable ignored) {}
    }

    private String command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
        }
        int exit = process.waitFor();
        if (exit != 0) throw new IllegalStateException(String.join(" ", args) + " exit=" + exit + " " + output);
        return output.toString();
    }

    private boolean processExists(String name) {
        try { return !command("pidof", name).trim().isEmpty(); }
        catch (Throwable ignored) { return false; }
    }

    private static boolean cameraIsResumed(String text) {
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("topResumedActivity=")
                    || trimmed.startsWith("ResumedActivity:")) {
                return trimmed.contains("com.sec.android.app.camera")
                        || trimmed.contains("com.samsung.android.camera");
            }
        }
        return false;
    }

    private static int integerAfter(String text, String marker) {
        int start = text.indexOf(marker);
        if (start < 0) return -1;
        start += marker.length();
        int end = start;
        while (end < text.length() && Character.isDigit(text.charAt(end))) end++;
        return Integer.parseInt(text.substring(start, end));
    }

    private static boolean proofPresentOnDisplay1(String text) {
        int start = text.indexOf("Display #1");
        if (start < 0) return false;
        int end = text.indexOf("ActivityTaskSupervisor state:", start + 1);
        if (end < 0) end = text.length();
        String display1 = text.substring(start, end);
        return display1.contains("com.ameprobe.flexshizuku/.ProofActivity")
                && (display1.contains("nowVisible=true")
                || display1.contains("mVisible=true"));
    }

    public void destroy() {
        stopController();
        System.exit(0);
    }
}
