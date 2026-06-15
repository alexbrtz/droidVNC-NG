package net.christianbeier.droidvnc_ng;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.view.ViewConfiguration;

import androidx.annotation.RequiresApi;

/**
 * Wraps all GestureDescription / dispatchGesture usage (API 24+) in a separate
 * class so that InputService can be loaded on API 23 without VerifyError.
 */
@RequiresApi(api = Build.VERSION_CODES.N)
class GestureHelper {

    private static class GestureCallback extends AccessibilityService.GestureResultCallback {
        boolean mCompleted = true;

        @Override
        public synchronized void onCompleted(GestureDescription gestureDescription) {
            mCompleted = true;
        }

        @Override
        public synchronized void onCancelled(GestureDescription gestureDescription) {
            mCompleted = true;
        }
    }

    private final AccessibilityService mService;
    private final GestureCallback mGestureCallback = new GestureCallback();

    private Path mPath;
    private long mLastGestureStartTime;

    GestureHelper(AccessibilityService service) {
        mService = service;
    }

    void startGesture(int x, int y) {
        mPath = new Path();
        mPath.moveTo(x, y);
        mLastGestureStartTime = System.currentTimeMillis();
    }

    void continueGesture(int x, int y) {
        mPath.lineTo(x, y);
    }

    void endGesture(int x, int y) {
        mPath.lineTo(x, y);
        long duration = System.currentTimeMillis() - mLastGestureStartTime;
        if (duration == 0) duration = 1;
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(mPath, 0, duration);
        mService.dispatchGesture(
                new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    void longPress(int x, int y) {
        mService.dispatchGesture(createClick(x, y,
                ViewConfiguration.getTapTimeout() + ViewConfiguration.getLongPressTimeout()),
                null, null);
    }

    void scroll(int x, int y, int scrollAmount) {
        if (!mGestureCallback.mCompleted) return;
        mGestureCallback.mCompleted = false;
        mService.dispatchGesture(
                createSwipe(x, y, x, y - scrollAmount, ViewConfiguration.getScrollDefaultDelay()),
                mGestureCallback, null);
    }

    private static GestureDescription createClick(int x, int y, int duration) {
        Path p = new Path();
        p.moveTo(x, y);
        return new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, duration))
                .build();
    }

    private static GestureDescription createSwipe(int x1, int y1, int x2, int y2, int duration) {
        Path p = new Path();
        p.moveTo(Math.max(x1, 0), Math.max(y1, 0));
        p.lineTo(Math.max(x2, 0), Math.max(y2, 0));
        return new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, duration))
                .build();
    }
}
