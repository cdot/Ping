package com.cdot.ping;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;

/**
 * A simple view for watching a graph of sonar samples.
 */
public class SonarView extends View {
    SampleData[] mSamples;
    int mPtr = 0;
    int mWidth;
    int mHeight;
    Paint depthPaint, tempPaint, strengthPaint;

    SonarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);

        depthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        depthPaint.setColor(Color.GREEN);
        depthPaint.setAntiAlias(true);
        depthPaint.setStrokeWidth(1);
        depthPaint.setStyle(Paint.Style.STROKE);

        tempPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tempPaint.setColor(Color.RED);
        tempPaint.setAntiAlias(true);
        tempPaint.setStrokeWidth(1);
        tempPaint.setStyle(Paint.Style.STROKE);

        strengthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strengthPaint.setColor(Color.MAGENTA);
        strengthPaint.setAntiAlias(true);
        strengthPaint.setStrokeWidth(1);
        strengthPaint.setStyle(Paint.Style.STROKE);
    }

    synchronized void sample(@NonNull SampleData data) {
        if (mPtr == mSamples.length) {
            System.arraycopy(mSamples, 1, mSamples, 0, mSamples.length - 1);
            mSamples[mPtr - 1] = data;
        } else
            mSamples[mPtr++] = data;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        synchronized (this) {
            mWidth = w;
            mSamples = new SampleData[w];
            mPtr = 0;
            mHeight = h;
        }
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
        int maxDepth = Ping.RANGE_DEPTH[Ping.P.getInt("range")];
        int maxStrength = Ping.MAX_STRENGTH;
        float maxTemp = Ping.MAX_TEMPERATURE;
        synchronized (this) {
            float depthy1 = scaley(mSamples[0].depth, maxDepth);
            float tempy1 = scaley(mSamples[0].depth, maxTemp);
            float strengthy1 = scaley(mSamples[0].depth, maxStrength);
            int x1 = 0;
            for (int i = 0; i < mPtr; i++) {
                int x2 = scalex(i);
                int depthy2 = scaley(mSamples[i].depth, maxDepth);
                int tempy2 = scaley(mSamples[i].temperature, maxTemp);
                int strengthy2 = scaley(mSamples[i].strength, maxStrength);
                canvas.drawLine(x1, depthy1, x2, depthy2, depthPaint);
                canvas.drawLine(x1, tempy1, x2, tempy2, tempPaint);
                canvas.drawLine(x1, strengthy1, x2, strengthy2, strengthPaint);
                depthy1 = depthy2;
                tempy1 = tempy2;
                strengthy1 = strengthy2;
                x1 = x2;
            }
        }
    }
}
