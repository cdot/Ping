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
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;

import com.cdot.ping.samplers.Sample;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.lang.Thread.interrupted;

/**
 * A graphical view for watching incoming sonar samples.
 */
public class SonarView extends View {

    private static final String TAG = SonarView.class.getSimpleName();

    // Dimensions of the drawing bitmap
    private static final int BITMAP_HEIGHT = 3600; // cm resolution, max depth 36m
    private static final int BITMAP_BOTTOM = BITMAP_HEIGHT - 1; // cm resolution, max depth 36m
    private static final int BITMAP_WIDTH = 5000; // 1 sample per pixel
    private static final int BITMAP_RIGHT = BITMAP_WIDTH - 1; // 1 sample per pixel
    private static final int MAX_STRENGTH = 255;
    private static final int MAX_FSTRENGTH = 15;
    private static final int[] RANGES = new int[]{3, 6, 9, 18, 24, 36, 36};
    private final Paint mWaterPaint;
    private final Paint mBottomPaint;
    private final Paint mDepthPaint;
    private final Paint mFishPaint;
    private final Canvas mDrawingCanvas;
    private final Bitmap mDrawingBitmap;
    private final Rect mDrawSrcRect;
    private final Rect mDrawDestRect;
    private final Settings mSettings;
    private final ConcurrentLinkedQueue<DecoratedSample> mSampleQueue = new ConcurrentLinkedQueue<>();
    private Thread mRenderThread;

    public SonarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);

        mSettings = new Settings(context);

        // Canvas that we draw to
        mDrawingCanvas = new Canvas();
        mDrawingBitmap = Bitmap.createBitmap(BITMAP_WIDTH, BITMAP_HEIGHT, Bitmap.Config.RGB_565);
        mDrawingCanvas.setBitmap(mDrawingBitmap);

        // Drawing canvas rect for the ares of the image to be drawn
        mDrawSrcRect = new Rect(0, 0, BITMAP_WIDTH, BITMAP_HEIGHT);
        // Screen canvas rect for the area to draw to
        mDrawDestRect = new Rect(0, 0, BITMAP_WIDTH, BITMAP_HEIGHT);

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
        final Shader depthShader = new LinearGradient(0, 0, 0, 1, Color.YELLOW, Color.RED, Shader.TileMode.MIRROR);
        mDepthPaint.setShader(depthShader);

        mFishPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mFishPaint.setAntiAlias(true);
        mFishPaint.setStrokeWidth(1);
        mFishPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        final Shader fishShader = new LinearGradient(0, 0, 0, 1, Color.LTGRAY, Color.GRAY, Shader.TileMode.MIRROR);
        mFishPaint.setShader(fishShader);

        final Matrix depthMat = new Matrix();
        final Matrix fishMat = new Matrix();
        final int SAMPLE_WIDTH = 10;

        mRenderThread = new Thread(new Runnable() {
            public void run() {
                while (!interrupted()) {
                    if (mSampleQueue.size() > 0) {
                        synchronized (this) {
                            int n = mSampleQueue.size();

                            // Scroll the drawing bitmap to accommodate the queued samples
                            Rect srcRect = new Rect(n, 0, BITMAP_RIGHT, BITMAP_BOTTOM);
                            Rect destRect = new Rect(0, 0, BITMAP_RIGHT - n * SAMPLE_WIDTH, BITMAP_BOTTOM);
                            mDrawingCanvas.drawBitmap(mDrawingBitmap, srcRect, destRect, null);

                            // Draw the water
                            mDrawingCanvas.drawRect(BITMAP_RIGHT - n * SAMPLE_WIDTH, 0, BITMAP_RIGHT, BITMAP_BOTTOM, mWaterPaint);

                            int left = BITMAP_RIGHT - SAMPLE_WIDTH;
                            int right = BITMAP_RIGHT;
                            for (; n > 0; n--) {
                                DecoratedSample ds = mSampleQueue.poll();
                                Sample sample = ds.sample;
                                int maxDepth = ds.maxDepth;

                                float depthError = ds.depthError;
                                float depthBottom = (sample.depth - depthError) * BITMAP_HEIGHT / maxDepth;
                                float depthMid = sample.depth * BITMAP_HEIGHT / maxDepth;
                                float depthTop = (sample.depth + depthError) * BITMAP_HEIGHT / maxDepth;

                                //mDrawingCanvas.drawLine(BITMAP_RIGHT - n, depthBottom, BITMAP_RIGHT - n, BITMAP_BOTTOM, mBottomPaint);
                                mDrawingCanvas.drawRect(left, depthBottom, right, BITMAP_BOTTOM, mBottomPaint);

                                if (sample.fishDepth > 0) {
                                    float fishError = ds.fishError;
                                    float fishBottom = (sample.fishDepth - fishError) * BITMAP_HEIGHT / maxDepth;
                                    float fishMid = sample.fishDepth * BITMAP_HEIGHT / maxDepth;
                                    float fishTop = (sample.fishDepth + fishError) * BITMAP_HEIGHT / maxDepth;

                                    fishMat.setScale(1, Math.abs(fishTop - fishBottom));
                                    fishMat.postTranslate(0, fishMid);
                                    fishShader.setLocalMatrix(fishMat);

                                    //mDrawingCanvas.drawLine(BITMAP_RIGHT - n, fishBottom, BITMAP_RIGHT - n, fishTop, mFishPaint);
                                    mDrawingCanvas.drawRect(left, fishTop, right, fishBottom, mFishPaint);
                                }

                                depthMat.setScale(1, Math.abs(depthTop - depthBottom));
                                depthMat.postTranslate(0, depthMid);
                                depthShader.setLocalMatrix(depthMat);
                                //mDrawingCanvas.drawLine(left, depthBottom, right, depthTop, mDepthPaint);
                                mDrawingCanvas.drawRect(left, depthTop, right, depthBottom, mDepthPaint);

                                right = left;
                                left -= SAMPLE_WIDTH;
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

    public void onSaveInstanceState(Bundle bundle) {
        if (bundle == null)
            return;
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        mDrawingBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        bundle.putByteArray("sonarview_bitmap", stream.toByteArray());
        super.onSaveInstanceState();
    }

    public void onRestoreInstanceState(Bundle bundle) {
        if (bundle == null)
            return;
        byte[] bits = bundle.getByteArray("sonarview_bitmap");
        Bitmap restore = BitmapFactory.decodeByteArray(bits, 0, bits.length, null);
        mDrawingCanvas.drawBitmap(restore, mDrawSrcRect, mDrawDestRect, null);
    }

    @Override // View
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mDrawDestRect.right = w;
        mDrawDestRect.bottom = h;
    }

    @Override // View
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Render the drawing bitmap on the screen. We let the depth scale, but make a 1:1 relationship
        // between screen pixels and samples over the width.
        mDrawSrcRect.left = BITMAP_WIDTH - mDrawDestRect.right;
        if (mDrawSrcRect.left < 0)
            // in the unlikely event the screen is wider than the background bitmap
            mDrawSrcRect.left = 0;
        canvas.drawBitmap(mDrawingBitmap, mDrawSrcRect, mDrawDestRect, null);
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
     * Handle an incoming sample
     */
    void sample(@NonNull Sample sample) {
        DecoratedSample ds = new DecoratedSample();
        ds.sample = sample;
        // Remember the range limit
        ds.maxDepth = RANGES[mSettings.getInt(Settings.PREF_RANGE)];
        // Convert strength in the range 0..255 to an error in metres
        float maxError = ds.maxDepth / (RANGES.length - 1.0f);
        ds.depthError = (MAX_STRENGTH - sample.strength) * maxError / MAX_STRENGTH;
        // Convert fishstrength in the range 0..15 to an error in metres
        maxError /= 2;
        ds.fishError = (MAX_FSTRENGTH - sample.fishStrength) * maxError / MAX_FSTRENGTH;
        mSampleQueue.add(ds);
    }

    private static class DecoratedSample {
        Sample sample;
        float depthError, fishError;
        int maxDepth;
    }
}
