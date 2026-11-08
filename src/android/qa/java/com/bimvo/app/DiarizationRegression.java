package com.bimvo.app;
import java.nio.file.*;
import java.util.*;
public final class DiarizationRegression {
 public static void main(String[] args) throws Exception {
  List<SpeakerInterval> intervals=Arrays.asList(new SpeakerInterval(0,16000,"speaker_1"),
   new SpeakerInterval(4000,6000,"speaker_2"), new SpeakerInterval(16000,16160,"speaker_2"),
   new SpeakerInterval(32000,48000,"speaker_1"));
  List<ChunkPlanner.Chunk> cuts=Arrays.asList(new ChunkPlanner.Chunk(0,0,16000,"ru","old_7"),
   new ChunkPlanner.Chunk(1,16000,32000,"en","old_8"),new ChunkPlanner.Chunk(2,32000,48000,"ru","old_7"));
  DiarizationResult r=new DiarizationResult(intervals,cuts);
  if(!r.chunks.get(0).speakerId.equals("speaker_unknown"))throw new AssertionError("Nested overlap lost");
  if(!r.chunks.get(1).speakerId.equals("speaker_2"))throw new AssertionError("10ms activity lost");
  if(!r.chunks.get(2).speakerId.equals("speaker_1"))throw new AssertionError("Returning ID changed");
  if(r.intervals.size()!=4 || r.intervals.get(1).end!=6000)throw new AssertionError("Raw overlap changed");
  for(int i=0;i<cuts.size();i++) {
   ChunkPlanner.Chunk a=cuts.get(i),b=r.chunks.get(i);
   if(a.start!=b.start||a.end!=b.end||a.index!=b.index||!a.reason.equals(b.reason))throw new AssertionError("ASR cut/LID changed");
  }
  Recording record=new Recording("a","test","a.wav","","audio/wav","hash",1,3000,0,false,
   Arrays.asList(new Utterance(0,"speaker_unknown",0,1000,"mixed")),intervals);
  if(record.speakers().size()!=2)throw new AssertionError("Raw speakers absent from snapshot");
  Files.writeString(Path.of(args[0]),TranscriptText.speakerJson(record));
  intervals.set(0,new SpeakerInterval(0,32000,"speaker_3"));
  if(record.speakerIntervals.get(0).end!=16000)throw new AssertionError("Mutable snapshot");
  try{new SpeakerInterval(5,5,"speaker_1");throw new AssertionError("zero accepted");}catch(IllegalArgumentException expected){}
  System.out.println("PASS: nested overlap, 10ms turn, returning ID, no duplicated ASR, exact cuts/LID, immutable snapshot, raw export");
 }
}
