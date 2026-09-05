package com.unciv.app;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.backends.iosrobovm.IOSApplication;
import com.badlogic.gdx.backends.iosrobovm.IOSApplicationConfiguration;
import com.badlogic.gdx.backends.iosrobovm.IOSAudio;

public class UncivIOSApplication extends IOSApplication {
    private final IOSRuntimeFeatures runtimeFeatures;

    public UncivIOSApplication(ApplicationListener listener, IOSApplicationConfiguration configuration) {
        this(listener, configuration, DefaultIOSRuntimeFeatures.INSTANCE);
    }

    public UncivIOSApplication(ApplicationListener listener, IOSApplicationConfiguration configuration,
                               IOSRuntimeFeatures runtimeFeatures) {
        super(listener, configuration);
        this.runtimeFeatures = runtimeFeatures;
    }

    @Override protected IOSAudio createAudio(IOSApplicationConfiguration configuration) {
        if (!configuration.useAudio) return super.createAudio(configuration);
        IOSAudio audio = runtimeFeatures.createAudio(configuration);
        return audio != null ? audio : super.createAudio(configuration);
    }
}
