package com.bg7yoz.ft8cn.eme.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

public class DopplerCorrectionView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private double maxCorrectionHz = 5000.0;
    private double rxCorrectionHz = 0.0;
    private double txCorrectionHz = 0.0;
    private double selectedCorrectionHz = 0.0;
    private double lastAppliedCorrectionHz = 0.0;
    private boolean limitExceeded = false;

    public DopplerCorrectionView(Context context) {
        super(context);
        init();
    }

    public DopplerCorrectionView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setMinimumHeight(dp(110));
    }

    public void setCorrections(double maxCorrectionHz,
                               double rxCorrectionHz,
                               double txCorrectionHz,
                               double selectedCorrectionHz,
                               double lastAppliedCorrectionHz,
                               boolean limitExceeded) {
        this.maxCorrectionHz = Math.max(1.0, maxCorrectionHz);
        this.rxCorrectionHz = rxCorrectionHz;
        this.txCorrectionHz = txCorrectionHz;
        this.selectedCorrectionHz = selectedCorrectionHz;
        this.lastAppliedCorrectionHz = lastAppliedCorrectionHz;
        this.limitExceeded = limitExceeded;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = resolveSize(dp(118), heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(18);
        float right = getWidth() - dp(18);
        float centerY = getHeight() / 2.0f + dp(10);
        float barHeight = dp(12);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(18, 32, 42));
        canvas.drawRoundRect(left, centerY - barHeight, right, centerY + barHeight, dp(8), dp(8), paint);

        paint.setColor(limitExceeded ? Color.rgb(210, 70, 60) : Color.rgb(70, 150, 180));
        canvas.drawRoundRect(left, centerY - barHeight, right, centerY + barHeight, dp(8), dp(8), paint);

        paint.setStrokeWidth(dp(2));
        paint.setColor(Color.WHITE);
        float centerX = (left + right) / 2.0f;
        canvas.drawLine(centerX, centerY - dp(22), centerX, centerY + dp(22), paint);

        drawMarker(canvas, left, right, centerY, rxCorrectionHz, Color.rgb(120, 210, 255), "RX");
        drawMarker(canvas, left, right, centerY, txCorrectionHz, Color.rgb(255, 190, 110), "TX");
        drawMarker(canvas, left, right, centerY, selectedCorrectionHz, Color.rgb(170, 255, 150), "SEL");
        drawMarker(canvas, left, right, centerY, lastAppliedCorrectionHz, Color.rgb(220, 160, 255), "LAST");

        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(12));
        paint.setColor(limitExceeded ? Color.rgb(255, 120, 100) : Color.WHITE);
        canvas.drawText(String.format(Locale.US,
                        "RX %.1f Hz   TX %.1f Hz   Selected %.1f Hz   Limit %.0f Hz",
                        rxCorrectionHz,
                        txCorrectionHz,
                        selectedCorrectionHz,
                        maxCorrectionHz),
                getWidth() / 2.0f,
                dp(18),
                paint);
        if (limitExceeded) {
            canvas.drawText("correction exceeds safety limit", getWidth() / 2.0f, getHeight() - dp(8), paint);
        }
    }

    private void drawMarker(Canvas canvas,
                            float left,
                            float right,
                            float centerY,
                            double correctionHz,
                            int color,
                            String label) {
        float x = correctionToX(left, right, correctionHz);
        paint.setColor(color);
        paint.setStrokeWidth(dp(3));
        canvas.drawLine(x, centerY - dp(26), x, centerY + dp(26), paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(9));
        canvas.drawText(label, x, centerY - dp(30), paint);
    }

    private float correctionToX(float left, float right, double correctionHz) {
        double normalized = Math.max(-1.0, Math.min(1.0, correctionHz / maxCorrectionHz));
        return (float) ((left + right) / 2.0 + normalized * (right - left) / 2.0);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
