package com.bimvo.app;

import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Standalone regression checks of the production classes, including the archive's Kaldi fixture. */
public final class SpeakerIntegrationChecks {
    public static void main(String[] args) throws Exception {
        checkClustering();
        if(VadInputGain.fromLevels(0,0)!=1 || VadInputGain.fromLevels(.1,.4)!=1 || VadInputGain.fromLevels(.003,.05)!=8)
            throw new AssertionError("VAD gain silence/normal/quiet");
        if(VadInputGain.fromLevels(.003,1)!=1)throw new AssertionError("VAD gain headroom");
        List<Utterance> source=Arrays.asList(new Utterance(0,"speaker_1",0,1000,"A"),
            new Utterance(1,"speaker_1",1000,2000,"B"),new Utterance(2,"speaker_1",2100,3000,"C"),
            new Utterance(3,"speaker_2",3000,3500,"D"),new Utterance(4,"speaker_1",3500,4000,"E"));
        List<SpeakerTimelineIntervals.Span> spans=SpeakerTimelineIntervals.group(source);
        if(spans.size()!=4 || spans.get(0).end!=2000 || source.get(0).endMs!=1000)
            throw new AssertionError("Timeline grouping lost silence/voice boundary or changed transcript");
        System.out.println("VAD gain and timeline grouping: OK");
        Path fixture=Path.of(args[0]);
        byte[] wav=Files.readAllBytes(fixture.resolve("pcm16.wav"));
        if(wav.length!=32044)throw new AssertionError("Unexpected WAV fixture");
        ByteBuffer pcm=ByteBuffer.wrap(wav,44,32000).order(ByteOrder.LITTLE_ENDIAN);
        float[] wave=new float[16000];for(int i=0;i<wave.length;i++)wave[i]=pcm.getShort()/32768f;
        float[][] features=SpeakerFbank.extract(wave);
        if(features.length!=98)throw new AssertionError("Frame count");
        ByteBuffer reference=ByteBuffer.wrap(Files.readAllBytes(fixture.resolve("fbank.f32le"))).order(ByteOrder.LITTLE_ENDIAN);
        double max=0,square=0;int failed=0;
        for(float[] frame:features)for(float value:frame){
            float expected=reference.getFloat();double difference=Math.abs(value-expected);
            max=Math.max(max,difference);square+=difference*difference;
            if(difference>1e-4+1e-3*Math.abs(expected))failed++;
        }
        System.out.println("Fbank max_abs="+max+" rmse="+Math.sqrt(square/(98*80))+" outside_tolerance="+failed);
        if(failed!=0)throw new AssertionError("Kaldi fixture mismatch");
        if(args.length>1){ByteBuffer output=ByteBuffer.allocate(98*80*4).order(ByteOrder.LITTLE_ENDIAN);for(float[] f:features)for(float v:f)output.putFloat(v);Files.write(Path.of(args[1]),output.array());}
    }
    private static SpeakerClustering.Window window(double start,double duration,float... vector){
        SpeakerClustering.Window w=new SpeakerClustering.Window(Math.round(start*16000),Math.round((start+duration)*16000));w.embedding=vector;return w;
    }
    private static void labels(int[] expected,SpeakerClustering.Window... input)throws Exception{
        List<SpeakerClustering.Window> windows=new ArrayList<>(Arrays.asList(input));SpeakerClustering.cluster(windows,()->false);
        for(int i=0;i<expected.length;i++)if(expected[i]!=windows.get(i).label)throw new AssertionError("Speaker index "+i+": expected "+expected[i]+", got "+windows.get(i).label);
    }
    static void checkClustering()throws Exception{
        if(new Utterance(0,"speaker_1",0,1000,Utterance.UNRECOGNIZED_TEXT).hasRecognizedText())throw new AssertionError("Unrecognized speech must not enter summary");
        if(!new Utterance(0,"speaker_1",0,1000,"Привет").hasRecognizedText())throw new AssertionError("Real transcript text rejected");
        labels(new int[]{1,2,1},window(0,2,1,0,0),window(4,2,0,1,0),window(160,2,.99f,.01f,0));
        labels(new int[]{1,0},window(0,2,1,0,0),window(3,.3,0,0,1));
        labels(new int[]{1,2,3},window(0,2,1,0,0),window(3,2,0,1,0),window(6,2,0,0,1));
        labels(new int[]{1,2,0},window(0,2,1,0),window(3,2,0,1),window(6,.4,1,1));
        labels(new int[]{0,0,1},window(0,2,0,0),window(3,2,Float.NaN,0),window(6,2,1,1));
        labels(new int[]{0},window(0,.5,1,0));
        SpeakerClustering.Window[] bridge=new SpeakerClustering.Window[3];double[] angles={0,48,68};
        for(int i=0;i<3;i++)bridge[i]=window(i*3,2,(float)Math.cos(Math.toRadians(angles[i])),(float)Math.sin(Math.toRadians(angles[i])));
        labels(new int[]{1,2,2},bridge);
        List<SpeakerClustering.Window> many=new ArrayList<>();
        for(int i=0;i<1100;i++){float[] v=new float[3];v[i%3]=1;many.add(window(i*3,2,v));}
        SpeakerClustering.cluster(many,()->false);
        for(int i=0;i<many.size();i++)if(many.get(i).label!=i%3+1)throw new AssertionError("Long-recording pooling");
        try{SpeakerClustering.cluster(many,()->true);throw new AssertionError("Cancellation ignored");}catch(java.io.InterruptedIOException expected){}
        List<SpeakerClustering.Window> windows=SpeakerDiarization.windows(Arrays.asList(new long[]{0,129600},new long[]{160000,176000}));
        long covered=0,previous=0;
        for(SpeakerClustering.Window w:windows){if(w.left<previous||w.right<=w.left||w.end-w.start>32000)throw new AssertionError("Window bounds");covered+=w.right-w.left;previous=w.right;w.label=1;}
        if(covered!=145600)throw new AssertionError("Lost/duplicated samples");
        List<ChunkPlanner.Chunk> chunks=SpeakerDiarization.chunks(windows);
        if(chunks.size()!=2||chunks.get(0).start!=0||chunks.get(0).end!=129600||chunks.get(1).start!=160000)throw new AssertionError("ASR plan crossed pause");
        System.out.println("Clustering/returning voices/unknown/bridge/pooling/cancellation/coverage: OK");
    }
}
