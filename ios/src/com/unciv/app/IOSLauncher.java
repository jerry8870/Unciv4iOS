package com.unciv.app;

import com.badlogic.gdx.backends.iosrobovm.IOSApplication;
import com.badlogic.gdx.backends.iosrobovm.IOSApplicationConfiguration;
import com.badlogic.gdx.graphics.glutils.HdpiMode;
import org.robovm.apple.foundation.NSAutoreleasePool;
import org.robovm.apple.uikit.UIApplication;

public final class IOSLauncher extends IOSApplication.Delegate {
    @Override
    protected IOSApplication createApplication() {
        IOSPlatform.configure();
        return new UncivIOSApplication(new IOSGame(), createConfiguration());
    }

    public static IOSApplicationConfiguration createConfiguration() {
        IOSApplicationConfiguration config = new IOSApplicationConfiguration();
        config.preventScreenDimming = true;
        config.orientationLandscape = true;
        config.orientationPortrait = true;
        config.preferredFramesPerSecond = 60;
        config.useAccelerometer = false;
        config.useCompass = false;
        config.useAudio = true;
        config.allowIpod = true;
        config.overrideRingerSwitch = false;
        config.statusBarVisible = false;
        config.hideHomeIndicator = true;
        config.hdpiMode = HdpiMode.Logical;
        return config;
    }

    public static void main(String[] argv) {
        NSAutoreleasePool pool = new NSAutoreleasePool();
        UIApplication.main(argv, null, IOSLauncher.class);
        pool.close();
    }
}
