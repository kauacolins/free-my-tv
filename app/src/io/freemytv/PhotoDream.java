package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The apps_only screensaver: your photos from backgrounds/ in full screen, like the ambient modes of Google TV and
 * Apple TV:
 *  - one every 30 s, in name order from a random start, with a slow zoom and pan (Ken Burns) and a 2.5 s crossfade;
 *    each photo is prepared 10% larger than the screen, so the zoom never upscales it;
 *  - bottom left, inside the safe area, only what helps at a glance: the time, large, and the date with the weather.
 *    A soft dark scrim keeps them readable on any photo, and they drift a few pixels so nothing sits still on the
 *    panel.
 * Any key exits.
 */
final class PhotoDream extends View implements Choreographer.FrameCallback, HomeData.Listener {
    private static final long SHOW_MS = 30_000, FADE_MS = 2_500, PREPARE_MS = 10_000, OPEN_MS = 1_500;
    private static final float ZOOM = 0.1f, SIDE = 96, BOTTOM = 1000;
    private static final double DRIFT_S = 600;

    /** One photo: the image and its motion (zooming in or out, toward one side). */
    private static final class Slide {
        final Bitmap image;
        final boolean zoomIn;
        final float panX, panY;
        long at;

        Slide(Bitmap image, int n) {
            this.image = image;
            zoomIn = n % 2 == 0;
            panX = (n / 2) % 2 == 0 ? 0.8f : -0.8f;
            panY = n % 3 == 0 ? -0.6f : 0.6f;
        }
    }

    /** True when there are photos to show (apps_only with a backgrounds/ folder). */
    static boolean available(Context c) {
        return Config.APPS_ONLY && list(c).length > 0;
    }

    private static String[] list(Context c) {
        try {
            String[] names = c.getAssets().list("backgrounds");
            return names == null ? new String[0] : names;
        } catch (IOException e) {
            return new String[0];
        }
    }

    private final HomeData data;
    private final String[] photos;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Matrix matrix = new Matrix();
    private final Paint photoPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint scrim = new Paint(Paint.DITHER_FLAG);
    private final Paint veil = new Paint();
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF screen = new RectF();
    private ExecutorService worker;
    private Paint timePaint, infoPaint;
    private Slide current, incoming, prepared;
    private boolean running, loading;
    private int next, count;
    private long openedAt, minute = -1;
    private int w, h;
    private float k = 1;
    private String time = "", date = "", temp = "", weather;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            handler.postDelayed(this, 60_000);
        }
    };

    PhotoDream(Context c) {
        super(c);
        data = HomeData.get(c);
        photos = list(c);
        Arrays.sort(photos);
        next = photos.length == 0 ? 0 : (int) (Math.random() * photos.length);
        veil.setColor(0xFF000000);
        dot.setColor(0xB3FFFFFF);
    }

    void start() {
        if (running) return;
        running = true;
        openedAt = SystemClock.uptimeMillis();
        worker = Executors.newSingleThreadExecutor();
        data.addListener(this);
        data.refreshIfStale();
        updateText();
        if (w > 0) load();
        handler.postDelayed(tick, 60_000);
        Choreographer.getInstance().postFrameCallback(this);
    }

    void stop() {
        if (!running) return;
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        data.removeListener(this);
        worker.shutdownNow();
    }

    @Override public void onData() {
        updateText();
    }

    private void updateText() {
        long now = System.currentTimeMillis();
        minute = now / 60_000;
        time = Ui.clock(now);
        date = Ui.dateCaps(now);
        HomeData.Weather wx = data.weather;
        boolean has = !Double.isNaN(wx.temp);
        temp = has ? HomeData.degrees(wx.temp) : "";
        weather = has && wx.code >= 0 ? Symbols.weather(wx.code, wx.isDay) : null;
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        timePaint = Ui.numbers(Ui.displayMedium, 150 * k, 0xFFFFFFFF);
        timePaint.setLetterSpacing(-0.02f);
        timePaint.setShadowLayer(24 * k, 0, 2 * k, 0x66000000);
        infoPaint = Ui.text(Ui.semibold, 26 * k, 0xEBFFFFFF);
        infoPaint.setLetterSpacing(0.14f);
        infoPaint.setShadowLayer(16 * k, 0, 1 * k, 0x80000000);
        // Darker toward the bottom-left corner, where the text sits; the rest of the photo stays as it is.
        scrim.setShader(new RadialGradient(0, h, w * 0.7f, new int[] {0x9E000000, 0x4D000000, 0},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP));
        if (running && current == null && !loading) load();
    }

    /** Decodes the next photo in the background, cropped to the screen's shape and 10% larger than it. */
    private void load() {
        if (loading || photos.length == 0 || w == 0) return;
        loading = true;
        final String path = "backgrounds/" + photos[next];
        next = (next + 1) % photos.length;
        final int pw = Math.round(w * (1 + ZOOM)), ph = Math.round(h * (1 + ZOOM));
        final Context c = getContext();
        worker.execute(() -> {
            Bitmap b = decode(c, path, pw, ph);
            handler.post(() -> {
                loading = false;
                if (!running || b == null) {
                    if (b == null) AppLog.w("screensaver photo not readable: " + path);
                    return;
                }
                Slide s = new Slide(b, count++);
                if (current == null) {
                    s.at = SystemClock.uptimeMillis();
                    current = s;
                } else {
                    prepared = s;
                }
            });
        });
    }

    private static Bitmap decode(Context c, String path, int pw, int ph) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = c.getAssets().open(path)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= pw && o.outHeight / (sample * 2) >= ph) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap src;
            try (InputStream in = c.getAssets().open(path)) {
                src = BitmapFactory.decodeStream(in, null, o);
            }
            if (src == null) return null;
            Bitmap out = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
            float scale = Math.max((float) pw / src.getWidth(), (float) ph / src.getHeight());
            float sw = src.getWidth() * scale, sh = src.getHeight() * scale;
            new Canvas(out).drawBitmap(src, null, new RectF((pw - sw) / 2, (ph - sh) / 2, (pw + sw) / 2, (ph + sh) / 2),
                    new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
            src.recycle();
            return Backdrop.gpu(out);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        long now = SystemClock.uptimeMillis();
        if (current != null) {
            long shown = now - current.at;
            if (incoming == null && prepared == null && !loading && shown > SHOW_MS - PREPARE_MS) load();
            if (incoming == null && prepared != null && shown >= SHOW_MS) {
                incoming = prepared;
                prepared = null;
                incoming.at = now;
            }
            if (incoming != null && now - incoming.at >= FADE_MS) {
                current.image.recycle();
                current = incoming;
                incoming = null;
            }
        }
        if (System.currentTimeMillis() / 60_000 != minute) updateText();
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(0xFF000000);
        if (timePaint == null) return;
        long now = SystemClock.uptimeMillis();
        if (current != null) drawSlide(c, current, now, 1);
        if (incoming != null) drawSlide(c, incoming, now, ease((now - incoming.at) / (float) FADE_MS));
        c.drawRect(screen, scrim);

        // The text drifts slowly (a few pixels over 10 minutes) so nothing stays still on the panel.
        double phase = System.currentTimeMillis() / 1000.0 / DRIFT_S * 2 * Math.PI;
        float x = SIDE * k + (float) Math.sin(phase) * 10 * k, base = BOTTOM * k + (float) Math.cos(phase) * 6 * k;
        float cap = Ui.cap(infoPaint);
        c.drawText(date, x, base, infoPaint);
        if (!temp.isEmpty()) {
            float ix = x + infoPaint.measureText(date) + 24 * k, mid = base - cap / 2;
            c.drawCircle(ix, mid, 3 * k, dot);
            ix += 24 * k;
            if (weather != null) {
                Symbols.draw(getContext(), c, weather, ix + 16 * k, mid, 30 * k, 0xEBFFFFFF);
                ix += 40 * k;
            }
            c.drawText(temp, ix, base, infoPaint);
        }
        c.drawText(time, x - Ui.leftBearing(timePaint, time), base - cap - 36 * k, timePaint);

        // Opens from black.
        float open = ease((now - openedAt) / (float) OPEN_MS);
        if (open < 1) {
            veil.setAlpha(Math.round(255 * (1 - open)));
            c.drawRect(screen, veil);
        }
    }

    /** Ken Burns: the photo zooms in or out and pans across its whole time on screen, at a steady pace. */
    private void drawSlide(Canvas c, Slide s, long now, float alpha) {
        float t = Ui.clamp((now - s.at) / (float) (SHOW_MS + FADE_MS));
        float bw = s.image.getWidth(), bh = s.image.getHeight(), min = Math.max(w / bw, h / bh);
        float sc = s.zoomIn ? min + (1 - min) * t : 1 - (1 - min) * t;
        float rx = (bw * sc - w) / 2, ry = (bh * sc - h) / 2;
        matrix.setScale(sc, sc);
        matrix.postTranslate(-rx + s.panX * rx * t, -ry + s.panY * ry * t);
        photoPaint.setAlpha(Math.round(255 * alpha));
        c.drawBitmap(s.image, matrix, photoPaint);
    }

    private static float ease(float t) {
        t = Ui.clamp(t);
        return t * t * (3 - 2 * t);
    }
}
