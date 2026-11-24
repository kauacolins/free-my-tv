package io.freemytv;

import android.service.dreams.DreamService;

/**
 * The Free My TV screensaver (enabled in Free My TV mode via `settings put secure screensaver_components`). The system
 * still decides when it starts and when the TV turns off (e.g. after 10 min idle, then 2 h later), as before: the app
 * doesn't change those timings. Any key exits. With apps_only and your own photos, PhotoDream shows them; otherwise
 * DreamView shows the recommendations' photos.
 */
public class Screensaver extends DreamService {
    private DreamView view;
    private PhotoDream photos;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Watchdog.install(this);
        Ui.init(this);
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        if (PhotoDream.available(this)) {
            photos = new PhotoDream(this);
            setContentView(photos);
        } else {
            view = new DreamView(this);
            setContentView(view);
        }
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        if (view != null) view.start();
        if (photos != null) photos.start();
    }

    @Override public void onDreamingStopped() {
        if (view != null) view.stop();
        if (photos != null) photos.stop();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        if (view != null) view.stop();
        if (photos != null) photos.stop();
        super.onDetachedFromWindow();
    }
}
