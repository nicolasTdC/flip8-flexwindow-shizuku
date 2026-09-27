package com.ameprobe.flexshizuku;

import android.app.Activity;
import android.graphics.ImageDecoder;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
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
import android.widget.ImageView;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

public final class ProofActivity extends Activity {
    private static WeakReference<ProofActivity> current = new WeakReference<>(null);
    private static final long MAX_SESSION_MS = 5 * 60 * 1000L;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private TextureView texture;
    private View contentView;
    private MediaPlayer player;
    private AnimatedImageDrawable animatedImage;
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
        File selected = new File(getFilesDir(), ControllerActivity.MEDIA_FILE);
        String kind = getSharedPreferences(ControllerActivity.MEDIA_PREFS, MODE_PRIVATE)
                .getString(ControllerActivity.PREF_MEDIA_KIND, null);
        if (selected.isFile() && "image".equals(kind)) {
            showAnimatedImage(root, selected);
        } else {
            showVideo(root);
        }
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

    private void showVideo(FrameLayout root) {
        texture = new TextureView(this);
        contentView = texture;
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
    }

    private void showAnimatedImage(FrameLayout root, File selected) {
        ImageView image = new ImageView(this);
        image.setBackgroundColor(Color.BLACK);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        contentView = image;
        root.addView(image, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        try {
            Drawable drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(selected));
            image.setImageDrawable(drawable);
            if (drawable instanceof AnimatedImageDrawable) {
                animatedImage = (AnimatedImageDrawable) drawable;
                animatedImage.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                animatedImage.start();
            }
        } catch (IOException | RuntimeException error) {
            root.removeView(image);
            contentView = null;
            showVideo(root);
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
                if (degrees == ORIENTATION_UNKNOWN || contentView == null) return;
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
                        && contentView.getWidth() > 0 && contentView.getHeight() > 0) {
                    float width = contentView.getWidth();
                    float height = contentView.getHeight();
                    targetScale = Math.max(width / height, height / width);
                }
                contentView.setRotation(targetRotation);
                contentView.setScaleX(targetScale);
                contentView.setScaleY(targetScale);
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
        File selected = new File(getFilesDir(), ControllerActivity.MEDIA_FILE);
        String kind = getSharedPreferences(ControllerActivity.MEDIA_PREFS, MODE_PRIVATE)
                .getString(ControllerActivity.PREF_MEDIA_KIND, null);
        if (selected.isFile() && "video".equals(kind)) {
            try {
                Surface surface = new Surface(surfaceTexture);
                player = new MediaPlayer();
                player.setSurface(surface);
                surface.release();
                player.setDataSource(selected.getAbsolutePath());
                player.setLooping(true);
                player.setVolume(0f, 0f);
                player.setOnPreparedListener(MediaPlayer::start);
                player.setOnErrorListener((unused, what, extra) -> {
                    startBundledPlayback(surfaceTexture);
                    return true;
                });
                player.prepareAsync();
                return;
            } catch (IOException | RuntimeException error) {
                stopPlayback();
            }
        }
        startBundledPlayback(surfaceTexture);
    }

    private void startBundledPlayback(SurfaceTexture surfaceTexture) {
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
        if (animatedImage != null) {
            animatedImage.stop();
            animatedImage = null;
        }
        if (player != null) {
            try { player.stop(); } catch (IllegalStateException ignored) { }
            player.release();
            player = null;
        }
    }
}
