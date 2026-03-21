package com.bg7yoz.ft8cn.ui;
/**
 * 瀑布图自定义控件。
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import static android.graphics.Bitmap.Config.ARGB_8888;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.timer.UtcTimer;

import java.util.ArrayList;
import java.util.List;

public class WaterfallView extends View {
    private int blockHeight = 2;//色块高度
    private float freq_width = 1;//频率的宽度
    private final int cycle = 2;
    private final int symbols = 93;
    private int lastSequential = 0;
    private Bitmap lastBitMap = null;
    private Canvas _canvas;
    private Bitmap scrollBitmap = null;
    private Canvas scrollCanvas = null;
    private int[] colorBuffer = new int[0];
    private final Paint linePaint = new Paint();
    private Paint touchPaint = new Paint();
    private final Paint fontPaint = new Paint();
    private final Paint messagePaint = new Paint();
    private final Paint textLinePaint = new Paint();
//    private final Paint messagePaintBack = new Paint();//消息背景
    private final Paint utcPaint = new Paint();
    private final Paint linearPaint = new Paint();
    private final Paint utcPainBack = new Paint();
    private float pathStart = 0;
    private float pathEnd = 0;

    private int touch_x = -1;
    private int freq_hz = -1;
    private boolean drawMessage = false;//是否画消息内容

    public WaterfallView(Context context) {
        super(context);
    }

    public WaterfallView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public WaterfallView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    private final ArrayList<Ft8Message> messages = new ArrayList<>();


    /**
     * 把dp值转换为像素点
     *
     * @param dp dp值
     * @return 像素点
     */
    private int dpToPixel(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp
                , getResources().getDisplayMetrics());
    }

    private void ensureScrollBuffer(int width, int height) {
        int scrollHeight = height - blockHeight;
        if (width <= 0 || scrollHeight <= 0) {
            scrollBitmap = null;
            scrollCanvas = null;
            return;
        }

        if (scrollBitmap == null
                || scrollBitmap.getWidth() != width
                || scrollBitmap.getHeight() != scrollHeight) {
            if (scrollBitmap != null) {
                scrollBitmap.recycle();
            }
            scrollBitmap = Bitmap.createBitmap(width, scrollHeight, ARGB_8888);
            scrollCanvas = new Canvas(scrollBitmap);
        }
    }

    private void shiftBitmapDown(Paint paint) {
        if (lastBitMap == null || _canvas == null) {
            return;
        }
        ensureScrollBuffer(getWidth(), getHeight());
        if (scrollBitmap == null || scrollCanvas == null) {
            return;
        }
        scrollCanvas.drawBitmap(lastBitMap, 0, 0, null);
        _canvas.drawBitmap(scrollBitmap, 0, blockHeight, paint);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        setClickable(true);
        blockHeight = Math.max(1, h / (symbols * cycle));
        freq_width = (float) w / 3000f;
        if (lastBitMap != null) {
            lastBitMap.recycle();
        }
        lastBitMap = Bitmap.createBitmap(w, h, ARGB_8888);
        _canvas = new Canvas(lastBitMap);
        ensureScrollBuffer(w, h);
        colorBuffer = new int[Math.max(1, w)];
        Paint blackPaint = new Paint();
        blackPaint.setColor(0xFF000000);
        _canvas.drawRect(0, 0, w, h, blackPaint);//先把背景画黑，防止文字重叠

        //linePaint = new Paint();
        linePaint.setColor(0xff990000);
        touchPaint = new Paint();
        touchPaint.setColor(0xff00ffff);
        touchPaint.setStrokeWidth(getResources().getDisplayMetrics().density);


        //fontPaint = new Paint();
        fontPaint.setTextSize(dpToPixel(10));
        fontPaint.setColor(0xff00ffff);
        fontPaint.setAntiAlias(true);
        fontPaint.setDither(true);
        fontPaint.setTextAlign(Paint.Align.LEFT);


        textLinePaint.setColor(0xff00ffff);
        textLinePaint.setAntiAlias(true);
        textLinePaint.setDither(true);
        textLinePaint.setStrokeWidth(2);
        textLinePaint.setStyle(Paint.Style.FILL_AND_STROKE);


        // messagePaint = new Paint();
        messagePaint.setTextSize(dpToPixel(11));
        messagePaint.setColor(0xff00ffff);
        messagePaint.setAntiAlias(true);
        messagePaint.setDither(true);
        messagePaint.setStrokeWidth(0);
        messagePaint.setStyle(Paint.Style.FILL_AND_STROKE);
        messagePaint.setTextAlign(Paint.Align.CENTER);
        messagePaint.setShadowLayer(10,5,5,Color.BLACK);

        //messagePaintBack = new Paint();
//        messagePaintBack.setTextSize(dpToPixel(11));
//        messagePaintBack.setColor(0xff000000);//背景不透明
//        messagePaintBack.setAntiAlias(true);
//        messagePaintBack.setDither(true);
//        messagePaintBack.setStrokeWidth(dpToPixel(3));
//        messagePaintBack.setFakeBoldText(true);
//        messagePaintBack.setStyle(Paint.Style.FILL_AND_STROKE);
//        messagePaintBack.setTextAlign(Paint.Align.CENTER);

        //utcPaint = new Paint();
        utcPaint.setTextSize(dpToPixel(10));
        utcPaint.setColor(0xff00ffff);//
        utcPaint.setAntiAlias(true);
        utcPaint.setDither(true);
        utcPaint.setStrokeWidth(0);
        utcPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        utcPaint.setTextAlign(Paint.Align.LEFT);


        //utcPainBack = new Paint();
        utcPainBack.setTextSize(dpToPixel(10));
        utcPainBack.setColor(0xff000000);//背景不透明
        utcPainBack.setAntiAlias(true);
        utcPainBack.setDither(true);
        utcPainBack.setStrokeWidth(dpToPixel(4));
        utcPainBack.setStyle(Paint.Style.FILL_AND_STROKE);
        utcPainBack.setTextAlign(Paint.Align.LEFT);


        pathStart = blockHeight * 2;
        pathEnd = blockHeight * 90;
        if (pathEnd < 130 * getResources().getDisplayMetrics().density) {//为了保证能写的下
            pathEnd = 130 * getResources().getDisplayMetrics().density;
        }

        super.onSizeChanged(w, h, oldw, oldh);

    }

    @SuppressLint("DefaultLocale")
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (lastBitMap == null) {
            return;
        }
        canvas.drawBitmap(lastBitMap, 0, 0, null);

        //计算频率
        if (touch_x > 0) {//画触摸线
            freq_hz = Math.round(3000f * (float) touch_x / (float) getWidth());
            if (freq_hz > 2900) {
                freq_hz = 2900;
            }
            if (freq_hz < 100) {
                freq_hz = 100;
            }

            if (touch_x > getWidth() / 2) {
                fontPaint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(String.format("%dHz", freq_hz)
                        , touch_x - 10, 250, fontPaint);
            } else {
                fontPaint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(String.format("%dHz", freq_hz)
                        , touch_x + 10, 250, fontPaint);
            }
            canvas.drawLine(touch_x, 0, touch_x, getHeight(), touchPaint);

        }
    }

    public void setWaveData(int[] data, int sequential, List<Ft8Message> msgs) {
        if (drawMessage && msgs != null) {//把需要画的消息复制出来防止多线程访问冲突
            messages.clear();
            messages.addAll(msgs);
        } else {
            messages.clear();//当设定不标记消息时，要清空原来的消息
        }

        if (data == null || data.length <= 0 || lastBitMap == null || _canvas == null) {
            return;
        }

        if (colorBuffer.length != data.length) {
            colorBuffer = new int[data.length];
        }

        //画分割线
        if (sequential != lastSequential) {
            shiftBitmapDown(linePaint);
            _canvas.drawRect(0, 0, getWidth(), getResources().getDisplayMetrics().density
                    , linePaint);
            _canvas.drawText(UtcTimer.getTimeStr(UtcTimer.getSystemTime()), 50
                    , 15 * getResources().getDisplayMetrics().density, utcPainBack);
            _canvas.drawText(UtcTimer.getTimeStr(UtcTimer.getSystemTime()), 50
                    , 15 * getResources().getDisplayMetrics().density, utcPaint);
        }
        lastSequential = sequential;

        //色块分布
        for (int i = 0; i < data.length; i++) {


            if (data[i] < 128) {//低于一半的音量，用蓝色0~256
                colorBuffer[i] = 0xff000000 | (data[i] << 1);
            } else if (data[i] < 192) {
                colorBuffer[i] = 0xff0000ff | (((data[i] - 127)) << 10);//放大4倍
            } else {
                colorBuffer[i] = 0xff00ffff | (((data[i] - 127)) << 18);//放大4倍
            }
        }
        LinearGradient linearGradient = new LinearGradient(0, 0, getWidth() * 2, 0, colorBuffer
                , null, Shader.TileMode.CLAMP);
        linearPaint.setShader(linearGradient);
        shiftBitmapDown(linearPaint);
        _canvas.drawRect(0, 0, getWidth(), blockHeight, linearPaint);

        //消息有3种：普通、CQ、有我
        if (drawMessage && !messages.isEmpty()) {
            drawMessage = false;//只画一遍
            //fontPaint.setTextAlign(Paint.Align.LEFT);
            //fontPaint.setStrikeThruText(true);
            for (Ft8Message msg : messages) {

                if (msg.inMyCall()) {//与我有关
                    messagePaint.setColor(0xffffb2b2);
                    textLinePaint.setColor(0xffffb2b2);
                } else if (msg.checkIsCQ()) {//CQ
                    messagePaint.setColor(0xffeeee00);
                    textLinePaint.setColor(0xffeeee00);
                } else {
                    messagePaint.setColor(0xff00ffff);
                    textLinePaint.setColor(0xff00ffff);
                }

                Path path = new Path();

                path.moveTo(msg.freq_hz * freq_width, pathStart);
                path.lineTo(msg.freq_hz * freq_width, pathEnd);



//                _canvas.drawTextOnPath(msg.getMessageText(true), path
//                        , 0, 0, messagePaintBack);//消息背景
                _canvas.drawTextOnPath(msg.getMessageText(true), path
                        , 0, 0, messagePaint);//消息
                if (GeneralVariables.checkQSLCallsign(msg.getCallsignFrom())) {//画删除线
                    float text_len = messagePaint.measureText(msg.getMessageText(true));
                    float text_start = ((pathEnd- pathStart)-text_len)/2;
                    float text_high =dpToPixel(4);//messagePaint.getFontSpacing()/2;
                    _canvas.drawLine(msg.freq_hz * freq_width + text_high , text_start
                            , msg.freq_hz * freq_width + text_high, text_len + text_start, textLinePaint);
                }
            }
        }


        postInvalidateOnAnimation();
    }

    public void setTouch_x(int touch_x) {
        this.touch_x = touch_x;
        postInvalidateOnAnimation();
    }

    public void setDrawMessage(boolean drawMessage) {
        this.drawMessage = drawMessage;
    }

    public int getFreq_hz() {
        return freq_hz;
    }
}
