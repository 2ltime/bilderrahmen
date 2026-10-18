package de.bilderrahmen;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_FOLDER = 1;

    private static final String[] INTERVAL_LABELS = {"30 Sekunden", "1 Minute", "2 Minuten", "5 Minuten", "10 Minuten"};
    private static final long[] INTERVAL_MS = {30_000, 60_000, 120_000, 300_000, 600_000};
    private static final String[] NIGHT_LABELS = {"Aus", "22 – 6 Uhr", "23 – 7 Uhr", "0 – 6 Uhr", "22 – 8 Uhr"};
    private static final int[][] NIGHT_HOURS = {{0, 0}, {22, 6}, {23, 7}, {0, 6}, {22, 8}};

    private SharedPreferences prefs;
    private FrameLayout root;
    private ImageView imgA, imgB;
    private boolean showingA = true;
    private View nightOverlay;
    private FlipClockView clock;
    private WeatherView weather;
    private FrameLayout.LayoutParams weatherParams;
    private FrameLayout.LayoutParams clockParams;
    private TextView hint;

    private final List<Uri> images = new ArrayList<>();
    private int index = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Random random = new Random();

    private final Runnable slide = new Runnable() {
        @Override public void run() {
            if (!isNight()) showNext();
            handler.postDelayed(this, intervalMs());
        }
    };

    private final Runnable weatherTask = new Runnable() {
        @Override public void run() {
            net.execute(MainActivity.this::fetchWeather);
            handler.postDelayed(this, 30 * 60_000);
        }
    };

    private final Runnable nightCheck = new Runnable() {
        @Override public void run() {
            applyNight();
            handler.postDelayed(this, 30_000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("rahmen", MODE_PRIVATE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        imgA = makeImage();
        imgB = makeImage();
        imgB.setAlpha(0f);
        root.addView(imgA, match());
        root.addView(imgB, match());

        nightOverlay = new View(this);
        nightOverlay.setBackgroundColor(Color.BLACK);
        nightOverlay.setVisibility(View.GONE);
        root.addView(nightOverlay, match());

        clock = new FlipClockView(this);
        clockParams = new FrameLayout.LayoutParams(600, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        root.addView(clock, clockParams);

        weather = new WeatherView(this);
        weather.setVisibility(View.GONE);
        weatherParams = new FrameLayout.LayoutParams(600, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        root.addView(weather, weatherParams);

        hint = new TextView(this);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(20);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(48, 48, 48, 48);
        hint.setText("Willkommen!\n\nLange auf den Bildschirm tippen,\num einen Bilderordner zu wählen.");
        root.addView(hint, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        root.setOnLongClickListener(v -> { showSettings(); return true; });

        setContentView(root);
        root.post(this::layoutClock);
        applyScaleType();
        applyClockVisibility();
        loadImages();
    }

    @Override protected void onResume() {
        super.onResume();
        hideSystemUi();
        handler.removeCallbacks(nightCheck);
        handler.post(nightCheck);
        handler.removeCallbacks(weatherTask);
        handler.post(weatherTask);
        if (!images.isEmpty()) {
            handler.removeCallbacks(slide);
            handler.post(slide);
        }
    }

    @Override protected void onPause() {
        handler.removeCallbacks(slide);
        handler.removeCallbacks(nightCheck);
        handler.removeCallbacks(weatherTask);
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        root.post(this::layoutClock);
    }

    // ---------- Layout ----------

    private ImageView makeImage() {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return iv;
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private void layoutClock() {
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0) return;
        boolean landscape = w > h;
        boolean withWeather = weather.getVisibility() == View.VISIBLE;
        int side = (int) (w * 0.04f), bottom = (int) (h * 0.06f);

        if (!withWeather) {
            clockParams.width = (int) (landscape ? w * 0.50f : w * 0.86f);
            clockParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            clockParams.leftMargin = 0;
            clockParams.bottomMargin = bottom;
        } else if (landscape) {
            // Uhr unten links, Wetter unten rechts
            clockParams.width = (int) (w * 0.42f);
            clockParams.gravity = Gravity.BOTTOM | Gravity.START;
            clockParams.leftMargin = side;
            clockParams.bottomMargin = bottom;
            weatherParams.width = (int) (w * 0.40f);
            weatherParams.gravity = Gravity.BOTTOM | Gravity.END;
            weatherParams.rightMargin = side;
            weatherParams.bottomMargin = bottom;
        } else {
            // hochkant: Uhr über dem Wetter
            int cw = (int) (w * 0.86f);
            weatherParams.width = cw;
            weatherParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            weatherParams.rightMargin = 0;
            weatherParams.bottomMargin = (int) (h * 0.04f);
            clockParams.width = cw;
            clockParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            clockParams.leftMargin = 0;
            clockParams.bottomMargin = weatherParams.bottomMargin + (int) WeatherView.heightFor(cw) + (int) (h * 0.025f);
        }
        clock.setLayoutParams(clockParams);
        weather.setLayoutParams(weatherParams);
    }

    /** Verschiebt die Uhr leicht, damit nichts ins AMOLED-Display einbrennt. */
    private void shiftClock() {
        int w = root.getWidth(), h = root.getHeight();
        float dx = (random.nextFloat() * 2f - 1f) * w * 0.03f;
        float dy = -random.nextFloat() * h * 0.03f;
        clock.animate().translationX(dx).translationY(dy).setDuration(1500).start();
        weather.animate().translationX(dx).translationY(dy).setDuration(1500)
                .withEndAction(weather::invalidate).start();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    // ---------- Bilder ----------

    private void loadImages() {
        String folder = prefs.getString("folder", null);
        if (folder == null) {
            hint.setVisibility(View.VISIBLE);
            return;
        }
        scan(Uri.parse(folder), true);
    }

    private void scan(Uri tree, boolean startSlides) {
        loader.execute(() -> {
            List<Uri> found = new ArrayList<>();
            try {
                collect(tree, DocumentsContract.getTreeDocumentId(tree), found, 0);
            } catch (Exception ignored) { }
            Collections.shuffle(found);
            handler.post(() -> {
                if (found.isEmpty() && !startSlides) return; // beim Nachladen nichts kaputt machen
                images.clear();
                images.addAll(found);
                index = 0;
                if (images.isEmpty()) {
                    hint.setText("Keine Bilder im gewählten Ordner gefunden.\n\nLange tippen für Einstellungen.");
                    hint.setVisibility(View.VISIBLE);
                } else {
                    hint.setVisibility(View.GONE);
                    if (startSlides) {
                        handler.removeCallbacks(slide);
                        handler.post(slide);
                    }
                }
            });
        });
    }

    private void collect(Uri tree, String docId, List<Uri> out, int depth) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor c = getContentResolver().query(children, cols, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String mime = c.getString(1);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (depth < 3) collect(tree, id, out, depth + 1);
                } else if (mime != null && mime.startsWith("image/")) {
                    out.add(DocumentsContract.buildDocumentUriUsingTree(tree, id));
                }
            }
        } catch (Exception ignored) { }
    }

    private void showNext() {
        if (images.isEmpty()) return;
        if (index >= images.size()) {
            index = 0;
            Collections.shuffle(images);
            String folder = prefs.getString("folder", null);
            if (folder != null) scan(Uri.parse(folder), false); // neue Fotos aufnehmen
        }
        Uri u = images.get(index++);
        loader.execute(() -> {
            Bitmap bm = decode(u);
            if (bm == null) return;
            Bitmap blur = makeBackdrop(bm);
            handler.post(() -> crossfade(bm, blur));
        });
    }

    private Bitmap decode(Uri u) {
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int target = Math.max(dm.widthPixels, dm.heightPixels);

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bm;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                bm = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bm == null) return null;

            int rot = 0;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                ExifInterface ex = new ExifInterface(in);
                int o = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (o == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
                else if (o == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
                else if (o == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
            } catch (Exception ignored) { }

            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                Bitmap r = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
                if (r != bm) bm.recycle();
                bm = r;
            }
            return bm;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** Kleines, weichgezeichnetes Abbild des Fotos für die Glasfelder. */
    private static Bitmap makeBackdrop(Bitmap bm) {
        try {
            int w = Math.max(4, bm.getWidth() / 12), h = Math.max(4, bm.getHeight() / 12);
            Bitmap small = Bitmap.createScaledBitmap(bm, w, h, true);
            if (small == bm) small = bm.copy(Bitmap.Config.ARGB_8888, true);
            int[] px = new int[w * h];
            small.getPixels(px, 0, w, 0, 0, w, h);
            for (int pass = 0; pass < 2; pass++) boxBlur(px, w, h, 2);
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            out.setPixels(px, 0, w, 0, 0, w, h);
            small.recycle();
            return out;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static void boxBlur(int[] px, int w, int h, int rad) {
        int[] tmp = new int[px.length];
        for (int dir = 0; dir < 2; dir++) {
            int[] src = dir == 0 ? px : tmp, dst = dir == 0 ? tmp : px;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int r = 0, g = 0, b = 0, n = 0;
                    for (int k = -rad; k <= rad; k++) {
                        int xx = dir == 0 ? Math.min(w - 1, Math.max(0, x + k)) : x;
                        int yy = dir == 1 ? Math.min(h - 1, Math.max(0, y + k)) : y;
                        int c = src[yy * w + xx];
                        r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF; n++;
                    }
                    dst[y * w + x] = 0xFF000000 | ((r / n) << 16) | ((g / n) << 8) | (b / n);
                }
            }
        }
    }

    private void crossfade(Bitmap bm, Bitmap blur) {
        if (blur != null) weather.setBackdrop(blur, bm.getWidth(), bm.getHeight(), prefs.getBoolean("fit", false));
        final ImageView in = showingA ? imgB : imgA;
        final ImageView out = showingA ? imgA : imgB;
        in.setImageBitmap(bm);
        in.setAlpha(0f);
        in.animate().alpha(1f).setDuration(1500).start();
        out.animate().alpha(0f).setDuration(1500).withEndAction(() -> out.setImageDrawable(null)).start();
        showingA = !showingA;
        shiftClock();
    }

    // ---------- Nachtruhe ----------

    private boolean isNight() {
        int s = prefs.getInt("nightStart", 23);
        int e = prefs.getInt("nightEnd", 7);
        if (s == e) return false;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return s < e ? (h >= s && h < e) : (h >= s || h < e);
    }

    private void applyNight() {
        boolean night = isNight();
        nightOverlay.setVisibility(night ? View.VISIBLE : View.GONE);
        clock.setAlpha(night ? 0.3f : 1f);
        weather.setAlpha(night ? 0.3f : 1f);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = night ? 0.01f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        if (night) shiftClock();
    }

    // ---------- Einstellungen ----------

    private long intervalMs() {
        return INTERVAL_MS[prefs.getInt("interval", 1)];
    }

    private void applyScaleType() {
        ImageView.ScaleType t = prefs.getBoolean("fit", false)
                ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP;
        imgA.setScaleType(t);
        imgB.setScaleType(t);
        if (weather != null) weather.setFit(prefs.getBoolean("fit", false));
    }

    private void applyClockVisibility() {
        clock.setVisibility(prefs.getBoolean("showClock", true) ? View.VISIBLE : View.GONE);
    }

    private int nightIndex() {
        int s = prefs.getInt("nightStart", 23), e = prefs.getInt("nightEnd", 7);
        for (int i = 0; i < NIGHT_HOURS.length; i++)
            if (NIGHT_HOURS[i][0] == s && NIGHT_HOURS[i][1] == e) return i;
        return 0;
    }

    private void showSettings() {
        String[] items = {
                "Bilderordner wählen",
                "Wechselintervall: " + INTERVAL_LABELS[prefs.getInt("interval", 1)],
                "Nachtruhe: " + NIGHT_LABELS[nightIndex()],
                "Bildanpassung: " + (prefs.getBoolean("fit", false) ? "Ganzes Bild" : "Bildschirm füllen"),
                "Uhr anzeigen: " + (prefs.getBoolean("showClock", true) ? "Ja" : "Nein"),
                "Wetter-Ort: " + (prefs.getString("city", "").isEmpty() ? "Aus" : prefs.getString("city", ""))
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Einstellungen")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: pickFolder(); break;
                        case 1: chooseInterval(); break;
                        case 2: chooseNight(); break;
                        case 3:
                            prefs.edit().putBoolean("fit", !prefs.getBoolean("fit", false)).apply();
                            applyScaleType();
                            break;
                        case 4:
                            prefs.edit().putBoolean("showClock", !prefs.getBoolean("showClock", true)).apply();
                            applyClockVisibility();
                            break;
                        case 5: chooseCity(); break;
                    }
                })
                .show();
    }

    private void chooseInterval() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Wechselintervall")
                .setSingleChoiceItems(INTERVAL_LABELS, prefs.getInt("interval", 1), (d, which) -> {
                    prefs.edit().putInt("interval", which).apply();
                    handler.removeCallbacks(slide);
                    if (!images.isEmpty()) handler.postDelayed(slide, intervalMs());
                    d.dismiss();
                })
                .show();
    }

    private void chooseNight() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Nachtruhe")
                .setSingleChoiceItems(NIGHT_LABELS, nightIndex(), (d, which) -> {
                    prefs.edit()
                            .putInt("nightStart", NIGHT_HOURS[which][0])
                            .putInt("nightEnd", NIGHT_HOURS[which][1])
                            .apply();
                    applyNight();
                    d.dismiss();
                })
                .show();
    }

    private void chooseCity() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("z. B. München");
        input.setText(prefs.getString("city", ""));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Wetter-Ort (leer = kein Wetter)")
                .setView(input)
                .setPositiveButton("OK", (d, w) -> {
                    prefs.edit().putString("city", input.getText().toString().trim())
                            .remove("lat").remove("lon").remove("place").apply();
                    net.execute(this::fetchWeather);
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    // ---------- Wetter (Open-Meteo, ohne Anmeldung) ----------

    private void fetchWeather() {
        String city = prefs.getString("city", "");
        if (city.isEmpty()) {
            handler.post(() -> {
                clock.setWeather("", "", "", "");
                weather.setVisibility(View.GONE);
                layoutClock();
            });
            return;
        }
        try {
            String lat = prefs.getString("lat", null), lon = prefs.getString("lon", null);
            String place = prefs.getString("place", city);
            if (lat == null || lon == null) {
                JSONObject geo = new JSONObject(httpGet("https://geocoding-api.open-meteo.com/v1/search?count=1&language=de&name="
                        + URLEncoder.encode(city, "UTF-8")));
                JSONArray res = geo.optJSONArray("results");
                if (res == null || res.length() == 0) {
                    handler.post(() -> clock.setWeather(city + " (nicht gefunden)", "", "", ""));
                    return;
                }
                JSONObject r = res.getJSONObject(0);
                lat = String.valueOf(r.getDouble("latitude"));
                lon = String.valueOf(r.getDouble("longitude"));
                place = r.optString("name", city);
                prefs.edit().putString("lat", lat).putString("lon", lon).putString("place", place).apply();
            }
            JSONObject f = new JSONObject(httpGet("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                    + "&longitude=" + lon
                    + "&current=temperature_2m,weather_code,is_day"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=5"));
            JSONObject cur = f.getJSONObject("current");
            JSONObject daily = f.getJSONObject("daily");
            JSONArray dates = daily.getJSONArray("time");
            JSONArray codes = daily.getJSONArray("weather_code");
            JSONArray maxs = daily.getJSONArray("temperature_2m_max");
            JSONArray mins = daily.getJSONArray("temperature_2m_min");

            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            java.text.SimpleDateFormat out = new java.text.SimpleDateFormat("EE", java.util.Locale.GERMAN);
            int n = Math.min(5, dates.length());
            WeatherView.Day[] days = new WeatherView.Day[n];
            for (int i = 0; i < n; i++) {
                String label = i == 0 ? "Heute" : out.format(in.parse(dates.getString(i))).replace(".", "");
                days[i] = new WeatherView.Day(label, codes.getInt(i),
                        (int) Math.round(maxs.getDouble(i)), (int) Math.round(mins.getDouble(i)));
            }
            final int temp = (int) Math.round(cur.getDouble("temperature_2m"));
            final int code = cur.getInt("weather_code");
            final boolean day = cur.optInt("is_day", 1) == 1;
            final int max = n > 0 ? days[0].max : temp, min = n > 0 ? days[0].min : temp;
            final String p = place;
            handler.post(() -> {
                clock.setWeather("", "", "", "");
                weather.setData(p, code, day, temp, max, min, days);
                if (weather.getVisibility() != View.VISIBLE) {
                    weather.setVisibility(View.VISIBLE);
                    layoutClock();
                }
            });
        } catch (Exception ignored) {
            // kein Netz: alte Anzeige bleibt stehen
        }
    }

    private static String weatherIcon(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code <= 2) return day ? "⛅" : "☁️";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 51 && code <= 57) return "🌦️";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "🌧️";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "❄️";
        if (code >= 95) return "⛈️";
        return "☁️";
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10_000);
        con.setReadTimeout(10_000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            con.disconnect();
        }
    }

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_FOLDER);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FOLDER || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) { }
        prefs.edit().putString("folder", uri.toString()).apply();
        hint.setText("Bilder werden geladen …");
        hint.setVisibility(View.VISIBLE);
        scan(uri, true);
    }
}
