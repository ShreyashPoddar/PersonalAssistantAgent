package com.paa.assistant.core.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.paa.assistant.data.db.FileIndexDao
import com.paa.assistant.data.models.FileIndexEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * StorageManager — interfaces with Android's Storage Access Framework (SAF).
 *
 * Implements:
 *  1. Directory indexing into local Room DB (file_index table)
 *  2. Fuzzy file searching by filename
 *  3. Streaming file bytes to Gemini multimodal API (PDF, images, text)
 */
@Singleton
class StorageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileIndexDao: FileIndexDao
) {
    private val tag = "StorageManager"

    /**
     * Scan and index a folder granted via ACTION_OPEN_DOCUMENT_TREE.
     * Recursively traverses DocumentFile and updates the file_index table.
     */
    suspend fun indexDirectoryTree(treeUri: Uri, directoryHint: String = "DOWNLOADS"): Int = withContext(Dispatchers.IO) {
        try {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext 0
            crawlDirectory(root, directoryHint)
        } catch (e: Exception) {
            Log.e(tag, "Failed to index directory $treeUri", e)
            0
        }
    }

    private suspend fun crawlDirectory(dir: DocumentFile, directoryHint: String): Int {
        var count = 0
        val files = dir.listFiles()
        for (file in files) {
            if (file.isDirectory) {
                count += crawlDirectory(file, directoryHint)
            } else if (file.isFile) {
                val entity = FileIndexEntity(
                    uriString = file.uri.toString(),
                    fileName = file.name ?: "unknown",
                    mimeType = file.type ?: "application/octet-stream",
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    directoryHint = directoryHint
                )
                fileIndexDao.insertFile(entity)
                count++
            }
        }
        return count
    }

    /**
     * Search for a file by partial name in the indexed files.
     */
    suspend fun findFileByName(filenameHint: String): FileIndexEntity? = withContext(Dispatchers.IO) {
        val results = fileIndexDao.searchByName(filenameHint)
        results.firstOrNull()
    }

    /**
     * Read file content bytes from a given SAF Uri.
     */
    suspend fun readFileBytes(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                readAllBytes(inputStream)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error reading file bytes from $uri", e)
            null
        }
    }

    /**
     * Extract filename and MIME type from a shared URI.
     */
    fun getFileMetadata(uri: Uri): Pair<String, String> {
        var name = "attachment"
        var mime = context.contentResolver.getType(uri) ?: "application/octet-stream"

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = cursor.getString(nameIndex) ?: name
                }
            }
        }
        return Pair(name, mime)
    }

    private fun readAllBytes(inputStream: InputStream): ByteArray {
        val buffer = ByteArray(8192)
        val output = ByteArrayOutputStream()
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
        }
        return output.toByteArray()
    }
}
