package com.bimvo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Circular microphone meter driven only by live recorder amplitude. */
final class RecordingWaveView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float target, shown;
    RecordingWaveView(Context context) {
        super(context);
        setContentDescription("Уровень микрофона");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    void setLevel(int level) {
        target = Math.max(0, Math.min(100, level)) / 100f;
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        shown += (target - shown) * .22f;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float size = Math.min(getWidth(), getHeight());
        float radius = size * .29f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, size * .012f));
        paint.setColor(BimvoStyle.BORDER);
        canvas.drawCircle(cx, cy, size * .45f, paint);
        paint.setColor(BimvoStyle.TEAL);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 48; i++) {
            double angle = i * Math.PI * 2 / 48;
            float envelope = .35f + .65f * (float)Math.abs(Math.sin(i * .79));
            float inner = radius + size * .04f;
            float outer = inner + size * (.015f + shown * .10f * envelope);
            canvas.drawLine(cx + (float)Math.cos(angle) * inner, cy + (float)Math.sin(angle) * inner,
                    cx + (float)Math.cos(angle) * outer, cy + (float)Math.sin(angle) * outer, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, size * .055f, paint);
        if (Math.abs(target - shown) > .002f && isShown()) postInvalidateOnAnimation();
    }
}
