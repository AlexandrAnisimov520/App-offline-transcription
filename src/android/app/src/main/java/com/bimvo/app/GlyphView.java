package com.bimvo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small bespoke line icons: no icon font, no image assets, no heavyweight UI package. */
public final class GlyphView extends View {
    public enum Icon { ARROW, BACK, PLUS, MIC, HOME, LIST, EXPORT, USER, PLAY, PAUSE,
        PENCIL, FILE, EYE, CHECK, SETTINGS, CLOSE }

    private final Icon icon;
    private final int color;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public GlyphView(Context context, Icon icon, int color) {
        super(context);
        this.icon = icon;
        this.color = color;
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float edge = Math.min(getWidth(), getHeight());
        float x = (getWidth() - edge) / 2f;
        float y = (getHeight() - edge) / 2f;
        canvas.save();
        canvas.translate(x, y);
        canvas.scale(edge / 24f, edge / 24f);
        paint.setColor(color);
        paint.setShader(null);
        paint.setStrokeWidth(1.45f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        Path p = new Path();
        switch (icon) {
            case ARROW:
                p.moveTo(3,12); p.lineTo(21,12); p.moveTo(14,5); p.lineTo(21,12); p.lineTo(14,19); break;
            case BACK:
                p.moveTo(19,3); p.lineTo(10,12); p.lineTo(19,21); break;
            case PLUS:
                p.moveTo(12,4); p.lineTo(12,20); p.moveTo(4,12); p.lineTo(20,12); break;
            case MIC:
                p.addRoundRect(new RectF(9,2,15,15),3,3,Path.Direction.CW);
                p.moveTo(5,11); p.cubicTo(5,22,19,22,19,11); p.moveTo(12,20); p.lineTo(12,23); break;
            case HOME:
                p.moveTo(3,12); p.lineTo(12,4); p.lineTo(21,12); p.lineTo(21,21); p.lineTo(10,21); break;
            case LIST:
                p.moveTo(4,6); p.lineTo(20,6); p.moveTo(4,12); p.lineTo(20,12); p.moveTo(4,18); p.lineTo(20,18); break;
            case EXPORT:
                p.moveTo(12,16); p.lineTo(12,2); p.moveTo(6,8); p.lineTo(12,2); p.lineTo(18,8);
                p.moveTo(4,13); p.lineTo(4,22); p.lineTo(20,22); p.lineTo(20,13); break;
            case USER:
                p.addCircle(12,8,4.1f,Path.Direction.CW);
                p.moveTo(3,22); p.cubicTo(3,12,21,12,21,22); break;
            case PLAY:
                p.moveTo(8,5); p.lineTo(19,12); p.lineTo(8,19); p.close(); break;
            case PAUSE:
                p.moveTo(8,5); p.lineTo(8,19); p.moveTo(16,5); p.lineTo(16,19); break;
            case PENCIL:
                p.moveTo(4,20); p.lineTo(8,19); p.lineTo(20,7); p.lineTo(17,4); p.lineTo(5,16); p.close(); break;
            case FILE:
                p.moveTo(5,2); p.lineTo(16,2); p.lineTo(20,7); p.lineTo(20,22); p.lineTo(5,22); p.close();
                p.moveTo(9,11); p.lineTo(17,11); p.moveTo(9,15); p.lineTo(17,15); break;
            case EYE:
                p.moveTo(2,12); p.cubicTo(7,3,17,3,22,12); p.cubicTo(17,21,7,21,2,12);
                p.addCircle(12,12,3,Path.Direction.CW); break;
            case CHECK:
                p.moveTo(4,12); p.lineTo(10,18); p.lineTo(20,6); break;
            case SETTINGS:
                p.addCircle(12,12,5,Path.Direction.CW);
                p.moveTo(12,1); p.lineTo(12,5); p.moveTo(12,19); p.lineTo(12,23);
                p.moveTo(1,12); p.lineTo(5,12); p.moveTo(19,12); p.lineTo(23,12); break;
            case CLOSE:
                p.moveTo(5,5); p.lineTo(19,19); p.moveTo(19,5); p.lineTo(5,19); break;
        }
        canvas.drawPath(p,paint);
        canvas.restore();
    }
}
