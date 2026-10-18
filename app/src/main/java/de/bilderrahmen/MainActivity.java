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
        clockParams.width = (int) (landscape ? w * 0.50f : w * 0.86f);
        clockParams.bottomMargin = (int) (h * 0.06f);
        clock.setLayoutParams(clockParams);
    }

    /** Verschiebt die Uhr leicht, damit nichts ins AMOLED-Display einbrennt. */
    private void shiftClock() {
        int w = root.getWidth(), h = root.getHeight();
        float dx = (random.nextFloat() * 2f - 1f) * w * 0.03f;
        float dy = -random.nextFloat() * h * 0.03f;
        clock.animate().translationX(dx).translationY(dy).setDuration(1500).start();
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
            if (bm != null) handler.post(() -> crossfade(bm));
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

    private void crossfade(Bitmap bm) {
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
            handler.post(() -> clock.setWeather("", "", "", ""));
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
                    + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"));
            JSONObject cur = f.getJSONObject("current");
            JSONObject daily = f.getJSONObject("daily");
            String temp = Math.round(cur.getDouble("temperature_2m")) + "°";
            String range = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)) + "° / "
                    + Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)) + "°";
            String icon = weatherIcon(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1);
            final String p = place;
            handler.post(() -> clock.setWeather(p, icon, temp, range));
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
