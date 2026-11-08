package com.bimvo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;

/** Draws the very same utterances used by the transcript and the exporter. */
public final class TimelineView extends View {
    public interface OnIntervalSelected { void onSelected(int turnId); }
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density, scaledDensity;
    private List<Utterance> turns=new ArrayList<>();
    private List<String> speakers=new ArrayList<>();
    private List<SpeakerTimelineIntervals.Span> spans=new ArrayList<>();
    private long duration=60_000;
    private int selected;
    private OnIntervalSelected listener;

    public TimelineView(Context context) {
        super(context);
        density=getResources().getDisplayMetrics().density;
        scaledDensity=getResources().getDisplayMetrics().scaledDensity;
        setClickable(true);
        setContentDescription(context.getString(R.string.map_hint));
    }
    private float dp(float value) { return value*density; }
    private float rowHeight() { return Math.max(dp(44),14*scaledDensity+dp(22)); }
    private float leftEdge() {
        paint.setTextSize(10*scaledDensity);
        float width=dp(88);
        for (String id:speakers) width=Math.max(width,paint.measureText(label(id))+dp(12));
        return Math.min(width,getWidth()*.55f);
    }
    private String label(String id) {
        if ("speaker_unknown".equals(id)) return "Не определён";
        return getContext().getString(R.string.speaker_label,id.startsWith("speaker_") ? id.substring(8) : id);
    }
    public void setData(List<Utterance> data,int selectedTurn,long timelineDurationMs) {
        turns=new ArrayList<>(data);
        turns.sort(java.util.Comparator.comparingLong((Utterance u)->u.startMs).thenComparingLong(u->u.endMs));
        spans=SpeakerTimelineIntervals.group(turns);
        LinkedHashSet<String> ids=new LinkedHashSet<>();
        duration=Math.max(1_000,timelineDurationMs);
        for (Utterance u:turns) { ids.add(u.speakerId); duration=Math.max(duration,u.endMs); }
        speakers=new ArrayList<>(ids);
        selected=selectedTurn;
        requestLayout();
        invalidate();
    }
    public void setSpeakerIntervals(List<SpeakerInterval> intervals) {
        if (intervals.isEmpty()) return; // Legacy/imported transcripts retain their original map.
        spans = new ArrayList<>();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (SpeakerInterval s : intervals) {
            spans.add(new SpeakerTimelineIntervals.Span(s.speakerId, s.start * 1000 / 16000, s.end * 1000 / 16000));
            ids.add(s.speakerId); duration = Math.max(duration, s.end * 1000 / 16000);
        }
        speakers = new ArrayList<>(ids);
        requestLayout(); invalidate();
    }
    public void setOnIntervalSelected(OnIntervalSelected callback) { listener=callback; }

    @Override protected void onMeasure(int width,int height) {
        setMeasuredDimension(resolveSize((int)dp(300),width),
                resolveSize((int)(dp(65)+speakers.size()*rowHeight()),height));
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left=leftEdge(), right=getWidth()-dp(7), chart=Math.max(dp(1),right-left);
        float bottom=dp(22)+Math.max(0,speakers.size()-1)*rowHeight();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1,density*.45f));
        paint.setColor(BimvoStyle.BORDER);
        for (int i=0;i<=4;i++) {
            float x=left+chart*i/4;
            canvas.drawLine(x,dp(7),x,bottom+dp(22),paint);
        }
        paint.setTypeface(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL));
        for (int index=0;index<speakers.size();index++) {
            String id=speakers.get(index);
            float center=dp(22)+index*rowHeight();
            paint.setColor(BimvoStyle.BORDER);
            canvas.drawLine(left,center,right,center,paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(BimvoStyle.WHITE);
            paint.setTextSize(10*scaledDensity);
            canvas.drawText(label(id),dp(2),center+dp(3),paint);
            // One continuous bar for touching chunks of the same voice; real pauses remain visible.
            for (SpeakerTimelineIntervals.Span span:spans) if (span.speaker.equals(id)) {
                float begin=left+chart*span.start/duration;
                float end=left+chart*span.end/duration;
                paint.setColor(BimvoStyle.speakerColor(id));
                paint.setAlpha(220);
                canvas.drawRoundRect(new RectF(begin,center-dp(7),Math.max(begin+dp(2),end),center+dp(7)),dp(4),dp(4),paint);
            }
            paint.setAlpha(255);
            // Highlight only the selected original turn; grouping never changes click targets.
            for (Utterance u:turns) if (u.id==selected && u.speakerId.equals(id)) {
                float begin=left+chart*u.startMs/duration, end=left+chart*u.endMs/duration;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.3f));
                paint.setColor(BimvoStyle.WHITE);
                canvas.drawRoundRect(new RectF(begin,center-dp(9),Math.max(begin+dp(3),end),center+dp(9)),dp(4),dp(4),paint);
                paint.setStyle(Paint.Style.FILL);
            }
            paint.setAlpha(255);
        }
        paint.setColor(BimvoStyle.MUTED);
        paint.setTextSize(9*scaledDensity);
        for (int i=0;i<=4;i++) {
            String time=TranscriptText.clock(duration*i/4);
            float x=left+chart*i/4, width=paint.measureText(time);
            canvas.drawText(time,Math.max(left-dp(8),Math.min(right-width,x-width/2)),bottom+dp(46),paint);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction()==MotionEvent.ACTION_UP && !speakers.isEmpty()) {
            float left=leftEdge();
            if (event.getX()<left || event.getY()>dp(22)+(speakers.size()-.5f)*rowHeight()) return true;
            int row=Math.max(0,Math.min(speakers.size()-1,Math.round((event.getY()-dp(22))/rowHeight())));
            long at=(long)(Math.max(0,Math.min(1,(event.getX()-left)/Math.max(1,getWidth()-dp(7)-left)))*duration);
            Utterance nearest=null;
            long distance=Long.MAX_VALUE;
            for (Utterance u:turns) if (u.speakerId.equals(speakers.get(row)) || "speaker_unknown".equals(u.speakerId)) {
                long gap=Math.max(0,Math.max(u.startMs-at,at-u.endMs));
                if (gap<distance) { nearest=u; distance=gap; }
            }
            if (nearest!=null) {
                selected=nearest.id;
                setContentDescription(label(nearest.speakerId)+" "+TranscriptText.clock(nearest.startMs)
                        +" - "+TranscriptText.clock(nearest.endMs));
                performClick();
                invalidate();
                if (listener!=null) listener.onSelected(selected);
            }
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
