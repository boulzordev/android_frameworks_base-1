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
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.IBiometricPromptReceiver;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Interpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.internal.telephony.IccCardConstants.State;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.internal.widget.LockPatternUtils;

import vendor.oneplus.hardware.display.V1_0.IOneplusDisplay;

/**
 * This class loads the view for the system-provided dialog. The view consists of:
 * Application Icon, Title, Subtitle, Description, Fingerprint Icon, Error/Help message area,
 * and positive/negative buttons.
 */
public class FingerprintDialogView extends LinearLayout {

    private static final String TAG = "FingerprintDialogView";

    private static final int ANIMATION_DURATION_SHOW = 250; // ms
    private static final int ANIMATION_DURATION_AWAY = 350; // ms

    private static final int STATE_NONE = 0;
    private static final int STATE_FINGERPRINT = 1;
    private static final int STATE_FINGERPRINT_ERROR = 2;
    private static final int STATE_FINGERPRINT_AUTHENTICATED = 3;

    private final IBinder mWindowToken = new Binder();
    private final Interpolator mLinearOutSlowIn;
    private final WindowManager mWindowManager;
    private final float mAnimationTranslationOffset;
    private final int mErrorColor;
    private final int mTextColor;
    private final int mFingerprintColor;

    private ViewGroup mLayout;
    private final TextView mErrorText;
    private Handler mHandler;
    private Bundle mBundle;
    private final LinearLayout mDialog;
    private int mLastState;
    private boolean mAnimatingAway;
    private boolean mWasForceRemoved;
    private FingerprintDialogImpl mDialogImpl;
    private IOneplusDisplay mDaemon = null;

    private boolean mShowDefaultDialog;
    private boolean mShowOnWindow;
    private boolean mShowingKeyguard;
    private boolean mShowingPressed;
    private final IBinder mSurfaceFlinger;
    private View mTransparentIconView;
    private PowerManager mPm;
    private boolean mGoingToSleep;
    private CircleImageView mIconDim;
    private CircleImageView mIconDisable;
    private CircleImageView mIconFlash;
    private CircleImageView mIconNormal;
    private boolean mIsKeyguardDone = false;
    private boolean mIsScreenOn;
    private boolean mIsScreenTurningOn;
    private final float mDisplayWidth;
    KeyguardUpdateMonitor mUpdateMonitor;
    //private boolean mFaceUnlocked;
    private boolean mDeviceInteractive = true;
    private String mOwnerString = "";
    private TextView mAodIndicationTextView;
    private int mAodMode = 0;
    private ViewGroup mDimLayout;
    private WindowManager.LayoutParams mPressedLayoutParams;
    private ViewGroup mPressedLayout;
    private LockPatternUtils mLockPatternUtils;
    private WindowManager.LayoutParams mDimLayoutParams;
    private boolean mDimOnWindow;
    private ImageView mDimView;
    private boolean mScreenOffAuthenticating = false;
    private SecurityMode mSecurityMode = SecurityMode.None;
    private final int OP_DISPLAY_AOD_MODE = 8;
    private final int OP_DISPLAY_APPLY_HIDE_AOD = 11;
    private final int OP_DISPLAY_NOTIFY_PRESS = 9;
    private final int OP_DISPLAY_SET_DIM = 10;
    private String WINDOW_FINGERPRINT_DIM_VIEW = "OPFingerprintVDDim";
    private String WINDOW_FINGERPRINT_HIGH_LIGHT_VIEW = "OPFingerprintVDpressed";
    private String WINDOW_FINGERPRINT_VIEW = "OPFingerprintView";

   KeyguardUpdateMonitorCallback mMonitorCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onScreenTurnedOff() {
            super.onScreenTurnedOff();
            Log.d("FingerprintDialogView", "onScreenTurnedOff");
            FingerprintDialogView.this.mIsScreenOn = false;
            FingerprintDialogView.this.mIsScreenTurningOn = false;
            if (FingerprintDialogView.this.mDialogImpl != null) {
                FingerprintDialogView.this.mDialogImpl.updateTransparentIconLayoutParams(false);
            }
            FingerprintDialogView.this.resetState();
            FingerprintDialogView.this.setDisplayAodMode(0);
            if (FingerprintDialogView.this.mIconDisable != null) {
                FingerprintDialogView.this.mIconDisable.setAlpha(0.6f);
            }
        }

        @Override
        public void onStartedGoingToSleep(int why) {
            super.onStartedGoingToSleep(why);
            FingerprintDialogView.this.mGoingToSleep = true;
        }

        @Override
        public void onFinishedGoingToSleep(int why) {
            super.onFinishedGoingToSleep(why);
            //FingerprintDialogView.this.mFaceUnlocked = false;
            FingerprintDialogView.this.mGoingToSleep = false;
            FingerprintDialogView.this.mDeviceInteractive = false;
            FingerprintDialogView.this.updateFPIndicationText();
        }

        @Override
        public void onStartedWakingUp() {
            super.onStartedWakingUp();
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("onStartedWakingUp, owner:");
            stringBuilder.append(FingerprintDialogView.this.getOwnerString());
            stringBuilder.append(", isShow:");
            //stringBuilder.append(FingerprintDialogView.this.mStatusBarKeyguardViewManager.isShowing());
            Log.d("FingerprintDialogView", stringBuilder.toString());
            FingerprintDialogView.this.mDeviceInteractive = true;
            if (!(!"forceShow-keyguard".equals(FingerprintDialogView.this.getOwnerString()) /*|| FingerprintDialogView.this.mStatusBarKeyguardViewManager.isShowing()*/ || FingerprintDialogView.this.mDialogImpl == null)) {
                FingerprintDialogView.this.mDialogImpl.hideFingerprintDialog();
            }
            if (FingerprintDialogView.this.mIconDisable != null) {
                FingerprintDialogView.this.mIconDisable.setAlpha(0.2f);
            }
            FingerprintDialogView.this.updateIconVisibility(false);
            FingerprintDialogView.this.setDisplayAodMode(0);
            FingerprintDialogView.this.updateFpDaemonStatus(5);
        }

        @Override
        public void onScreenTurnedOn() {
            super.onScreenTurnedOn();
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("onScreenTurnedOn: ");
            stringBuilder.append(FingerprintDialogView.this.mLayout.getAlpha());
            Log.d("FingerprintDialogView", stringBuilder.toString());
            //if (!FingerprintDialogView.this.mPm.isInteractive()) {
            //    FingerprintDialogView.this.setDisplayAodMode(2);
            //}
                //            Bundle b = new Bundle();
                //b.putString("key_fingerprint_package_name", "forceShow-keyguard");
                //showFingerprintDialog(b, null);
            FingerprintDialogView.this.mIsScreenOn = true;
            FingerprintDialogView.this.mIsScreenTurningOn = false;
            FingerprintDialogView.this.updateIconVisibility(false);
            FingerprintDialogView.this.updateFpDaemonStatus(5);
        }

       // @Override
       // public void onScreenTurningOn() {
       //     super.onScreenTurningOn();
       //     StringBuilder stringBuilder = new StringBuilder();
       //     stringBuilder.append("onScreenTurningOn: interactive = ");
        //    stringBuilder.append(FingerprintDialogView.this.mPm.isInteractive());
        //    Log.d("FingerprintDialogView", stringBuilder.toString());
        //    FingerprintDialogView.this.mIsScreenTurningOn = true;
        //    if (!FingerprintDialogView.this.mPm.isInteractive()) {
        //        FingerprintDialogView.this.setDisplayAodMode(2);
        //    }
       // }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("onKeyguardVisibilityChanged: ");
            stringBuilder.append(showing);
            Log.d("FingerprintDialogView", stringBuilder.toString());
            super.onKeyguardVisibilityChanged(showing);
            FingerprintDialogView.this.mShowingKeyguard = showing;
            if (showing) {
                //StringBuilder stringBuilder2 = new StringBuilder();
                //stringBuilder2.append("live wallpaper: ");
                //stringBuilder2.append(LSState.getInstance().getPhoneStatusBar().isShowingWallpaper());
                //Log.d("FingerprintDialogView", stringBuilder2.toString());
               // if (LSState.getInstance().getPhoneStatusBar().isShowingWallpaper()) {
                //    FingerprintDialogView.this.setDisplayHideAod(0);
               // } else {
                    FingerprintDialogView.this.setDisplayHideAod(1);
               // }
                FingerprintDialogView.this.updateFpDaemonStatus(5);
            }
            FingerprintDialogView.this.updateIconVisibility(false);
        }

        @Override
        public void onKeyguardBouncerChanged(boolean isBouncer) {
            if (true /*FingerprintDialogView.this.mStatusBarKeyguardViewManager.isShowing()*/) {
                FingerprintDialogView.this.updateIconVisibility(false);
            }
        }

       // @Override
       // public void onAuthenticateChanged(boolean authenticating, int type, int result, int reserved) {
       //     if (!authenticating) {
       ////         if (type == KeyguardViewMediator.AUTHENTICATE_FACEUNLOCK && !FingerprintDialogView.this.mIsKeyguardDone && FingerprintDialogView.this.mShowingKeyguard) {
        //            FingerprintDialogView.this.mHandler.postDelayed(FingerprintDialogView.this.mUpdateIconRunnable, 700);
        //        }
        //        FingerprintDialogView.this.mScreenOffAuthenticating = false;
       //     } else if (type == KeyguardViewMediator.AUTHENTICATE_FACEUNLOCK) {
        //        FingerprintDialogView.this.mScreenOffAuthenticating = true;
       ////         FingerprintDialogView.this.mHandler.removeCallbacks(FingerprintDialogView.this.mUpdateIconRunnable);
       //         FingerprintDialogView.this.updateIconVisibility(true);
       //     }
       // }

        @Override
        public void onStrongAuthStateChanged(int userId) {
            super.onStrongAuthStateChanged(userId);
            boolean allowed = true;
            if (FingerprintDialogView.this.mUpdateMonitor != null) {
                allowed = FingerprintDialogView.this.mUpdateMonitor.isUnlockingWithFingerprintAllowed();
            }
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("onStrongAuthStateChanged, ");
            stringBuilder.append(allowed);
            Log.d("FingerprintDialogView", stringBuilder.toString());
            if (!allowed) {
                FingerprintDialogView.this.updateIconVisibility(false);
            }
        }

        @Override
        public void onFingerprintAuthenticated(int userId) {
            super.onFingerprintAuthenticated(userId);
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("onFingerprintAuthenticated, ");
            stringBuilder.append(userId);
            Log.d("FingerprintDialogView", stringBuilder.toString());
            FingerprintDialogView.this.mIsKeyguardDone = true;
            //FingerprintDialogView.this.stopAnimation();
            FingerprintDialogView.this.updateFpDaemonStatus(6);
            FingerprintDialogView.this.handleFpResultEvent();
            FingerprintDialogView.this.updateIconVisibility(true);
            FingerprintDialogView.this.mDialogImpl.hideFingerprintDialog();
        }

        @Override
        public void onSimStateChanged(int subId, int slotId, State simState) {
            if (FingerprintDialogView.this.mUpdateMonitor != null && FingerprintDialogView.this.mUpdateMonitor.isSimPinSecure()) {
                FingerprintDialogView.this.updateIconVisibility(false);
            }
        }

        @Override
        public void onUserSwitching(int userId) {
            super.onUserSwitching(userId);
            //if (FingerprintDialogView.this.mFpAnimationCtrl != null) {
            //    FingerprintDialogView.this.mFpAnimationCtrl.updateAnimationRes();
           // }
        }
    };

    private final Runnable mShowAnimationRunnable = new Runnable() {
        @Override
        public void run() {
            mLayout.animate()
                    .alpha(1f)
                    .setDuration(ANIMATION_DURATION_SHOW)
                    .setInterpolator(mLinearOutSlowIn)
                    .withLayer()
                    .start();
            mDialog.animate()
                    .translationY(0)
                    .setDuration(ANIMATION_DURATION_SHOW)
                    .setInterpolator(mLinearOutSlowIn)
                    .withLayer()
                    .start();
        }
    };

    public FingerprintDialogView(Context context, Handler handler, FingerprintDialogImpl dialogImpl) {
        super(context);
        mHandler = handler;
        mDialogImpl = dialogImpl;
        mLinearOutSlowIn = Interpolators.LINEAR_OUT_SLOW_IN;
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mAnimationTranslationOffset = getResources()
                .getDimension(R.dimen.fingerprint_dialog_animation_translation_offset);
        mErrorColor = Color.parseColor(
                getResources().getString(R.color.fingerprint_dialog_error_color));
        mTextColor = Color.parseColor(
                getResources().getString(R.color.fingerprint_dialog_text_light_color));
        mFingerprintColor = Color.parseColor(
                getResources().getString(R.color.fingerprint_dialog_fingerprint_color));

        DisplayMetrics metrics = new DisplayMetrics();
        mWindowManager.getDefaultDisplay().getMetrics(metrics);
        mDisplayWidth = metrics.widthPixels;

        // Create the dialog
        LayoutInflater factory = LayoutInflater.from(getContext());
        mLayout = (ViewGroup) factory.inflate(R.layout.op_fingerprint_view, this, false);
        this.mDimLayout = (ViewGroup) factory.inflate(R.layout.op_fingerprint_dim_view, null, false);
        mPm = (PowerManager) this.mContext.getSystemService("power");
        mPressedLayout = (ViewGroup) factory.inflate(R.layout.op_fingerprint_high_light_view, null, false);
        mPressedLayoutParams = getHighLightLayoutParams();
        mDimLayoutParams = getDimLayoutParams();
        mSurfaceFlinger = ServiceManager.getService("SurfaceFlinger");
        mUpdateMonitor = KeyguardUpdateMonitor.getInstance(this.mContext);
        addView(mLayout);

        mDialog = mLayout.findViewById(R.id.dialog);

        mErrorText = mLayout.findViewById(R.id.error);
        try {
            this.mDaemon = IOneplusDisplay.getService();
        } catch (Exception e) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("Exception e = ");
            stringBuilder.append(e.toString());
            Log.d("FingerprintDialogView", stringBuilder.toString());
        }

            this.mDimView = (ImageView) this.mDimLayout.findViewById(R.id.op_fingerprint_dim_view);
            this.mIconFlash = (CircleImageView) this.mPressedLayout.findViewById(R.id.op_fingerprint_icon_white);
            this.mIconNormal = (CircleImageView) this.mLayout.findViewById(R.id.op_fingerprint_icon);
            this.mIconDisable = (CircleImageView) this.mLayout.findViewById(R.id.op_fingerprint_icon_disable);
            this.mIconDim = (CircleImageView) this.mLayout.findViewById(R.id.op_fingerprint_icon_dim);
            this.mAodIndicationTextView = (TextView) this.mLayout.findViewById(R.id.op_aod_fp_indication_text);
            this.mUpdateMonitor.registerCallback(this.mMonitorCallback);
            mLockPatternUtils = new LockPatternUtils(this.mContext);

        mLayout.setOnKeyListener(new View.OnKeyListener() {
            boolean downPressed = false;
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode != KeyEvent.KEYCODE_BACK) {
                    return false;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN && downPressed == false) {
                    downPressed = true;
                } else if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    downPressed = false;
                } else if (event.getAction() == KeyEvent.ACTION_UP && downPressed == true) {
                    downPressed = false;
                    mHandler.obtainMessage(FingerprintDialogImpl.MSG_USER_CANCELED).sendToTarget();
                }
                return true;
            }
        });

        final View space = mLayout.findViewById(R.id.space);
        final View leftSpace = mLayout.findViewById(R.id.left_space);
        final View rightSpace = mLayout.findViewById(R.id.right_space);
        final Button negative = mLayout.findViewById(R.id.button2);
        final Button positive = mLayout.findViewById(R.id.button1);

        setDismissesDialog(space);
        setDismissesDialog(leftSpace);
        setDismissesDialog(rightSpace);

        negative.setOnClickListener((View v) -> {
            mHandler.obtainMessage(FingerprintDialogImpl.MSG_BUTTON_NEGATIVE).sendToTarget();
        });

        positive.setOnClickListener((View v) -> {
            mHandler.obtainMessage(FingerprintDialogImpl.MSG_BUTTON_POSITIVE).sendToTarget();
        });

        mLayout.setFocusableInTouchMode(true);
        mLayout.requestFocus();

        if (this.mUpdateMonitor != null) {
            this.mUpdateMonitor.setFingerprintDialogView(this);
        }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        final TextView title = mLayout.findViewById(R.id.title);
        final TextView subtitle = mLayout.findViewById(R.id.subtitle);
        final TextView description = mLayout.findViewById(R.id.description);
        final Button negative = mLayout.findViewById(R.id.button2);
        final Button positive = mLayout.findViewById(R.id.button1);

        mDialog.getLayoutParams().width = (int) mDisplayWidth;

        mLastState = STATE_NONE;
        updateFingerprintIcon(STATE_FINGERPRINT);
/*
        title.setText(mBundle.getCharSequence(BiometricPrompt.KEY_TITLE));
        title.setSelected(true);

        final CharSequence subtitleText = mBundle.getCharSequence(BiometricPrompt.KEY_SUBTITLE);
        if (TextUtils.isEmpty(subtitleText)) {
            subtitle.setVisibility(View.GONE);
        } else {
            subtitle.setVisibility(View.VISIBLE);
            subtitle.setText(subtitleText);
        }

        final CharSequence descriptionText = mBundle.getCharSequence(BiometricPrompt.KEY_DESCRIPTION);
        if (TextUtils.isEmpty(descriptionText)) {
            description.setVisibility(View.GONE);
        } else {
            description.setVisibility(View.VISIBLE);
            description.setText(descriptionText);
        }

        negative.setText(mBundle.getCharSequence(BiometricPrompt.KEY_NEGATIVE_TEXT));

        final CharSequence positiveText =
                mBundle.getCharSequence(BiometricPrompt.KEY_POSITIVE_TEXT);
        positive.setText(positiveText); // needs to be set for marquee to work
        if (positiveText != null) {
            positive.setVisibility(View.VISIBLE);
        } else {
            positive.setVisibility(View.GONE);
        }*/
                this.mIsKeyguardDone = false;
        this.mShowOnWindow = true;
/*
        if (!mWasForceRemoved) {
            // Dim the background and slide the dialog up
            mDialog.setTranslationY(mAnimationTranslationOffset);
            mLayout.setAlpha(0f);
            postOnAnimation(mShowAnimationRunnable);
        } else {
            // Show the dialog immediately
            mLayout.animate().cancel();
            mDialog.animate().cancel();
            mDialog.setAlpha(1.0f);
            mDialog.setTranslationY(0);
            mLayout.setAlpha(1.0f);
        }
        mWasForceRemoved = false;
        */
                updateIconVisibility(false);
        this.mLayout.setAlpha(1.0f);
        updateFpDaemonStatus(5);
    }

    @Override
    protected void onDetachedFromWindow() {
        Log.d("FingerprintDialogView", "onDetachedFromWindow");
        if (true /*!this.mShowDefaultDialog*/) {
            resetState();
            this.mShowOnWindow = false;
        }
        super.onDetachedFromWindow();
    }

    private void setDismissesDialog(View v) {
        v.setClickable(true);
        v.setOnTouchListener((View view, MotionEvent event) -> {
            mHandler.obtainMessage(FingerprintDialogImpl.MSG_HIDE_DIALOG, true /* userCanceled */)
                    .sendToTarget();
            return true;
        });
    }

    public void startDismiss() {
        mAnimatingAway = true;

        final Runnable endActionRunnable = new Runnable() {
            @Override
            public void run() {
                FingerprintDialogView.this.setPressDimWindow(false);
                mWindowManager.removeView(FingerprintDialogView.this);
                mAnimatingAway = false;
            }
        };

      if (false) {
        postOnAnimation(new Runnable() {
            @Override
            public void run() {
                mLayout.animate()
                        .alpha(0f)
                        .setDuration(ANIMATION_DURATION_AWAY)
                        .setInterpolator(mLinearOutSlowIn)
                        .withLayer()
                        .start();
                mDialog.animate()
                        .translationY(mAnimationTranslationOffset)
                        .setDuration(ANIMATION_DURATION_AWAY)
                        .setInterpolator(mLinearOutSlowIn)
                        .withLayer()
                        .withEndAction(endActionRunnable)
                        .start();
            }
        });
        }
                this.mLayout.setAlpha(0.0f);
        updateFpDaemonStatus(6);
        endActionRunnable.run();
    }

    /**
     * Force remove the window, cancelling any animation that's happening. This should only be
     * called if we want to quickly show the dialog again (e.g. on rotation). Calling this method
     * will cause the dialog to show without an animation the next time it's attached.
     */
    public void forceRemove() {
        mLayout.animate().cancel();
        mDialog.animate().cancel();
        mWindowManager.removeView(FingerprintDialogView.this);
        mAnimatingAway = false;
        mWasForceRemoved = true;
    }

    public boolean isAnimatingAway() {
        return mAnimatingAway;
    }

    public void setBundle(Bundle bundle) {
        mBundle = bundle;
    }

    // Clears the temporary message and shows the help message.
    protected void resetMessage() {
        updateFingerprintIcon(STATE_FINGERPRINT);
        mErrorText.setText(R.string.fingerprint_dialog_touch_sensor);
        mErrorText.setTextColor(mTextColor);
    }

    // Shows an error/help message
    private void showTemporaryMessage(String message) {
        mHandler.removeMessages(FingerprintDialogImpl.MSG_CLEAR_MESSAGE);
        updateFingerprintIcon(STATE_FINGERPRINT_ERROR);
        mErrorText.setText(message);
        mErrorText.setTextColor(mErrorColor);
        mErrorText.setContentDescription(message);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(FingerprintDialogImpl.MSG_CLEAR_MESSAGE),
                BiometricPrompt.HIDE_DIALOG_DELAY);
    }

    public void showHelpMessage(String message) {
        showTemporaryMessage(message);
    }

    public void showErrorMessage(String error) {
        showTemporaryMessage(error);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(FingerprintDialogImpl.MSG_HIDE_DIALOG,
                false /* userCanceled */), BiometricPrompt.HIDE_DIALOG_DELAY);
    }

    private void updateFingerprintIcon(int newState) {
        Drawable icon  = getAnimationForTransition(mLastState, newState);

        if (icon == null) {
            Log.e(TAG, "Animation not found");
            return;
        }

        final AnimatedVectorDrawable animation = icon instanceof AnimatedVectorDrawable
                ? (AnimatedVectorDrawable) icon
                : null;

        final ImageView fingerprint_icon = mLayout.findViewById(R.id.fingerprint_icon);
        fingerprint_icon.setImageDrawable(icon);

        if (animation != null && shouldAnimateForTransition(mLastState, newState)) {
            animation.forceAnimationOnUI();
            animation.start();
        }

        mLastState = newState;
    }

    private boolean shouldAnimateForTransition(int oldState, int newState) {
        if (oldState == STATE_NONE && newState == STATE_FINGERPRINT) {
            return false;
        } else if (oldState == STATE_FINGERPRINT && newState == STATE_FINGERPRINT_ERROR) {
            return true;
        } else if (oldState == STATE_FINGERPRINT_ERROR && newState == STATE_FINGERPRINT) {
            return true;
        } else if (oldState == STATE_FINGERPRINT && newState == STATE_FINGERPRINT_AUTHENTICATED) {
            // TODO(b/77328470): add animation when fingerprint is authenticated
            return false;
        }
        return false;
    }

    public void showFingerprintDialog(Bundle b, Object o) {
        this.mDialogImpl.forceShowDialog(b, (IBiometricPromptReceiver) o);
    }

    public void onFingerprintEventCallback(int acquireInfo, int vendorCode) {
        if (this.mDialogImpl != null) {
            this.mDialogImpl.onFingerprintEventCallback(acquireInfo, vendorCode);
        }
    }

    private Drawable getAnimationForTransition(int oldState, int newState) {
        int iconRes;
        if (oldState == STATE_NONE && newState == STATE_FINGERPRINT) {
            iconRes = R.drawable.fingerprint_dialog_fp_to_error;
        } else if (oldState == STATE_FINGERPRINT && newState == STATE_FINGERPRINT_ERROR) {
            iconRes = R.drawable.fingerprint_dialog_fp_to_error;
        } else if (oldState == STATE_FINGERPRINT_ERROR && newState == STATE_FINGERPRINT) {
            iconRes = R.drawable.fingerprint_dialog_error_to_fp;
        } else if (oldState == STATE_FINGERPRINT && newState == STATE_FINGERPRINT_AUTHENTICATED) {
            // TODO(b/77328470): add animation when fingerprint is authenticated
            iconRes = R.drawable.fingerprint_dialog_error_to_fp;
        }
        else {
            return null;
        }
        return mContext.getDrawable(iconRes);
    }

    public WindowManager.LayoutParams getLayoutParams() {
        return getCustomLayoutParams(this.WINDOW_FINGERPRINT_VIEW);
        /*final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        lp.setTitle("FingerprintDialogView");
        lp.token = mWindowToken;
        return lp;*/
    }

   private WindowManager.LayoutParams getCustomLayoutParams(String title) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        if (title.equals(this.WINDOW_FINGERPRINT_VIEW)) {
            lp.type = 2305;
        } else if (title.equals(this.WINDOW_FINGERPRINT_DIM_VIEW)) {
            lp.type = 2304;
        } else if (title.equals(this.WINDOW_FINGERPRINT_HIGH_LIGHT_VIEW)) {
            lp.type = 2306;
            lp.privateFlags |= 1048576;
        }
        lp.privateFlags |= 16;
        int i = 1;
        lp.layoutInDisplayCutoutMode = 1;
        boolean isKeyguard = isKeyguard(this.mOwnerString);
        lp.flags = 16778520;
        lp.format = -2;
        lp.width = -1;
        lp.height = -1;
        lp.gravity = 17;
        if (isKeyguard || "com.oneplus.applocker".equals(this.mOwnerString)) {
            i = -1;
        }
        lp.screenOrientation = i;
        lp.windowAnimations = 84738067;
        lp.setTitle(title);
        lp.token = this.mWindowToken;
        if (true) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("getCustomLayoutParams owner:");
            stringBuilder.append(this.mOwnerString);
            stringBuilder.append(" title:");
            stringBuilder.append(title);
            Log.i("FingerprintDialogView", stringBuilder.toString());
        }
        setSystemUiVisibility(getSystemUiVisibility() | 1026);
        return lp;
    }

    public void handleFpResultEvent() {
        if (true /*!this.mShowDefaultDialog*/) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("handleFpResultEvent, ");
            stringBuilder.append(this.mShowingPressed);
            Log.d("FingerprintDialogView", stringBuilder.toString());
            if (!this.mShowOnWindow) {
                Log.d("FingerprintDialogView", "fp window not exist don't show pressed button");
            } else if (this.mShowingPressed) {
                this.mHandler.removeCallbacks(this.mPressTimeoutRunnable);
                //playAnimation(FingerprintAnimationCtrl.TYPE_ANIMATION_TOUCH_UP);
                setDisplayPressMode(0);
            }
        }
    }

    public void setDisplayPressMode(int mode) {
        boolean press = mode == 1;
        try {
            if (mShowingPressed == press) {
                Log.d("FingerprintDialogView", "setDisplayPressMode: the same state");
                return;
            }
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("set press mode to ");
            stringBuilder.append(mode);
            Log.d("FingerprintDialogView", stringBuilder.toString());
            mShowingPressed = press;
            mIconNormal.setVisibility(4);
            mIconDim.setVisibility(0);
            mIconDim.updateIconDim();
            
            if (mPm.isInteractive() || mode != 1) {
                mDaemon.setMode(9, mode);
            }
        } catch (Exception e) {
            StringBuilder stringBuilder2 = new StringBuilder();
            stringBuilder2.append("Exception e = ");
            stringBuilder2.append(e.toString());
            Log.d("FingerprintDialogView", stringBuilder2.toString());
        }
    }

    public void setDisplayAodMode(int mode) {
       try {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("set aod mode: ");
            stringBuilder.append(mode);
            stringBuilder.append(", current : ");
            stringBuilder.append(mAodMode);
            Log.d("FingerprintDialogView", stringBuilder.toString());
            if (mAodMode != mode) {
                mAodMode = mode;
                mDaemon.setMode(8, mode);
            }
        } catch (Exception e) {
            StringBuilder stringBuilder2 = new StringBuilder();
            stringBuilder2.append("Exception e = ");
            stringBuilder2.append(e.toString());
            Log.d("FingerprintDialogView", stringBuilder2.toString());
        }
    }

    public void setDisplayDimMode(int mode) {
        try {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("set dim mode to ");
            stringBuilder.append(mode);
            Log.d("FingerprintDialogView", stringBuilder.toString());
            mDaemon.setMode(10, mode);
            if (mode == 0) {
                mDimOnWindow = false;
                //OIMCManager.notifyModeChange("FingerPrintMode", 2, 0);
                return;
            }
            //OIMCManager.notifyModeChange("FingerPrintMode", 1, 0);
        } catch (Exception e) {
            StringBuilder stringBuilder2 = new StringBuilder();
            stringBuilder2.append("Exception e = ");
            stringBuilder2.append(e.toString());
            Log.d("FingerprintDialogView", stringBuilder2.toString());
        }
    }

    public void setDisplayHideAod(int mode) {
        try {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("set hide aod mode to ");
            stringBuilder.append(mode);
            Log.d("FingerprintDialogView", stringBuilder.toString());
            mDaemon.setMode(11, mode);
        } catch (Exception e) {
            StringBuilder stringBuilder2 = new StringBuilder();
            stringBuilder2.append("Exception e = ");
            stringBuilder2.append(e.toString());
            Log.d("FingerprintDialogView", stringBuilder2.toString());
        }
    }

    private Runnable mPressTimeoutRunnable = new Runnable() {
        public void run() {
            if (FingerprintDialogView.this.mShowingPressed) {
                FingerprintDialogView.this.setDisplayPressMode(0);
                //FingerprintDialogView.this.playAnimation(FingerprintAnimationCtrl.TYPE_ANIMATION_TOUCH_UP);
                //LSState.getInstance().getPhoneStatusBar().onFpPressedTimeOut();
            }
        }
    };

    public void postTimeOutRunnable() {
        this.mHandler.postDelayed(this.mPressTimeoutRunnable, 1000);
    }

    public void showFingerprintPressed() {
        if (true /*!this.mShowDefaultDialog*/) {
            if (this.mShowingPressed) {
                Log.d("FingerprintDialogView", "press state the same");
            } else if (!this.mShowOnWindow) {
                Log.d("FingerprintDialogView", "fp window not exist don't show pressed button");
            } else if (this.mIconDisable.getVisibility() == 0) {
                Log.d("FingerprintDialogView", "fp is disabled currently");
                this.mShowingPressed = true;
            } else {
                StringBuilder stringBuilder = new StringBuilder();
                stringBuilder.append("showFingerprintPressed = true, owner:");
                stringBuilder.append(this.mOwnerString);
                stringBuilder.append(", done:");
                stringBuilder.append(this.mIsKeyguardDone);
                Log.d("FingerprintDialogView", stringBuilder.toString());
                this.mDialogImpl.updateTransparentIconLayoutParams(true);
                boolean z = this.mShowingKeyguard;
                //playAnimation(FingerprintAnimationCtrl.TYPE_ANIMATION_TOUCH_DOWN);
                if (!this.mPm.isInteractive()) {
                    updateFpDaemonStatus(5);
                }
                setDisplayPressMode(1);
            }
        }
    }

    public void hideFingerprintPressed() {
        this.mHandler.removeCallbacks(this.mPressTimeoutRunnable);
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("showFingerprintPressed = false, owner:");
        stringBuilder.append(this.mOwnerString);
        stringBuilder.append(", done:");
        stringBuilder.append(this.mIsKeyguardDone);
        Log.d("FingerprintDialogView", stringBuilder.toString());
        setDisplayPressMode(0);
        if (this.mPm.isInteractive()) {
            //playAnimation(FingerprintAnimationCtrl.TYPE_ANIMATION_TOUCH_UP);
            return;
        }
        //stopAnimation();
        updateDimViewVisibility(false);
    }

    private void updateFPIndicationText() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("updateFPIndicationText: ");
        //stringBuilder.append(this.mUpdateMonitor.isFingerprintLockout());
        //Log.d("FingerprintDialogView", stringBuilder.toString());
        //if (this.mUpdateMonitor.isFingerprintLockout()) {
        //    this.mAodIndicationTextView.setText(17039933);
        //    return;
       // }
        this.mSecurityMode = getSecurityMode();
        int resId = 0;
        if (this.mSecurityMode == SecurityMode.Pattern) {
            resId = R.string.kg_prompt_reason_timeout_pattern;
        } else if (this.mSecurityMode == SecurityMode.Password) {
            resId = R.string.kg_prompt_reason_timeout_password;
        } else if (this.mSecurityMode == SecurityMode.PIN) {
            resId = R.string.kg_prompt_reason_timeout_pin;
        }
        StringBuilder stringBuilder2 = new StringBuilder();
        stringBuilder2.append("updateFPIndicationText: ");
        stringBuilder2.append(this.mSecurityMode);
        Log.d("FingerprintDialogView", stringBuilder2.toString());
        if (resId != 0) {
            this.mAodIndicationTextView.setText(resId);
        } else {
            this.mAodIndicationTextView.setText("");
        }
    }
    
    public SecurityMode getSecurityMode() {
        int passwordQuality = this.mLockPatternUtils.getKeyguardStoredPasswordQuality(KeyguardUpdateMonitor.getCurrentUser());
        if (passwordQuality == 0) {
            return SecurityMode.None;
        }
        if (passwordQuality == 65536) {
            return SecurityMode.Pattern;
        }
        if (passwordQuality == 131072 || passwordQuality == 196608) {
            return SecurityMode.PIN;
        }
        if (passwordQuality == 262144 || passwordQuality == 327680 || passwordQuality == 393216 || passwordQuality == 524288) {
            return SecurityMode.Password;
        }
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Unknown security quality:");
        stringBuilder.append(passwordQuality);
        throw new IllegalStateException(stringBuilder.toString());
    }

   public void updateIconVisibility(boolean forceHide) {
        boolean z = forceHide;
        if (this.mUpdateMonitor == null) {
            this.mUpdateMonitor = KeyguardUpdateMonitor.getInstance(this.mContext);
        }
        if (this.mIconNormal == null || this.mIconDisable == null /*|| this.mStatusBarKeyguardViewManager == null*/) {
            boolean z2 = false;
            String str = "FingerprintDialogView";
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("not update when icon null, ");
            stringBuilder.append(this.mIconNormal == null);
            stringBuilder.append(", ");
            if (this.mIconDisable == null) {
                z2 = true;
            }
            stringBuilder.append(z2);
            Log.w(str, stringBuilder.toString());
            return;
        }
        boolean isUnlockwithFingerPrintAllowed = this.mUpdateMonitor.isUnlockingWithFingerprintAllowed();
        boolean isOccluded = false; //this.mStatusBarKeyguardViewManager.isOccluded();
        boolean isBouncer = false; //this.mStatusBarKeyguardViewManager.isBouncerShowing();
        boolean isImeShow = false; //this.mUpdateMonitor.isImeShow();
        boolean isSimPin = false; //this.mUpdateMonitor.isSimPinSecure();
        boolean isDreaming = this.mUpdateMonitor.isDreaming();
        boolean isQSExpanded = false; //this.mUpdateMonitor.isQSExpanded();
        boolean isPreventModeActivte = false; //this.mUpdateMonitor.isPreventModeActivte();
        boolean faceRecognizing = false; //this.mUpdateMonitor.isFacelockRecognizing();
        boolean isLaunchingCamera = false; //this.mUpdateMonitor.isLaunchingCamera();
        boolean isShowing = false; //this.mStatusBarKeyguardViewManager.isShowing();
        StringBuilder stringBuilder2 = new StringBuilder();
        stringBuilder2.append("updateIconVisibility: fp client = ");
        stringBuilder2.append(this.mOwnerString);
        stringBuilder2.append(", forceHide = ");
        stringBuilder2.append(z);
        stringBuilder2.append(", isBouncer = ");
        stringBuilder2.append(isBouncer);
        stringBuilder2.append(", isImeShow = ");
        stringBuilder2.append(isImeShow);
        stringBuilder2.append(", showOnWindow = ");
        stringBuilder2.append(this.mShowOnWindow);
        stringBuilder2.append(", goingToSleep = ");
        stringBuilder2.append(this.mGoingToSleep);
        stringBuilder2.append(", screenOn = ");
        stringBuilder2.append(this.mIsScreenOn);
        stringBuilder2.append(", isUnlockAllowed = ");
        stringBuilder2.append(isUnlockwithFingerPrintAllowed);
        stringBuilder2.append(", interactive = ");
        stringBuilder2.append(this.mDeviceInteractive);
        stringBuilder2.append(", keyguard visible = ");
        stringBuilder2.append(this.mShowingKeyguard);
        stringBuilder2.append(", isDreaming = ");
        stringBuilder2.append(isDreaming);
        stringBuilder2.append(", isOccluded = ");
        stringBuilder2.append(isOccluded);
        //stringBuilder2.append(", isFaceUnlocked = ");
        //stringBuilder2.append(this.mFaceUnlocked);
        stringBuilder2.append(", isSimPinSecure = ");
        stringBuilder2.append(isSimPin);
        stringBuilder2.append(", isQSExpanded = ");
        stringBuilder2.append(isQSExpanded);
        stringBuilder2.append(", isLaunchingCamera = ");
        stringBuilder2.append(isLaunchingCamera);
        stringBuilder2.append(", isPreventActivte = ");
        stringBuilder2.append(isPreventModeActivte);
        stringBuilder2.append(", isShowing = ");
        stringBuilder2.append(isShowing);
        //stringBuilder2.append(", isLockOut = ");
        //stringBuilder2.append(this.mUpdateMonitor.isFingerprintLockout());
        stringBuilder2.append(", isFacelockRecognizing = ");
        stringBuilder2.append(faceRecognizing);
        stringBuilder2.append(", mScreenOffAuthenticating = ");
        stringBuilder2.append(this.mScreenOffAuthenticating);
        stringBuilder2.append(", visibility = ");
        stringBuilder2.append(this.mIconNormal.getVisibility());
        Log.d("FingerprintDialogView", stringBuilder2.toString());
        String caseLog = "0";
        if (z) {
            this.mIconNormal.setVisibility(4);
            this.mIconDim.setVisibility(4);
            this.mIconDisable.setVisibility(4);
            this.mAodIndicationTextView.setVisibility(4);
            caseLog = "1";
        } else if (!this.mShowOnWindow) {
            this.mIconNormal.setVisibility(4);
            this.mIconDim.setVisibility(4);
            this.mIconDisable.setVisibility(4);
            this.mAodIndicationTextView.setVisibility(4);
            caseLog = "2";
        } else if (((this.mDeviceInteractive || this.mGoingToSleep) && isOccluded && !isBouncer) || isSimPin || isLaunchingCamera || ((this.mDeviceInteractive && !isShowing && isKeyguard(this.mOwnerString) && this.mIsScreenOn) || (this.mShowingKeyguard && !isPreventModeActivte && ((isQSExpanded && !isBouncer) || (isImeShow && isBouncer))))) {
            this.mIconNormal.setVisibility(0);
            this.mIconDim.setVisibility(0);
            this.mIconDisable.setVisibility(4);
            this.mAodIndicationTextView.setVisibility(4);
            this.mDialogImpl.updateTransparentIconVisibility(0);
            caseLog = "3";
        //} else if (this.mFaceUnlocked) {
         //   this.mIconNormal.setVisibility(4);
        //    this.mIconDim.setVisibility(4);
        //    this.mIconDisable.setVisibility(4);
        //    this.mAodIndicationTextView.setVisibility(4);
        //    caseLog = "4";
        //} else if (this.mUpdateMonitor.isFingerprintLockout()) {
         //   this.mIconNormal.setVisibility(4);
        //    this.mIconDim.setVisibility(4);
        //    this.mIconDisable.setVisibility(0);
       //     this.mAodIndicationTextView.setVisibility(this.mDeviceInteractive ? 4 : 0);
       //     caseLog = "5";
        } else if (!isUnlockwithFingerPrintAllowed && !this.mIsKeyguardDone && (isKeyguard(this.mOwnerString) || "forceShow-keyguard".equals(this.mOwnerString))) {
            this.mIconNormal.setVisibility(4);
            this.mIconDim.setVisibility(4);
            this.mIconDisable.setVisibility(0);
            this.mAodIndicationTextView.setVisibility(this.mDeviceInteractive ? 4 : 0);
            caseLog = "6";
        } else if (TextUtils.isEmpty(this.mOwnerString)) {
            this.mIconNormal.setVisibility(4);
            this.mIconDim.setVisibility(4);
            caseLog = "7";
        } else if (this.mIconNormal.getVisibility() == 4) {
            if (!isKeyguard(this.mOwnerString)) {
                this.mIconNormal.setVisibility(0);
                this.mIconDim.setVisibility(0);
                this.mIconDisable.setVisibility(4);
                this.mAodIndicationTextView.setVisibility(4);
                this.mDialogImpl.updateTransparentIconVisibility(0);
                caseLog = "8-2";
            } else if (this.mShowingKeyguard && faceRecognizing && (!this.mIsScreenOn || this.mScreenOffAuthenticating)) {
                this.mIconNormal.setVisibility(4);
                this.mIconDim.setVisibility(4);
                this.mIconDisable.setVisibility(4);
                this.mAodIndicationTextView.setVisibility(4);
                caseLog = "8-0";
            } else if (this.mShowingKeyguard || isDreaming || (!this.mShowingKeyguard && isBouncer)) {
                this.mIconNormal.setVisibility(0);
                this.mIconDim.setVisibility(0);
                this.mIconDisable.setVisibility(4);
                this.mAodIndicationTextView.setVisibility(4);
                this.mDialogImpl.updateTransparentIconVisibility(0);
                caseLog = "8-1";
            }
        }
        if (true) {
            stringBuilder2 = new StringBuilder();
            stringBuilder2.append("caseLog: ");
            stringBuilder2.append(caseLog);
            Log.d("FingerprintDialogView", stringBuilder2.toString());
        }
    }

    public void setOwnerString(String pkg) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("fp client to ");
        stringBuilder.append(pkg);
        Log.d("FingerprintDialogView", stringBuilder.toString());
        this.mOwnerString = pkg;
        this.mIsKeyguardDone = false;
        this.mHandler.post(new Runnable() {
            public void run() {
                FingerprintDialogView.this.updateIconVisibility(false);
            }
        });
    }

    private void resetState() {
        this.mDialogImpl.resetState();
        updateDimViewVisibility(false);
        updateIconVisibility(false);
        //stopAnimation();
    }

    public void updateDimViewVisibility(boolean show) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("updateDimViewVisibility: show = ");
        stringBuilder.append(show);
        stringBuilder.append(", isScreenOn = ");
        stringBuilder.append(this.mIsScreenOn);
        Log.d("FingerprintDialogView", stringBuilder.toString());
        if (show) {
            if (this.mPm.isInteractive()) {
                setDisplayDimMode(1);
            }
        } else if (!show) {
            setDisplayDimMode(0);
        }
    }

    public void setTransparentIconView(View iconView) {
        this.mTransparentIconView = iconView;
    }

    private boolean isKeyguard(String pkg) {
        return "com.android.systemui".equals(pkg) || "forceShow-keyguard".equals(pkg);
    }

   public String getOwnerString() {
        return this.mOwnerString;
    }


    public void updateFpDaemonStatus(int status) {
        if (true /*!this.mShowDefaultDialog*/) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("updateFpDaemonStatus: ");
            stringBuilder.append(status);
            stringBuilder.append(", showing = ");
            stringBuilder.append(this.mDimLayout.isAttachedToWindow());
            stringBuilder.append(", ");
            stringBuilder.append(this.mShowOnWindow);
            Log.d("FingerprintDialogView", stringBuilder.toString());
            if (this.mShowOnWindow) {
                if (status == 5 && shouldEnableHBM()) {
                    updateDimViewVisibility(true);
                } else if (status == 6) {
                    updateDimViewVisibility(false);
                }
            }
        }
    }

    private boolean shouldEnableHBM() {
        if (this.mLayout.getAlpha() == 0.0f || !this.mShowOnWindow) {
            Log.d("FingerprintDialogView", "don't enable HBM dim view is gone or not show on window");
            return false;
        } else if (this.mShowingPressed) {
            Log.d("FingerprintDialogView", "force enable HBM since highlight icon is visible");
            return true;
        } else if (this.mUpdateMonitor != null && this.mUpdateMonitor.isGoingToSleep()) {
            Log.d("FingerprintDialogView", "don't enable HBM due to going to sleep");
            return false;
        } else if (/*LSState.getInstance().getFingerprintUnlockControl().isWakeAndUnlock() ||*/ this.mIsKeyguardDone) {
            Log.d("FingerprintDialogView", "don't enable HBM due to duraing fp wake and unlock");
            return false;
        } else if (this.mPm.isInteractive() /*&& this.mStatusBarKeyguardViewManager != null && this.mStatusBarKeyguardViewManager.isOccluded() && !this.mStatusBarKeyguardViewManager.isBouncerShowing()*/) {
            Log.d("FingerprintDialogView", "don't enable HBM due to keyguard is occluded and device is interactive");
            return false;
        } else if (!this.mPm.isInteractive() && this.mIsScreenOn && this.mShowingPressed) {
            Log.d("FingerprintDialogView", "force enable HBM in aod and fp is pressed");
            return true;
        } else if (!this.mPm.isInteractive()) {
            Log.d("FingerprintDialogView", "don't enable HBM due to device isn't interactive");
            return false;
        //} else if (this.mFaceUnlocked) {
        //    Log.d("FingerprintDialogView", "don't enable HBM due to already face unlocked");
        //    return false;
        } else if (/*!this.mUpdateMonitor.isFingerprintLockout() &&*/ !this.mUpdateMonitor.isUserInLockdown(KeyguardUpdateMonitor.getCurrentUser())) {
            return true;
        } else {
            Log.d("FingerprintDialogView", "don't enable HBM due to lockout");
            return false;
        }
    }

    public void setPressDimWindow(boolean attach) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("setPressDimWindow: ");
        stringBuilder.append(attach);
        Log.d("FingerprintDialogView", stringBuilder.toString());
        if (attach) {
            this.mDimLayoutParams = getDimLayoutParams();
            this.mPressedLayoutParams = getHighLightLayoutParams();
            this.mWindowManager.addView(this.mPressedLayout, this.mPressedLayoutParams);
            this.mWindowManager.addView(this.mDimLayout, this.mDimLayoutParams);
            return;
        }
        this.mWindowManager.removeViewImmediate(this.mPressedLayout);
        this.mWindowManager.removeViewImmediate(this.mDimLayout);
    }

    private WindowManager.LayoutParams getHighLightLayoutParams() {
        return getCustomLayoutParams(this.WINDOW_FINGERPRINT_HIGH_LIGHT_VIEW);
    }

    private WindowManager.LayoutParams getDimLayoutParams() {
        return getCustomLayoutParams(this.WINDOW_FINGERPRINT_DIM_VIEW);
    }

    public void removeTimeOutMessage() {
        this.mHandler.removeCallbacks(this.mPressTimeoutRunnable);
    }

    public void notifyBrightnessChange() {
        if (this.mShowOnWindow) {
            this.mIconDim.onBrightnessChange();
        }
    }

    public void notifyFingerprintAuthenticated() {
        if (!this.mIsKeyguardDone) {
            this.mIsKeyguardDone = true;
            //stopAnimation();
            if (!this.mPm.isInteractive()) {
                setDisplayAodMode(0);
            }
            updateFpDaemonStatus(6);
            handleFpResultEvent();
            updateIconVisibility(true);
            this.mDialogImpl.hideFingerprintDialog();
        }
    }
}
