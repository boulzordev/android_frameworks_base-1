/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.fingerprint;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.IBiometricPromptReceiver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

import com.android.internal.os.SomeArgs;
import com.android.internal.util.omni.CustomFingerprintDialog;
import com.android.systemui.R;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SystemUI;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;

import dalvik.system.PathClassLoader;

import java.lang.reflect.Constructor;

public class CustomFingerprintDialogImpl extends SystemUI implements CommandQueue.Callbacks {
    private static final String TAG = "CustomFingerprintDialogImpl";
    private static final boolean DEBUG = true;

    protected static final int MSG_SHOW_DIALOG = 1;
    protected static final int MSG_FINGERPRINT_AUTHENTICATED = 2;
    protected static final int MSG_FINGERPRINT_HELP = 3;
    protected static final int MSG_FINGERPRINT_ERROR = 4;
    protected static final int MSG_HIDE_DIALOG = 5;
    protected static final int MSG_BUTTON_NEGATIVE = 6;
    protected static final int MSG_USER_CANCELED = 7;
    protected static final int MSG_BUTTON_POSITIVE = 8;
    protected static final int MSG_CLEAR_MESSAGE = 9;

    private WindowManager mWindowManager;
    private IBiometricPromptReceiver mReceiver;
    private boolean mDialogShowing;
    //private CustomFingerprintDialog mDialogView;
    private CustomFingerprintDialogView mDialogView;
    public View mTransparentIconView;
    private boolean mFingerOnSensor = false;
    private boolean mFingerOnView = false;
    private boolean mFpSensorPressing;
    private boolean mOnViewPressing;
    private int mTransparentIconSize;
    private boolean mTransparentIconShowing = false;

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MSG_SHOW_DIALOG:
                    handleShowDialog((SomeArgs) msg.obj);
                    break;
                case MSG_FINGERPRINT_AUTHENTICATED:
                    handleFingerprintAuthenticated();
                    break;
                case MSG_FINGERPRINT_HELP:
                    handleFingerprintHelp((String) msg.obj);
                    break;
                case MSG_FINGERPRINT_ERROR:
                    handleFingerprintError((String) msg.obj);
                    break;
                case MSG_HIDE_DIALOG:
                    handleHideDialog((Boolean) msg.obj);
                    break;
                case MSG_BUTTON_NEGATIVE:
                    handleButtonNegative();
                    break;
                case MSG_USER_CANCELED:
                    handleUserCanceled();
                    break;
                case MSG_BUTTON_POSITIVE:
                    handleButtonPositive();
                    break;
                case MSG_CLEAR_MESSAGE:
                    handleClearMessage();
                    break;
                case 100:
                    handleFingerprintAcquire(msg.arg1, msg.arg2);
                    return;
                case 101:
                    handleFingerprintEnroll();
                    return;
                case 102:
                    handleFingerprintAuthenticatedFail();
                    return;
            }
        }
    };

    @Override
    public void start() {
        if (!mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
            return;
        }
        getComponent(CommandQueue.class).addCallbacks(this);
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mDialogView = new CustomFingerprintDialogView(mContext, mHandler, this);


        mTransparentIconView = LayoutInflater.from(mContext).inflate(R.layout.op_fingerprint_icon, null);
        mTransparentIconView.setOnTouchListener(new OnTouchListener() {
                public boolean onTouch(View v, MotionEvent event) {
                    int action = event.getAction();
                    //if (FingerprintDialogImpl.DEBUG && (action == 1 || action == 0)) {
                        StringBuilder stringBuilder = new StringBuilder();
                        stringBuilder.append("onTouchTransparent: ");
                        stringBuilder.append(action);
                        stringBuilder.append(", mDialogShowing = ");
                        stringBuilder.append(mDialogShowing);
                        stringBuilder.append(", mTransparentIconShowing = ");
                        stringBuilder.append(mTransparentIconShowing);
                        Log.d("FingerprintDialogImpl", stringBuilder.toString());
                    //}
                    if (action == 0) {
                        mFingerOnView = true;
                        if (mDialogShowing && !mFpSensorPressing) {
                            mOnViewPressing = true;
                        //    mDialogView.mIconFlash.setVisibility(0);
                        //    mDialogView.mIconNormal.setVisibility(4); 
                        //    mDialogView.mIconDim.setVisibility(0);
                        //    mDialogView.mIconDim.setAlpha(1);
                            mDialogView.postTimeOutRunnable();
                            mDialogView.showFingerprintPressed();
                        }
                    } else if (action == 1) {
                        mOnViewPressing = false;
                        mFingerOnView = false;
                        //mIsFaceUnlocked = false;
                        updateTransparentIconLayoutParams(false);
                        //mDialogView.mIconFlash.setVisibility(4);
                        //mDialogView.mIconNormal.setVisibility(0); 
                        //mDialogView.mIconDim.setVisibility(4);
                        //mDialogView.mIconDim.setAlpha(0);
                        if (mTransparentIconShowing && !mDialogShowing) {
                            mWindowManager.removeViewImmediate(mTransparentIconView);
                            mTransparentIconShowing = false;
                        }
                        mDialogView.hideFingerprintPressed();
                    }
                    //handleQLTouchEvent(event);
                    return true;
                }
});
         mTransparentIconSize = mContext.getResources().getDimensionPixelSize(R.dimen.op_biometric_transparent_icon_size);
        //String customFingerprintDialogLib = mContext.getResources().getString(
        //        com.android.internal.R.string.config_customFingerprintDialogLib);

        //String customFingerprintDialogClass = mContext.getResources().getString(
        //        com.android.internal.R.string.config_customFingerprintDialogClass);

        //if (!customFingerprintDialogLib.isEmpty() && !customFingerprintDialogClass.isEmpty()) {
        //    try {
        //        PathClassLoader loader =  new PathClassLoader(customFingerprintDialogLib,
        //                getClass().getClassLoader());
//
        //        Class<?> klass = loader.loadClass(customFingerprintDialogClass);
        //        Constructor<?> constructor = klass.getConstructor(Context.class, Handler.class);
        //        mDialogView = (CustomFingerprintDialog) constructor.newInstance(
         //               mContext, mHandler);
         //       if(DEBUG) Log.d(TAG, "Custom fingerprint dialog view loaded");
         //   } catch (Exception e) {
        //        Log.w(TAG, "Could not instantiate custom fingerprint dialog view "
       //                 + customFingerprintDialogClass + " from class "
       //                 + customFingerprintDialogLib, e);
        //    }
       // }
    }

    @Override
    public void showCustomFingerprintDialog(Bundle bundle, IBiometricPromptReceiver receiver) {
        if (DEBUG) Log.d(TAG, "showCustomFingerprintDialog");
        // Remove these messages as they are part of the previous client
        mHandler.removeMessages(MSG_FINGERPRINT_ERROR);
        mHandler.removeMessages(MSG_FINGERPRINT_HELP);
        mHandler.removeMessages(MSG_FINGERPRINT_AUTHENTICATED);
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = bundle;
        args.arg2 = receiver;
        mHandler.obtainMessage(MSG_SHOW_DIALOG, args).sendToTarget();
    }

    @Override
    public void onFingerprintAuthenticated() {
        if (DEBUG) Log.d(TAG, "onFingerprintAuthenticated");
        mHandler.obtainMessage(MSG_FINGERPRINT_AUTHENTICATED).sendToTarget();
    }

    @Override
    public void onFingerprintHelp(String message) {
        if (DEBUG) Log.d(TAG, "onFingerprintHelp: " + message);
        mHandler.obtainMessage(MSG_FINGERPRINT_HELP, message).sendToTarget();
    }

    @Override
    public void onFingerprintError(String error) {
        if (DEBUG) Log.d(TAG, "onFingerprintError: " + error);
        mHandler.obtainMessage(MSG_FINGERPRINT_ERROR, error).sendToTarget();
    }

    @Override
    public void hideCustomFingerprintDialog() {
        if (DEBUG) Log.d(TAG, "hideFingerprintDialog");
        mHandler.obtainMessage(MSG_HIDE_DIALOG, false /* userCanceled */).sendToTarget();
    }

    private void handleShowDialog(SomeArgs args) {
        Bundle bundle = (Bundle) args.arg1;
        String authenticatedPkg = bundle.getString("key_fingerprint_package_name", "");
        if (authenticatedPkg != null) {
            mDialogView.setOwnerString(authenticatedPkg);
        }
        if (DEBUG) Log.d(TAG, "handleShowDialog, isAnimatingAway: "
                + mDialogView.isAnimatingAway());
        if (mDialogView.isAnimatingAway()) {
            mDialogView.forceRemove();
        } else if (mDialogShowing) {
            Log.w(TAG, "Dialog already showing");
            return;
        }
        mReceiver = (IBiometricPromptReceiver) args.arg2;
        mDialogView.setBundle((Bundle)args.arg1);
        mWindowManager.addView(mDialogView, mDialogView.getLayoutParams());
        mDialogShowing = true;
        this.mOnViewPressing = false;
        this.mFpSensorPressing = false;
        if (mTransparentIconShowing) {
            updateTransparentIconLayoutParams(false);
        } else {
            mTransparentIconShowing = true;
            mWindowManager.addView(mTransparentIconView, getIconLayoutParams());
        }
        mDialogShowing = true;
    }

    private void handleFingerprintAuthenticated() {
        if (DEBUG) Log.d(TAG, "handleFingerprintAuthenticated");
        mDialogView.announceForAccessibility(
                mContext.getResources().getText(
                        com.android.internal.R.string.fingerprint_authenticated));
        handleHideDialog(false /* userCanceled */);
    }

    private void handleFingerprintHelp(String message) {
        if (DEBUG) Log.d(TAG, "handleFingerprintHelp: " + message);
        mDialogView.showHelpMessage(message);
    }

    private void handleFingerprintError(String error) {
        if (DEBUG) Log.d(TAG, "handleFingerprintError: " + error);
        if (!mDialogShowing) {
            if (DEBUG) Log.d(TAG, "Dialog already dismissed");
            return;
        }
        mDialogView.showErrorMessage(error);
    }

    private void handleHideDialog(boolean userCanceled) {
        if (DEBUG) Log.d(TAG, "handleHideDialog, userCanceled: " + userCanceled);
        if (!mDialogShowing) {
            // This can happen if there's a race and we get called from both
            // onAuthenticated and onError, etc.
            Log.w(TAG, "Dialog already dismissed, userCanceled: " + userCanceled);
            return;
        }
        if (userCanceled && mReceiver != null) {
            try {
                mReceiver.onDialogDismissed(BiometricPrompt.DISMISSED_REASON_USER_CANCEL);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException when hiding dialog", e);
            }
        }
        mReceiver = null;
        mDialogShowing = false;
        mDialogView.startDismiss();
    }

    private void handleButtonNegative() {
        if (mReceiver == null) {
            Log.e(TAG, "Receiver is null");
            return;
        }
        try {
            mReceiver.onDialogDismissed(BiometricPrompt.DISMISSED_REASON_NEGATIVE);
        } catch (RemoteException e) {
            Log.e(TAG, "Remote exception when handling negative button", e);
        }
        handleHideDialog(false /* userCanceled */);
    }

    private void handleButtonPositive() {
        if (mReceiver == null) {
            Log.e(TAG, "Receiver is null");
            return;
        }
        try {
            mReceiver.onDialogDismissed(BiometricPrompt.DISMISSED_REASON_POSITIVE);
        } catch (RemoteException e) {
            Log.e(TAG, "Remote exception when handling positive button", e);
        }
        handleHideDialog(false /* userCanceled */);
    }

    private void handleClearMessage() {
        mDialogView.resetMessage();
    }

    private void handleUserCanceled() {
        handleHideDialog(true /* userCanceled */);
    }

    public void onFingerprintEventCallback(int acquireInfo, int vendorCode) {
        if (DEBUG) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("onFingerprintEventCallback: acquireInfo = ");
            stringBuilder.append(acquireInfo);
            stringBuilder.append(", vendorCode = ");
            stringBuilder.append(vendorCode);
            Log.d("FingerprintDialogImpl", stringBuilder.toString());
        }
        mHandler.obtainMessage(100, acquireInfo, vendorCode).sendToTarget();
    }

   private void handleFingerprintAcquire(int acquiredInfo, int vendorCode) {
        boolean isInterActive = KeyguardUpdateMonitor.getInstance(mContext).isDeviceInteractive();
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("handleFingerprintAcquire: acquireInfo = ");
        stringBuilder.append(acquiredInfo);
        stringBuilder.append(", onViewPressing = ");
        stringBuilder.append(mOnViewPressing);
        stringBuilder.append(", SensorPressing = ");
        stringBuilder.append(mFpSensorPressing);
        stringBuilder.append(", vendorCode = ");
        stringBuilder.append(vendorCode);
        stringBuilder.append(", interactive = ");
        stringBuilder.append(isInterActive);
        stringBuilder.append(", IconShow = ");
        stringBuilder.append(mTransparentIconShowing);
        stringBuilder.append(", dialogShowing = ");
        stringBuilder.append(mDialogShowing);
        Log.d("FingerprintDialogImpl", stringBuilder.toString());
        if (acquiredInfo == 6) {
            mFingerOnSensor = vendorCode == 0;
            if (DEBUG) {
                StringBuilder stringBuilder2 = new StringBuilder();
                stringBuilder2.append("handleFingerprintAcquire mFingerOnSensor = ");
                stringBuilder2.append(mFingerOnSensor);
                stringBuilder2.append(" mFingerOnView ");
                stringBuilder2.append(mFingerOnView);
                Log.d("FingerprintDialogImpl", stringBuilder2.toString());
            }
            if (!(mFingerOnSensor || mDialogShowing || mFingerOnView || !mTransparentIconShowing)) {
                mWindowManager.removeViewImmediate(mTransparentIconView);
                mTransparentIconShowing = false;
            }
        }
        if (acquiredInfo == 6 && vendorCode == 0) {
            mDialogView.removeTimeOutMessage();
        }
        if (acquiredInfo == 6 && vendorCode == 0 && !mOnViewPressing && mDialogShowing) {
            mFpSensorPressing = true;
            mDialogView.showFingerprintPressed();
        } else if (acquiredInfo == 6 && vendorCode == 1 && !mOnViewPressing && mFpSensorPressing) {
            updateTransparentIconLayoutParams(false);
            if (mTransparentIconShowing && !mDialogShowing) {
                mWindowManager.removeViewImmediate(mTransparentIconView);
                mTransparentIconShowing = false;
            }
            mFpSensorPressing = false;
            mDialogView.hideFingerprintPressed();
        }
    }

    private void handleFingerprintEnroll() {
        mDialogView.handleFpResultEvent();
    }

    private void handleFingerprintAuthenticatedFail() {
        mDialogView.handleFpResultEvent();
    }

    public void forceShowDialog(Bundle b, IBiometricPromptReceiver receiver) {
        mHandler.removeMessages(4);
        mHandler.removeMessages(3);
        mHandler.removeMessages(2);
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = b;
        args.arg2 = receiver;
        handleShowDialog(args);
    }

   private LayoutParams getIconLayoutParams() {
        LayoutParams lp = new LayoutParams(mTransparentIconSize, mTransparentIconSize, 2305, 16777480, -3);
        lp.privateFlags |= 16;
        lp.setTitle("FingerprintTransparentIcon");
        lp.gravity = 51;
        lp.x = mContext.getResources().getDimensionPixelSize(R.dimen.op_biometric_transparent_icon_location_x);
        lp.y = mContext.getResources().getDimensionPixelSize(R.dimen.op_biometric_transparent_icon_location_y);
        return lp;
    }

    public void updateTransparentIconLayoutParams(boolean expand) {
        Log.d("FingerprintDialogImpl", "updateTransparentIconLayoutParams - expand : " + expand + " - mTransparentIconShowing " + mTransparentIconShowing);
        if (mTransparentIconShowing) {
            int w;
            int h;
            int x;
            int y;
            int orientation;
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("updateTransparentIconLayoutParams: ");
            stringBuilder.append(expand);
            Log.d("FingerprintDialogImpl", stringBuilder.toString());
            LayoutParams lp = getIconLayoutParams();
            if (expand) {
                w = -1;
                h = -1;
                x = 0;
                y = 0;
                orientation = 1;
            } else {
                w = mTransparentIconSize;
                h = mTransparentIconSize;
                x = mContext.getResources().getDimensionPixelSize(R.dimen.op_biometric_transparent_icon_location_x);
                y = mContext.getResources().getDimensionPixelSize(R.dimen.op_biometric_transparent_icon_location_y);
                orientation = -1;
            }
            lp.width = w;
            lp.height = h;
            lp.x = x;
            lp.y = y;
            lp.screenOrientation = orientation;
            mWindowManager.updateViewLayout(mTransparentIconView, lp);
        }
    }

    public void updateTransparentIconVisibility(int visibility) {
        Log.e("FingerprintDialogImpl", "updateTransparentIconVisibility : visibility - " + visibility);
        //if (visibility != 8 || (!mOnViewPressing && !mFpSensorPressing)) {
            mTransparentIconView.setVisibility(visibility);
        //}
    }

    public void resetState() {
        mFpSensorPressing = false;
        mOnViewPressing = false;

    }
}
