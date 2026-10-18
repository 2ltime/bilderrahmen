package de.bilderrahmen;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateInterpolator;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;

/**
 * Klappuhr im klassischen Stil: zwei große Klappkarten (Stunden | Minuten) mit Scharnier,
 * 3D-Klappanimation und einer Leiste mit Ort, Datum und Wetter darunter.
 */
public class FlipClockView extends View {

    private static final int FLIP_MS = 650;

    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint flapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint digitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hingePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Camera camera = new Camera();
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final HashMap<String, Bitmap> cache = new HashMap<>();

    // 0 = Stunden, 1 = Minuten
    private final String[] cur = {"", ""};
    private final String[] prev = {"", ""};
    private final float[] progress = {1f, 1f};
    private final ValueAnimator[] anims = new ValueAnimator[2];

    private String dateText = "";
    private String place = "", icon = "", temp = "", range = "";
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, d. MMMM", Locale.GERMAN);

    private float tileW, tileH, gap, radius, hinge, baseline, barTop, barH;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateTime(true);
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 1000 - (now % 1000) + 20);
        }
    };

    public FlipClockView(Context ctx) {
        super(ctx);
        digitPaint.setColor(0xFFF4F4F4);
        digitPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        digitPaint.setTextAlign(Paint.Align.CENTER);

        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setColor(0x33FFFFFF);

        hingePaint.setColor(0xFF070707);
        pinPaint.setColor(0xFF6A6A6A);
        shadowPaint.setColor(0x80000000);
        barPaint.setColor(0x99000000);

        textPaint.setColor(Color.WHITE);
        textPaint.setShadowLayer(4f, 0f, 1.5f, 0xCC000000);
    }

    /** Wetterdaten setzen; leere Strings blenden das Wetter aus. */
    public void setWeather(String place, String icon, String temp, String range) {
        this.place = place == null ? "" : place;
        this.icon = icon == null ? "" : icon;
        this.temp = temp == null ? "" : temp;
        this.range = range == null ? "" : range;
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateTime(false);
        handler.post(tick);
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        for (ValueAnimator a : anims) if (a != null) a.cancel();
        super.onDetachedFromWindow();
    }

    // ---------- Maße ----------

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        float g = w * 0.03f;
        float tw = (w - g) / 2f;
        float th = tw * 0.80f;
        float h = th + th * 0.08f + th * 0.36f;
        setMeasuredDimension(w, (int) Math.ceil(h));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        gap = w * 0.03f;
        tileW = (w - gap) / 2f;
        tileH = tileW * 0.80f;
        radius = tileW * 0.06f;
        hinge = Math.max(2f, tileH * 0.014f);
        barTop = tileH * 1.08f;
        barH = tileH * 0.36f;

        cardPaint.setShader(new LinearGradient(0, 0, 0, tileH,
                new int[]{0xFF4A4A4A, 0xFF2B2B2B, 0xFF1F1F1F, 0xFF121212},
                new float[]{0f, 0.5f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        glossPaint.setShader(new LinearGradient(0, 0, 0, tileH / 2f,
                0x26FFFFFF, 0x05FFFFFF, Shader.TileMode.CLAMP));
        edgePaint.setStrokeWidth(Math.max(1f, tileH * 0.004f));

        digitPaint.setTextSize(tileH * 0.78f);
        digitPaint.setShadowLayer(tileH * 0.012f, 0f, tileH * 0.008f, 0xAA000000);
        Paint.FontMetrics fm = digitPaint.getFontMetrics();
        baseline = tileH / 2f - (fm.ascent + fm.descent) / 2f;

        camera.setLocation(0f, 0f, -tileH * 3f / 72f);
        cache.clear();
    }

    // ---------- Zeit ----------

    private void updateTime(boolean animate) {
        Calendar cal = Calendar.getInstance();
        String[] now = {
                String.format(Locale.GERMAN, "%02d", cal.get(Calendar.HOUR_OF_DAY)),
                String.format(Locale.GERMAN, "%02d", cal.get(Calendar.MINUTE))
        };
        dateText = dateFmt.format(cal.getTime());

        for (int i = 0; i < 2; i++) {
            if (now[i].equals(cur[i])) continue;
            prev[i] = cur[i];
            cur[i] = now[i];
            if (animate && !prev[i].isEmpty()) startFlip(i);
            else progress[i] = 1f;
        }
        invalidate();
    }

    private void startFlip(final int i) {
        if (anims[i] != null) anims[i].cancel();
        progress[i] = 0f;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(FLIP_MS);
        a.setInterpolator(new AccelerateInterpolator(1.4f));
        a.addUpdateListener(v -> {
            progress[i] = (float) v.getAnimatedValue();
            invalidate();
        });
        anims[i] = a;
        a.start();
    }

    // ---------- Zeichnen ----------

    @Override protected void onDraw(Canvas c) {
        if (tileW <= 0) return;
        drawTile(c, 0f, 0);
        drawTile(c, tileW + gap, 1);
        drawBar(c);
    }

    private void drawTile(Canvas c, float x, int i) {
        float mid = tileH / 2f;
        String n = cur[i], o = prev[i];
        float p = progress[i];

        rect.set(x + tileW * 0.01f, tileH * 0.03f, x + tileW * 1.01f, tileH * 1.03f);
        c.drawRoundRect(rect, radius, radius, shadowPaint);

        if (p >= 1f || o.isEmpty()) {
            c.drawBitmap(half(n, true), x, 0f, bmpPaint);
            c.drawBitmap(half(n, false), x, mid, bmpPaint);
        } else if (p < 0.5f) {
            // obere Hälfte der alten Zahl klappt nach vorne weg
            float s = p / 0.5f;
            drawShaded(c, half(n, true), x, 0f, 0.55f + 0.45f * s);
            c.drawBitmap(half(o, false), x, mid, bmpPaint);
            drawFlap(c, x, half(o, true), true, s * 90f, 1f - 0.45f * s);
        } else {
            // untere Hälfte der neuen Zahl klappt herunter
            float s = (p - 0.5f) / 0.5f;
            c.drawBitmap(half(n, true), x, 0f, bmpPaint);
            drawShaded(c, half(o, false), x, mid, 1f - 0.45f * (1f - s));
            drawFlap(c, x, half(n, false), false, (1f - s) * 90f, 0.55f + 0.45f * s);
        }

        // Scharnier-Stifte links und rechts
        float pinW = tileW * 0.022f, pinH = tileH * 0.13f;
        rect.set(x - pinW * 0.45f, mid - pinH / 2f, x + pinW * 0.55f, mid + pinH / 2f);
        c.drawRoundRect(rect, pinW / 2f, pinW / 2f, pinPaint);
        rect.set(x + tileW - pinW * 0.55f, mid - pinH / 2f, x + tileW + pinW * 0.45f, mid + pinH / 2f);
        c.drawRoundRect(rect, pinW / 2f, pinW / 2f, pinPaint);
    }

    private void drawShaded(Canvas c, Bitmap bm, float x, float y, float bright) {
        setBright(flapPaint, bright);
        c.drawBitmap(bm, x, y, flapPaint);
    }

    private void drawFlap(Canvas c, float x, Bitmap bm, boolean top, float deg, float bright) {
        if (deg >= 89.5f) return;
        camera.save();
        camera.rotateX(top ? -deg : deg);
        camera.getMatrix(matrix);
        camera.restore();
        float hingeY = top ? bm.getHeight() : 0f;
        matrix.preTranslate(-tileW / 2f, -hingeY);
        matrix.postTranslate(x + tileW / 2f, tileH / 2f);
        setBright(flapPaint, bright);
        c.drawBitmap(bm, matrix, flapPaint);
    }

    private void setBright(Paint p, float b) {
        int v = Math.max(0, Math.min(255, Math.round(b * 255f)));
        p.setColorFilter(v >= 255 ? null : new LightingColorFilter(Color.rgb(v, v, v), 0));
    }

    /** Eine Kartenhälfte als Bild (wird zwischengespeichert). */
    private Bitmap half(String text, boolean top) {
        String key = text + (top ? "T" : "B");
        Bitmap b = cache.get(key);
        if (b != null) return b;
        if (cache.size() > 16) cache.clear();

        int w = Math.max(1, Math.round(tileW));
        int h = Math.max(1, Math.round(tileH / 2f));
        b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (!top) c.translate(0f, -tileH / 2f);

        rect.set(0f, 0f, tileW, tileH);
        c.drawRoundRect(rect, radius, radius, cardPaint);
        if (top) c.drawRoundRect(rect, radius, radius, glossPaint);
        c.drawText(text, tileW / 2f, baseline, digitPaint);
        float e = edgePaint.getStrokeWidth() / 2f;
        rect.set(e, e, tileW - e, tileH - e);
        c.drawRoundRect(rect, radius, radius, edgePaint);
        c.drawRect(0f, tileH / 2f - hinge / 2f, tileW, tileH / 2f + hinge / 2f, hingePaint);

        cache.put(key, b);
        return b;
    }

    private void drawBar(Canvas c) {
        float w = getWidth();
        rect.set(0f, barTop, w, barTop + barH);
        c.drawRoundRect(rect, radius, radius, barPaint);

        float pad = barH * 0.32f;

        // links: Ort + Datum
        textPaint.setTextAlign(Paint.Align.LEFT);
        if (!place.isEmpty()) {
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textPaint.setTextSize(barH * 0.34f);
            c.drawText(place, pad, barTop + barH * 0.45f, textPaint);
            textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            textPaint.setTextSize(barH * 0.25f);
            c.drawText(dateText, pad, barTop + barH * 0.80f, textPaint);
        } else {
            textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            textPaint.setTextSize(barH * 0.34f);
            c.drawText(dateText, pad, barTop + barH * 0.62f, textPaint);
        }

        // rechts: Wetter
        if (!temp.isEmpty()) {
            float right = w - pad;
            textPaint.setTextAlign(Paint.Align.RIGHT);

            textPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            textPaint.setTextSize(barH * 0.46f);
            float tw = textPaint.measureText(temp);
            c.drawText(temp, right, barTop + barH * 0.52f, textPaint);

            textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            textPaint.setTextSize(barH * 0.22f);
            float rw = range.isEmpty() ? 0f : textPaint.measureText(range);
            if (!range.isEmpty()) c.drawText(range, right, barTop + barH * 0.84f, textPaint);

            if (!icon.isEmpty()) {
                textPaint.setTextSize(barH * 0.58f);
                c.drawText(icon, right - Math.max(tw, rw) - barH * 0.18f, barTop + barH * 0.72f, textPaint);
            }
        }
    }
}
