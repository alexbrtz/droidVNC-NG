package net.christianbeier.droidvnc_ng;

/*
 * DroidVNC-NG InputService that binds to the Android a11y API and posts input events sent by the native backend to Android.
 *
 * Its original version was copied from https://github.com/anyvnc/anyvnc/blob/master/apps/ui/android/src/com/anyvnc/AnyVncAccessibilityService.java at
 * f32015d9d29d2d022217f52a99f676ace90cc29e.
 *
 * Original author is Tobias Junghans <tobydox@veyon.io>
 *
 * Licensed under GPL-2.0 as per https://github.com/anyvnc/anyvnc/blob/master/COPYING.
 *
 * Swipe fixes and gesture handling by Christian Beier <info@christianbeier.net>.
 *
 * Root-based input fallback for API < 24 by alexbrtz.
 */

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;

public class InputService extends AccessibilityService {

    private static final String TAG = "InputService";

    private static InputService instance;

    private Handler mMainHandler;

    private boolean mIsButtonOneDown;

    private boolean mIsKeyCtrlDown;
    private boolean mIsKeyAltDown;
    private boolean mIsKeyShiftDown;
    private boolean mIsKeyDelDown;
    private boolean mIsKeyEscDown;

    private float mScaling;

    /** API 24+ gesture dispatcher — null on API 23 */
    private GestureHelper mGestureHelper;

    /** Root-path gesture tracking — static so it works without service binding */
    private static float sScaling = 1.0f;
    private static boolean sIsButtonOneDown = false;
    private static int sRootStartX, sRootStartY;
    private static long sLastGestureStartTime;

    /** Persistent root shell — must be declared before sHasRoot */
    private static Process      sSuShell;
    private static PrintWriter  sSuWriter;
    private static final Object sSuLock = new Object();

    /** Whether root shell access is available (checked once at startup) */
    private static boolean sHasRoot = checkRoot();


    // ── AccessibilityService lifecycle ────────────────────────────────────────

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        mMainHandler = new Handler(instance.getMainLooper());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mGestureHelper = new GestureHelper(this);
            Log.i(TAG, "onServiceConnected: using GestureDescription (API 24+)");
        } else {
            Log.i(TAG, "onServiceConnected: API 23, root=" + sHasRoot);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        Log.i(TAG, "onDestroy");
    }


    // ── Public API ────────────────────────────────────────────────────────────

    public static boolean isEnabled() {
        return instance != null;
    }

    public static boolean setScaling(float scaling) {
        sScaling = scaling;
        if (instance != null) instance.mScaling = scaling;
        return true;
    }

    @SuppressWarnings("unused")
    public static void onPointerEvent(int buttonMask, int x, int y, long client) {
        try {
            // API 23 root path: works even without service binding
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                x /= sScaling;
                y /= sScaling;

                if ((buttonMask & (1 << 0)) != 0 && !sIsButtonOneDown) {
                    sIsButtonOneDown = true;
                    sRootStartX = x; sRootStartY = y;
                    sLastGestureStartTime = System.currentTimeMillis();
                }
                if ((buttonMask & (1 << 0)) == 0 && sIsButtonOneDown) {
                    sIsButtonOneDown = false;
                    long duration = System.currentTimeMillis() - sLastGestureStartTime;
                    if (sRootStartX == x && sRootStartY == y) {
                        execRoot("input tap " + x + " " + y);
                    } else {
                        execRoot("input swipe " + sRootStartX + " " + sRootStartY
                                + " " + x + " " + y + " " + Math.max(duration, 1));
                    }
                }
                if ((buttonMask & (1 << 2)) != 0) {
                    execRoot("input swipe " + x + " " + y + " " + x + " " + y + " 600");
                }
                if ((buttonMask & (1 << 3)) != 0) {
                    execRoot("input swipe " + x + " " + y + " " + x + " " + Math.max(y - 400, 0) + " 300");
                }
                if ((buttonMask & (1 << 4)) != 0) {
                    execRoot("input swipe " + x + " " + y + " " + x + " " + (y + 400) + " 300");
                }
                return;
            }

            // API 24+ path: needs service instance for GestureDescription
            if (instance == null) return;

            x /= instance.mScaling;
            y /= instance.mScaling;

            if ((buttonMask & (1 << 0)) != 0 && !instance.mIsButtonOneDown) {
                instance.mIsButtonOneDown = true;
                instance.startGesture(x, y);
            }
            if ((buttonMask & (1 << 0)) != 0 && instance.mIsButtonOneDown) {
                instance.continueGesture(x, y);
            }
            if ((buttonMask & (1 << 0)) == 0 && instance.mIsButtonOneDown) {
                instance.mIsButtonOneDown = false;
                instance.endGesture(x, y);
            }
            if ((buttonMask & (1 << 2)) != 0) {
                instance.longPress(x, y);
            }
            if ((buttonMask & (1 << 3)) != 0 || (buttonMask & (1 << 4)) != 0) {
                DisplayMetrics dm = new DisplayMetrics();
                ((WindowManager) instance.getApplicationContext()
                        .getSystemService(Context.WINDOW_SERVICE))
                        .getDefaultDisplay().getRealMetrics(dm);
                int amount = (buttonMask & (1 << 3)) != 0
                        ? -dm.heightPixels / 2 : dm.heightPixels / 2;
                instance.scroll(x, y, amount);
            }

        } catch (Exception e) {
            Log.e(TAG, "onPointerEvent: failed: " + Log.getStackTraceString(e));
        }
    }

    public static void onKeyEvent(int down, long keysym, long client) {
        Log.d(TAG, "onKeyEvent: keysym " + keysym + " down " + down + " by client " + client);
        try {
            if (keysym == 0xFFE3) instance.mIsKeyCtrlDown  = down != 0;
            if (keysym == 0xFFE9 || keysym == 0xFF7E) instance.mIsKeyAltDown  = down != 0;
            if (keysym == 0xFFE1) instance.mIsKeyShiftDown = down != 0;
            if (keysym == 0xFFFF) instance.mIsKeyDelDown   = down != 0;
            if (keysym == 0xFF1B) instance.mIsKeyEscDown   = down != 0;

            if (instance.mIsKeyCtrlDown && instance.mIsKeyAltDown && instance.mIsKeyDelDown) {
                Log.i(TAG, "onKeyEvent: got Ctrl-Alt-Del");
                instance.mMainHandler.post(MainService::togglePortraitInLandscapeWorkaround);
            }

            if (instance.mIsKeyCtrlDown && instance.mIsKeyShiftDown && instance.mIsKeyEscDown) {
                Log.i(TAG, "onKeyEvent: got Ctrl-Shift-Esc");
                instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
            }

            if (keysym == 0xFF50 && down != 0) {
                instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            }

            if (keysym == 0xFF1B && down != 0) {
                instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            }

        } catch (Exception e) {
            Log.e(TAG, "onKeyEvent: failed: " + e);
        }
    }

    public static void onCutText(String text, long client) {
        Log.d(TAG, "onCutText: text '" + text + "' by client " + client);
        try {
            instance.mMainHandler.post(() ->
                    ((ClipboardManager) instance.getSystemService(Context.CLIPBOARD_SERVICE))
                            .setPrimaryClip(ClipData.newPlainText(text, text)));
        } catch (Exception e) {
            Log.e(TAG, "onCutText: failed: " + e);
        }
    }


    // ── Gesture dispatch (API 24+ only — root path is handled in onPointerEvent) ──

    private void startGesture(int x, int y)    { mGestureHelper.startGesture(x, y); }
    private void continueGesture(int x, int y) { mGestureHelper.continueGesture(x, y); }
    private void endGesture(int x, int y)      { mGestureHelper.endGesture(x, y); }
    private void longPress(int x, int y)       { mGestureHelper.longPress(x, y); }
    private void scroll(int x, int y, int amt) { mGestureHelper.scroll(x, y, amt); }


    // ── Persistent root shell ─────────────────────────────────────────────────

    private static boolean checkRoot() {
        synchronized (sSuLock) {
            try {
                sSuShell  = Runtime.getRuntime().exec("su");
                sSuWriter = new PrintWriter(new OutputStreamWriter(sSuShell.getOutputStream()));
                sSuWriter.println("echo ok");
                sSuWriter.flush();
                Log.i(TAG, "checkRoot: persistent su shell opened");
                return true;
            } catch (Exception e) {
                Log.w(TAG, "checkRoot: no root available: " + e);
                return false;
            }
        }
    }

    private static boolean isShellAlive() {
        if (sSuShell == null) return false;
        try {
            sSuShell.exitValue();
            return false; // exited
        } catch (IllegalThreadStateException e) {
            return true;  // still running
        }
    }

    private static void execRoot(String cmd) {
        synchronized (sSuLock) {
            try {
                if (!isShellAlive()) {
                    Log.w(TAG, "execRoot: shell died, reopening");
                    checkRoot();
                }
                sSuWriter.println(cmd);
                sSuWriter.flush();
            } catch (Exception e) {
                Log.e(TAG, "execRoot: failed '" + cmd + "': " + e);
            }
        }
    }
}
