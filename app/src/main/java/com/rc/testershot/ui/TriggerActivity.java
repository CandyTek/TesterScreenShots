package com.rc.testershot.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import com.rc.testershot.service.ShotService;

/**
 * Invisible trampoline used by the Quick Settings tile (and by notification buttons on Android 12+
 * when no shell / accessibility is available): starting an activity collapses the shade.
 */
public class TriggerActivity extends Activity {
    public static final String EXTRA_DELAY = "delay";

    public static Intent intent(Context c, int delay) {
        return new Intent(c, TriggerActivity.class)
                .putExtra(EXTRA_DELAY, delay)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_NO_HISTORY);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent i = ShotService.captureIntent(this, getIntent().getIntExtra(EXTRA_DELAY, 0));
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        finish();
        overridePendingTransition(0, 0);
    }
}
