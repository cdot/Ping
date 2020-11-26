package com.cdot.ping;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;

import java.util.concurrent.ConcurrentLinkedQueue;

import static java.lang.Thread.interrupted;

/**
 * A simple view for watching a graph of sonar samples.
 */
public class SonarView extends View {

    private static final String TAG = SonarView.class.getSimpleName();

    // Dimensions of the drawing bitmap
    private static final int BITMAP_HEIGHT = 3600; // cm resolution, max depth 36m
    private static final int BITMAP_WIDTH = 5000; // 1 sample per pixel

    Paint mPaint;
    Shader mWaterShader, mBottomShader;
    Canvas mDrawingCanvas;
    Bitmap mDrawingBitmap;
    Rect mScrollSrcRect;
    Rect mScrollDestRect;
    Rect mDrawSrcRect;
    Rect mDrawDestRect;
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

        // Canvas rectangles used to scroll when samples arrive
        mScrollSrcRect = new Rect(1, 0, BITMAP_WIDTH - 1, BITMAP_HEIGHT);
        mScrollDestRect = new Rect(mScrollSrcRect);
        mScrollDestRect.offset(-1, 0);

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
                        int n = mDepthQueue.size();
                        synchronized (this) {
                            // Scroll the drawing bitmap
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

    void finish() {
        if (mRenderThread != null) {
            Log.d(TAG, "renderThread stopping");
            mRenderThread.interrupt();
            mRenderThread = null;
        }
    }
    void sample(@NonNull Bundle b) {
        int depth = (int) (b.getDouble("depth") * 100.0);
        mDepthQueue.add(depth);
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
}
