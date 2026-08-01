package com.bg7yoz.ft8cn.eme.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

public class MoonSkyView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private double azimuthDeg = Double.NaN;
    private double elevationDeg = Double.NaN;

    public MoonSkyView(Context context) {
        super(context);
        init();
    }

    public MoonSkyView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setMinimumHeight(dp(170));
    }

    public void setMoonPosition(double azimuthDeg, double elevationDeg) {
        this.azimuthDeg = azimuthDeg;
        this.elevationDeg = elevationDeg;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int desiredHeight = dp(190);
        int height = resolveSize(desiredHeight, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float cx = width / 2.0f;
        float cy = height / 2.0f + dp(8);
        float radius = Math.min(width, height) * 0.38f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(13, 28, 42));
        canvas.drawCircle(cx, cy, radius, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.rgb(80, 130, 150));
        canvas.drawCircle(cx, cy, radius, paint);
        canvas.drawCircle(cx, cy, radius * 0.66f, paint);
        canvas.drawCircle(cx, cy, radius * 0.33f, paint);
        canvas.drawLine(cx, cy - radius, cx, cy + radius, paint);
        canvas.drawLine(cx - radius, cy, cx + radius, cy, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(11));
        paint.setColor(Color.rgb(180, 220, 230));
        canvas.drawText("N", cx, cy - radius - dp(5), paint);
        canvas.drawText("S", cx, cy + radius + dp(16), paint);
        canvas.drawText("W", cx - radius - dp(10), cy + dp(4), paint);
        canvas.drawText("E", cx + radius + dp(10), cy + dp(4), paint);

        if (!Double.isFinite(azimuthDeg) || !Double.isFinite(elevationDeg)) {
            drawCenteredText(canvas, cx, cy, "Moon unavailable", Color.rgb(240, 160, 100));
            return;
        }

        boolean belowHorizon = elevationDeg < 0.0;
        double clampedElevation = Math.max(0.0, Math.min(90.0, elevationDeg));
        double radial = (90.0 - clampedElevation) / 90.0;
        double azRad = Math.toRadians(azimuthDeg);
        float moonX = (float) (cx + Math.sin(azRad) * radius * radial);
        float moonY = (float) (cy - Math.cos(azRad) * radius * radial);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(belowHorizon ? Color.rgb(180, 90, 70) : Color.rgb(255, 230, 130));
        canvas.drawCircle(moonX, moonY, dp(8), paint);
        paint.setColor(Color.argb(90, 255, 230, 130));
        canvas.drawCircle(moonX, moonY, dp(15), paint);

        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(12));
        paint.setColor(Color.WHITE);
        canvas.drawText(String.format(Locale.US, "Moon Az %.1f  El %.1f", azimuthDeg, elevationDeg),
                cx,
                dp(18),
                paint);
        if (belowHorizon) {
            paint.setColor(Color.rgb(255, 150, 120));
            canvas.drawText("below horizon", cx, height - dp(10), paint);
        }
    }

    private void drawCenteredText(Canvas canvas, float cx, float cy, String text, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(13));
        paint.setColor(color);
        canvas.drawText(text, cx, cy, paint);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
