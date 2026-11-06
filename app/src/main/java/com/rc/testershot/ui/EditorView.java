package com.rc.testershot.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.rc.testershot.ui.editor.CropOp;
import com.rc.testershot.ui.editor.MosaicOp;
import com.rc.testershot.ui.editor.Op;
import com.rc.testershot.ui.editor.Renderer;
import com.rc.testershot.ui.editor.StrokeOp;
import com.rc.testershot.ui.editor.TextOp;

import java.util.ArrayList;
import java.util.List;

/**
 * Displays an image fit-center (pinch-zoom / pan / double-tap zoom) and, in edit mode, lets the
 * user annotate it. Edits are kept as a list of {@link Op}s in image pixel coordinates so they
 * survive relayout (rotation) and can be re-rendered at full resolution when saving.
 *
 * <p>Raster ops (crop, mosaic) are baked into a cached bitmap ({@link #getFlat()}); vector ops after
 * the last raster op are drawn live on top of it.
 */
public class EditorView extends View {
    public static final int TOOL_PEN = 1;
    public static final int TOOL_LINE = 2;
    public static final int TOOL_ARROW = 3;
    public static final int TOOL_RECT = 4;
    public static final int TOOL_OVAL = 5;
    public static final int TOOL_TEXT = 6;
    public static final int TOOL_MOSAIC = 7;
    public static final int TOOL_CROP = 8;

    public static final int WIDTH_THIN = 0;
    public static final int WIDTH_MEDIUM = 1;
    public static final int WIDTH_THICK = 2;

    private static final float[] WIDTH_DP = {2.5f, 5f, 9f};
    private static final float[] TEXT_SP = {16f, 24f, 36f};
    private static final float MAX_ZOOM = 8f;

    public interface Listener {
        /** Ops were added/removed (undo/redo availability may have changed). */
        void onHistoryChanged();

        /** The text tool was tapped at an image position while no text was pending. */
        void onTextTap(float imageX, float imageY);

        /** A bitmap could not be allocated while applying a crop/mosaic step (it was dropped). */
        void onOutOfMemory();
    }

    private Listener listener;

    private Bitmap base;          // decoded image; never modified
    private Bitmap flat;          // base with ops[0, flatEnd) applied (== base when flatEnd == 0)
    private int flatEnd;
    private final ArrayList<Op> ops = new ArrayList<>();
    private final ArrayList<Op> redo = new ArrayList<>();

    private final Matrix matrix = new Matrix();   // image -> view
    private final Matrix inverse = new Matrix();  // view -> image
    private final RectF tmpRect = new RectF();
    private float fitScale = 1f;

    private boolean editing;
    private int tool = TOOL_PEN;
    private int color = 0xFFF44336;
    private int widthLevel = WIDTH_MEDIUM;
    private String pendingText;

    // gesture state
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private boolean multiTouch;
    private float lastX, lastY;

    // in-progress shape (image coordinates)
    private boolean drawing;
    private Path curPath;
    private float sx, sy, ex, ey, lastPx, lastPy;
    private Paint previewStroke, previewFill;
    private float previewWidth;

    // crop (image coordinates of the current flat bitmap)
    private final RectF crop = new RectF();
    private static final int EDGE_L = 1, EDGE_T = 2, EDGE_R = 4, EDGE_B = 8, EDGE_MOVE = 16;
    private int cropDrag;

    private final float density;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mosaicPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mosaicFill = new Paint();

    public EditorView(Context context) {
        this(context, null);
    }

    public EditorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        dimPaint.setColor(0x99000000);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(0xFFFFFFFF);
        borderPaint.setStrokeWidth(1.5f * density);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setColor(0x80FFFFFF);
        gridPaint.setStrokeWidth(Math.max(1f, 0.75f * density));
        handlePaint.setStyle(Paint.Style.STROKE);
        handlePaint.setColor(0xFFFFFFFF);
        handlePaint.setStrokeWidth(3.5f * density);
        handlePaint.setStrokeCap(Paint.Cap.SQUARE);
        mosaicPaint.setStyle(Paint.Style.STROKE);
        mosaicPaint.setColor(0xFFFFFFFF);
        mosaicPaint.setStrokeWidth(1.5f * density);
        mosaicPaint.setPathEffect(new DashPathEffect(new float[]{6 * density, 4 * density}, 0));
        mosaicFill.setColor(0x40FFFFFF);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (editing || flat == null) return false;
                float cur = getScale();
                if (cur > fitScale * 1.05f) fit();
                else zoomBy(2.5f, e.getX(), e.getY());
                invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (editing && tool == TOOL_TEXT && !multiTouch && flat != null) {
                    float[] p = toImage(e.getX(), e.getY());
                    if (p[0] < 0 || p[1] < 0 || p[0] > flat.getWidth() || p[1] > flat.getHeight()) return true;
                    if (pendingText != null) {
                        String t = pendingText;
                        pendingText = null;
                        addText(t, p[0], p[1]);
                    } else if (listener != null) {
                        listener.onTextTap(p[0], p[1]);
                    }
                    return true;
                }
                return false;
            }
        });
        gestureDetector.setIsLongpressEnabled(false);
    }

    // ------------------------------------------------------------------ public API

    public void setListener(Listener l) {
        listener = l;
    }

    /** Takes ownership of {@code bmp}; recycles the previous image and clears all edits. */
    public void setImage(Bitmap bmp) {
        Bitmap oldBase = base, oldFlat = flat;
        base = bmp;
        flat = bmp;
        flatEnd = 0;
        ops.clear();
        redo.clear();
        drawing = false;
        pendingText = null;
        resetCrop();
        fit();
        invalidate();
        if (oldFlat != null && oldFlat != oldBase && oldFlat != bmp) oldFlat.recycle();
        if (oldBase != null && oldBase != bmp) oldBase.recycle();
        notifyHistory();
    }

    public boolean hasImage() {
        return flat != null && !flat.isRecycled();
    }

    /** Recycles all bitmaps; the view shows nothing afterwards. */
    public void release() {
        Bitmap b = base, f = flat;
        base = null;
        flat = null;
        ops.clear();
        redo.clear();
        if (f != null && f != b) f.recycle();
        if (b != null) b.recycle();
        invalidate();
    }

    /** Bitmap with the first {@link #getFlatEnd()} ops applied. Do not modify or recycle. */
    public Bitmap getFlat() {
        return flat;
    }

    public int getFlatEnd() {
        return flatEnd;
    }

    /** Copy of the op list (ops themselves are immutable). */
    public List<Op> snapshotOps() {
        return new ArrayList<>(ops);
    }

    public void setEditing(boolean on) {
        editing = on;
        drawing = false;
        pendingText = null;
        if (!on) resetCrop();
        fit();
        invalidate();
    }

    public boolean isEditing() {
        return editing;
    }

    public void setTool(int t) {
        tool = t;
        drawing = false;
        if (t != TOOL_TEXT) pendingText = null;
        if (t == TOOL_CROP) resetCrop();
        invalidate();
    }

    public int getTool() {
        return tool;
    }

    public void setColor(int c) {
        color = c;
    }

    public int getColor() {
        return color;
    }

    public void setWidthLevel(int level) {
        widthLevel = Math.max(0, Math.min(WIDTH_DP.length - 1, level));
    }

    public int getWidthLevel() {
        return widthLevel;
    }

    /** Text to place at the next tap with the text tool. */
    public void setPendingText(String text) {
        pendingText = text;
    }

    public boolean hasPendingText() {
        return pendingText != null;
    }

    public void addText(String text, float imageX, float imageY) {
        if (text == null || text.trim().isEmpty() || flat == null) return;
        float sizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP[widthLevel],
                getResources().getDisplayMetrics()) / fitScale;
        addOp(new TextOp(text, imageX, imageY, sizePx, color));
    }

    public boolean canUndo() {
        return !ops.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public boolean isDirty() {
        return !ops.isEmpty();
    }

    public void undo() {
        if (ops.isEmpty()) return;
        Op op = ops.remove(ops.size() - 1);
        redo.add(op);
        if (op.isRaster()) rebuildFlat();
        invalidate();
        notifyHistory();
    }

    public void redo() {
        if (redo.isEmpty()) return;
        Op op = redo.remove(redo.size() - 1);
        ops.add(op);
        if (op.isRaster() && !rebuildFlat()) {
            ops.remove(ops.size() - 1);
            redo.add(op);
        }
        invalidate();
        notifyHistory();
    }

    /** Drops all edits (keeps the image). */
    public void discardEdits() {
        ops.clear();
        redo.clear();
        drawing = false;
        pendingText = null;
        rebuildFlat();
        invalidate();
        notifyHistory();
    }

    public void resetCrop() {
        if (flat != null) crop.set(0, 0, flat.getWidth(), flat.getHeight());
        else crop.setEmpty();
        invalidate();
    }

    /** Applies the current crop rectangle as an undoable step. Returns false if nothing changed. */
    public boolean applyCrop() {
        if (flat == null) return false;
        Rect r = new Rect(Math.round(crop.left), Math.round(crop.top), Math.round(crop.right), Math.round(crop.bottom));
        if (!r.intersect(0, 0, flat.getWidth(), flat.getHeight())) return false;
        if (r.width() < 2 || r.height() < 2) return false;
        if (r.width() == flat.getWidth() && r.height() == flat.getHeight()) return false;
        addOp(new CropOp(r));
        resetCrop();
        return true;
    }

    // ------------------------------------------------------------------ ops / cache

    private void addOp(Op op) {
        ops.add(op);
        redo.clear();
        if (op.isRaster() && !rebuildFlat()) ops.remove(ops.size() - 1);
        invalidate();
        notifyHistory();
    }

    /** Makes {@link #flat} match ops up to the last raster op. Returns false on OOM (state unchanged). */
    private boolean rebuildFlat() {
        if (base == null) return false;
        int end = 0;
        for (int i = ops.size() - 1; i >= 0; i--) {
            if (ops.get(i).isRaster()) {
                end = i + 1;
                break;
            }
        }
        if (end == flatEnd && flat != null) return true;
        Bitmap old = flat;
        int oldW = old == null ? 0 : old.getWidth(), oldH = old == null ? 0 : old.getHeight();
        Bitmap next;
        try {
            if (end == 0) next = base;
            else if (end > flatEnd && old != null) next = Renderer.render(old, ops, flatEnd, end);
            else next = Renderer.render(base, ops, 0, end);
        } catch (OutOfMemoryError e) {
            if (listener != null) listener.onOutOfMemory();
            return false;
        }
        flat = next;
        flatEnd = end;
        if (old != null && old != base && old != next) old.recycle();
        if (next.getWidth() != oldW || next.getHeight() != oldH) {
            resetCrop();
            fit();
        }
        return true;
    }

    private void notifyHistory() {
        if (listener != null) listener.onHistoryChanged();
    }

    // ------------------------------------------------------------------ geometry

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        drawing = false;
        fit();
    }

    private float margin() {
        return editing ? 20 * density : 0;
    }

    /** Resets zoom so the whole image fits (with a margin for crop handles in edit mode). */
    public void fit() {
        int vw = getWidth(), vh = getHeight();
        if (flat == null || vw == 0 || vh == 0) return;
        float m = margin();
        float aw = Math.max(1, vw - 2 * m), ah = Math.max(1, vh - 2 * m);
        int bw = flat.getWidth(), bh = flat.getHeight();
        float s = Math.min(aw / bw, ah / bh);
        fitScale = s;
        matrix.setScale(s, s);
        matrix.postTranslate((vw - bw * s) / 2f, (vh - bh * s) / 2f);
        matrix.invert(inverse);
        invalidate();
    }

    private float getScale() {
        float[] v = new float[9];
        matrix.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    private void zoomBy(float f, float fx, float fy) {
        if (flat == null) return;
        float cur = getScale();
        float target = Math.max(fitScale, Math.min(fitScale * MAX_ZOOM, cur * f));
        float k = target / cur;
        matrix.postScale(k, k, fx, fy);
        clampTranslation();
        invalidate();
    }

    private void panBy(float dx, float dy) {
        matrix.postTranslate(dx, dy);
        clampTranslation();
        invalidate();
    }

    private void clampTranslation() {
        if (flat == null) return;
        tmpRect.set(0, 0, flat.getWidth(), flat.getHeight());
        matrix.mapRect(tmpRect);
        float m = margin();
        float vw = getWidth(), vh = getHeight();
        float dx = 0, dy = 0;
        if (tmpRect.width() <= vw - 2 * m) dx = (vw - tmpRect.width()) / 2f - tmpRect.left;
        else if (tmpRect.left > m) dx = m - tmpRect.left;
        else if (tmpRect.right < vw - m) dx = vw - m - tmpRect.right;
        if (tmpRect.height() <= vh - 2 * m) dy = (vh - tmpRect.height()) / 2f - tmpRect.top;
        else if (tmpRect.top > m) dy = m - tmpRect.top;
        else if (tmpRect.bottom < vh - m) dy = vh - m - tmpRect.bottom;
        matrix.postTranslate(dx, dy);
        matrix.invert(inverse);
    }

    private float[] toImage(float vx, float vy) {
        float[] p = new float[]{vx, vy};
        inverse.mapPoints(p);
        return p;
    }

    private float strokeWidthPx() {
        return WIDTH_DP[widthLevel] * density / fitScale;
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (flat == null) return false;
        scaleDetector.onTouchEvent(e);
        gestureDetector.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                multiTouch = false;
                lastX = e.getX();
                lastY = e.getY();
                if (editing) begin(e.getX(), e.getY());
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                multiTouch = true;
                drawing = false;   // a second finger turns the gesture into zoom/pan
                cropDrag = 0;
                focal(e, -1);
                break;
            case MotionEvent.ACTION_MOVE:
                if (multiTouch || !editing || tool == TOOL_TEXT) {
                    float ox = lastX, oy = lastY;
                    focal(e, -1);
                    panBy(lastX - ox, lastY - oy);
                } else {
                    move(e.getX(), e.getY());
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                focal(e, e.getActionIndex());
                break;
            case MotionEvent.ACTION_UP:
                if (editing && !multiTouch) end(e.getX(), e.getY());
                drawing = false;
                cropDrag = 0;
                multiTouch = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                drawing = false;
                cropDrag = 0;
                multiTouch = false;
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }

    /** Sets lastX/lastY to the average of all pointers except {@code skip}. */
    private void focal(MotionEvent e, int skip) {
        float x = 0, y = 0;
        int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            x += e.getX(i);
            y += e.getY(i);
            n++;
        }
        if (n > 0) {
            lastX = x / n;
            lastY = y / n;
        }
    }

    private void begin(float vx, float vy) {
        float[] p = toImage(vx, vy);
        sx = ex = lastPx = p[0];
        sy = ey = lastPy = p[1];
        switch (tool) {
            case TOOL_PEN:
                curPath = new Path();
                curPath.moveTo(p[0], p[1]);
                startPreview();
                drawing = true;
                break;
            case TOOL_LINE:
            case TOOL_ARROW:
            case TOOL_RECT:
            case TOOL_OVAL:
            case TOOL_MOSAIC:
                startPreview();
                drawing = true;
                break;
            case TOOL_CROP:
                cropDrag = hitCrop(vx, vy);
                lastX = vx;
                lastY = vy;
                break;
            default:
                break;
        }
    }

    private void startPreview() {
        previewWidth = strokeWidthPx();
        previewStroke = StrokeOp.makeStroke(color, previewWidth);
        previewFill = StrokeOp.makeFill(color);
    }

    private void move(float vx, float vy) {
        if (tool == TOOL_CROP) {
            if (cropDrag != 0) dragCrop(vx, vy);
            return;
        }
        if (!drawing) return;
        float[] p = toImage(vx, vy);
        if (tool == TOOL_PEN) {
            float mx = (lastPx + p[0]) / 2f, my = (lastPy + p[1]) / 2f;
            curPath.quadTo(lastPx, lastPy, mx, my);
            lastPx = p[0];
            lastPy = p[1];
        }
        ex = p[0];
        ey = p[1];
    }

    private void end(float vx, float vy) {
        if (!drawing) return;
        move(vx, vy);
        drawing = false;
        float minLen = 6 * density / getScale();   // ignore accidental taps for shapes
        float w = previewWidth;
        switch (tool) {
            case TOOL_PEN:
                curPath.lineTo(ex, ey);
                if (Math.hypot(ex - sx, ey - sy) < 0.5f) curPath.lineTo(ex + 0.1f, ey);  // a dot
                addOp(StrokeOp.pen(curPath, color, w));
                curPath = null;
                break;
            case TOOL_LINE:
            case TOOL_ARROW:
            case TOOL_RECT:
            case TOOL_OVAL:
                if (Math.hypot(ex - sx, ey - sy) < minLen) return;
                addOp(StrokeOp.shape(shapeKind(tool), sx, sy, ex, ey, color, w));
                break;
            case TOOL_MOSAIC: {
                RectF r = new RectF(Math.min(sx, ex), Math.min(sy, ey), Math.max(sx, ex), Math.max(sy, ey));
                if (!r.intersect(0, 0, flat.getWidth(), flat.getHeight())) return;
                if (r.width() < 2 || r.height() < 2) return;
                addOp(new MosaicOp(r, MosaicOp.defaultBlock(flat.getWidth(), flat.getHeight())));
                break;
            }
            default:
                break;
        }
    }

    private static int shapeKind(int t) {
        switch (t) {
            case TOOL_LINE: return StrokeOp.LINE;
            case TOOL_ARROW: return StrokeOp.ARROW;
            case TOOL_RECT: return StrokeOp.RECT;
            case TOOL_OVAL: return StrokeOp.OVAL;
            default: return StrokeOp.PEN;
        }
    }

    private int hitCrop(float vx, float vy) {
        RectF v = new RectF(crop);
        matrix.mapRect(v);
        float t = 24 * density;
        int flags = 0;
        boolean inY = vy > v.top - t && vy < v.bottom + t;
        boolean inX = vx > v.left - t && vx < v.right + t;
        if (inY && Math.abs(vx - v.left) < t) flags |= EDGE_L;
        else if (inY && Math.abs(vx - v.right) < t) flags |= EDGE_R;
        if (inX && Math.abs(vy - v.top) < t) flags |= EDGE_T;
        else if (inX && Math.abs(vy - v.bottom) < t) flags |= EDGE_B;
        // for a tiny rectangle prefer the nearer edge
        if ((flags & EDGE_L) != 0 && Math.abs(vx - v.right) < Math.abs(vx - v.left)) flags = (flags & ~EDGE_L) | EDGE_R;
        if ((flags & EDGE_T) != 0 && Math.abs(vy - v.bottom) < Math.abs(vy - v.top)) flags = (flags & ~EDGE_T) | EDGE_B;
        if (flags == 0 && v.contains(vx, vy)) flags = EDGE_MOVE;
        return flags;
    }

    private void dragCrop(float vx, float vy) {
        float s = getScale();
        float dx = (vx - lastX) / s, dy = (vy - lastY) / s;
        lastX = vx;
        lastY = vy;
        float w = flat.getWidth(), h = flat.getHeight();
        float min = Math.min(Math.min(w, h), 48 * density / s);
        if (cropDrag == EDGE_MOVE) {
            dx = Math.max(-crop.left, Math.min(w - crop.right, dx));
            dy = Math.max(-crop.top, Math.min(h - crop.bottom, dy));
            crop.offset(dx, dy);
            return;
        }
        if ((cropDrag & EDGE_L) != 0) crop.left = Math.max(0, Math.min(crop.right - min, crop.left + dx));
        if ((cropDrag & EDGE_R) != 0) crop.right = Math.min(w, Math.max(crop.left + min, crop.right + dx));
        if ((cropDrag & EDGE_T) != 0) crop.top = Math.max(0, Math.min(crop.bottom - min, crop.top + dy));
        if ((cropDrag & EDGE_B) != 0) crop.bottom = Math.min(h, Math.max(crop.top + min, crop.bottom + dy));
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        Bitmap f = flat;
        if (f == null || f.isRecycled()) return;
        canvas.save();
        canvas.concat(matrix);
        canvas.drawBitmap(f, 0, 0, bitmapPaint);
        canvas.clipRect(0, 0, f.getWidth(), f.getHeight());
        for (int i = flatEnd; i < ops.size(); i++) ops.get(i).draw(canvas);
        if (drawing) {
            if (tool == TOOL_MOSAIC) {
                float l = Math.min(sx, ex), t = Math.min(sy, ey), r = Math.max(sx, ex), b = Math.max(sy, ey);
                canvas.drawRect(l, t, r, b, mosaicFill);
                canvas.restore();
                tmpRect.set(l, t, r, b);
                matrix.mapRect(tmpRect);
                canvas.drawRect(tmpRect, mosaicPaint);   // dashed outline at screen width
                return;
            }
            StrokeOp.drawShape(canvas, tool == TOOL_PEN ? StrokeOp.PEN : shapeKind(tool), curPath,
                    sx, sy, ex, ey, previewWidth, previewStroke, previewFill);
        }
        canvas.restore();
        if (editing && tool == TOOL_CROP) drawCropOverlay(canvas);
    }

    private void drawCropOverlay(Canvas canvas) {
        RectF v = new RectF(crop);
        matrix.mapRect(v);
        float w = getWidth(), h = getHeight();
        canvas.drawRect(0, 0, w, v.top, dimPaint);
        canvas.drawRect(0, v.bottom, w, h, dimPaint);
        canvas.drawRect(0, v.top, v.left, v.bottom, dimPaint);
        canvas.drawRect(v.right, v.top, w, v.bottom, dimPaint);
        for (int i = 1; i <= 2; i++) {
            float x = v.left + v.width() * i / 3f, y = v.top + v.height() * i / 3f;
            canvas.drawLine(x, v.top, x, v.bottom, gridPaint);
            canvas.drawLine(v.left, y, v.right, y, gridPaint);
        }
        canvas.drawRect(v, borderPaint);
        float len = Math.min(18 * density, Math.min(v.width(), v.height()) / 2f);
        float o = handlePaint.getStrokeWidth() / 2f;
        float l = v.left - o, t = v.top - o, r = v.right + o, b = v.bottom + o;
        canvas.drawLine(l, t, l + len, t, handlePaint);
        canvas.drawLine(l, t, l, t + len, handlePaint);
        canvas.drawLine(r, t, r - len, t, handlePaint);
        canvas.drawLine(r, t, r, t + len, handlePaint);
        canvas.drawLine(l, b, l + len, b, handlePaint);
        canvas.drawLine(l, b, l, b - len, handlePaint);
        canvas.drawLine(r, b, r - len, b, handlePaint);
        canvas.drawLine(r, b, r, b - len, handlePaint);
        // edge midpoint handles
        float mx = v.centerX(), my = v.centerY(), hl = len / 2f;
        canvas.drawLine(mx - hl, t, mx + hl, t, handlePaint);
        canvas.drawLine(mx - hl, b, mx + hl, b, handlePaint);
        canvas.drawLine(l, my - hl, l, my + hl, handlePaint);
        canvas.drawLine(r, my - hl, r, my + hl, handlePaint);
    }
}
