package com.cdot.ping;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;

import com.cdot.ping.devices.SampleData;

/**
 * A simple view for watching a graph of sonar samples.
 */
public class SonarView extends View {
    SampleData[] mSamples;
    int mPtr = 0;
    int mWidth;
    int mHeight;
    Paint paint;
    Shader shader;

    public SonarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);

        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setAntiAlias(true);
        paint.setStrokeWidth(1);
        paint.setStyle(Paint.Style.STROKE);
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
            shader = new LinearGradient(0, 0, 0, mHeight, Color.GREEN,  Color.YELLOW, Shader.TileMode.MIRROR);
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
            float tempy1 = scaley(mSamples[0].temperature, maxTemp);
            int x1 = 0;
            for (int i = 0; i < mPtr; i++) {
                int x2 = scalex(i);
                int depthy2 = scaley(mSamples[i].depth, maxDepth);
                int tempy2 = scaley(mSamples[i].temperature, maxTemp);
                int strength = scaley(mSamples[i].strength, maxStrength);
                paint.setColor(Color.RED);
                canvas.drawLine(x1, tempy1, x2, tempy2, paint);
                paint.setColor(Color.GREEN);
                canvas.drawLine(x1, depthy1, x2, depthy2, paint);
                paint.setShader(shader);
                if (depthy2 + strength > mHeight) strength = mHeight - depthy2;
                if (strength > 75)
                    canvas.drawLine(x2, depthy2, x2, depthy2 + strength, paint);
                paint.setShader(null);
                depthy1 = depthy2;
                tempy1 = tempy2;
                x1 = x2;
            }
        }
    }
}
