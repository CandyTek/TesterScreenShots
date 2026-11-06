package com.rc.testershot.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import com.rc.testershot.R;
import com.rc.testershot.service.ShotService;

/** Asks for screen-recording permission and hands the result to the foreground service. */
public class ProjectionRequestActivity extends Activity {
    private static final int REQ = 7;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return;
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.err_projection_unsupported, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            Intent i = new Intent(this, ShotService.class)
                    .setAction(ShotService.ACTION_PROJECTION)
                    .putExtra(ShotService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(ShotService.EXTRA_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        }
        finish();
    }
}
