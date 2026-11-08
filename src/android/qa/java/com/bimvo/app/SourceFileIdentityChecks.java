package com.bimvo.app;
import java.io.*;
import java.nio.charset.StandardCharsets;
public final class SourceFileIdentityChecks {
 static final String SHA="2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
 static ByteArrayInputStream input(){return new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8));}
 public static void main(String[] args)throws Exception {
  SourceFileIdentity.requireMatch(input(),SHA,()->false);
  SourceFileIdentity.requireMatch(input(),SHA.toUpperCase(java.util.Locale.ROOT),()->false);
  for(String hash:new String[]{null,"bad","0".repeat(64)}) {
   try{SourceFileIdentity.requireMatch(input(),hash,()->false);throw new AssertionError("Unmatched source accepted");}catch(SourceFileIdentity.Mismatch expected){}
  }
  try{SourceFileIdentity.requireMatch(input(),SHA,()->true);throw new AssertionError("Cancel ignored");}catch(InterruptedIOException expected){}
  try{SourceFileIdentity.requireMatch(null,SHA,()->false);throw new AssertionError("Missing stream accepted");}catch(SourceFileIdentity.Mismatch expected){}
  System.out.println("PASS: correct source, uppercase SHA, wrong content, invalid/missing SHA, cancellation, null source");
 }
}
