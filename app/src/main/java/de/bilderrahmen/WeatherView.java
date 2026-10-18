package de.bilderrahmen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Wetter-Widget im Glas-Look: oben das aktuelle Wetter, darunter die 5-Tage-Vorhersage.
 * Die Glasfelder zeigen das aktuelle Foto weichgezeichnet und leicht abgedunkelt.
 */
public class WeatherView extends View {

    public static class Day {
        public final String label;
        public final int code, max, min;
        public Day(String label, int code, int max, int min) {
            this.label = label; this.code = code; this.max = max; this.min = min;
        }
    }

    private static final int AMBER = Color.rgb(250, 199, 117);
    private static final int CLOUD = Color.rgb(220, 222, 230);
    private static final int CLOUD_RAIN = Color.rgb(190, 200, 215);
    private static final int CLOUD_STORM = Color.rgb(150, 156, 172);
    private static final int RAIN = Color.rgb(133, 183, 235);
    private static final int SUB = Color.rgb(196, 198, 210);

    private String place = "", condition = "", temp = "", sub = "";
    private int code = 0;
    private boolean isDay = true;
    private Day[] days = new Day[0];

    private Bitmap backdrop;
    private int srcW, srcH;
    private boolean fit;
    private final Matrix bdMatrix = new Matrix();

    private final Paint bdPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path path2 = new Path();
    private final RectF r = new RectF();
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EE, d. MMM", Locale.GERMAN);

    public WeatherView(Context ctx) {
        super(ctx);
        tintPaint.setColor(Color.argb(56, 8, 10, 20));      // 22 % dunkle Tönung wie im Entwurf
        fallbackPaint.setColor(Color.argb(150, 8, 10, 20)); // ohne Foto etwas dunkler
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setColor(Color.argb(80, 255, 255, 255));
        text.setColor(Color.WHITE);
        text.setShadowLayer(3f, 0f, 1f, 0x88000000);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    // ---------- Daten ----------

    public void setData(String place, int code, boolean isDay, int temp, int max, int min, Day[] days) {
        this.place = place;
        this.code = code;
        this.isDay = isDay;
        this.condition = conditionText(code);
        this.temp = temp + "°";
        String d = dateFmt.format(new Date()).replace(".,", ",");
        this.sub = d + " · " + max + "° / " + min + "°";
        this.days = days;
        invalidate();
    }

    /** Foto für den Glas-Effekt (klein und weichgezeichnet) plus Originalgröße des Fotos. */
    public void setBackdrop(Bitmap blurred, int srcW, int srcH, boolean fit) {
        this.backdrop = blurred;
        this.srcW = srcW;
        this.srcH = srcH;
        this.fit = fit;
        invalidate();
    }

    public void setFit(boolean fit) {
        this.fit = fit;
        invalidate();
    }

    public static float heightFor(float width) {
        return width * (0.155f + 0.03f + 0.215f);
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, (int) Math.ceil(heightFor(w)));
    }

    // ---------- Zeichnen ----------

    @Override protected void onDraw(Canvas c) {
        float w = getWidth();
        if (w <= 0) return;
        float curH = w * 0.155f, gapV = w * 0.03f, foreH = w * 0.215f;
        float rad = w * 0.035f;
        outlinePaint.setStrokeWidth(Math.max(1.5f, w * 0.002f));

        // aktuelles Wetter
        r.set(0, 0, w, curH);
        glass(c, r, rad);
        float cy = curH / 2f;
        float iconX = curH * 0.55f;
        drawIcon(c, code, isDay, iconX, cy, curH * 0.30f);

        float x = curH * 1.05f;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        text.setTextSize(curH * 0.50f);
        text.setColor(Color.WHITE);
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(temp, x, cy - (fm.ascent + fm.descent) / 2f, text);
        x += text.measureText(temp) + curH * 0.30f;

        float maxW = w - x - curH * 0.25f;
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        fitText(place.isEmpty() ? condition : place + " · " + condition, curH * 0.20f, maxW);
        c.drawText(place.isEmpty() ? condition : place + " · " + condition, x, cy - curH * 0.05f, text);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        text.setColor(SUB);
        fitText(sub, curH * 0.165f, maxW);
        c.drawText(sub, x, cy + curH * 0.24f, text);

        // 5-Tage-Vorhersage
        float top = curH + gapV;
        r.set(0, top, w, top + foreH);
        glass(c, r, rad);
        if (days.length == 0) return;
        float step = w / days.length;
        text.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < days.length; i++) {
            Day d = days[i];
            float cx = step * (i + 0.5f);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            text.setColor(Color.WHITE);
            text.setTextSize(foreH * 0.14f);
            c.drawText(d.label, cx, top + foreH * 0.25f, text);
            drawIcon(c, d.code, true, cx, top + foreH * 0.52f, foreH * 0.15f);
            text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            text.setColor(SUB);
            text.setTextSize(foreH * 0.12f);
            c.drawText(d.max + "° / " + d.min + "°", cx, top + foreH * 0.86f, text);
        }
        text.setColor(Color.WHITE);
    }

    private void fitText(String s, float size, float maxW) {
        text.setTextSize(size);
        float tw = text.measureText(s);
        if (tw > maxW && maxW > 0) text.setTextSize(size * maxW / tw);
    }

    /** Glasfeld: Foto weichgezeichnet durchscheinen lassen, leicht tönen, heller Rand. */
    private void glass(Canvas c, RectF box, float rad) {
        path.reset();
        path.addRoundRect(box, rad, rad, Path.Direction.CW);
        if (backdrop != null && !backdrop.isRecycled() && getParent() instanceof View) {
            View p = (View) getParent();
            float pw = p.getWidth(), ph = p.getHeight();
            if (pw > 0 && ph > 0 && srcW > 0 && srcH > 0) {
                c.save();
                c.clipPath(path);
                c.translate(-(getLeft() + getTranslationX()), -(getTop() + getTranslationY()));
                float s = fit ? Math.min(pw / srcW, ph / srcH) : Math.max(pw / srcW, ph / srcH);
                float dx = (pw - srcW * s) / 2f, dy = (ph - srcH * s) / 2f;
                bdMatrix.setScale((float) srcW / backdrop.getWidth() * s, (float) srcH / backdrop.getHeight() * s);
                bdMatrix.postTranslate(dx, dy);
                c.drawBitmap(backdrop, bdMatrix, bdPaint);
                c.restore();
                c.drawPath(path, tintPaint);
            } else {
                c.drawPath(path, fallbackPaint);
            }
        } else {
            c.drawPath(path, fallbackPaint);
        }
        c.drawPath(path, outlinePaint);
    }

    // ---------- Wetter-Symbole ----------

    private void drawIcon(Canvas c, int code, boolean day, float cx, float cy, float s) {
        if (code == 0) {
            if (day) sun(c, cx, cy, s); else moon(c, cx, cy, s);
        } else if (code <= 2) {
            if (day) sun(c, cx - s * 0.35f, cy - s * 0.35f, s * 0.75f);
            else moon(c, cx - s * 0.35f, cy - s * 0.35f, s * 0.75f);
            cloud(c, cx + s * 0.2f, cy + s * 0.25f, s * 0.95f, CLOUD);
        } else if (code == 3) {
            cloud(c, cx, cy, s * 1.1f, CLOUD);
        } else if (code == 45 || code == 48) {
            stroke.setColor(CLOUD);
            stroke.setStrokeWidth(s * 0.16f);
            for (int k = -1; k <= 1; k++) {
                float o = k * s * 0.42f, ind = (k == 0) ? s * 0.25f : 0f;
                c.drawLine(cx - s + ind, cy + o, cx + s - ind * 0.3f, cy + o, stroke);
            }
        } else if (code >= 71 && code <= 77 || code == 85 || code == 86) {
            cloud(c, cx, cy - s * 0.25f, s, CLOUD);
            fill.setColor(Color.WHITE);
            for (int k = -1; k <= 1; k++) c.drawCircle(cx + k * s * 0.45f, cy + s * 0.75f + (k == 0 ? s * 0.15f : 0f), s * 0.11f, fill);
        } else if (code >= 95) {
            cloud(c, cx, cy - s * 0.25f, s, CLOUD_STORM);
            fill.setColor(AMBER);
            path2.reset();
            path2.moveTo(cx + s * 0.10f, cy + s * 0.15f);
            path2.lineTo(cx - s * 0.22f, cy + s * 0.65f);
            path2.lineTo(cx + s * 0.02f, cy + s * 0.65f);
            path2.lineTo(cx - s * 0.12f, cy + s * 1.05f);
            path2.lineTo(cx + s * 0.30f, cy + s * 0.48f);
            path2.lineTo(cx + s * 0.06f, cy + s * 0.48f);
            path2.close();
            c.drawPath(path2, fill);
        } else {
            // Niesel, Regen, Schauer
            cloud(c, cx, cy - s * 0.25f, s, CLOUD_RAIN);
            stroke.setColor(RAIN);
            stroke.setStrokeWidth(s * 0.13f);
            for (int k = -1; k <= 1; k++) {
                float x = cx + k * s * 0.45f;
                c.drawLine(x, cy + s * 0.50f, x - s * 0.18f, cy + s * 0.95f, stroke);
            }
        }
    }

    private void sun(Canvas c, float cx, float cy, float s) {
        fill.setColor(AMBER);
        c.drawCircle(cx, cy, s * 0.55f, fill);
        stroke.setColor(AMBER);
        stroke.setStrokeWidth(s * 0.12f);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            c.drawLine(cx + cos * s * 0.78f, cy + sin * s * 0.78f, cx + cos * s * 1.02f, cy + sin * s * 1.02f, stroke);
        }
    }

    private void moon(Canvas c, float cx, float cy, float s) {
        float rr = s * 0.8f;
        path2.reset();
        path2.addCircle(cx, cy, rr, Path.Direction.CW);
        Path cut = new Path();
        cut.addCircle(cx + rr * 0.55f, cy - rr * 0.45f, rr * 0.95f, Path.Direction.CW);
        path2.op(cut, Path.Op.DIFFERENCE);
        fill.setColor(AMBER);
        c.drawPath(path2, fill);
    }

    private void cloud(Canvas c, float cx, float cy, float s, int color) {
        fill.setColor(color);
        r.set(cx - s, cy - s * 0.3f, cx, cy + s * 0.5f);
        c.drawOval(r, fill);
        r.set(cx - s * 0.5f, cy - s * 0.7f, cx + s * 0.4f, cy + s * 0.4f);
        c.drawOval(r, fill);
        r.set(cx - s * 0.1f, cy - s * 0.35f, cx + s, cy + s * 0.5f);
        c.drawOval(r, fill);
        c.drawRect(cx - s * 0.7f, cy + s * 0.05f, cx + s * 0.7f, cy + s * 0.5f, fill);
    }

    // ---------- Texte ----------

    static String conditionText(int code) {
        if (code == 0) return "Klar";
        if (code == 1) return "Überwiegend klar";
        if (code == 2) return "Teils bewölkt";
        if (code == 3) return "Bewölkt";
        if (code == 45 || code == 48) return "Nebel";
        if (code >= 51 && code <= 55) return "Nieselregen";
        if (code == 56 || code == 57) return "Gefrierender Niesel";
        if (code >= 61 && code <= 65) return "Regen";
        if (code == 66 || code == 67) return "Gefrierender Regen";
        if (code >= 71 && code <= 75) return "Schnee";
        if (code == 77) return "Schneegriesel";
        if (code >= 80 && code <= 82) return "Regenschauer";
        if (code == 85 || code == 86) return "Schneeschauer";
        if (code == 95) return "Gewitter";
        if (code >= 96) return "Gewitter mit Hagel";
        return "";
    }
}
