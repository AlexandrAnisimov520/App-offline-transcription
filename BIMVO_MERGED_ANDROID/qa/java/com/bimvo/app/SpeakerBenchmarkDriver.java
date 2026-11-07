package com.bimvo.app;
import java.nio.file.*;
import java.nio.*;
import java.io.RandomAccessFile;
import java.util.*;

/** Compare the actual Android feature/clustering classes to the Python reference, without Android UI. */
public final class SpeakerBenchmarkDriver {
    public static void main(String[] args)throws Exception{
        if(args[0].equals("features")){
            Path output=Path.of(args[3]);Files.createDirectories(output);
            List<String> metadata=new ArrayList<>();metadata.add("start,end,frames,file");
            List<String> rows=Files.readAllLines(Path.of(args[2]));
            try(RandomAccessFile pcm=new RandomAccessFile(args[1],"r")){
                int index=0;
                for(String line:rows.subList(1,rows.size())){
                    String[] row=line.split(",");long start=Long.parseLong(row[0]),end=Long.parseLong(row[1]);
                    float[][] feature=row[2].equals("1")||end-start<4000?null:SpeakerFbank.extract(SpeakerDiarization.read(pcm,start,end));
                    String name="fbank-"+index+++".bin";
                    if(feature!=null){ByteBuffer bytes=ByteBuffer.allocate(feature.length*80*4).order(ByteOrder.LITTLE_ENDIAN);for(float[] frame:feature)for(float v:frame)bytes.putFloat(v);Files.write(output.resolve(name),bytes.array());}
                    metadata.add(start+","+end+","+(feature==null?0:feature.length)+","+name);
                }
            }
            Files.write(output.resolve("features.csv"),metadata);
        }else{
            List<SpeakerClustering.Window> windows=new ArrayList<>();
            for(String line:Files.readAllLines(Path.of(args[1]))){
                String[] row=line.split(",");SpeakerClustering.Window w=new SpeakerClustering.Window(Long.parseLong(row[0]),Long.parseLong(row[1]));
                if(row.length>2){w.embedding=new float[row.length-2];for(int i=2;i<row.length;i++)w.embedding[i-2]=Float.parseFloat(row[i]);}windows.add(w);
            }
            SpeakerClustering.cluster(windows,()->false);List<String> labels=new ArrayList<>();for(SpeakerClustering.Window w:windows)labels.add(Integer.toString(w.label));Files.write(Path.of(args[2]),labels);
        }
    }
}
