package com.unciv.app;

import com.badlogic.gdx.backends.iosrobovm.IOSApplication;
import com.badlogic.gdx.backends.iosrobovm.IOSApplicationConfiguration;
import com.badlogic.gdx.backends.iosrobovm.IOSGraphics;
import com.badlogic.gdx.backends.iosrobovm.IOSUIViewController;
import org.robovm.apple.foundation.NSAutoreleasePool;
import org.robovm.apple.uikit.UIApplication;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class IOSUiTestLauncher extends IOSApplication.Delegate {
    @Override
    protected IOSApplication createApplication() {
        verifyRequiredMessageDigests();
        IOSPlatform.configure();
        IOSApplicationConfiguration config = IOSLauncher.createConfiguration();
        return new UncivIOSApplication(new IOSGame(), config) {
            @Override
            protected IOSUIViewController createUIViewController(IOSGraphics graphics) {
                return new IOSUiTestViewController(this, graphics);
            }
        };
    }

    private static void verifyRequiredMessageDigests() {
        verifyRequiredMessageDigest("SHA-1", 20);
        verifyRequiredMessageDigest("SHA-256", 32);
    }

    private static void verifyRequiredMessageDigest(String algorithm, int expectedLength) {
        try {
            byte[] digest = MessageDigest.getInstance(algorithm).digest(new byte[0]);
            if (digest.length != expectedLength) {
                throw new IllegalStateException("Invalid " + algorithm + " digest length " + digest.length);
            }
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Missing required MessageDigest " + algorithm, ex);
        }
    }

    public static void main(String[] argv) {
        NSAutoreleasePool pool = new NSAutoreleasePool();
        UIApplication.main(argv, null, IOSUiTestLauncher.class);
        pool.close();
    }
}
