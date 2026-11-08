package com.bimvo.app;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;

/** Decorative microphone animation; the adjacent status supplies accessible recording state. */
public final class RecordingWaveView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float target, level;
    public RecordingWaveView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    public void setLevel(int value) { target = Math.max(0, Math.min(100, value)) / 100f; invalidate(); }
    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        paint.setShader(new LinearGradient(0, 0, Math.max(1,w), Math.max(1,h),
                BimvoStyle.TEAL, BimvoStyle.PURPLE, Shader.TileMode.CLAMP));
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        level += (target - level) * .14f;
        float w=getWidth(), h=getHeight(), cx=w/2, cy=h/2;
        float radius=Math.min(w,h)*.29f;
        double phase=SystemClock.uptimeMillis()/650.0;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f,getResources().getDisplayMetrics().density));
        for(int i=0;i<3;i++) {
            paint.setAlpha(65-i*16);
            canvas.drawCircle(cx,cy,radius*(1+i*.16f)+(float)Math.sin(phase+i)*radius*(.025f+level*.09f),paint);
        }
        for(int wave=0;wave<4;wave++) {
            paint.setAlpha(220-wave*25);
            path.reset();
            for(int i=0;i<=72;i++) {
                float x=-1+2f*i/72;
                float envelope=(float)Math.pow(Math.max(0,1-x*x),1.5);
                float y=cy+(float)Math.sin(x*7+phase*(1+wave*.08)+wave*.7)*radius*(.09f+level*.67f)*envelope;
                float px=cx+x*radius*1.35f;
                if(i==0)path.moveTo(px,y);else path.lineTo(px,y);
            }
            canvas.drawPath(path,paint);
        }
        paint.setAlpha(255);
        if(isAttachedToWindow() && getWindowVisibility()==VISIBLE && isShown()) postInvalidateOnAnimation();
    }
    @Override protected void onVisibilityChanged(View changed,int visibility) {
        super.onVisibilityChanged(changed,visibility);if(visibility==VISIBLE)invalidate();
    }
}
