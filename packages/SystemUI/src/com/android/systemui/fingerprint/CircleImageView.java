package com.android.systemui.fingerprint;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Path;
import android.graphics.Path.Direction;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffXfermode;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.provider.Settings.System;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.ImageView;

import com.android.systemui.R;


public class CircleImageView extends ImageView {
    private final int[][] BRIGHTNESS_ALPHA_ARRAY;
    private final String TAG;
    private int TYPE_DIM;
    private int TYPE_DISABLE;
    private int TYPE_HIGH_LIGHT;
    private int TYPE_NORMAL;
    private Context mContext;
    private int mDefaultBacklight;
    public PaintFlagsDrawFilter mPaintFlagsDrawFilter;
    private int mType;
    Paint paint;
    Path path;

    private int interpolate(int x, int xa, int xb, int ya, int yb) {
        int sub = 0;
        int bf = (((yb - ya) * 2) * (x - xa)) / (xb - xa);
        int factor = bf / 2;
        int plus = bf % 2;
        if (!(xa - xb == 0 || yb - ya == 0)) {
            sub = (((2 * (x - xa)) * (x - xb)) / (yb - ya)) / (xa - xb);
        }
        return ((ya + factor) + plus) + sub;
    }

    private int getDimAlpha() {
        int brightness = System.getIntForUser(this.mContext.getContentResolver(), "screen_brightness", this.mDefaultBacklight, -2);
        int level = this.BRIGHTNESS_ALPHA_ARRAY.length;
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("brightness = ");
        stringBuilder.append(brightness);
        stringBuilder.append(", level = ");
        stringBuilder.append(level);
        Log.d("CircleImageView", stringBuilder.toString());
        int i = 0;
        while (i < level && this.BRIGHTNESS_ALPHA_ARRAY[i][0] < brightness) {
            i++;
        }
        if (i == 0) {
            return this.BRIGHTNESS_ALPHA_ARRAY[0][1];
        }
        if (i == level) {
            return this.BRIGHTNESS_ALPHA_ARRAY[level - 1][1];
        }
        return interpolate(brightness, this.BRIGHTNESS_ALPHA_ARRAY[i - 1][0], this.BRIGHTNESS_ALPHA_ARRAY[i][0], this.BRIGHTNESS_ALPHA_ARRAY[i - 1][1], this.BRIGHTNESS_ALPHA_ARRAY[i][1]);
    }

    public CircleImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        this.TAG = "CircleImageView";
        this.TYPE_DISABLE = 0;
        this.TYPE_NORMAL = 1;
        this.TYPE_DIM = 2;
        this.TYPE_HIGH_LIGHT = 3;
        this.BRIGHTNESS_ALPHA_ARRAY = new int[][]{new int[]{0, 255}, new int[]{1, 241}, new int[]{2, 236}, new int[]{4, 235}, new int[]{5, 234}, new int[]{6, 232}, new int[]{10, 228}, new int[]{20, 220}, new int[]{30, 212}, new int[]{45, 204}, new int[]{70, 190}, new int[]{100, 179}, new int[]{150, 166}, new int[]{227, 144}, new int[]{300, 131}, new int[]{400, 112}, new int[]{500, 96}, new int[]{600, 83}, new int[]{800, 60}, new int[]{1023, 34}, new int[]{2000, 131}};
        init(context);
    }

    public CircleImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.TAG = "CircleImageView";
        this.TYPE_DISABLE = 0;
        this.TYPE_NORMAL = 1;
        this.TYPE_DIM = 2;
        this.TYPE_HIGH_LIGHT = 3;
        this.BRIGHTNESS_ALPHA_ARRAY = new int[][]{new int[]{0, 255}, new int[]{1, 241}, new int[]{2, 236}, new int[]{4, 235}, new int[]{5, 234}, new int[]{6, 232}, new int[]{10, 228}, new int[]{20, 220}, new int[]{30, 212}, new int[]{45, 204}, new int[]{70, 190}, new int[]{100, 179}, new int[]{150, 166}, new int[]{227, 144}, new int[]{300, 131}, new int[]{400, 112}, new int[]{500, 96}, new int[]{600, 83}, new int[]{800, 60}, new int[]{1023, 34}, new int[]{2000, 131}};
        init(context);
    }

    public CircleImageView(Context context) {
        super(context);
        this.TAG = "CircleImageView";
        this.TYPE_DISABLE = 0;
        this.TYPE_NORMAL = 1;
        this.TYPE_DIM = 2;
        this.TYPE_HIGH_LIGHT = 3;
        this.BRIGHTNESS_ALPHA_ARRAY = new int[][]{new int[]{0, 255}, new int[]{1, 241}, new int[]{2, 236}, new int[]{4, 235}, new int[]{5, 234}, new int[]{6, 232}, new int[]{10, 228}, new int[]{20, 220}, new int[]{30, 212}, new int[]{45, 204}, new int[]{70, 190}, new int[]{100, 179}, new int[]{150, 166}, new int[]{227, 144}, new int[]{300, 131}, new int[]{400, 112}, new int[]{500, 96}, new int[]{600, 83}, new int[]{800, 60}, new int[]{1023, 34}, new int[]{2000, 131}};
        init(context);
    }

    public void init(Context context) {
        this.mContext = context;
        int id = getId();
        //if (id == R.id.op_fingerprint_icon_white) {
        //    this.mType = this.TYPE_HIGH_LIGHT;
        /*} else*/ if (id == R.id.op_fingerprint_icon_disable) {
            this.mType = this.TYPE_DISABLE;
        } else if (id == R.id.op_fingerprint_icon_dim) {
            this.mType = this.TYPE_DIM;
        } else if (id == R.id.op_fingerprint_icon) {
            this.mType = this.TYPE_HIGH_LIGHT;
        }
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("init view: ");
        stringBuilder.append(this.mType);
        Log.d("CircleImageView", stringBuilder.toString());
        //initPaint();
        initPosition(context);
        this.mDefaultBacklight = ((PowerManager) context.getSystemService(PowerManager.class)).getDefaultScreenBrightnessSetting();
    }

    private void initPaint() {
        this.mPaintFlagsDrawFilter = new PaintFlagsDrawFilter(0, 3);
        this.paint = new Paint();
        this.paint.setAntiAlias(true);
        if (this.mType == this.TYPE_HIGH_LIGHT) {
            this.paint.setColor(Color.parseColor("#00FF00"));
        } else if (this.mType == this.TYPE_DIM) {
            this.paint.setColor(Color.parseColor("#000000"));
        }
        this.paint.setStyle(Style.FILL);
    }

    private void initPosition(Context context) {
        int y = context.getResources().getDimensionPixelSize(R.dimen.op_biometric_icon_normal_location_y);
        if (this.mType == this.TYPE_HIGH_LIGHT) {
            y = context.getResources().getDimensionPixelSize(R.dimen.op_biometric_icon_flash_location_y);
        }
        setY((float) y);
    }

    protected void onDraw(Canvas cns) {
        super.onDraw(cns);
        if (false /*this.mType == this.TYPE_HIGH_LIGHT || this.mType == this.TYPE_DIM*/) {
            int radius = 0;
            if (this.mType == this.TYPE_HIGH_LIGHT) {
                radius = this.mContext.getResources().getDimensionPixelOffset(R.dimen.op_biometric_icon_flash_width);
            } else if (this.mType == this.TYPE_DIM) {
                radius = this.mContext.getResources().getDimensionPixelOffset(R.dimen.op_biometric_icon_normal_width);
            }
            float h = (float) getMeasuredHeight();
            float w = (float) getMeasuredWidth();
            if (this.path == null) {
                this.path = new Path();
                this.path.addCircle(w / 2.0f, h / 2.0f, (float) Math.min((double) (w / 2.0f), ((double) h) / 2.0d), Direction.CCW);
                this.path.close();
            }
            if (this.mType == this.TYPE_DIM) {
                this.paint.setXfermode(new PorterDuffXfermode(Mode.SRC_IN));
            }
            cns.drawCircle(w / 2.0f, h / 2.0f, (float) (radius / 2), this.paint);
        }
    }

    public void updateIconDim() {
        int val = getDimAlpha();
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("updateIconDim: ");
        stringBuilder.append(val);
        Log.d("CircleImageView", stringBuilder.toString());
        float alpha = ((float) val) / 255.0f;
        float ratio = ((float) SystemProperties.getInt("sys.fod.icon.dim", 70)) / 100.0f;
        StringBuilder stringBuilder2 = new StringBuilder();
        stringBuilder2.append("alpha = ");
        stringBuilder2.append(alpha);
        stringBuilder2.append(", ratio = ");
        stringBuilder2.append(ratio);
        Log.d("CircleImageView", stringBuilder2.toString());
        setAlpha(alpha * ratio);
    }

    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (this.mType == this.TYPE_DIM) {
            //updateIconDim();
        }
    }

    public void onBrightnessChange() {
        updateIconDim();
    }
}

