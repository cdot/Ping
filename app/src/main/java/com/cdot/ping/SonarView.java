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
    private static final int MAX_DEPTH = 36;
    private static final int DEPTH_RANGE = BITMAP_HEIGHT - MAX_STRENGTH;
    private int depth2bm(double d) {
        return (int)(MAX_STRENGTH / 2 + d * DEPTH_RANGE / MAX_DEPTH);
    }

    private static final int[] RANGES = new int[] { 3, 6, 9, 18, 24, 36, 36 };

    private Paint mWaterPaint;
    private Paint mBottomPaint;
    private Paint mPaint;

    private Canvas mDrawingCanvas;
    private Bitmap mDrawingBitmap;
    private Rect mDrawSrcRect;
    private Rect mDrawDestRect;
    private Settings mSettings;

    private class DecoratedSample {
        Sample sample;
        float depthError;
        int maxDepth;
    }

    public void onSaveInstanceState(Bundle bundle) {
        if (bundle == null)
            return;
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        mDrawingBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        bundle.putByteArray("sonarview_bitmap", stream.toByteArray());
    }

    public void onRestoreInstanceState(Bundle bundle) {
        if (bundle == null)
            return;
        byte[] bits = bundle.getByteArray("sonarview_bitmap");
        Bitmap restore = BitmapFactory.decodeByteArray(bits, 0, bits.length, null);
        mDrawingCanvas.drawBitmap(restore, mDrawSrcRect, mDrawDestRect, null);
    }

    private ConcurrentLinkedQueue<DecoratedSample> mSampleQueue = new ConcurrentLinkedQueue<>();
    Thread mRenderThread;

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
        mWaterPaint.setStyle(Paint.Style.FILL);
        Shader waterShader = new LinearGradient(0, 0, 0, BITMAP_HEIGHT, Color.CYAN, Color.BLUE, Shader.TileMode.MIRROR);
        mWaterPaint.setShader(waterShader);

        mBottomPaint = new Paint();
        mBottomPaint.setStyle(Paint.Style.STROKE);
        Shader bottomShader = new LinearGradient(0, 0, 0, BITMAP_HEIGHT, Color.GREEN, Color.BLACK, Shader.TileMode.MIRROR);
        mBottomPaint.setShader(bottomShader);

        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaint.setAntiAlias(true);
        mPaint.setStrokeWidth(1);
        mPaint.setStyle(Paint.Style.STROKE);
        final Shader shader = new LinearGradient(0, 0, 0, 1, Color.YELLOW, Color.RED, Shader.TileMode.MIRROR);
        final Matrix mat = new Matrix();
        mPaint.setShader(shader);

        mRenderThread = new Thread(new Runnable() {
            public void run() {
                while (!interrupted()) {
                    if (mSampleQueue.size() > 0) {
                        synchronized (this) {
                            int n = mSampleQueue.size();
                            // Scroll the drawing bitmap to accommodate the queued samples
                            Rect srcRect = new Rect(n, 0, BITMAP_RIGHT, BITMAP_BOTTOM);
                            Rect destRect = new Rect(0, 0, BITMAP_RIGHT - n, BITMAP_BOTTOM);
                            mDrawingCanvas.drawBitmap(mDrawingBitmap, srcRect, destRect, null);
                            mDrawingCanvas.drawRect(BITMAP_RIGHT - n, 0, BITMAP_RIGHT, BITMAP_BOTTOM, mWaterPaint);

                            for (; n > 0; n--) {
                                DecoratedSample ds = mSampleQueue.poll();
                                Sample sample = ds.sample;
                                float error = ds.depthError;
                                int maxDepth = ds.maxDepth;
                                float bottom = (float)(sample.depth - error) * BITMAP_HEIGHT / maxDepth;
                                float mid = (float)sample.depth * BITMAP_HEIGHT / maxDepth;
                                float top = (float)(sample.depth + error) * BITMAP_HEIGHT / maxDepth;
                                mDrawingCanvas.drawLine(BITMAP_RIGHT - n, bottom, BITMAP_RIGHT - n, BITMAP_BOTTOM, mBottomPaint);
                                mat.setScale(1, (float)(top - bottom));
                                mat.postTranslate(0, (float)mid);
                                shader.setLocalMatrix(mat);
                                mDrawingCanvas.drawLine(BITMAP_RIGHT - n, bottom, BITMAP_RIGHT - n, top, mPaint);
                            }
                        }

                        post(new Runnable() {
                            public void run() {
                                invalidate();
                            }
                        });
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
        float maxError = ds.maxDepth / (RANGES.length - 1);
        ds.depthError = (MAX_STRENGTH - sample.strength) * maxError / MAX_STRENGTH;
        mSampleQueue.add(ds);
    }
}
