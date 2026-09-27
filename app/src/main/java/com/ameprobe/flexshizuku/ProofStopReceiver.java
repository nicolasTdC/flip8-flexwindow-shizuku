package com.ameprobe.flexshizuku;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ProofStopReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        ProofActivity.stopIfRunning();
    }
}
