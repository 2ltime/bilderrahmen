package de.bilderrahmen;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/** Klappzahlen-Uhr (HH:MM) mit Datum. Ziffern klappen beim Minutenwechsel um. */
public class FlipClockView extends View {

    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint digitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hingePaint = new Paint();
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint colonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint datePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final String[] cur = {"", "", "", ""};
    private final String[] prev = {"", "", "", ""};
    private final float[] progress = {1f, 1f, 1f, 1f};
    private String dateText = "";
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, d. MMMM", Locale.GERMAN);

    private float cardW, cardH, gap, colonW, dateH, radius, hinge, baseline;
    private ValueAnimator anim;

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
        Typeface tf = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        digitPaint.setColor(0xFFF2F2F2);
        digitPaint.setTypeface(tf);
        digitPaint.setTextAlign(Paint.Align.CENTER);
        hingePaint.setColor(0xFF050505);
        shadowPaint.setColor(0x77000000);
        colonPaint.setColor(0xDDFFFFFF);
        datePaint.setColor(Color.WHITE);
        datePaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        datePaint.setTextAlign(Paint.Align.CENTER);
        datePaint.setShadowLayer(6f, 0f, 2f, 0xCC000000);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateTime(false);
        handler.post(tick);
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        float cw = w * 0.22f;
        float ch = cw * 1.4f;
        setMeasuredDimension(w, (int) (ch + ch * 0.32f));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        cardW = w * 0.22f;
        gap = w * 0.02f;
        colonW = w * 0.08f;
        cardH = cardW * 1.4f;
        dateH = cardH * 0.32f;
        radius = cardW * 0.09f;
        hinge = Math.max(2f, cardH * 0.012f);

        cardPaint.setShader(new LinearGradient(0, 0, 0, cardH,
                new int[]{0xFF3E3E3E, 0xFF2A2A2A, 0xFF1E1E1E, 0xFF111111},
                new float[]{0f, 0.5f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        digitPaint.setTextSize(cardH * 0.78f);
        Paint.FontMetrics fm = digitPaint.getFontMetrics();
        baseline = cardH / 2f - (fm.ascent + fm.descent) / 2f;
        datePaint.setTextSize(dateH * 0.55f);
    }

    private void updateTime(boolean animate) {
        Calendar cal = Calendar.getInstance();
        String t = String.format(Locale.GERMAN, "%02d%02d",
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
        dateText = dateFmt.format(cal.getTime());

        final boolean[] changed = new boolean[4];
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            String d = String.valueOf(t.charAt(i));
            if (!d.equals(cur[i])) {
                prev[i] = cur[i];
                cur[i] = d;
                changed[i] = true;
                any = true;
                progress[i] = animate ? 0f : 1f;
            }
        }
        if (any && animate) {
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(700);
            anim.setInterpolator(new AccelerateDecelerateInterpolator());
            anim.addUpdateListener(a -> {
                float v = (float) a.getAnimatedValue();
                for (int i = 0; i < 4; i++) if (changed[i]) progress[i] = v;
                invalidate();
            });
            anim.start();
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        float x = 0f;
        drawCard(c, x, 0); x += cardW + gap;
        drawCard(c, x, 1); x += cardW;

        float cx = x + colonW / 2f;
        float dotR = cardW * 0.055f;
        c.drawCircle(cx, cardH * 0.36f, dotR, colonPaint);
        c.drawCircle(cx, cardH * 0.64f, dotR, colonPaint);
        x += colonW;

        drawCard(c, x, 2); x += cardW + gap;
        drawCard(c, x, 3);

        c.drawText(dateText, getWidth() / 2f, cardH + dateH * 0.72f, datePaint);
    }

    private void drawCard(Canvas c, float x, int i) {
        float p = progress[i];
        String n = cur[i];
        String o = prev[i];

        rect.set(x, cardH * 0.025f, x + cardW, cardH * 1.025f);
        c.drawRoundRect(rect, radius, radius, shadowPaint);

        if (p >= 1f) {
            drawHalf(c, x, n, true, 1f, 0f);
            drawHalf(c, x, n, false, 1f, 0f);
        } else if (p < 0.5f) {
            // Phase 1: obere Hälfte der alten Ziffer klappt nach unten
            float s = 1f - p / 0.5f;
            drawHalf(c, x, n, true, 1f, 0f);
            drawHalf(c, x, o, false, 1f, 0f);
            drawHalf(c, x, o, true, s, (1f - s) * 0.6f);
        } else {
            // Phase 2: untere Hälfte der neuen Ziffer klappt herunter
            float s = (p - 0.5f) / 0.5f;
            drawHalf(c, x, n, true, 1f, 0f);
            drawHalf(c, x, o, false, 1f, (1f - s) * 0.35f);
            drawHalf(c, x, n, false, s, (1f - s) * 0.6f);
        }

        float mid = cardH / 2f;
        c.drawRect(x, mid - hinge / 2f, x + cardW, mid + hinge / 2f, hingePaint);
    }

    private void drawHalf(Canvas c, float x, String d, boolean top, float scaleY, float shade) {
        if (scaleY <= 0.001f) return;
        float mid = cardH / 2f;
        c.save();
        c.scale(1f, scaleY, x + cardW / 2f, mid);
        if (top) c.clipRect(x, 0f, x + cardW, mid);
        else c.clipRect(x, mid, x + cardW, cardH);
        rect.set(x, 0f, x + cardW, cardH);
        c.drawRoundRect(rect, radius, radius, cardPaint);
        c.drawText(d, x + cardW / 2f, baseline, digitPaint);
        if (shade > 0f) {
            shadePaint.setColor(Color.argb((int) (Math.min(1f, shade) * 255), 0, 0, 0));
            c.drawRoundRect(rect, radius, radius, shadePaint);
        }
        c.restore();
    }
}
