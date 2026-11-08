package com.bimvo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/** Wireframe BIMVO prism; vector-drawn, so the logo stays sharp on any screen. */
public final class PrismView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public PrismView(Context context) {
        super(context);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float left = w * .13f, top = h * .09f;
        float[][] pts = {
            {w * .51f, top},
            {w * .93f, h * .31f},
            {w * .82f, h * .79f},
            {w * .31f, h * .91f},
            {left, h * .39f},
            {w * .55f, h * .53f}
        };
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.2f, getResources().getDisplayMetrics().density * .72f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setShader(new LinearGradient(0, 0, w, h,
                BimvoStyle.TEAL, BimvoStyle.PURPLE, Shader.TileMode.CLAMP));
        Path outside = new Path();
        outside.moveTo(pts[0][0], pts[0][1]);
        for (int i = 1; i < 5; i++) outside.lineTo(pts[i][0], pts[i][1]);
        outside.close();
        canvas.drawPath(outside, paint);
        for (int i = 0; i < 5; i++) {
            canvas.drawLine(pts[5][0], pts[5][1], pts[i][0], pts[i][1], paint);
        }
        paint.setShader(null);
    }
}
