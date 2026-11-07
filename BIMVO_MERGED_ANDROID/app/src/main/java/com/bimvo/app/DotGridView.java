package com.bimvo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Decorative, extremely low-contrast dot grid shared by all seven reference screens. */
public final class DotGridView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float stride;
    private final float radius;

    public DotGridView(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        stride = density * 32f;
        radius = Math.max(.55f, density * .26f);
        paint.setColor(Color.argb(11, 220, 220, 236));
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (float x = stride * .8f; x < getWidth(); x += stride) {
            for (float y = stride * .8f; y < getHeight(); y += stride) {
                canvas.drawCircle(x, y, radius, paint);
            }
        }
    }
}
