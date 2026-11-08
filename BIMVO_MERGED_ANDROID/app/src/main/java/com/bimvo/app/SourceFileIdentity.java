package com.bimvo.app;

import java.io.*;
import java.security.*;
import java.util.function.BooleanSupplier;

/** Read-only identity check before changing the user-selected source document. */
final class SourceFileIdentity {
    static final class Mismatch extends IOException { Mismatch() { super("Selected source differs from recording"); } }
    static void requireMatch(InputStream input, String expected, BooleanSupplier cancelled) throws IOException {
        if(input==null || expected==null || !expected.matches("[a-fA-F0-9]{64}"))throw new Mismatch();
        MessageDigest digest;
        try { digest=MessageDigest.getInstance("SHA-256"); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        byte[] buffer=new byte[65536];
        while(true) {
            if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new InterruptedIOException("Cancelled");
            int count=input.read(buffer);if(count<0)break;if(count>0)digest.update(buffer,0,count);
        }
        StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        if(!hash.toString().equalsIgnoreCase(expected))throw new Mismatch();
    }
}
