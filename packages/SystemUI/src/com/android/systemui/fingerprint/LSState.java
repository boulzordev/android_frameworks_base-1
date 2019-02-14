package com.android.systemui.plugin;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.ViewGroup;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.statusbar.phone.FingerprintUnlockController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;

public class LSState {
    private static LSState sInstance;
    private final boolean DEBUG = true;
    private final String TAG = "LSState";
    private final int WHAT_UI_INIT = 1;
    private ViewGroup mContainer;
    private Context mContext;
    private FingerprintUnlockController mFingerprintUnlockControl;
    private boolean mInit = false;
    private boolean mIsFinishedScreenTuredOn = false;

    private Looper mNonUiLooper;
    private StatusBar mPhonstatusBar;
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private KeyguardUpdateMonitor mUpdateMonitor;

    public static synchronized LSState getInstance() {
        LSState lSState;
        synchronized (LSState.class) {
            if (sInstance == null) {
                sInstance = new LSState();
            }
            lSState = sInstance;
        }
        return lSState;
    }

    LSState() {
    }

    public void init(Context context, ViewGroup container, StatusBar phoneStatusBar) {
        synchronized (this) {
            if (!this.mInit) {
                Log.d("LSState", "init");
                this.mContainer = container;
                this.mUpdateMonitor = KeyguardUpdateMonitor.getInstance(context);
                this.mPhonstatusBar = phoneStatusBar;
                boolean bootCmp = this.mUpdateMonitor.hasBootCompleted();
                this.mInit = true;
                this.mContext = context;
                getNonUILooper();
            }
        }
    }

    public void onFingerprintStartedGoingToSleep() {
        this.mIsFinishedScreenTuredOn = false;
    }

    public void onWallpaperChange(Bitmap bitmap) {
        this.mPhonstatusBar.onWallpaperChange(bitmap);
    }

    public Looper getNonUILooper() {
        Looper looper;
        synchronized (this) {
            if (this.mNonUiLooper == null) {
                HandlerThread handerTread = new HandlerThread("LSState thread");
                handerTread.start();
                this.mNonUiLooper = handerTread.getLooper();
            }
            looper = this.mNonUiLooper;
        }
        return looper;
    }

    public void setStatusBarKeyguardViewManager(StatusBarKeyguardViewManager statusBarKeyguardViewManager) {
        this.mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
    }

    public StatusBarKeyguardViewManager getStatusBarKeyguardViewManager() {
        return this.mStatusBarKeyguardViewManager;
    }

    public StatusBar getPhoneStatusBar() {
        return this.mPhonstatusBar;
    }

    public ViewGroup getContainer() {
        return this.mContainer;
    }

    public void setFingerprintUnlockControl(FingerprintUnlockController fingerprintUnlockController) {
        this.mFingerprintUnlockControl = fingerprintUnlockController;
    }

    public FingerprintUnlockController getFingerprintUnlockControl() {
        return this.mFingerprintUnlockControl;
    }
}
