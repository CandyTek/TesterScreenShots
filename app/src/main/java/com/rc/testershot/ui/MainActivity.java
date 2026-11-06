package com.rc.testershot.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.drawable.BitmapDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;

import com.rc.testershot.Prefs;
import com.rc.testershot.R;
import com.rc.testershot.Workspaces;
import com.rc.testershot.capture.CaptureEngine;
import com.rc.testershot.databinding.ActivityMainBinding;
import com.rc.testershot.databinding.ItemShotBinding;
import com.rc.testershot.server.LanServer;
import com.rc.testershot.server.NetAddresses;
import com.rc.testershot.service.ShotService;
import com.rc.testershot.util.QrBitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Workspace browser: grid of screenshots, delete, add from gallery, workspace switching, LAN QR code. */
public class MainActivity extends Activity implements CaptureEngine.Listener {
    private static final int REQ_PICK = 1;
    private static final int REQ_STORAGE = 2;

    private ActivityMainBinding b;
    private final ThumbLoader thumbs = new ThumbLoader();
    private final ShotAdapter adapter = new ShotAdapter();
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback netCallback;
    private String qrUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        b.grid.setAdapter(adapter);
        b.workspaceButton.setOnClickListener(v -> showWorkspaceDialog());
        b.btnAdd.setOnClickListener(v -> pickImages());
        b.btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        b.btnCapture.setOnClickListener(v -> {
            Toast.makeText(this, getString(R.string.toast_capture_in, 3), Toast.LENGTH_SHORT).show();
            ShotService.start(this, ShotService.ACTION_START);
            startService(ShotService.captureIntent(this, 3));
        });
        applySplitOrientation();
        setupQrPanel();
        b.statusLine.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        ShotService.start(this, ShotService.ACTION_START);
        requestStorageIfNeeded();
        registerNetworkCallback();
    }

    @Override
    protected void onResume() {
        super.onResume();
        CaptureEngine.get(this).addListener(this);
        ShotService.start(this, ShotService.ACTION_REFRESH); // e.g. overlay permission just granted -> show ball
        reload();
        updateStatus();
        updateQr();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CaptureEngine.get(this).removeListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (netCallback != null) {
            try {
                ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCallback);
            } catch (Exception ignored) {}
        }
        thumbs.shutdown();
        bg.shutdownNow();
    }

    // ------------------------------------------------------------------ engine callbacks

    @Override
    public void onBackendsChanged() {
        updateStatus();
        updateQr();
    }

    @Override
    public void onShotSaved(File file) {
        reload();
    }

    // ------------------------------------------------------------------ status / banner

    private void updateStatus() {
        CaptureEngine e = CaptureEngine.get(this);
        CaptureEngine.Backend be = e.predictBackend();
        if (be != null) {
            b.statusLine.setText(getString(R.string.status_method, ShotService.backendLabel(this, be)));
            b.statusLine.setTextColor(getColor(R.color.ok));
        } else {
            b.statusLine.setText(R.string.status_no_method);
            b.statusLine.setTextColor(getColor(R.color.error));
        }

        if (needsStoragePermission()) {
            showBanner(getString(R.string.banner_storage), v -> requestStorageIfNeeded());
        } else if (Build.VERSION.SDK_INT >= 23 && Prefs.get(this).showThumbnail() && !Settings.canDrawOverlays(this)) {
            showBanner(getString(R.string.banner_overlay), v -> startActivity(new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        } else {
            b.banner.setVisibility(View.GONE);
        }
    }

    private void showBanner(String text, View.OnClickListener click) {
        b.banner.setText(text);
        b.banner.setOnClickListener(click);
        b.banner.setVisibility(View.VISIBLE);
    }

    private boolean needsStoragePermission() {
        // targetSdk 29 + requestLegacyExternalStorage: WRITE_EXTERNAL_STORAGE gives plain file access on every version.
        return Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageIfNeeded() {
        if (Build.VERSION.SDK_INT >= 23 && needsStoragePermission()) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            reload();
            updateStatus();
        }
    }

    // ------------------------------------------------------------------ grid

    private void reload() {
        final String ws = Prefs.get(this).workspace();
        b.workspaceName.setText(ws);
        b.qrTitle.setText(getString(R.string.qr_title_ws, ws));
        bg.execute(() -> {
            List<File> files = Workspaces.listImages(Workspaces.currentDir(this));
            long total = 0;
            for (File f : files) total += f.length();
            final long sum = total;
            main.post(() -> {
                if (isDestroyed()) return;
                adapter.set(files);
                b.empty.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
                b.footer.setText(getString(R.string.footer_count, files.size(), Formatter.formatShortFileSize(this, sum),
                        Workspaces.dir(ws).getAbsolutePath()));
            });
        });
    }

    private void confirmDelete(File f) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_title)
                .setMessage(getString(R.string.delete_message, f.getName()))
                .setPositiveButton(R.string.delete, (d, w) -> bg.execute(() -> {
                    Workspaces.deleteImage(this, f);
                    main.post(this::reload);
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private final class ShotAdapter extends BaseAdapter {
        private List<File> items = new ArrayList<>();

        void set(List<File> files) {
            items = files;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() { return items.size(); }

        @Override
        public File getItem(int position) { return items.get(position); }

        @Override
        public long getItemId(int position) { return items.get(position).getAbsolutePath().hashCode(); }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ItemShotBinding ib;
            if (convertView == null) {
                ib = ItemShotBinding.inflate(getLayoutInflater(), parent, false);
                ib.getRoot().setTag(ib);
            } else {
                ib = (ItemShotBinding) convertView.getTag();
            }
            File f = items.get(position);
            int col = b.grid.getColumnWidth() > 0 ? b.grid.getColumnWidth() : ib.thumb.getLayoutParams().height;
            // Cells follow the screen's aspect ratio, so screenshots fill them in either orientation.
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            float ratio = Math.max(0.5f, Math.min(2.2f, (float) dm.heightPixels / dm.widthPixels));
            int h = (int) (col * ratio);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) ib.thumb.getLayoutParams();
            if (lp.height != h) {
                lp.height = h;
                ib.thumb.setLayoutParams(lp);
            }
            thumbs.load(f, ib.thumb, col);
            ib.label.setText(DateFormat.format("MM-dd HH:mm:ss", new Date(f.lastModified())));
            ib.delete.setOnClickListener(v -> confirmDelete(f));
            ib.getRoot().setOnClickListener(v -> startActivity(PreviewActivity.intent(MainActivity.this, f)));
            return ib.getRoot();
        }
    }

    // ------------------------------------------------------------------ add images

    private void pickImages() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT)
                .setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(Intent.createChooser(i, getString(R.string.add_images)), REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        final List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        final File dir = Workspaces.currentDir(this);
        bg.execute(() -> {
            List<File> added = new ArrayList<>();
            for (Uri u : uris) {
                try {
                    File out = targetFor(dir, u);
                    try (InputStream in = getContentResolver().openInputStream(u);
                         OutputStream os = new FileOutputStream(out)) {
                        if (in == null) continue;
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    added.add(out);
                } catch (Exception e) {
                    android.util.Log.w("MainActivity", "import " + u, e);
                }
            }
            Workspaces.scan(this, added);
            main.post(() -> {
                Toast.makeText(this, getString(R.string.toast_added, added.size()), Toast.LENGTH_SHORT).show();
                reload();
            });
        });
    }

    private File targetFor(File dir, Uri u) {
        String name = null;
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {}
        if (name == null || !Workspaces.isImage(name)) {
            String mime = getContentResolver().getType(u);
            String ext = mime != null && mime.contains("png") ? "png" : mime != null && mime.contains("webp") ? "webp" : "jpg";
            name = "Imported_" + DateFormat.format("yyyyMMdd_HHmmss", new Date()) + "." + ext;
        }
        name = Workspaces.sanitize(name);
        return Workspaces.uniqueFile(dir, Workspaces.baseName(name), name.substring(name.lastIndexOf('.') + 1));
    }

    // ------------------------------------------------------------------ workspaces

    private void showWorkspaceDialog() {
        final List<String> names = Workspaces.list(this);
        final String cur = Prefs.get(this).workspace();
        final List<String> labels = new ArrayList<>();
        for (String n : names) {
            labels.add((n.equals(cur) ? "✓ " : "    ") + n + "  (" + Workspaces.count(n) + ")");
        }
        ListView lv = new ListView(this);
        lv.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.switch_workspace)
                .setView(lv)
                .setPositiveButton(R.string.new_workspace, (d, w) -> promptName(null))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        lv.setOnItemClickListener((AdapterView<?> p, View v, int pos, long id) -> {
            switchTo(names.get(pos));
            dlg.dismiss();
        });
        lv.setOnItemLongClickListener((p, v, pos, id) -> {
            dlg.dismiss();
            manageWorkspace(names.get(pos));
            return true;
        });
        dlg.show();
        Toast.makeText(this, R.string.hint_long_press_workspace, Toast.LENGTH_SHORT).show();
    }

    private void switchTo(String name) {
        Workspaces.create(name);
        Prefs.get(this).setWorkspace(name);
        thumbs.clear();
        reload();
        ShotService.start(this, ShotService.ACTION_REFRESH);
    }

    private void manageWorkspace(String name) {
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(new CharSequence[]{getString(R.string.rename), getString(R.string.delete_workspace)}, (d, which) -> {
                    if (which == 0) promptName(name);
                    else confirmDeleteWorkspace(name);
                })
                .show();
    }

    /** renameFrom == null: create a new workspace. */
    private void promptName(String renameFrom) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setSingleLine();
        if (renameFrom != null) {
            et.setText(renameFrom);
            et.selectAll();
        } else {
            et.setText(DateFormat.format("yyyyMMdd", new Date()));
            et.selectAll();
        }
        FrameLayout box = new FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);
        new AlertDialog.Builder(this)
                .setTitle(renameFrom == null ? R.string.new_workspace : R.string.rename)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String n = Workspaces.sanitize(et.getText().toString());
                    if (n == null) return;
                    if (renameFrom == null) {
                        if (!Workspaces.create(n)) {
                            Toast.makeText(this, R.string.err_create_workspace, Toast.LENGTH_LONG).show();
                            return;
                        }
                        switchTo(n);
                    } else if (!n.equals(renameFrom)) {
                        if (!Workspaces.rename(this, renameFrom, n)) {
                            Toast.makeText(this, R.string.err_rename_workspace, Toast.LENGTH_LONG).show();
                            return;
                        }
                        reload();
                        ShotService.start(this, ShotService.ACTION_REFRESH);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDeleteWorkspace(String name) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_workspace)
                .setMessage(getString(R.string.delete_workspace_message, name, Workspaces.count(name)))
                .setPositiveButton(R.string.delete, (d, w) -> bg.execute(() -> {
                    Workspaces.delete(this, name);
                    main.post(() -> {
                        thumbs.clear();
                        reload();
                        ShotService.start(this, ShotService.ACTION_REFRESH);
                    });
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ QR / LAN server

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) { main.postDelayed(MainActivity.this::updateQr, 800); }

                @Override
                public void onLost(Network network) { main.postDelayed(MainActivity.this::updateQr, 800); }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder().build(), netCallback);
        } catch (Exception e) {
            netCallback = null;
        }
    }

    /** Grid and QR panel side by side in landscape; QR panel in the lower half in portrait. */
    private void applySplitOrientation() {
        boolean portrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        b.split.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        View gridColumn = b.split.getChildAt(0);
        int divider = Math.max(1, (int) getResources().getDisplayMetrics().density);
        if (portrait) {
            gridColumn.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            b.qrPanel.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            b.divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, divider));
        } else {
            gridColumn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            b.qrPanel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            b.divider.setLayoutParams(new LinearLayout.LayoutParams(divider, LinearLayout.LayoutParams.MATCH_PARENT));
        }
    }

    private List<NetAddresses.Entry> qrAddrs = new ArrayList<>();
    private List<String> qrLabels = new ArrayList<>();
    private String selectedIp;

    private void setupQrPanel() {
        b.iface.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < qrAddrs.size()) {
                    selectedIp = qrAddrs.get(position).ip;
                    updateQr();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        b.btnCopy.setOnClickListener(v -> {
            if (qrUrl == null) return;
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("url", qrUrl));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        });
        b.btnOpen.setOnClickListener(v -> {
            if (qrUrl == null) return;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(qrUrl)));
            } catch (Exception ignored) {}
        });
    }

    private void updateQr() {
        if (b == null) return;
        boolean enabled = Prefs.get(this).serverEnabled();
        b.qrPanel.setVisibility(enabled ? View.VISIBLE : View.GONE);
        b.divider.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (!enabled) {
            qrUrl = null;
            return;
        }
        b.qrTitle.setText(getString(R.string.qr_title_ws, Prefs.get(this).workspace()));
        LanServer s = ShotService.server();
        if (s == null) {
            String err = ShotService.serverError();
            showQrMessage(err != null ? getString(R.string.err_server, err) : getString(R.string.server_not_running));
            return;
        }
        List<NetAddresses.Entry> addrs = NetAddresses.list();
        if (addrs.isEmpty()) {
            showQrMessage(getString(R.string.err_no_network));
            return;
        }
        int sel = 0;
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < addrs.size(); i++) {
            NetAddresses.Entry e = addrs.get(i);
            labels.add(kindLabel(e.kind) + "  " + e.ip + "  (" + e.iface + ")");
            if (e.ip.equals(selectedIp)) sel = i;
        }
        qrAddrs = addrs;
        if (!labels.equals(qrLabels)) {
            qrLabels = labels;
            ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            b.iface.setAdapter(ad);
        }
        if (b.iface.getSelectedItemPosition() != sel) b.iface.setSelection(sel);
        b.iface.setVisibility(addrs.size() > 1 ? View.VISIBLE : View.GONE);

        String url = NetAddresses.url(addrs.get(sel).ip, s.getPort(), Prefs.get(this).serverToken());
        b.qr.setVisibility(View.VISIBLE);
        b.qrMessage.setVisibility(View.GONE);
        b.url.setVisibility(View.VISIBLE);
        b.qrHint.setVisibility(View.VISIBLE);
        b.qrButtons.setVisibility(View.VISIBLE);
        if (url.equals(qrUrl)) return;
        qrUrl = url;
        b.url.setText(url);
        // Render big and disable filtering so the scaled modules stay crisp.
        BitmapDrawable d = new BitmapDrawable(getResources(), QrBitmap.render(url, 720));
        d.setFilterBitmap(false);
        b.qr.setImageDrawable(d);
    }

    private void showQrMessage(String msg) {
        qrUrl = null;
        b.qr.setVisibility(View.GONE);
        b.iface.setVisibility(View.GONE);
        b.url.setVisibility(View.GONE);
        b.qrHint.setVisibility(View.GONE);
        b.qrButtons.setVisibility(View.GONE);
        b.qrMessage.setVisibility(View.VISIBLE);
        b.qrMessage.setText(msg);
    }

    private String kindLabel(String kind) {
        switch (kind) {
            case NetAddresses.KIND_WIFI: return getString(R.string.net_wifi);
            case NetAddresses.KIND_ETHERNET: return getString(R.string.net_ethernet);
            case NetAddresses.KIND_HOTSPOT: return getString(R.string.net_hotspot);
            case NetAddresses.KIND_USB: return getString(R.string.net_usb);
            default: return getString(R.string.net_other);
        }
    }
}
