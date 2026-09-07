package com.unciv.app;

import com.badlogic.gdx.backends.iosrobovm.IOSApplication;
import com.badlogic.gdx.backends.iosrobovm.IOSGraphics;
import com.badlogic.gdx.backends.iosrobovm.IOSUIViewController;
import com.unciv.utils.Display;
import org.robovm.apple.coregraphics.CGRect;
import org.robovm.apple.foundation.NSNotificationCenter;
import org.robovm.apple.foundation.NSObject;
import org.robovm.apple.uikit.UIInterfaceOrientationMask;
import org.robovm.apple.uikit.UIWindow;

/** Shared by normal and instrumented builds so their rotation and inset behavior stays identical. */
public class UncivIOSViewController extends IOSUIViewController {
    private final IOSDisplay display;
    private NSObject keyboardObserver;

    public UncivIOSViewController(IOSApplication application, IOSGraphics graphics) {
        super(application, graphics);
        display = (IOSDisplay) Display.INSTANCE.getPlatform();
        display.attach(this);
    }

    @Override public UIInterfaceOrientationMask getSupportedInterfaceOrientations() {
        return display == null ? UIInterfaceOrientationMask.Landscape : display.getOrientationMask();
    }

    @Override public void viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews();
        if (display != null) display.updateGeometry();
    }

    @Override public void viewSafeAreaInsetsDidChange() {
        super.viewSafeAreaInsetsDidChange();
        if (display != null) display.updateGeometry();
    }

    @Override public void viewDidAppear(boolean animated) {
        super.viewDidAppear(animated);
        if (display != null) display.updateGeometry();
        display.restoreOrientation();
        if (keyboardObserver == null) {
            keyboardObserver = UIWindow.Notifications.observeKeyboardDidChangeFrame(animation -> {
                UIWindow window = getView().getWindow();
                if (window == null) return;
                CGRect frame = getView().convertRectFromCoordinateSpace(
                    animation.getEndFrame(), window.getScreen().getCoordinateSpace());
                boolean overlaps = frame.getWidth() > 0 && frame.getHeight() > 0
                    && frame.getX() < getView().getBounds().getWidth() && frame.getMaxX() > 0
                    && frame.getY() < getView().getBounds().getHeight() && frame.getMaxY() > 0;
                display.updateKeyboard(overlaps ? (float)Math.max(0, frame.getY()) : null);
            });
        }
    }

    @Override public void viewDidDisappear(boolean animated) {
        if (keyboardObserver != null) {
            NSNotificationCenter.getDefaultCenter().removeObserver(keyboardObserver);
            keyboardObserver = null;
        }
        display.updateKeyboard(null);
        super.viewDidDisappear(animated);
    }
}
