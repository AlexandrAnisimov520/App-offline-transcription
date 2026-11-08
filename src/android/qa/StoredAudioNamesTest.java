package com.bimvo.app;
import java.nio.file.*;
import java.util.*;
public class StoredAudioNamesTest {
 public static void main(String[] args) throws Exception {
  String id="12345678-1234-1234-1234-123456789012";
  String old=id+".audio", next=id+"_Лекция.m4a";
  if(!StoredAudioNames.valid(id,old)||!StoredAudioNames.valid(id,next))throw new AssertionError("valid name rejected");
  for(String n:new String[]{"../outside",id+"_../outside",id+"_..\\outside",id+"_",""})if(StoredAudioNames.valid(id,n))throw new AssertionError("unsafe name accepted");
  Path dir=Files.createTempDirectory("bimvo-rename-");
  try {
   byte[] audio={1,2,3,4};Files.write(dir.resolve(old),audio);
   StoredAudioNames.writeJournal(dir.resolve(".rename-"+id).toFile(),old,next);
   Files.move(dir.resolve(old),dir.resolve(next));
   StoredAudioNames.recoverRenames(dir.toFile(),Set.of(old));
   if(!Arrays.equals(audio,Files.readAllBytes(dir.resolve(old)))||Files.exists(dir.resolve(next)))throw new AssertionError("interrupted rename not restored");
   StoredAudioNames.writeJournal(dir.resolve(".rename-"+id).toFile(),old,next);
   Files.move(dir.resolve(old),dir.resolve(next));
   StoredAudioNames.recoverRenames(dir.toFile(),Set.of(next));
   if(!Arrays.equals(audio,Files.readAllBytes(dir.resolve(next))))throw new AssertionError("committed rename lost");
   System.out.println("PASS: Cyrillic and legacy filenames; traversal rejected; interrupted and committed rename recovery preserves audio bytes");
  } finally {try(var fs=Files.list(dir)){for(Path f:fs.toList())Files.delete(f);}Files.delete(dir);}
 }
}
