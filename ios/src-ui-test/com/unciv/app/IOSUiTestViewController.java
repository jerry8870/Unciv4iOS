package com.unciv.app;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.backends.iosrobovm.IOSApplication;
import com.badlogic.gdx.backends.iosrobovm.IOSGraphics;
import com.badlogic.gdx.backends.iosrobovm.IOSUIViewController;
import com.unciv.UncivGame;
import com.unciv.ui.audio.MusicTrackController;
import org.robovm.apple.coregraphics.CGRect;
import org.robovm.apple.foundation.NSRunLoop;
import org.robovm.apple.foundation.NSRunLoopMode;
import org.robovm.apple.foundation.NSTimer;
import org.robovm.apple.uikit.UIColor;
import org.robovm.apple.uikit.UILabel;

/** Exposes non-interactive accessibility markers in the explicit UI-test build only. */
final class IOSUiTestViewController extends IOSUIViewController {
    static final String READY_IDENTIFIER = "unciv.ui-test.ready";
    static final String SCREEN_IDENTIFIER_PREFIX = "unciv.ui-test.screen.";
    static final String HOST_VISIBLE_IDENTIFIER = "unciv.ui-test.host.visible";
    static final String HOST_COVERED_IDENTIFIER = "unciv.ui-test.host.covered";

    private static final double SCREEN_REFRESH_INTERVAL_SECONDS = 0.2;

    private final IOSApplication application;
    private UILabel readyMarker;
    private UILabel screenMarker;
    private UILabel hostMarker;
    private NSTimer screenMarkerTimer;
    private String currentScreenIdentifier;
    private String currentHostIdentifier;
    private boolean audioProbeStarted;

    IOSUiTestViewController(IOSApplication application, IOSGraphics graphics) {
        super(application, graphics);
        this.application = application;
    }

    @Override
    public void viewDidAppear(boolean animated) {
        super.viewDidAppear(animated);
        installMarkersIfNeeded();
        refreshMarkers();
        startScreenMarkerTimer();
    }

    @Override
    public void viewWillDisappear(boolean animated) {
        setHostMarker("covered", HOST_COVERED_IDENTIFIER);
        super.viewWillDisappear(animated);
    }

    @Override
    public void viewDidDisappear(boolean animated) {
        super.viewDidDisappear(animated);
        stopScreenMarkerTimer();
    }

    private void installMarkersIfNeeded() {
        if (readyMarker == null) {
            readyMarker = newMarker("Unciv ready", READY_IDENTIFIER);
            getView().addSubview(readyMarker);
        }
        if (screenMarker == null) {
            screenMarker = newMarker("unavailable", SCREEN_IDENTIFIER_PREFIX + "unavailable");
            getView().addSubview(screenMarker);
        }
        if (hostMarker == null) {
            hostMarker = newMarker("visible", HOST_VISIBLE_IDENTIFIER);
            getView().addSubview(hostMarker);
        }
    }

    private UILabel newMarker(String text, String identifier) {
        UILabel marker = new UILabel(new CGRect(0, 0, 1, 1));
        marker.setText(text);
        marker.setTextColor(UIColor.clear());
        marker.setBackgroundColor(UIColor.clear());
        marker.setUserInteractionEnabled(false);
        marker.setAccessibilityIdentifier(identifier);
        return marker;
    }

    private void setHostMarker(String text, String identifier) {
        if (hostMarker == null) return;
        if (identifier.equals(currentHostIdentifier)) return;
        hostMarker.setText(text);
        hostMarker.setAccessibilityIdentifier(identifier);
        currentHostIdentifier = identifier;
    }

    private void startScreenMarkerTimer() {
        if (screenMarkerTimer != null && screenMarkerTimer.isValid()) return;

        screenMarkerTimer = new NSTimer(
            SCREEN_REFRESH_INTERVAL_SECONDS,
            true,
            timer -> refreshMarkers(),
            false
        );
        screenMarkerTimer.setTolerance(SCREEN_REFRESH_INTERVAL_SECONDS / 2);
        NSRunLoop.getMain().addTimer(NSRunLoopMode.Common, screenMarkerTimer);
    }

    private void stopScreenMarkerTimer() {
        if (screenMarkerTimer == null) return;
        screenMarkerTimer.invalidate();
        screenMarkerTimer = null;
    }

    private void refreshMarkers() {
        if (getPresentedViewController() == null) {
            setHostMarker("visible", HOST_VISIBLE_IDENTIFIER);
        } else {
            setHostMarker("covered", HOST_COVERED_IDENTIFIER);
        }
        refreshScreenMarker();
        if (!audioProbeStarted && Gdx.audio != null) {
            audioProbeStarted = true;
            Gdx.app.postRunnable(this::verifyAudio);
        }
    }

    private void verifyAudio() {
        Sound mp3 = null;
        String result = "failed";
        try {
            mp3 = Gdx.audio.newSound(Gdx.files.internal("sounds/policy.mp3"));
            if (mp3.play(0.05f) < 0) throw new IllegalStateException("MP3 playback did not start");
            MusicTrackController track = new MusicTrackController(0f, 1f);
            track.load(Gdx.files.internal("sounds/cityAncient.ogg"), null, null);
            if (track.getState() != MusicTrackController.State.Error) {
                track.clear();
                throw new IllegalStateException("Unsupported OGG failure was not contained");
            }
            track.clear();
            result = "passed";
        } catch (RuntimeException ex) {
            Gdx.app.error("IOS_AUDIO_PROBE", "Audio acceptance failed", ex);
        }
        final Sound playingSound = mp3;
        NSRunLoop.getMain().addTimer(NSRunLoopMode.Common,
            new NSTimer(1.0, false, timer -> {
                if (playingSound != null) playingSound.dispose();
            }, false));
        getView().addSubview(newMarker(result, "unciv.ui-test.audio." + result));
    }

    private void refreshScreenMarker() {
        String screenName = "unavailable";
        if (application.getApplicationListener() instanceof UncivGame) {
            UncivGame game = (UncivGame)application.getApplicationListener();
            if (game.getScreen() != null) screenName = game.getScreen().getClass().getSimpleName();
        }

        String identifier = SCREEN_IDENTIFIER_PREFIX + screenName;
        if (identifier.equals(currentScreenIdentifier)) return;

        screenMarker.setText(screenName);
        screenMarker.setAccessibilityIdentifier(identifier);
        currentScreenIdentifier = identifier;
    }

}
