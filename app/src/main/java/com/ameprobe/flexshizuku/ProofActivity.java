package com.ameprobe.flexshizuku;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.OrientationEventListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.view.Surface;
import android.view.TextureView;
import java.lang.ref.WeakReference;

public final class ProofActivity extends Activity {
    private static WeakReference<ProofActivity> current = new WeakReference<>(null);
    private static final long MAX_SESSION_MS = 5 * 60 * 1000L;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private TextureView texture;
    private MediaPlayer player;
    private OrientationEventListener orientationListener;
    private int candidateOrientationBucket = -1;
    private long candidateOrientationSinceMs;

    static void stopIfRunning() {
        ProofActivity activity = current.get();
        if (activity != null) activity.runOnUiThread(activity::finishAndRemoveTask);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        current = new WeakReference<>(this);
        // Let the FlexWindow follow the user's normal One UI inactivity
        // timeout. The controller restores the activity after a normal wake
        // and unlock, rather than holding a screen wake lock indefinitely.
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setDecorFitsSystemWindows(false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // TextureView is deliberately used instead of VideoView/SurfaceView.
        // Samsung's unfolded cover-display compositor may reject a separate
        // video surface, while a TextureView stays in this app's normal window.
        texture = new TextureView(this);
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                startPlayback(surface);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                stopPlayback();
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
        });
        root.addView(texture, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        setContentView(root);
        startContentRotationTracking();

        // The inner-display controller remains the manual kill switch. The
        // proof also self-terminates after a short session for this test.
        timeoutHandler.postDelayed(this::finishAndRemoveTask, MAX_SESSION_MS);

        WindowInsetsController controller = getWindow().getDecorView().getWindowInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override protected void onDestroy() {
        stopContentRotationTracking();
        timeoutHandler.removeCallbacksAndMessages(null);
        stopPlayback();
        if (current.get() == this) current.clear();
        super.onDestroy();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private void startContentRotationTracking() {
        orientationListener = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int degrees) {
                if (degrees == ORIENTATION_UNKNOWN || texture == null) return;
                int bucket = ((degrees + 45) / 90) % 4;
                long now = android.os.SystemClock.uptimeMillis();
                if (bucket != candidateOrientationBucket) {
                    candidateOrientationBucket = bucket;
                    candidateOrientationSinceMs = now;
                    return;
                }
                if (now - candidateOrientationSinceMs < 400L) return;
                // Use the phone's absolute orientation. Unlocking while the
                // phone is sideways must not redefine which position is up.
                int rotationBucket = bucket;
                float targetRotation = rotationBucket * 90f;

                // Rotating the nearly-square cover canvas by 90/270 degrees
                // swaps its bounds. Scale to cover so no bars appear.
                float targetScale = 1f;
                if ((rotationBucket & 1) == 1
                        && texture.getWidth() > 0 && texture.getHeight() > 0) {
                    float width = texture.getWidth();
                    float height = texture.getHeight();
                    targetScale = Math.max(width / height, height / width);
                }
                texture.setRotation(targetRotation);
                texture.setScaleX(targetScale);
                texture.setScaleY(targetScale);
            }
        };
        if (orientationListener.canDetectOrientation()) orientationListener.enable();
    }

    private void stopContentRotationTracking() {
        if (orientationListener != null) {
            orientationListener.disable();
            orientationListener = null;
        }
    }

    private void startPlayback(SurfaceTexture surfaceTexture) {
        stopPlayback();
        try {
            player = MediaPlayer.create(this, R.raw.example_cover_animation);
            if (player == null) { finishAndRemoveTask(); return; }
            player.setSurface(new Surface(surfaceTexture));
            player.setLooping(true);
            player.setVolume(0f, 0f);
            player.setOnErrorListener((unused, what, extra) -> {
                finishAndRemoveTask();
                return true;
            });
            player.start();
        } catch (RuntimeException error) {
            finishAndRemoveTask();
        }
    }

    private void stopPlayback() {
        if (player != null) {
            try { player.stop(); } catch (IllegalStateException ignored) { }
            player.release();
            player = null;
        }
    }
}
