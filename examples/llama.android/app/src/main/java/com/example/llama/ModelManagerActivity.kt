package com.example.llama

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.ModelInfo
import com.arm.aichat.ModelRepository
import com.arm.aichat.gguf.GgufMetadata
import com.arm.aichat.gguf.GgufMetadataReader
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ModelManagerActivity : AppCompatActivity() {

    private lateinit var modelsListRv: RecyclerView
    private lateinit var emptyStateLayout: LinearLayout
    private lateinit var btnImportModel: MaterialButton
    private lateinit var btnBack: ImageButton
    private lateinit var modelAdapter: ModelAdapter
    private lateinit var modelRepository: ModelRepository

    private var allModels: List<ModelInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_model_manager)

        // Initialize repository
        modelRepository = ModelRepository.getInstance(this)

        // Find views
        modelsListRv = findViewById(R.id.models_list)
        emptyStateLayout = findViewById(R.id.empty_state)
        btnImportModel = findViewById(R.id.btn_import_model)
        btnBack = findViewById(R.id.btn_back)

        // Setup RecyclerView
        modelAdapter = ModelAdapter(
            onItemClick = { model ->
                // Show model details or select model
                Toast.makeText(this, "已选择：${model.displayName}", Toast.LENGTH_SHORT).show()
            },
            onDeleteClick = { model -> showDeleteConfirmation(model) },
            onLoadClick = { model -> handleLoadUnloadModel(model) }
        )
        modelsListRv.layoutManager = LinearLayoutManager(this)
        modelsListRv.adapter = modelAdapter

        // Import button click
        btnImportModel.setOnClickListener {
            pickModelFile.launch(arrayOf("*/*"))
        }

        // Back button click
        btnBack.setOnClickListener {
            finish()
        }

        // Load models list
        loadModelsList()
    }

    override fun onResume() {
        super.onResume()
        loadModelsList()
    }

    private val pickModelFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        Log.i(TAG, "Selected model file uri:\n $uri")
        uri?.let { handleModelImport(it) }
    }

    /**
     * Handle model file import
     */
    private fun handleModelImport(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Parse GGUF metadata
                Log.i(TAG, "Parsing GGUF metadata...")
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val metadata = GgufMetadataReader.create().readStructuredMetadata(inputStream)
                    
                    metadata?.let { meta ->
                        Log.i(TAG, "GGUF parsed: \n$meta")
                        
                        // Generate model info
                        val modelName = meta.filename() + FILE_EXTENSION_GGUF
                        val modelsDir = modelRepository.ensureModelsDirectory()
                        val destFile = File(modelsDir, modelName)
                        
                        // Copy file directly from URI using ContentResolver (no temp file)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@ModelManagerActivity, "正在导入模型...", Toast.LENGTH_SHORT).show()
                        }
                        
                        val copyResult = copyFileFromUri(uri, destFile)
                        
                        if (copyResult.isSuccess) {
                            // Add to repository
                            val modelInfo = ModelInfo(
                                id = UUID.randomUUID().toString(),
                                fileName = modelName,
                                displayName = meta.basic.name ?: modelName,
                                fileSize = destFile.length(),
                                filePath = destFile.absolutePath,
                                importTime = System.currentTimeMillis()
                            )
                            
                            modelRepository.addModel(modelInfo)
                            
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@ModelManagerActivity,
                                    "模型导入成功：${modelInfo.displayName}",
                                    Toast.LENGTH_LONG
                                ).show()
                                loadModelsList()
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@ModelManagerActivity,
                                    "模型导入失败：${copyResult.exceptionOrNull()?.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error importing model", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@ModelManagerActivity,
                        "模型导入失败：${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Copy file directly from URI using ContentResolver (no temp file, works with Android 11+ scoped storage)
     */
    private fun copyFileFromUri(uri: Uri, destFile: File): Result<Unit> {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy file from URI: ${uri}", e)
            Result.failure(e)
        }
    }

    /**
     * Load and display models list
     */
    private fun loadModelsList() {
        lifecycleScope.launch {
            allModels = modelRepository.getAllModels()
            
            val currentModelId = modelRepository.getCurrentModelId()
            modelAdapter.setCurrentModelId(currentModelId)
            modelAdapter.submitList(allModels)
            
            // Update empty state visibility
            emptyStateLayout.visibility = if (allModels.isEmpty()) {
                View.VISIBLE
            } else {
                View.GONE
            }
            modelsListRv.visibility = if (allModels.isEmpty()) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }
    }

    /**
     * Show delete confirmation dialog
     */
    private fun showDeleteConfirmation(model: ModelInfo) {
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除模型 \"${model.displayName}\" 吗？\n\n此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                deleteModel(model)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * Delete a model
     */
    private fun deleteModel(model: ModelInfo) {
        lifecycleScope.launch {
            val success = modelRepository.removeModel(model.id)
            if (success) {
                Toast.makeText(
                    this@ModelManagerActivity,
                    "模型已删除",
                    Toast.LENGTH_SHORT
                ).show()
                loadModelsList()
            } else {
                Toast.makeText(
                    this@ModelManagerActivity,
                    "删除失败",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Handle load/unload model
     */
    private fun handleLoadUnloadModel(model: ModelInfo) {
        val currentModelId = modelRepository.getCurrentModelId()
        
        if (currentModelId == model.id) {
            // Unload model
            lifecycleScope.launch {
                modelRepository.clearCurrentModel()
                Toast.makeText(
                    this@ModelManagerActivity,
                    "模型已卸载",
                    Toast.LENGTH_SHORT
                ).show()
                loadModelsList()
            }
        } else {
            // Load model - navigate back to MainActivity
            lifecycleScope.launch {
                modelRepository.setCurrentModel(model.id)
                Toast.makeText(
                    this@ModelManagerActivity,
                    "模型已加载：${model.displayName}",
                    Toast.LENGTH_SHORT
                ).show()
                loadModelsList()
                
                // Navigate back to MainActivity
                finish()
            }
        }
    }

    companion object {
        private val TAG = ModelManagerActivity::class.java.simpleName
        private const val FILE_EXTENSION_GGUF = ".gguf"
    }
}
