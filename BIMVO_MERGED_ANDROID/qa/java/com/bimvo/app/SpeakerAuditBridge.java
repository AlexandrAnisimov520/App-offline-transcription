package com.bimvo.app;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Host-only bridge to replay production resampling and window geometry without Android MediaCodec. */
public final class SpeakerAuditBridge {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("gain")) { System.out.println(VadInputGain.estimate(new java.io.File(args[1]), () -> false)); return; }
        if (args[0].equals("resample")) {
            int rate=Integer.parseInt(args[3]),channels=Integer.parseInt(args[4]);
            if(channels<1||channels>8)throw new IllegalArgumentException("channels");
            try(RandomAccessFile input=new RandomAccessFile(args[1],"r");OutputStream output=Files.newOutputStream(Path.of(args[2]))){
                PcmResampler converter=new PcmResampler(rate,output);
                byte[] buffer=new byte[8192*channels*2];
                while(input.getFilePointer()<input.length()){
                    int count=(int)Math.min(buffer.length,input.length()-input.getFilePointer());
                    if(count%(2*channels)!=0)throw new IOException("partial PCM frame");
                    input.readFully(buffer,0,count);
                    for(int offset=0;offset<count;offset+=channels*2){
                        float sum=0;
                        for(int c=0;c<channels;c++){int i=offset+c*2;sum+=(short)((buffer[i]&255)|(buffer[i+1]<<8))/32768f;}
                        converter.add(sum/channels);
                    }
                }
                converter.finish();
            }
        }else if(args[0].equals("windows")){
            List<long[]> speech=new ArrayList<>();
            List<String> lines=Files.readAllLines(Path.of(args[1]));
            for(String line:lines.subList(1,lines.size())){String[] p=line.split(",");speech.add(new long[]{Long.parseLong(p[0]),Long.parseLong(p[1])});}
            List<String> rows=new ArrayList<>();rows.add("index,start,end,left,right");int index=0;
            for(SpeakerClustering.Window w:SpeakerDiarization.windows(speech))rows.add(index+++","+w.start+","+w.end+","+w.left+","+w.right);
            Files.write(Path.of(args[2]),rows);
        }else throw new IllegalArgumentException("Expected resample/windows");
    }
}
