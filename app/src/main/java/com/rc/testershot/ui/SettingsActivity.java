package com.rc.testershot.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.SeekBar;

import com.rc.testershot.BuildConfig;
import com.rc.testershot.Prefs;
import com.rc.testershot.R;
import com.rc.testershot.Workspaces;
import com.rc.testershot.capture.CaptureEngine;
import com.rc.testershot.capture.RootShell;
import com.rc.testershot.capture.ShotAccessibilityService;
import com.rc.testershot.databinding.ActivitySettingsBinding;
import com.rc.testershot.server.LanServer;
import com.rc.testershot.service.ShotService;

/** Plain-views settings screen; every change is saved immediately. */
public class SettingsActivity extends Activity implements CaptureEngine.Listener {
    private ActivitySettingsBinding b;
    private Prefs p;
    private boolean binding;

    public interface IntSink { void put(int v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        p = Prefs.get(this);
        b.back.setOnClickListener(v -> finish());

        binding = true;
        switch (p.method()) {
            case Prefs.METHOD_ROOT: b.rbRoot.setChecked(true); break;
            case Prefs.METHOD_ADB: b.rbAdb.setChecked(true); break;
            case Prefs.METHOD_ACCESSIBILITY: b.rbAccessibility.setChecked(true); break;
            case Prefs.METHOD_PROJECTION: b.rbProjection.setChecked(true); break;
            default: b.rbAuto.setChecked(true);
        }
        switch (p.format()) {
            case Prefs.FORMAT_PNG: b.rbPng.setChecked(true); break;
            case Prefs.FORMAT_WEBP: b.rbWebp.setChecked(true); break;
            default: b.rbJpeg.setChecked(true);
        }
        b.swFallbackAcc.setChecked(p.fallbackAccessibility());
        b.swFallbackProj.setChecked(p.fallbackProjection());
        b.swCropNav.setChecked(p.cropNavBar());
        b.swMeta.setChecked(p.saveMeta());
        b.swLogcat.setChecked(p.saveLogcat());
        b.swThumb.setChecked(p.showThumbnail());
        b.swServer.setChecked(p.serverEnabled());
        b.swServerZip.setChecked(p.serverZip());
        b.etAdbPort.setText(p.adbPort() > 0 ? String.valueOf(p.adbPort()) : "");
        b.etCollapseWait.setText(String.valueOf(p.collapseWaitMs()));
        b.etLogLines.setText(String.valueOf(p.logcatLines()));
        b.etThumbSec.setText(String.valueOf(p.thumbnailSeconds()));
        b.etServerPort.setText(String.valueOf(p.serverPort()));
        b.seekQuality.setProgress(p.quality() - 1);
        updateQualityLabel();
        binding = false;

        b.methodGroup.setOnCheckedChangeListener((g, id) -> {
            String m = id == b.rbRoot.getId() ? Prefs.METHOD_ROOT
                    : id == b.rbAdb.getId() ? Prefs.METHOD_ADB
                    : id == b.rbAccessibility.getId() ? Prefs.METHOD_ACCESSIBILITY
                    : id == b.rbProjection.getId() ? Prefs.METHOD_PROJECTION : Prefs.METHOD_AUTO;
            p.setMethod(m);
            CaptureEngine.get(this).detectAsync(false);
            refreshState();
        });
        b.formatGroup.setOnCheckedChangeListener((g, id) -> {
            p.setFormat(id == b.rbPng.getId() ? Prefs.FORMAT_PNG : id == b.rbWebp.getId() ? Prefs.FORMAT_WEBP : Prefs.FORMAT_JPEG);
            updateQualityLabel();
        });
        b.seekQuality.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) p.setQuality(progress + 1);
                updateQualityLabel();
            }

            @Override public void onStartTrackingTouch(SeekBar s) {}

            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        b.swFallbackAcc.setOnCheckedChangeListener((v, c) -> { p.setFallbackAccessibility(c); refreshState(); });
        b.swFallbackProj.setOnCheckedChangeListener((v, c) -> { p.setFallbackProjection(c); refreshState(); });
        b.swCropNav.setOnCheckedChangeListener((v, c) -> p.setCropNavBar(c));
        b.swMeta.setOnCheckedChangeListener((v, c) -> p.setSaveMeta(c));
        b.swLogcat.setOnCheckedChangeListener((v, c) -> p.setSaveLogcat(c));
        b.swThumb.setOnCheckedChangeListener((v, c) -> p.setShowThumbnail(c));
        b.swServerZip.setOnCheckedChangeListener((v, c) -> p.setServerZip(c));
        b.swServer.setOnCheckedChangeListener((v, c) -> {
            p.setServerEnabled(c);
            ShotService.start(this, ShotService.ACTION_SERVER);
        });

        onInt(b.etAdbPort, v -> { p.setAdbPort(v > 0 && v < 65536 ? v : 0); }, 0);
        onInt(b.etCollapseWait, v -> p.setCollapseWaitMs(Math.max(0, Math.min(5000, v))), 450);
        onInt(b.etLogLines, v -> p.setLogcatLines(Math.max(10, Math.min(20000, v))), 500);
        onInt(b.etThumbSec, v -> p.setThumbnailSeconds(Math.max(1, Math.min(60, v))), 4);
        onInt(b.etServerPort, v -> {
            if (v >= 1024 && v < 65536 && v != p.serverPort()) {
                p.setServerPort(v);
                ShotService.start(this, ShotService.ACTION_SERVER);
            }
        }, 8848);

        b.btnDetect.setOnClickListener(v -> {
            CaptureEngine.get(this).detectAsync(true);
            b.tvState.setText(R.string.set_detecting);
        });
        b.btnAcc.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception ignored) {}
        });
        b.btnProj.setOnClickListener(v -> startActivity(new Intent(this, ProjectionRequestActivity.class)));
        b.btnProjStop.setOnClickListener(v -> {
            CaptureEngine.get(this).projection().stop();
            CaptureEngine.get(this).onBackendsChanged();
        });
        b.btnOverlay.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 23) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            }
        });
        b.btnToken.setOnClickListener(v -> {
            p.regenerateToken();
            refreshState();
        });
        if (Build.VERSION.SDK_INT < 23) b.btnOverlay.setVisibility(View.GONE);

        b.tvAbout.setText(getString(R.string.set_about_text, BuildConfig.VERSION_NAME, Workspaces.root().getAbsolutePath()));
    }

    private void onInt(EditText et, IntSink sink, int fallback) {
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

            @Override public void onTextChanged(CharSequence s, int st, int bf, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (binding) return;
                int v;
                try {
                    v = s.length() == 0 ? fallback : Integer.parseInt(s.toString().trim());
                } catch (NumberFormatException e) {
                    return;
                }
                sink.put(v);
            }
        });
    }

    private void updateQualityLabel() {
        boolean lossless = Prefs.FORMAT_PNG.equals(p.format());
        b.tvQuality.setText(lossless ? getString(R.string.set_quality_lossless) : getString(R.string.set_quality, p.quality()));
        b.seekQuality.setEnabled(!lossless);
    }

    @Override
    protected void onResume() {
        super.onResume();
        CaptureEngine.get(this).addListener(this);
        refreshState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CaptureEngine.get(this).removeListener(this);
    }

    @Override
    public void onBackendsChanged() {
        refreshState();
    }

    private void refreshState() {
        CaptureEngine e = CaptureEngine.get(this);
        StringBuilder sb = new StringBuilder();
        Boolean root = e.rootState();
        sb.append(getString(R.string.method_root)).append(": ")
                .append(root == null ? (RootShell.hasSuBinary() ? getString(R.string.state_unknown) : getString(R.string.state_no_su))
                        : root ? getString(R.string.state_ok) : getString(R.string.state_denied)).append('\n');
        String adb = e.adbState();
        sb.append(getString(R.string.method_adb)).append(": ")
                .append(e.adbConnected() ? getString(R.string.state_ok) + " (" + e.effectiveAdbPort() + ")"
                        : adb == null || adb.isEmpty() ? getString(R.string.state_unknown) : adb).append('\n');
        sb.append(getString(R.string.method_accessibility)).append(": ")
                .append(Build.VERSION.SDK_INT < 30 ? getString(R.string.state_needs_11)
                        : ShotAccessibilityService.isRunning() ? getString(R.string.state_ok) : getString(R.string.state_off)).append('\n');
        sb.append(getString(R.string.method_projection)).append(": ")
                .append(e.projection().isActive() ? getString(R.string.state_ok) : getString(R.string.state_off)).append('\n');
        CaptureEngine.Backend be = e.predictBackend();
        sb.append('\n').append(getString(R.string.set_active)).append(": ")
                .append(be == null ? getString(R.string.state_none) : ShotService.backendLabel(this, be));
        String err = e.lastError();
        if (err != null) sb.append('\n').append(getString(R.string.set_last_error)).append(": ").append(err);
        b.tvState.setText(sb.toString());

        b.btnProjStop.setEnabled(e.projection().isActive());

        LanServer s = ShotService.server();
        String serverText;
        if (!p.serverEnabled()) serverText = getString(R.string.server_disabled);
        else if (s != null) serverText = getString(R.string.server_running, s.getPort(), p.serverToken());
        else if (ShotService.serverError() != null) serverText = getString(R.string.err_server, ShotService.serverError());
        else serverText = getString(R.string.server_not_running);
        b.tvServer.setText(serverText);
    }
}
