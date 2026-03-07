package com.example.llama

import java.io.File

/**
 * Data class representing a stored model
 */
data class ModelData(
    val id: String,
    val name: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val addedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis()
) {
    val fileSizeFormatted: String
        get() = formatFileSize(fileSize)
    
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
}
