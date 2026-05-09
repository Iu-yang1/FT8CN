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

import java.util.ArrayList;
import java.util.List;

public class ColumnarView extends View {
    private int width;
    private final int spacing = 1;
    private final int blockHeight = 5;
    private int blockSpeed = 5;
    private final int distance = 2;

    private boolean drawblock = false;
    private final Paint paint = new Paint();
    private final List<Rect> newData = new ArrayList<>();
    private final List<Rect> blockData = new ArrayList<>();

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

        if (drawblock && !newData.isEmpty()) {
            if (blockData.size() != newData.size()) {
                blockData.clear();
                for (int i = 0; i < binCount; i++) {
                    Rect blockRect = new Rect();
                    blockRect.top = getHeight() - blockHeight;
                    blockRect.bottom = getHeight();
                    blockData.add(blockRect);
                }
            }
            for (int i = 0; i < blockData.size() && i < newData.size(); i++) {
                Rect block = blockData.get(i);
                Rect column = newData.get(i);
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

        newData.clear();
        float rateHeight = 0.95f * getHeight() / 256f;
        for (int i = 0; i < binCount; i++) {
            Rect colRect = new Rect();
            colRect.left = i * getWidth() / binCount;
            colRect.top = getHeight() - Math.round(data[i] * rateHeight);
            colRect.right = Math.max(colRect.left + 1, (i + 1) * getWidth() / binCount - spacing);
            colRect.bottom = getHeight();
            newData.add(colRect);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        setClickable(true);
        super.onSizeChanged(w, h, oldw, oldh);

        lastBitMap = Bitmap.createBitmap(w, h, ARGB_8888);
        _canvas = new Canvas(lastBitMap);
        LinearGradient linearGradient = new LinearGradient(0f, 0f, 0f, getHeight(),
                new int[]{0xff00ffff, 0xff00ffff, Color.BLUE},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP);
        paint.setShader(linearGradient);

        touchPaint = new Paint();
        touchPaint.setColor(0xff00ffff);
        touchPaint.setStrokeWidth(2);
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
        invalidate();
    }

    public void setTouch_x(int touch_x) {
        this.touch_x = touch_x;
    }

    public int getFreq_hz() {
        return freq_hz;
    }
}
