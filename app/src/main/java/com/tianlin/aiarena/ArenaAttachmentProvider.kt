package com.tianlin.aiarena

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Unpredictable, expiring, read-only leases; no API can enumerate or address a stored file by id. */
internal object ArenaAttachmentLeases {
    data class Lease(val owner: String, val file: File, val attachment: ArenaAttachment, val expiresAt: Long)
    private val leases = mutableMapOf<String, Lease>()
    @Synchronized fun issue(context: Context, owner: String, attachment: ArenaAttachment, file: File): Uri {
        purge()
        val token = UUID.randomUUID().toString()
        leases[token] = Lease(owner, file, attachment, SystemClock.elapsedRealtime() + 180_000L)
        return Uri.Builder().scheme("content").authority("${context.packageName}.attachments").appendPath(token).build()
    }
    @Synchronized fun resolve(uri: Uri): Lease {
        purge()
        return leases[uri.pathSegments.singleOrNull()] ?: throw FileNotFoundException("附件授权已失效")
    }
    @Synchronized fun revoke(owner: String) { leases.entries.removeAll { it.value.owner == owner } }
    @Synchronized fun retainedIds(): Set<String> { purge(); return leases.values.map { it.attachment.id }.toSet() }
    private fun purge() { leases.entries.removeAll { it.value.expiresAt < SystemClock.elapsedRealtime() } }
}

class ArenaAttachmentProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri): String = ArenaAttachmentLeases.resolve(uri).attachment.mimeType
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val attachment = ArenaAttachmentLeases.resolve(uri).attachment
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column -> when (column) {
                OpenableColumns.DISPLAY_NAME -> attachment.name
                OpenableColumns.SIZE -> attachment.sizeBytes
                else -> null
            } })
        }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("附件只读")
        return ParcelFileDescriptor.open(ArenaAttachmentLeases.resolve(uri).file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
}
