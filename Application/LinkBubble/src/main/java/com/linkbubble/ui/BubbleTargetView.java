package com.linkbubble.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import com.linkbubble.Config;
import com.linkbubble.MainController;
import com.linkbubble.R;
import com.linkbubble.Settings;
import com.linkbubble.util.Util;
import com.linkbubble.physics.Circle;

/**
 * Created by gw on 21/11/13.
 */
public class BubbleTargetView extends RelativeLayout {
    private Context mContext;
    private ImageView mCircleView;
    private ImageView mImage;
    private CanvasView mCanvasView;

    private float mXFraction;
    private float mYFraction;
    private float mButtonWidth;
    private float mButtonHeight;
    private float mSnapWidth;
    private float mSnapHeight;
    private Circle mSnapCircle;
    private Circle mDefaultCircle;
    private Config.BubbleAction mAction;

    private RelativeLayout.LayoutParams mCanvasLayoutParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

    private LinearInterpolator mLinearInterpolator = new LinearInterpolator();
    private int mInitialX;
    private int mInitialY;
    private int mTargetX;
    private int mTargetY;
    private float mAnimPeriod;
    private float mAnimTime;
    private boolean mEnableMove;

    private final float mMaxAlpha = 1.0f;
    private final float mFadeTime = 0.2f;
    private final float mAlphaDelta = mMaxAlpha / mFadeTime;
    private float mCurrentAlpha = 1.0f;
    private float mTargetAlpha = 1.0f;

    private void setTargetPos(int x, int y, float t) {
        if (x != mTargetX || y != mTargetY) {
            mInitialX = mCanvasLayoutParams.leftMargin;
            mInitialY = mCanvasLayoutParams.topMargin;

            mTargetX = x;
            mTargetY = y;

            mAnimPeriod = t;
            mAnimTime = 0.0f;

            MainController.get().scheduleUpdate();
        }
    }

    public BubbleTargetView(CanvasView canvasView, Context context, Config.BubbleAction action, float xFraction, float yFraction, boolean enableMove) {
        super(context);
        Init(canvasView, context, Settings.get().getConsumeBubbleIcon(action), action, xFraction, yFraction, enableMove);
    }

    public BubbleTargetView(CanvasView canvasView, Context context, int resId, Config.BubbleAction action, float xFraction, float yFraction, boolean enableMove) {
        super(context);
        Drawable d = context.getResources().getDrawable(resId);
        Init(canvasView, context, d, action, xFraction, yFraction, enableMove);
    }

    public void onConsumeBubblesChanged() {
        Drawable d = null;

        switch (mAction) {
            case ConsumeLeft:
            case ConsumeRight:
                d = Settings.get().getConsumeBubbleIcon(mAction);
                break;
            default:
                break;
        }

        if (d != null) {

            if (d instanceof BitmapDrawable) {
                Bitmap bm = ((BitmapDrawable)d).getBitmap();
                mButtonWidth = bm.getWidth();
                mButtonHeight = bm.getHeight();
            } else {
                mButtonWidth = d.getIntrinsicWidth();
                mButtonHeight = d.getIntrinsicHeight();
            }
            Util.Assert(mButtonWidth > 0);
            Util.Assert(mButtonHeight > 0);

            mImage.setImageDrawable(d);

            RelativeLayout.LayoutParams imageLP = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            imageLP.leftMargin = (int) (0.5f + mDefaultCircle.mRadius - mButtonWidth * 0.5f);
            imageLP.topMargin = (int) (0.5f + mDefaultCircle.mRadius - mButtonHeight * 0.5f);
            updateViewLayout(mImage, imageLP);
        }
    }

    private void Init(CanvasView canvasView, Context context, Drawable d, Config.BubbleAction action, float xFraction, float yFraction, boolean enableMove) {
        mCanvasView = canvasView;
        mEnableMove = enableMove;
        mContext = context;
        mAction = action;
        mXFraction = xFraction;
        mYFraction = yFraction;

        if (d instanceof BitmapDrawable) {
            Bitmap bm = ((BitmapDrawable)d).getBitmap();
            mButtonWidth = bm.getWidth();
            mButtonHeight = bm.getHeight();
        } else {
            mButtonWidth = d.getIntrinsicWidth();
            mButtonHeight = d.getIntrinsicHeight();
        }
        Util.Assert(mButtonWidth > 0);
        Util.Assert(mButtonHeight > 0);

        mImage = new ImageView(mContext);
        mImage.setImageDrawable(d);

        mCircleView = new ImageView(mContext);
        mCircleView.setImageResource(R.drawable.target_default);

        Drawable snapDrawable = mContext.getResources().getDrawable(R.drawable.target_snap);
        mSnapWidth = snapDrawable.getIntrinsicWidth();
        mSnapHeight = snapDrawable.getIntrinsicHeight();
        Util.Assert(mSnapWidth > 0 && mSnapHeight > 0 && mSnapWidth == mSnapHeight);
        mSnapCircle = new Circle(Config.mScreenWidth * mXFraction, Config.mScreenHeight * mYFraction, mSnapWidth * 0.5f);

        Drawable defaultDrawable = mContext.getResources().getDrawable(R.drawable.target_default);
        float defaultWidth = defaultDrawable.getIntrinsicWidth();
        float defaultHeight = defaultDrawable.getIntrinsicHeight();
        Util.Assert(defaultWidth > 0 && defaultHeight > 0 && defaultWidth == defaultHeight);
        mDefaultCircle = new Circle(Config.mScreenWidth * mXFraction, Config.mScreenHeight * mYFraction, defaultWidth * 0.5f);

        addView(mCircleView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        RelativeLayout.LayoutParams imageLP = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        imageLP.leftMargin = (int) (0.5f + mDefaultCircle.mRadius - mButtonWidth * 0.5f);
        imageLP.topMargin = (int) (0.5f + mDefaultCircle.mRadius - mButtonHeight * 0.5f);
        addView(mImage, imageLP);

        // Add main relative layout to canvasView
        mCanvasLayoutParams.leftMargin = (int) (0.5f + mDefaultCircle.mX - mDefaultCircle.mRadius);
        mCanvasLayoutParams.topMargin = (int) (0.5f + mDefaultCircle.mY - mDefaultCircle.mRadius);
        mCanvasLayoutParams.rightMargin = -100;
        mCanvasLayoutParams.bottomMargin = -100;
        mCanvasView.addView(this, mCanvasLayoutParams);
    }

    public void fadeIn() {
        mTargetAlpha = mMaxAlpha;
        MainController.get().scheduleUpdate();
    }

    public void fadeOut() {
        mTargetAlpha = 0.0f;
        MainController.get().scheduleUpdate();
    }

    public void update(float dt, BubbleLegacyView bubble) {
        if (bubble != null && !bubble.isSnapping() && mEnableMove) {
            float xf = (bubble.getXPos() + Config.mBubbleWidth * 0.5f) / Config.mScreenWidth;
            xf = 2.0f * Util.clamp(0.0f, xf, 1.0f) - 1.0f;
            Util.Assert(xf >= -1.0f && xf <= 1.0f);

            mSnapCircle.mX = Config.mScreenWidth * mXFraction + xf * Config.mScreenWidth * 0.1f;

            if (mYFraction > 0.5f) {
                int bubbleYC = (int) (bubble.getYPos() + Config.mBubbleHeight * 0.5f);
                int bubbleY0 = (int) (Config.mScreenHeight * 0.75f);
                int bubbleY1 = (int) (Config.mScreenHeight * 0.90f);

                int targetY0 = (int) (Config.mScreenHeight * mYFraction);
                int targetY1 = (int) (Config.mScreenHeight * (mYFraction + 0.05f));

                if (bubbleYC < bubbleY0) {
                    mSnapCircle.mY = Config.mScreenHeight * mYFraction;
                } else if (bubbleYC < bubbleY1) {
                    float yf = (float)(bubbleYC - bubbleY0) / (float)(bubbleY1 - bubbleY0);
                    mSnapCircle.mY = Config.mScreenHeight * mYFraction + yf * (targetY1 - targetY0);
                } else {
                    mSnapCircle.mY = bubbleYC;
                }
            } else {
                int bubbleYC = (int) (bubble.getYPos() + Config.mBubbleHeight * 0.5f);
                int bubbleY0 = (int) (Config.mScreenHeight * 0.25f);
                int bubbleY1 = (int) (Config.mScreenHeight * 0.10f);

                int targetY0 = (int) (Config.mScreenHeight * mYFraction);
                int targetY1 = (int) (Config.mScreenHeight * (mYFraction + 0.05f));

                if (bubbleYC > bubbleY0) {
                    mSnapCircle.mY = Config.mScreenHeight * mYFraction;
                } else if (bubbleYC > bubbleY1) {
                    float yf = (float)(bubbleYC - bubbleY0) / (float)(bubbleY1 - bubbleY0);
                    mSnapCircle.mY = Config.mScreenHeight * mYFraction - yf * (targetY1 - targetY0);
                } else {
                    mSnapCircle.mY = bubbleYC;
                }

            }

            mSnapCircle.mY = Util.clamp(0, mSnapCircle.mY, Config.mScreenHeight - mDefaultCircle.mRadius);

            mDefaultCircle.mX = mSnapCircle.mX;
            mDefaultCircle.mY = mSnapCircle.mY;

            setTargetPos((int) (0.5f + mDefaultCircle.mX - mDefaultCircle.mRadius),
                    (int) (0.5f + mDefaultCircle.mY - mDefaultCircle.mRadius), 0.03f);
        }

        if (mAnimTime < mAnimPeriod) {
            Util.Assert(mAnimPeriod > 0.0f);

            mAnimTime = Util.clamp(0.0f, mAnimTime + dt, mAnimPeriod);

            float tf = mAnimTime / mAnimPeriod;
            float interpolatedFraction;
            interpolatedFraction = mLinearInterpolator.getInterpolation(tf);
            Util.Assert(interpolatedFraction >= 0.0f && interpolatedFraction <= 1.0f);

            int x = (int) (mInitialX + (mTargetX - mInitialX) * interpolatedFraction);
            int y = (int) (mInitialY + (mTargetY - mInitialY) * interpolatedFraction);

            mCanvasLayoutParams.leftMargin = x;
            mCanvasLayoutParams.topMargin = y;
            mCanvasView.updateViewLayout(this, mCanvasLayoutParams);

            MainController.get().scheduleUpdate();
        }

        if (mCurrentAlpha < mTargetAlpha) {
            mCurrentAlpha = Util.clamp(0.0f, mCurrentAlpha + mAlphaDelta * dt, mMaxAlpha);
            MainController.get().scheduleUpdate();
        } else if (mCurrentAlpha > mTargetAlpha) {
            mCurrentAlpha = Util.clamp(0.0f, mCurrentAlpha - mAlphaDelta * dt, mMaxAlpha);
            MainController.get().scheduleUpdate();
        }
        Util.Assert(mCurrentAlpha >= 0.0f && mCurrentAlpha <= 1.0f);
        setAlpha(mCurrentAlpha);
        if (mCurrentAlpha == 0.0f) {
            setVisibility(GONE);
        } else {
            setVisibility(VISIBLE);
        }
    }

    public void OnOrientationChanged() {
        mSnapCircle.mX = Config.mScreenWidth * mXFraction;
        mSnapCircle.mY = Config.mScreenHeight * mYFraction;

        mDefaultCircle.mX = Config.mScreenWidth * mXFraction;
        mDefaultCircle.mY = Config.mScreenHeight * mYFraction;

        // Add main relative layout to canvas
        RelativeLayout.LayoutParams targetLayoutLP = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        targetLayoutLP.leftMargin = (int) (0.5f + mDefaultCircle.mX - mDefaultCircle.mRadius);
        targetLayoutLP.topMargin = (int) (0.5f + mDefaultCircle.mY - mDefaultCircle.mRadius);
        mCanvasView.updateViewLayout(this, targetLayoutLP);
    }

    public Config.BubbleAction GetAction() {
        return mAction;
    }

    public Circle GetSnapCircle() {
        return mSnapCircle;
    }

    public Circle GetDefaultCircle() {
        return mDefaultCircle;
    }
}
