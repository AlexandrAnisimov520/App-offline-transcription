package com.bimvo.app;
import java.nio.*;
import java.nio.file.*;
public class PcmTests {
 public static void main(String[] args) throws Exception {
  Path dir=Files.createTempDirectory("bimvo-pcm-");
  try {
   Path input=dir.resolve("input.pcm"), output=dir.resolve("output.pcm");
   ByteBuffer b=ByteBuffer.allocate(32000).order(ByteOrder.LITTLE_ENDIAN);
   for(int i=0;i<16000;i++)b.putShort((short)(1000+100*Math.sin(i*.03)));
   byte[] original=b.array();Files.write(input,original);
   DiarizationPcm.normalize(input.toFile(),output.toFile(),()->false);
   if(!java.util.Arrays.equals(original,Files.readAllBytes(input)))throw new AssertionError("original changed");
   byte[] normalized=Files.readAllBytes(output);if(normalized.length!=original.length)throw new AssertionError("duration changed");
   ByteBuffer v=ByteBuffer.wrap(normalized).order(ByteOrder.LITTLE_ENDIAN);long sum=0;int max=0;
   while(v.hasRemaining()){int n=v.getShort();sum+=n;max=Math.max(max,Math.abs(n));}
   if(Math.abs(sum/16000.0)>1 || max < 900 || max > 1100)throw new AssertionError("normalization mismatch");
   Path cancelled=dir.resolve("cancelled.pcm");
   try{DiarizationPcm.normalize(input.toFile(),cancelled.toFile(),()->true);throw new AssertionError("cancel ignored");}catch(java.io.InterruptedIOException expected){}
   if(Files.exists(cancelled))throw new AssertionError("partial target exposed");
   System.out.println("PASS: original preserved, duration preserved, DC removal, gain cap, atomic cancellation");
  }finally{try(var files=Files.list(dir)){for(Path f:files.toList())Files.delete(f);}Files.delete(dir);}
 }
}
