package com.example.llama

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for managing model persistence and operations
 */
class ModelRepository(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val modelsDir = File(context.filesDir, MODELS_DIRECTORY)
    
    /**
     * Get all stored models
     */
    fun getModels(): List<ModelData> {
        val json = prefs.getString(KEY_MODELS, null) ?: return emptyList()
        val type = object : TypeToken<List<ModelData>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Get the last used model
     */
    fun getLastUsedModel(): ModelData? {
        val lastUsedId = prefs.getString(KEY_LAST_USED_MODEL, null) ?: return null
        return getModels().find { it.id == lastUsedId }
    }
    
    /**
     * Set the last used model
     */
    fun setLastUsedModel(modelId: String) {
        prefs.edit().putString(KEY_LAST_USED_MODEL, modelId).apply()
        // Update lastUsedAt timestamp
        val models = getModels().toMutableList()
        val index = models.indexOfFirst { it.id == modelId }
        if (index != -1) {
            models[index] = models[index].copy(lastUsedAt = System.currentTimeMillis())
            saveModels(models)
        }
    }
    
    /**
     * Add a new model
     */
    suspend fun addModel(name: String, fileName: String, sourceFile: File): Result<ModelData> = withContext(Dispatchers.IO) {
        try {
            // Create models directory if not exists
            if (!modelsDir.exists()) {
                modelsDir.mkdirs()
            }
            
            // Generate unique ID
            val id = "model_${System.currentTimeMillis()}"
            val destFile = File(modelsDir, fileName)
            
            // Copy file with chunked copy for large files
            copyFileChunked(sourceFile, destFile)
            
            // Create model data
            val modelData = ModelData(
                id = id,
                name = name,
                fileName = fileName,
                filePath = destFile.absolutePath,
                fileSize = destFile.length(),
                addedAt = System.currentTimeMillis()
            )
            
            // Save to list
            val models = getModels().toMutableList()
            models.add(0, modelData) // Add to top
            saveModels(models)
            
            Result.success(modelData)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Delete a model
     */
    suspend fun deleteModel(modelId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val models = getModels().toMutableList()
            val model = models.find { it.id == modelId }
            
            if (model != null) {
                // Delete file
                val file = File(model.filePath)
                if (file.exists()) {
                    file.delete()
                }
                
                // Remove from list
                models.remove(model)
                saveModels(models)
                
                // Clear last used if this was the last used model
                val lastUsedId = prefs.getString(KEY_LAST_USED_MODEL, null)
                if (lastUsedId == modelId) {
                    prefs.edit().remove(KEY_LAST_USED_MODEL).apply()
                }
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Check if a model file exists
     */
    fun modelExists(modelId: String): Boolean {
        val model = getModels().find { it.id == modelId }
        return model?.let { File(it.filePath).exists() } ?: false
    }
    
    /**
     * Get model by ID
     */
    fun getModelById(modelId: String): ModelData? {
        return getModels().find { it.id == modelId }
    }
    
    /**
     * Copy file in chunks for large files (safer for memory)
     */
    private fun copyFileChunked(source: File, dest: File, chunkSize: Int = 8192) {
        FileInputStream(source).use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(chunkSize)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
            }
        }
    }
    
    /**
     * Save models list to preferences
     */
    private fun saveModels(models: List<ModelData>) {
        val json = gson.toJson(models)
        prefs.edit().putString(KEY_MODELS, json).apply()
    }
    
    companion object {
        private const val PREFS_NAME = "model_preferences"
        private const val KEY_MODELS = "stored_models"
        private const val KEY_LAST_USED_MODEL = "last_used_model"
        private const val MODELS_DIRECTORY = "models"
    }
}
