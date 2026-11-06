package com.rc.testershot.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.rc.testershot.Prefs;
import com.rc.testershot.R;
import com.rc.testershot.Workspaces;
import com.rc.testershot.databinding.ActivityPreviewBinding;
import com.rc.testershot.ui.editor.Op;
import com.rc.testershot.ui.editor.Renderer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Full-size preview of one screenshot with share / delete, plus an annotation editor
 * (pen, line, arrow, rectangle, oval, text, mosaic, crop) with undo/redo.
 *
 * <p>Result: {@link #RESULT_OK} if the file was changed, copied or deleted; the result intent
 * carries {@link #EXTRA_PATH} (the file now shown) and {@link #EXTRA_DELETED}.
 */
public class PreviewActivity extends Activity implements EditorView.Listener {
    public static final String EXTRA_PATH = "path";
    /** Result extra: true if the image was deleted. */
    public static final String EXTRA_DELETED = "deleted";

    /** Largest decoded dimension (also a safe GPU texture size). */
    private static final int MAX_DIM = 4096;

    private static final int[] PALETTE = {
            0xFFF44336, 0xFFFFEB3B, 0xFF4CAF50, 0xFF2196F3, 0xFFFFFFFF, 0xFF000000};
    private static final int[] PALETTE_NAMES = {
            R.string.ed_color_red, R.string.ed_color_yellow, R.string.ed_color_green,
            R.string.ed_color_blue, R.string.ed_color_white, R.string.ed_color_black};

    public static Intent intent(Context c, File f) {
        return new Intent(c, PreviewActivity.class).putExtra(EXTRA_PATH, f.getAbsolutePath());
    }

    private ActivityPreviewBinding b;
    private final Handler main = new Handler(Looper.getMainLooper());
    private File file;
    private boolean editing;
    private boolean busy;        // loading or saving on a background thread
    private boolean destroyed;
    private AlertDialog progressDialog;
    private ImageButton[] toolButtons;
    private int[] toolIds;
    private View[] swatches;
    private ImageButton[] widthButtons;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityPreviewBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null) {
            finish();
            return;
        }
        file = new File(path);
        b.txtName.setText(file.getName());
        b.editor.setListener(this);

        b.btnBack.setOnClickListener(v -> finish());
        b.btnShare.setOnClickListener(v -> share());
        b.btnDelete.setOnClickListener(v -> confirmDelete());
        b.btnEdit.setOnClickListener(v -> {
            if (b.editor.hasImage() && !busy) setEditing(true);
        });
        b.btnCancel.setOnClickListener(v -> cancelEdit());
        b.btnUndo.setOnClickListener(v -> b.editor.undo());
        b.btnRedo.setOnClickListener(v -> b.editor.redo());
        b.btnSave.setOnClickListener(v -> chooseSave());
        b.btnCropApply.setOnClickListener(v -> {
            if (!b.editor.applyCrop()) toast(getString(R.string.ed_crop_nothing));
        });
        b.btnCropReset.setOnClickListener(v -> b.editor.resetCrop());

        toolButtons = new ImageButton[]{b.toolPen, b.toolLine, b.toolArrow, b.toolRect, b.toolOval,
                b.toolText, b.toolMosaic, b.toolCrop};
        toolIds = new int[]{EditorView.TOOL_PEN, EditorView.TOOL_LINE, EditorView.TOOL_ARROW,
                EditorView.TOOL_RECT, EditorView.TOOL_OVAL, EditorView.TOOL_TEXT,
                EditorView.TOOL_MOSAIC, EditorView.TOOL_CROP};
        for (int i = 0; i < toolButtons.length; i++) {
            final int t = toolIds[i];
            toolButtons[i].setOnClickListener(v -> selectTool(t));
        }
        widthButtons = new ImageButton[]{b.btnWidthThin, b.btnWidthMedium, b.btnWidthThick};
        for (int i = 0; i < widthButtons.length; i++) {
            final int level = i;
            widthButtons[i].setOnClickListener(v -> selectWidth(level));
        }
        buildPalette();
        selectColor(0);
        selectWidth(EditorView.WIDTH_MEDIUM);
        updateToolButtons();
        updateHistoryButtons();

        load(file);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (progressDialog != null) progressDialog.dismiss();
        // while a background thread still reads the bitmaps, let the GC reclaim them instead
        if (!busy && b != null) b.editor.release();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (busy && editing) return;
        if (editing) cancelEdit();
        else super.onBackPressed();
    }

    // ------------------------------------------------------------------ loading

    private void load(final File f) {
        busy = true;
        b.progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            final int[] sample = new int[1];
            final Bitmap bmp = decode(f, sample);
            main.post(() -> {
                busy = false;
                if (destroyed) {
                    if (bmp != null) bmp.recycle();
                    return;
                }
                b.progress.setVisibility(View.GONE);
                if (bmp == null) {
                    toast(getString(R.string.ed_load_failed));
                    finish();
                    return;
                }
                b.editor.setImage(bmp);
                if (sample[0] > 1) toast(getString(R.string.ed_downscaled));
            });
        }, "preview-decode").start();
    }

    /** Decodes at full size unless that exceeds {@link #MAX_DIM} or the free heap. */
    private static Bitmap decode(File f, int[] sampleOut) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        Runtime rt = Runtime.getRuntime();
        long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        int sample = 1;
        // the editor may need ~3 copies at once (original, cache, render for save)
        while (o.outWidth / sample > MAX_DIM || o.outHeight / sample > MAX_DIM
                || 3L * (o.outWidth / sample) * (o.outHeight / sample) * 4 > free * 0.8) {
            sample *= 2;
            if (sample >= 64) break;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try {
                Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), d);
                if (bmp == null) return null;
                if (bmp.getConfig() != Bitmap.Config.ARGB_8888) {
                    Bitmap c = bmp.copy(Bitmap.Config.ARGB_8888, false);
                    if (c != null) {
                        bmp.recycle();
                        bmp = c;
                    }
                }
                sampleOut[0] = sample;
                return bmp;
            } catch (OutOfMemoryError e) {
                sample *= 2;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ mode switching

    private void setEditing(boolean on) {
        editing = on;
        b.viewBar.setVisibility(on ? View.GONE : View.VISIBLE);
        b.editBar.setVisibility(on ? View.VISIBLE : View.GONE);
        b.editPanel.setVisibility(on ? View.VISIBLE : View.GONE);
        b.editor.setEditing(on);
        if (on) {
            int t = b.editor.getTool();
            if (t == EditorView.TOOL_TEXT) t = EditorView.TOOL_PEN;   // don't pop up a dialog on entry
            selectTool(t);
        } else {
            b.txtHint.setVisibility(View.GONE);
        }
        updateHistoryButtons();
    }

    private void cancelEdit() {
        if (!b.editor.isDirty()) {
            b.editor.discardEdits();
            setEditing(false);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.ed_discard_title)
                .setMessage(R.string.ed_discard_message)
                .setPositiveButton(R.string.ed_discard, (d, w) -> {
                    b.editor.discardEdits();
                    setEditing(false);
                })
                .setNegativeButton(R.string.ed_keep_editing, null)
                .show();
    }

    // ------------------------------------------------------------------ tools

    private void selectTool(int t) {
        b.editor.setTool(t);
        updateToolButtons();
        boolean crop = t == EditorView.TOOL_CROP;
        b.cropBar.setVisibility(crop ? View.VISIBLE : View.GONE);
        b.styleBar.setVisibility(crop ? View.GONE : View.VISIBLE);
        int hint = 0;
        if (t == EditorView.TOOL_TEXT) hint = R.string.ed_hint_text_tap;
        else if (t == EditorView.TOOL_MOSAIC) hint = R.string.ed_hint_mosaic;
        else if (t == EditorView.TOOL_CROP) hint = R.string.ed_hint_crop;
        showHint(hint);
        if (t == EditorView.TOOL_TEXT) promptText(false, 0, 0);
    }

    private void updateToolButtons() {
        int t = b.editor.getTool();
        for (int i = 0; i < toolButtons.length; i++) toolButtons[i].setSelected(toolIds[i] == t);
    }

    private void showHint(int res) {
        if (res == 0) {
            b.txtHint.setVisibility(View.GONE);
        } else {
            b.txtHint.setText(res);
            b.txtHint.setVisibility(View.VISIBLE);
        }
    }

    private void selectWidth(int level) {
        b.editor.setWidthLevel(level);
        for (int i = 0; i < widthButtons.length; i++) widthButtons[i].setSelected(i == level);
    }

    private void buildPalette() {
        int size = getResources().getDimensionPixelSize(R.dimen.ed_swatch_size);
        int margin = dp(6);
        swatches = new View[PALETTE.length];
        for (int i = 0; i < PALETTE.length; i++) {
            final int idx = i;
            FrameLayout cell = new FrameLayout(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size + 2 * margin, ViewGroup.LayoutParams.MATCH_PARENT);
            cell.setLayoutParams(lp);
            cell.setContentDescription(getString(PALETTE_NAMES[i]));
            cell.setClickable(true);
            cell.setOnClickListener(v -> selectColor(idx));
            View dot = new View(this);
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(size, size);
            dlp.gravity = android.view.Gravity.CENTER;
            dot.setLayoutParams(dlp);
            cell.addView(dot);
            b.colorRow.addView(cell);
            swatches[i] = dot;
        }
    }

    private void selectColor(int idx) {
        b.editor.setColor(PALETTE[idx]);
        for (int i = 0; i < swatches.length; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(PALETTE[i]);
            if (i == idx) g.setStroke(dp(3), 0xFFFFFFFF);
            else g.setStroke(dp(1), 0x66FFFFFF);
            swatches[i].setBackground(g);
            swatches[i].setScaleX(i == idx ? 1.15f : 1f);
            swatches[i].setScaleY(i == idx ? 1.15f : 1f);
        }
    }

    /** Asks for text; places it at (x, y) if {@code hasPos}, otherwise at the next tap. */
    private void promptText(final boolean hasPos, final float x, final float y) {
        final EditText et = new EditText(this);
        et.setHint(R.string.ed_text_hint);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setMaxLines(5);
        FrameLayout wrap = new FrameLayout(this);
        int pad = dp(20);
        wrap.setPadding(pad, dp(8), pad, 0);
        wrap.addView(et, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.ed_text_title)
                .setView(wrap)
                .setPositiveButton(R.string.ed_ok, (d, w) -> {
                    String text = et.getText().toString();
                    if (text.trim().isEmpty()) return;
                    if (hasPos) {
                        b.editor.addText(text, x, y);
                        showHint(R.string.ed_hint_text_tap);
                    } else {
                        b.editor.setPendingText(text);
                        showHint(R.string.ed_hint_text);
                    }
                })
                .setNegativeButton(R.string.ed_dialog_cancel, null)
                .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dlg.show();
        et.requestFocus();
    }

    // ------------------------------------------------------------------ EditorView.Listener

    @Override
    public void onHistoryChanged() {
        updateHistoryButtons();
        if (editing && b.editor.getTool() == EditorView.TOOL_TEXT && !b.editor.hasPendingText()
                && b.txtHint.getVisibility() == View.VISIBLE) {
            b.txtHint.setText(R.string.ed_hint_text_tap);
        }
    }

    @Override
    public void onTextTap(float imageX, float imageY) {
        promptText(true, imageX, imageY);
    }

    @Override
    public void onOutOfMemory() {
        toast(getString(R.string.ed_out_of_memory));
    }

    private void updateHistoryButtons() {
        setEnabled(b.btnUndo, b.editor.canUndo());
        setEnabled(b.btnRedo, b.editor.canRedo());
    }

    private static void setEnabled(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.35f);
    }

    // ------------------------------------------------------------------ saving

    private static String ext(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean writable(String ext) {
        return ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png") || ext.equals("webp");
    }

    private void chooseSave() {
        if (busy) return;
        if (!b.editor.isDirty()) {
            toast(getString(R.string.ed_no_changes));
            setEditing(false);
            return;
        }
        if (!writable(ext(file))) {
            // gif/bmp cannot be written back: only offer a PNG copy
            new AlertDialog.Builder(this)
                    .setTitle(R.string.ed_save_title)
                    .setItems(new CharSequence[]{getString(R.string.ed_save_copy_png)}, (d, w) -> save(false))
                    .setNegativeButton(R.string.ed_dialog_cancel, null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.ed_save_title)
                .setItems(new CharSequence[]{getString(R.string.ed_save_overwrite), getString(R.string.ed_save_copy)},
                        (d, w) -> save(w == 0))
                .setNegativeButton(R.string.ed_dialog_cancel, null)
                .show();
    }

    @SuppressWarnings("deprecation")
    private void save(final boolean overwrite) {
        final File src = file;
        final String ext = ext(src);
        final boolean ok = writable(ext);
        final String outExt = ok ? ext : "png";
        final File target = overwrite && ok ? src
                : Workspaces.uniqueFile(src.getParentFile(), Workspaces.baseName(src.getName()) + "_edit", outExt);
        final Bitmap.CompressFormat fmt;
        final int quality;
        if (outExt.equals("png")) {
            fmt = Bitmap.CompressFormat.PNG;
            quality = 100;
        } else if (outExt.equals("webp")) {
            fmt = Bitmap.CompressFormat.WEBP;
            quality = Prefs.get(this).quality();
        } else {
            fmt = Bitmap.CompressFormat.JPEG;
            quality = Prefs.get(this).quality();
        }
        final Bitmap start = b.editor.getFlat();
        final int from = b.editor.getFlatEnd();
        final List<Op> ops = b.editor.snapshotOps();

        busy = true;
        showProgress();
        new Thread(() -> {
            Bitmap out = null;
            Throwable error = null;
            try {
                out = Renderer.render(start, ops, from, ops.size());
                write(out, target, fmt, quality);
            } catch (Throwable t) {
                error = t;
            }
            final Bitmap result = out;
            final Throwable err = error;
            main.post(() -> onSaved(target, result, err));
        }, "preview-save").start();
    }

    private void onSaved(File target, Bitmap result, Throwable err) {
        busy = false;
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
        if (destroyed) {
            if (result != null) result.recycle();
            if (b != null) b.editor.release();
            return;
        }
        if (err != null) {
            if (result != null) result.recycle();
            String msg = err instanceof OutOfMemoryError ? getString(R.string.ed_out_of_memory)
                    : String.valueOf(err.getMessage());
            toast(getString(R.string.ed_save_failed, msg));
            return;
        }
        Workspaces.scan(this, target);
        file = target;
        b.txtName.setText(target.getName());
        b.editor.setImage(result);     // replaces original + cache, clears history
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_PATH, target.getAbsolutePath()));
        setEditing(false);
        toast(getString(R.string.ed_saved, target.getName()));
    }

    /** Writes via a temp file so a failed save never destroys the original. */
    private static void write(Bitmap bmp, File target, Bitmap.CompressFormat fmt, int quality) throws IOException {
        File dir = target.getParentFile();
        File tmp = new File(dir, "." + target.getName() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        boolean done = false;
        try {
            if (!bmp.compress(fmt, quality, fos)) throw new IOException("compress failed");
            fos.flush();
            try {
                fos.getFD().sync();
            } catch (IOException ignored) {
                // best effort
            }
            done = true;
        } finally {
            fos.close();
            if (!done) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (!tmp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new IOException("cannot write " + target.getName());
            }
        }
    }

    private void showProgress() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = dp(24);
        row.setPadding(pad, pad, pad, pad);
        ProgressBar pb = new ProgressBar(this);
        row.addView(pb, new LinearLayout.LayoutParams(dp(40), dp(40)));
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(R.string.ed_saving);
        tv.setTextSize(16);
        tv.setPadding(dp(20), 0, 0, 0);
        row.addView(tv);
        progressDialog = new AlertDialog.Builder(this).setView(row).setCancelable(false).create();
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.show();
    }

    // ------------------------------------------------------------------ share / delete

    private static String mimeOf(File f) {
        switch (ext(f)) {
            case "png": return "image/png";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "bmp": return "image/bmp";
            default: return "image/jpeg";
        }
    }

    private void share() {
        if (busy) return;
        final File f = file;
        MediaScannerConnection.scanFile(getApplicationContext(), new String[]{f.getAbsolutePath()},
                new String[]{mimeOf(f)}, (path, uri) -> main.post(() -> {
                    if (destroyed) return;
                    if (uri == null) {
                        toast(getString(R.string.ed_share_failed));
                        return;
                    }
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("image/*");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.setClipData(ClipData.newRawUri(f.getName(), uri));
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(Intent.createChooser(send, getString(R.string.ed_share_title)));
                    } catch (ActivityNotFoundException e) {
                        toast(getString(R.string.ed_share_failed));
                    }
                }));
    }

    private void confirmDelete() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.ed_delete_title)
                .setMessage(getString(R.string.ed_delete_message, file.getName()))
                .setPositiveButton(R.string.ed_delete, (d, w) -> {
                    if (Workspaces.deleteImage(this, file) || !file.exists()) {
                        setResult(RESULT_OK, new Intent()
                                .putExtra(EXTRA_PATH, file.getAbsolutePath())
                                .putExtra(EXTRA_DELETED, true));
                        finish();
                    } else {
                        toast(getString(R.string.ed_delete_failed));
                    }
                })
                .setNegativeButton(R.string.ed_dialog_cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ util

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
