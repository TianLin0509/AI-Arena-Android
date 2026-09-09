package com.tianlin.aiarena;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Java deliberately: this separate test-provider process has no target APK Kotlin runtime. */
public class AttachmentFixtureProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if ("probe.png".equals(name)) return "image/png";
        if ("probe.pdf".equals(name)) return "application/pdf";
        if ("probe.txt".equals(name) || "too-big.txt".equals(name)) return "text/plain";
        throw new IllegalArgumentException("Unknown synthetic fixture");
    }
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        getType(uri);
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{uri.getLastPathSegment(), null});
        return cursor;
    }
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        getType(uri);
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        File file = new File(getContext().getCacheDir(), "attachment-fixture-" + uri.getLastPathSegment());
        try (FileOutputStream output = new FileOutputStream(file)) {
            if ("too-big.txt".equals(uri.getLastPathSegment())) {
                byte[] buffer = new byte[1024];
                java.util.Arrays.fill(buffer, (byte)65);
                for (int i=0; i<11*1024; i++) output.write(buffer);
            } else output.write(bytes(uri.getLastPathSegment()));
        } catch (IOException error) { throw new FileNotFoundException(error.toString()); }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    public static Uri uri(String name) { return Uri.parse("content://com.tianlin.aiarena.test.attachment-fixtures/" + name); }
    public static byte[] bytes(String name) {
        if ("probe.png".equals(name)) return Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aT1EAAAAASUVORK5CYII=", Base64.DEFAULT);
        if ("probe.pdf".equals(name)) return "%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
        if ("probe.txt".equals(name)) return "ARENA-7391: seven apples plus three pears.\n".getBytes(StandardCharsets.UTF_8);
        throw new IllegalArgumentException("Unknown fixture");
    }
}
