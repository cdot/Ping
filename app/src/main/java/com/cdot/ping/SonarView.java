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
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;

import java.util.concurrent.ConcurrentLinkedQueue;

import static java.lang.Thread.interrupted;

/**
 * A simple view for watching incoming sonar samples.
 */
public class SonarView extends View {

    private static final String TAG = SonarView.class.getSimpleName();

    // Dimensions of the drawing bitmap
    private static final int BITMAP_HEIGHT = 3600; // cm resolution, max depth 36m
    private static final int BITMAP_WIDTH = 5000; // 1 sample per pixel

    private Paint mPaint;
    private Shader mWaterShader, mBottomShader;
    private Canvas mDrawingCanvas;
    private Bitmap mDrawingBitmap;
    private Rect mDrawSrcRect;
    private Rect mDrawDestRect;

    private ConcurrentLinkedQueue<Integer> mDepthQueue = new ConcurrentLinkedQueue<>();
    Thread mRenderThread = null;

    public SonarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);

        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaint.setAntiAlias(true);
        mPaint.setStrokeWidth(1);
        mPaint.setStyle(Paint.Style.STROKE);

        // Canvas that we draw to
        mDrawingCanvas = new Canvas();
        mDrawingBitmap = Bitmap.createBitmap(BITMAP_WIDTH, BITMAP_HEIGHT, Bitmap.Config.RGB_565);
        mDrawingCanvas.setBitmap(mDrawingBitmap);

        // Drawing canvas rect for the ares of the image to be drawn
        mDrawSrcRect = new Rect(0, 0, BITMAP_WIDTH, BITMAP_HEIGHT);
        // Screen canvas rect for the area to draw to
        mDrawDestRect = new Rect(0, 0, BITMAP_WIDTH, BITMAP_HEIGHT);

        mWaterShader = new LinearGradient(0, 0, 0, BITMAP_HEIGHT, Color.CYAN, Color.BLUE, Shader.TileMode.MIRROR);
        mBottomShader = new LinearGradient(0, 0, 0, BITMAP_HEIGHT, Color.YELLOW, Color.BLACK, Shader.TileMode.MIRROR);

        mRenderThread = new Thread(new Runnable() {
            public void run() {
                while (!interrupted()) {
                    if (mDepthQueue.size() > 0) {
                        synchronized (this) {
                            int n = mDepthQueue.size();
                            // Scroll the drawing bitmap to accommodate the queued samples
                            Rect srcRect = new Rect(1, 0, BITMAP_WIDTH - 1, BITMAP_HEIGHT);
                            Rect destRect = new Rect(srcRect);
                            destRect.offset(-n, 0);
                            mDrawingCanvas.drawBitmap(mDrawingBitmap, srcRect, destRect, null);
                            for (; n > 0; n--) {
                                Integer depth = mDepthQueue.poll();
                                mPaint.setShader(mWaterShader);
                                mDrawingCanvas.drawLine(BITMAP_WIDTH - n, 0, BITMAP_WIDTH - n, depth, mPaint);
                                mPaint.setShader(mBottomShader);
                                mDrawingCanvas.drawLine(BITMAP_WIDTH - n, depth, BITMAP_WIDTH - n, BITMAP_HEIGHT - 1, mPaint);
                            }
                        }

                        post(new Runnable() {
                            public void run() {
                                invalidate();
                            }
                        });
                    } else {
                        try {
                            Thread.sleep(100);
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
     * @param b a bundle that includes at least double "depth"
     */
    void sample(@NonNull Bundle b) {
        int depth = (int) (b.getDouble("depth") * 100.0);
        mDepthQueue.add(depth);
    }
}
