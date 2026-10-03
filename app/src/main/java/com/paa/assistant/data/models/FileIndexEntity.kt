package com.paa.assistant.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Index of files accessible to PAA via Storage Access Framework (SAF).
 * PAA never copies files — it only maintains a URI reference table
 * so it can fuzzy-match by filename when the user asks to read a file.
 */
@Entity(tableName = "file_index")
data class FileIndexEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uriString: String,          // Persistent SAF URI string
    val fileName: String,           // e.g. "project_report.pdf"
    val mimeType: String,           // e.g. "application/pdf"
    val sizeBytes: Long,
    val lastModified: Long,
    val directoryHint: String = "DOWNLOADS"  // DOWNLOADS, DOCUMENTS, WHATSAPP_DOCS
)
