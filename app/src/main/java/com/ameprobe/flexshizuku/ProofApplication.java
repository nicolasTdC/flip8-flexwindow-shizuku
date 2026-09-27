package com.ameprobe.flexshizuku;

import android.app.Application;
import android.util.Log;
import rikka.shizuku.Shizuku;

public final class ProofApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Shizuku.addBinderDeadListener(() -> {
            Log.w("FlexShizuku", "SHIZUKU_BINDER_DEAD");
            ProofActivity.stopIfRunning();
        });
    }
}
