package com.cdot.ping;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class SonarView extends View {
    SonarData[] mSamples = new SonarData[256];
    int mPtr = 0;
    int mWidth;
    int mHeight;
    Paint mGroundPaint;

    SonarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mGroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        setWillNotDraw(false);
        mGroundPaint.setColor(Color.RED);
        mGroundPaint.setAntiAlias(true);
        mGroundPaint.setStrokeWidth(1);
        mGroundPaint.setStyle(Paint.Style.STROKE);
    }

    void sample(SonarData data) {
        if (mPtr == mSamples.length)
            mPtr = 0;
        mSamples[mPtr++] = data;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mWidth = w;
        mHeight = h;
    }

    private int scalex(int sample) {
        return (int) (mWidth * (float) sample / mSamples.length);
    }

    private int scaley(float sample, float sampleMax) {
        return (int) (mHeight * sample / sampleMax);
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mSamples == null || mPtr == 0)
            return;
        float last = mSamples[0].depth;
        for (int i = 0; i < mPtr; i++) {
            canvas.drawLine(scalex(i - 1), scaley(last, 36), scalex(i), scaley(mSamples[i].depth, 36), mGroundPaint);
            last = mSamples[i].depth;
        }
    }
}
