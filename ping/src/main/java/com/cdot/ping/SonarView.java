/*
 * Copyright © 2020 C-Dot Consultants
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.cdot.ping;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;

import com.cdot.ping.samplers.LoggingService;
import com.cdot.ping.samplers.Sample;
import com.cdot.ping.samplers.SampleCache;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.lang.Thread.interrupted;

/**
 * A graphical view for watching incoming sonar samples.
 */
public class SonarView extends View {

    private static final String TAG = SonarView.class.getSimpleName();
    // Dimensions of the drawing bitmap. The drawing bitmap just has to be big enough that when it is
    // scaled to the sreen it looks OK.
    private static final int BITMAP_HEIGHT = 1920; // reasonable guesstimate of the max height of the screen
    private static final int BITMAP_BOTTOM = BITMAP_HEIGHT - 1; // bottom pixel index
    private static final int BITMAP_WIDTH = BITMAP_HEIGHT; // might be rotated, so can't be less than height
    private static final int BITMAP_RIGHT = BITMAP_WIDTH - 1; // rightmost pixel index
    private final Paint mWaterPaint;  // used for water
    private final Paint mBottomPaint; // used for the bottom
    private final Paint mDepthPaint;  // depth bars. Scaled and centred on the measured depth, to show the reported error
    private final Shader mDepthShader;
    private final Matrix mDepthMat = new Matrix(); // used to transform the depth gradient
    private final Paint mFishPaint;   // fish bars. Scaled and centred like mDepthPaint
    private final Shader mFishShader;
    private final Matrix mFishMat = new Matrix(); // used to transform the fish gradient
    // The bitmap we actually draw to
    private final Bitmap mOffscreenBitmap;
    // Canvas that we use to draw to mOffscreenBitmap
    private final Canvas mOffscreenCanvas;
    // Drawing canvas rect for the areas of the image to be drawn
    private final Rect mOffscreenBitmapRect;
    // Screen canvas rect for the area to draw to
    private final Rect mScreenRect;
    private final Settings mSettings;
    private final ConcurrentLinkedQueue<Sample> mSampleQueue = new ConcurrentLinkedQueue<>();
    Context mContext;
    private Thread mRenderThread;

    // Sample bar width in backing-bitmap pixels
    private int mSampleWidthPx = BITMAP_WIDTH / 500;
    // Max depth displayed
    private float mMaxDepth = 36;

    public SonarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        mContext = context;

        mSettings = new Settings(context);

        mOffscreenCanvas = new Canvas();
        mOffscreenBitmap = Bitmap.createBitmap(BITMAP_WIDTH, BITMAP_HEIGHT, Bitmap.Config.RGB_565);
        mOffscreenCanvas.setBitmap(mOffscreenBitmap);

        mOffscreenBitmapRect = new Rect(0, 0, BITMAP_WIDTH, BITMAP_HEIGHT);
        mScreenRect = new Rect(0, 0, BITMAP_WIDTH, BITMAP_HEIGHT);

        mWaterPaint = new Paint();
        mWaterPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        Shader waterShader = new LinearGradient(0, 0, 0, BITMAP_HEIGHT, Color.CYAN, Color.BLUE, Shader.TileMode.MIRROR);
        mWaterPaint.setShader(waterShader);

        mBottomPaint = new Paint();
        mBottomPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        Shader bottomShader = new LinearGradient(0, 0, 0, BITMAP_HEIGHT, Color.GREEN, Color.BLACK, Shader.TileMode.MIRROR);
        mBottomPaint.setShader(bottomShader);

        mDepthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mDepthPaint.setAntiAlias(true);
        mDepthPaint.setStrokeWidth(1);
        mDepthPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        mDepthShader = new LinearGradient(0, 0, 0, 1, Color.YELLOW, Color.RED, Shader.TileMode.MIRROR);
        mDepthPaint.setShader(mDepthShader);

        mFishPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mFishPaint.setAntiAlias(true);
        mFishPaint.setStrokeWidth(1);
        mFishPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        mFishShader = new LinearGradient(0, 0, 0, 1, Color.LTGRAY, Color.GRAY, Shader.TileMode.MIRROR);
        mFishPaint.setShader(mFishShader);

        resetScale();

        mRenderThread = new Thread(new Runnable() {
            public void run() {
                while (!interrupted()) {
                    if (mSampleQueue.size() > 0) {
                        synchronized (SonarView.this) {
                            int n = mSampleQueue.size();

                            // Scroll the drawing bitmap to accommodate the queued samples
                            Rect srcRect = new Rect(n, 0, BITMAP_RIGHT, BITMAP_BOTTOM);
                            Rect destRect = new Rect(0, 0, BITMAP_RIGHT - n * mSampleWidthPx, BITMAP_BOTTOM);
                            mOffscreenCanvas.drawBitmap(mOffscreenBitmap, srcRect, destRect, null);

                            // Draw the water
                            mOffscreenCanvas.drawRect(BITMAP_RIGHT - n * mSampleWidthPx, 0, BITMAP_RIGHT, BITMAP_BOTTOM, mWaterPaint);

                            int left = BITMAP_RIGHT - mSampleWidthPx;
                            for (; n > 0; n--) {
                                Sample sample = mSampleQueue.poll();
                                drawSample(sample, left);
                                left -= mSampleWidthPx;
                            }
                        }

                        post(() -> invalidate());
                    } else {
                        try {
                            Thread.sleep(250);
                        } catch (InterruptedException ignore) {
                        }
                    }
                }
            }
        });
        mRenderThread.start();
    }

    private synchronized void redrawAll() {
        mOffscreenCanvas.drawRect(0, 0, BITMAP_RIGHT, BITMAP_BOTTOM, mWaterPaint);

        // Try and open the sample file
        try {
            SampleCache samf = new SampleCache(new File(mContext.getExternalFilesDir(null), LoggingService.CACHEFILE_NAME), true);
            int nSams = Math.min(BITMAP_WIDTH / mSampleWidthPx, samf.getUsedSamples());
            if (nSams > 0) {
                Sample[] sama = new Sample[nSams];
                samf.snapshot(sama, 0, nSams);
                int left = BITMAP_RIGHT - nSams * mSampleWidthPx;
                for (int sami = sama.length - nSams; sami < sama.length; sami++) {
                    drawSample(sama[sami], left);
                    left += mSampleWidthPx;
                }
            }
            samf.close();
        } catch (IOException ioe) {
        }
    }

    private void drawSample(Sample sample, int left) {
        // Strength is interpreted as a percentage of measured depth. Convert to an error in metres
        float depthError = sample.depth * (100 - sample.strength) / 100;
        float depthBottom = (sample.depth + depthError) * BITMAP_HEIGHT / mMaxDepth;
        float depthMid = sample.depth * BITMAP_HEIGHT / mMaxDepth;
        float depthTop = (sample.depth - depthError) * BITMAP_HEIGHT / mMaxDepth;

        int right = left + mSampleWidthPx;

        if (mSampleWidthPx == 1)
            mOffscreenCanvas.drawLine(left, depthBottom, left, BITMAP_BOTTOM, mBottomPaint);
        else
            mOffscreenCanvas.drawRect(left, depthBottom, right, BITMAP_BOTTOM, mBottomPaint);

        if (sample.depth <= 0)
            return;

        mDepthMat.setScale(1, Math.abs(depthTop - depthBottom));
        mDepthMat.postTranslate(0, depthMid);
        mDepthShader.setLocalMatrix(mDepthMat);
        if (mSampleWidthPx == 1)
            mOffscreenCanvas.drawLine(left, depthBottom, left, depthTop, mDepthPaint);
        else
            mOffscreenCanvas.drawRect(left, depthTop, right, depthBottom, mDepthPaint);

        if (sample.fishDepth > 0 && sample.depth > sample.fishDepth) {
            float fishError = sample.fishDepth * (100 - sample.fishStrength) / 100f;
            float fishBottom = (sample.fishDepth + fishError) * BITMAP_HEIGHT / mMaxDepth;
            float fishMid = sample.fishDepth * BITMAP_HEIGHT / mMaxDepth;
            float fishTop = (sample.fishDepth - fishError) * BITMAP_HEIGHT / mMaxDepth;

            mFishMat.setScale(1, Math.abs(fishTop - fishBottom));
            mFishMat.postTranslate(0, fishMid);
            mFishShader.setLocalMatrix(mFishMat);

            if (mSampleWidthPx == 1)
                mOffscreenCanvas.drawLine(left, fishBottom, left, fishTop, mFishPaint);
            else
                mOffscreenCanvas.drawRect(left, Math.max(0, fishTop), right, Math.min(fishBottom, depthMid), mFishPaint);
        }
    }

    @Override // View
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mScreenRect.right = w;
        mScreenRect.bottom = h;
    }

    @Override // View
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Render the drawing bitmap on the screen. We let the depth scale, but make a 1:1 relationship
        // between screen pixels and samples over the width.
        mOffscreenBitmapRect.left = BITMAP_WIDTH - mScreenRect.right;
        if (mOffscreenBitmapRect.left < 0)
            // in the unlikely event the screen is wider than the background bitmap
            mOffscreenBitmapRect.left = 0;
        canvas.drawBitmap(mOffscreenBitmap, mOffscreenBitmapRect, mScreenRect, null);
    }

    /**
     * Must be called to terminate the rendering thread when the view is finished with
     */
    void stop() {
        if (mRenderThread != null) {
            Log.d(TAG, "renderThread stopping");
            mRenderThread.interrupt();
            mRenderThread = null;
        }
    }

    /**
     * Reset the display scale. Will redraw the sample history.
     */
    void resetScale() {
        mMaxDepth = Settings.RANGES[mSettings.getInt(Settings.PREF_RANGE)];
        int nf = (int) (BITMAP_WIDTH * mSettings.getFloat(Settings.PREF_ZOOM_LEVEL)) / BITMAP_WIDTH;
        mSampleWidthPx = (nf < 1) ? 1 : nf;
        redrawAll();
    }

    void zoom(float factor) {
        mSettings.put(Settings.PREF_ZOOM_LEVEL, mSettings.getFloat(Settings.PREF_ZOOM_LEVEL) * factor);
        resetScale();
    }

    /**
     * Handle an incoming sample
     */
    void sample(@NonNull Sample sample) {
        mSampleQueue.add(sample);
    }
}
