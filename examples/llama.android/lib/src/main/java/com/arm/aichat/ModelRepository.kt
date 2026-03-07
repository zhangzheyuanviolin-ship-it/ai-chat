package com.arm.aichat

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Data class representing a model file
 */
data class ModelInfo(
    val id: String,
    val fileName: String,
    val displayName: String,
    val fileSize: Long,
    val filePath: String,
    val importTime: Long = System.currentTimeMillis(),
    val lastUsedTime: Long = 0
)

/**
 * Repository for managing model files with persistence
 */
class ModelRepository private constructor(
    private val context: Context
) {
    companion object {
        @Volatile
        private var instance: ModelRepository? = null

        fun getInstance(context: Context): ModelRepository {
            return instance ?: synchronized(this) {
                ModelRepository(context.applicationContext).also { instance = it }
            }
        }

        private const val PREFS_NAME = "model_preferences"
        private const val KEY_CURRENT_MODEL_ID = "current_model_id"
        private const val KEY_MODEL_PREFIX = "model_"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val modelsDir: File = File(context.filesDir, "models")

    /**
     * Get all imported models
     */
    suspend fun getAllModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        ensureModelsDirectory()
        val models = mutableListOf<ModelInfo>()
        
        // Read all model entries from SharedPreferences
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(KEY_MODEL_PREFIX)) {
                val modelData = value as? String
                modelData?.let {
                    parseModelInfo(it)?.let { info ->
                        // Verify file still exists
                        if (File(info.filePath).exists()) {
                            models.add(info)
                        }
                    }
                }
            }
        }
        
        // Sort by last used time (most recent first)
        models.sortedByDescending { it.lastUsedTime }
    }

    /**
     * Add a new model to the repository
     */
    suspend fun addModel(modelInfo: ModelInfo) = withContext(Dispatchers.IO) {
        ensureModelsDirectory()
        val modelData = serializeModelInfo(modelInfo)
        prefs.edit().putString("${KEY_MODEL_PREFIX}${modelInfo.id}", modelData).apply()
    }

    /**
     * Remove a model from the repository and delete the file
     */
    suspend fun removeModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        val model = getModelById(modelId)
        model?.let {
            // Delete the actual file
            File(it.filePath).delete()
            // Remove from preferences
            prefs.edit().remove("${KEY_MODEL_PREFIX}${modelId}").apply()
            // Clear current model if this was the current one
            if (getCurrentModelId() == modelId) {
                clearCurrentModel()
            }
            true
        } ?: false
    }

    /**
     * Get a specific model by ID
     */
    suspend fun getModelById(modelId: String): ModelInfo? = withContext(Dispatchers.IO) {
        val modelData = prefs.getString("${KEY_MODEL_PREFIX}${modelId}", null)
        modelData?.let { parseModelInfo(it) }
    }

    /**
     * Get the currently loaded model ID
     */
    fun getCurrentModelId(): String? {
        return prefs.getString(KEY_CURRENT_MODEL_ID, null)
    }

    /**
     * Set the currently loaded model
     */
    fun setCurrentModel(modelId: String) {
        prefs.edit().putString(KEY_CURRENT_MODEL_ID, modelId).apply()
        // Update last used time
        getModelById(modelId)?.let { model ->
            val updatedModel = model.copy(lastUsedTime = System.currentTimeMillis())
            val modelData = serializeModelInfo(updatedModel)
            prefs.edit().putString("${KEY_MODEL_PREFIX}${modelId}", modelData).apply()
        }
    }

    /**
     * Clear the current model (on unload)
     */
    fun clearCurrentModel() {
        prefs.edit().remove(KEY_CURRENT_MODEL_ID).apply()
    }

    /**
     * Check if a model file exists
     */
    fun modelFileExists(filePath: String): Boolean {
        return File(filePath).exists()
    }

    /**
     * Get the models directory, creating it if necessary
     */
    fun ensureModelsDirectory(): File {
        return modelsDir.also {
            if (it.exists() && !it.isDirectory) {
                it.delete()
            }
            if (!it.exists()) {
                it.mkdirs()
            }
        }
    }

    /**
     * Serialize ModelInfo to JSON-like string
     */
    private fun serializeModelInfo(model: ModelInfo): String {
        return "${model.id}|${model.fileName}|${model.displayName}|${model.fileSize}|${model.filePath}|${model.importTime}|${model.lastUsedTime}"
    }

    /**
     * Deserialize ModelInfo from string
     */
    private fun parseModelInfo(data: String): ModelInfo? {
        return try {
            val parts = data.split("|")
            if (parts.size >= 5) {
                ModelInfo(
                    id = parts[0],
                    fileName = parts[1],
                    displayName = parts[2],
                    fileSize = parts[3].toLongOrNull() ?: 0,
                    filePath = parts[4],
                    importTime = parts.getOrNull(5)?.toLongOrNull() ?: System.currentTimeMillis(),
                    lastUsedTime = parts.getOrNull(6)?.toLongOrNull() ?: 0
                )
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Copy file in chunks for large files
     */
    suspend fun copyFileInChunks(sourcePath: String, destPath: String, chunkSize: Int = 1024 * 1024): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(sourcePath)
            val destFile = File(destPath)
            
            if (!sourceFile.exists()) {
                return@withContext Result.failure(Exception("Source file not found"))
            }
            
            ensureModelsDirectory()
            
            // Delete destination if it exists
            if (destFile.exists()) {
                destFile.delete()
            }
            
            sourceFile.inputStream().use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(chunkSize)
                    var bytesRead: Int
                    var totalBytesCopied = 0L
                    val totalBytes = sourceFile.length()
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesCopied += bytesRead
                    }
                    
                    // Verify file size
                    if (destFile.length() == totalBytes) {
                        Result.success(Unit)
                    } else {
                        Result.failure(Exception("File copy verification failed"))
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
