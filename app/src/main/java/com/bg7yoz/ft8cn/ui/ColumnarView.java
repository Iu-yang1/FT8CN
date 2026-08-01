package com.bg7yoz.ft8cn.ui;

import static android.graphics.Bitmap.Config.ARGB_8888;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import com.bg7yoz.ft8cn.spectrum.SpectrumListener;

public class ColumnarView extends View {
    private int width;
    private final int spacing = 1;
    private final int blockHeight = 5;
    private int blockSpeed = 5;
    private final int distance = 2;

    private boolean drawblock = false;
    private boolean hasData = false;
    private final Paint paint = new Paint();
    private Rect[] newData = new Rect[0];
    private Rect[] blockData = new Rect[0];

    private Bitmap lastBitMap = null;
    private Canvas _canvas;
    private Paint touchPaint;
    private int touch_x = -1;
    private int freq_hz = -1;

    public ColumnarView(Context context) {
        super(context);
    }

    public ColumnarView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ColumnarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setBlockSpeed(int blockSpeed) {
        this.blockSpeed = blockSpeed;
    }

    public void setShowBlock(boolean showBlock) {
        drawblock = showBlock;
    }

    public void setWaveData(int[] data) {
        if (data == null || data.length <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        int binCount = data.length;
        width = Math.max(1, getWidth() / binCount);

        ensureRectCapacity(binCount);
        if (drawblock && hasData) {
            for (int i = 0; i < binCount; i++) {
                Rect block = blockData[i];
                Rect column = newData[i];
                block.left = column.left;
                block.right = column.right;
                if (column.top < block.top) {
                    block.top = column.top - blockHeight - distance;
                } else {
                    block.top += blockSpeed;
                }
                block.bottom = block.top + blockHeight;
            }
        }

        float rateHeight = 0.95f * getHeight() / 256f;
        for (int i = 0; i < binCount; i++) {
            Rect colRect = newData[i];
            colRect.left = i * getWidth() / binCount;
            colRect.top = getHeight() - Math.round(data[i] * rateHeight);
            colRect.right = Math.max(colRect.left + 1, (i + 1) * getWidth() / binCount - spacing);
            colRect.bottom = getHeight();
        }
        hasData = true;
        postInvalidateOnAnimation();
    }

    private void ensureRectCapacity(int binCount) {
        if (newData.length == binCount) {
            return;
        }
        newData = new Rect[binCount];
        blockData = new Rect[binCount];
        for (int i = 0; i < binCount; i++) {
            newData[i] = new Rect();
            blockData[i] = new Rect(0, getHeight() - blockHeight, 0, getHeight());
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        setClickable(true);
        super.onSizeChanged(w, h, oldw, oldh);
        createDrawingBuffer(w, h);
    }

    private void createDrawingBuffer(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        if (lastBitMap != null) {
            lastBitMap.recycle();
        }
        lastBitMap = Bitmap.createBitmap(w, h, ARGB_8888);
        _canvas = new Canvas(lastBitMap);
        LinearGradient linearGradient = new LinearGradient(0f, 0f, 0f, getHeight(),
                new int[]{0xff00ffff, 0xff00ffff, Color.BLUE},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP);
        paint.setShader(linearGradient);

        touchPaint = new Paint();
        touchPaint.setColor(0xff00ffff);
        touchPaint.setStrokeWidth(2);
        hasData = false;
        for (Rect block : blockData) {
            block.top = h - blockHeight;
            block.bottom = h;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (lastBitMap == null) {
            createDrawingBuffer(getWidth(), getHeight());
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (_canvas == null || lastBitMap == null) {
            return;
        }

        _canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        for (Rect rect : newData) {
            _canvas.drawRect(rect, paint);
        }
        if (drawblock) {
            for (Rect rect : blockData) {
                _canvas.drawRect(rect, paint);
            }
        }
        canvas.drawBitmap(lastBitMap, 0, 0, null);

        if (touch_x > 0) {
            freq_hz = Math.round((float) SpectrumListener.DISPLAY_MAX_FREQUENCY_HZ
                    * (float) touch_x / (float) getWidth());
            canvas.drawLine(touch_x, 0, touch_x, getHeight(), touchPaint);
        }
    }

    public void setTouch_x(int touch_x) {
        this.touch_x = touch_x;
        postInvalidateOnAnimation();
    }

    public int getFreq_hz() {
        return freq_hz;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (lastBitMap != null) {
            lastBitMap.recycle();
            lastBitMap = null;
            _canvas = null;
        }
        super.onDetachedFromWindow();
    }
}
