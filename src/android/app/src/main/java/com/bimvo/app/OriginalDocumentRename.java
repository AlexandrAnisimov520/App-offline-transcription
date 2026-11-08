package com.bimvo.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;

/** Renames only the explicitly selected matching SAF source; never derives a raw filesystem path. */
final class OriginalDocumentRename {
    static final class Unsupported extends IOException { Unsupported() { super("Provider does not support rename"); } }
    static void rename(Context context, Uri uri, Recording recording) throws Exception {
        if(!DocumentsContract.isDocumentUri(context,uri))throw new Unsupported();
        int flags=0;
        try(Cursor c=context.getContentResolver().query(uri,new String[]{DocumentsContract.Document.COLUMN_FLAGS},null,null,null)) {
            if(c==null || !c.moveToFirst())throw new Unsupported();
            flags=c.getInt(0);
        }
        if((flags & DocumentsContract.Document.FLAG_SUPPORTS_RENAME)==0)throw new Unsupported();
        try(InputStream input=context.getContentResolver().openInputStream(uri)) {
            SourceFileIdentity.requireMatch(input,recording.sha256,()->false);
        }
        String filename=ImportFiles.safeDisplayName(recording.originalName).replace('/','_').replace('\\','_');
        Uri renamed=DocumentsContract.renameDocument(context.getContentResolver(),uri,filename);
        if(renamed==null)throw new IOException("Provider returned no renamed document");
    }
}
