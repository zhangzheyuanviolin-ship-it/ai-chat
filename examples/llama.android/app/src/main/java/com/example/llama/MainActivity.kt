package com.example.llama

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.ModelRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // Android views
    private lateinit var ggufTv: TextView
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: EditText
    private lateinit var userActionFab: FloatingActionButton
    private lateinit var btnModelManager: MaterialButton

    // Arm AI Chat inference engine
    private lateinit var engine: InferenceEngine
    private lateinit var modelRepository: ModelRepository

    // Conversation states
    private var isModelReady = false
    private val messages = mutableListOf<Message>()
    private val lastAssistantMsg = StringBuilder()
    private val messageAdapter = MessageAdapter(messages)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Initialize repository
        modelRepository = ModelRepository.getInstance(this)

        // Find views
        ggufTv = findViewById(R.id.gguf)
        messagesRv = findViewById(R.id.messages)
        messagesRv.layoutManager = LinearLayoutManager(this)
        messagesRv.adapter = messageAdapter
        userInputEt = findViewById(R.id.user_input)
        userActionFab = findViewById(R.id.fab)
        btnModelManager = findViewById(R.id.btn_model_manager)

        // Arm AI Chat initialization
        lifecycleScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(applicationContext)
        }

        // Model manager button click - navigate to ModelManagerActivity
        btnModelManager.setOnClickListener {
            val intent = Intent(this, ModelManagerActivity::class.java)
            startActivity(intent)
        }

        // Send message button click
        userActionFab.setOnClickListener {
            if (isModelReady) {
                handleUserInput()
            } else {
                Toast.makeText(this, "请先从模型管理页面加载模型", Toast.LENGTH_SHORT).show()
            }
        }

        // Try to auto-load last used model
        autoLoadLastModel()
    }

    override fun onResume() {
        super.onResume()
        // Check if model state changed while in ModelManagerActivity
        checkModelState()
    }

    /**
     * Auto-load the last used model on app start
     */
    private fun autoLoadLastModel() {
        lifecycleScope.launch {
            val currentModelId = modelRepository.getCurrentModelId()
            currentModelId?.let { modelId ->
                val modelInfo = modelRepository.getModelById(modelId)
                modelInfo?.let { model ->
                    // Verify model file still exists
                    if (modelRepository.modelFileExists(model.filePath)) {
                        Log.i(TAG, "Auto-loading last used model: ${model.displayName}")
                        withContext(Dispatchers.Main) {
                            ggufTv.text = "正在自动加载模型：${model.displayName}..."
                        }
                        loadModel(model.fileName, model.filePath)
                    } else {
                        Log.w(TAG, "Last model file not found, clearing reference")
                        modelRepository.clearCurrentModel()
                        withContext(Dispatchers.Main) {
                            ggufTv.text = "上次使用的模型文件未找到，请重新选择模型。"
                        }
                    }
                }
            } ?: run {
                Log.i(TAG, "No last model found, waiting for user to select")
                withContext(Dispatchers.Main) {
                    ggufTv.text = "请从模型管理页面加载模型。"
                }
            }
        }
    }

    /**
     * Check if model state changed (called from onResume)
     */
    private fun checkModelState() {
        val currentModelId = modelRepository.getCurrentModelId()
        if (currentModelId != null && !isModelReady) {
            // Model was loaded in ModelManagerActivity, reload it here
            lifecycleScope.launch {
                val modelInfo = modelRepository.getModelById(currentModelId)
                modelInfo?.let { model ->
                    if (modelRepository.modelFileExists(model.filePath)) {
                        loadModel(model.fileName, model.filePath)
                    }
                }
            }
        }
    }

    /**
     * Load the model file
     */
    private suspend fun loadModel(modelName: String, modelPath: String) = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Loading model: $modelName")
            withContext(Dispatchers.Main) {
                userInputEt.hint = "正在加载模型..."
            }
            engine.loadModel(modelPath)
            
            withContext(Dispatchers.Main) {
                isModelReady = true
                userInputEt.hint = "输入消息并发送..."
                userInputEt.isEnabled = true
                userActionFab.isEnabled = true
                ggufTv.text = "已加载模型：$modelName"
                Toast.makeText(this@MainActivity, "模型加载成功", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            withContext(Dispatchers.Main) {
                ggufTv.text = "模型加载失败：${e.message}"
                Toast.makeText(this@MainActivity, "模型加载失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Validate and send the user message into [InferenceEngine]
     */
    private fun handleUserInput() {
        userInputEt.text.toString().also { userSsg ->
            if (userSsg.isEmpty()) {
                Toast.makeText(this, "输入消息不能为空!", Toast.LENGTH_SHORT).show()
            } else {
                userInputEt.text = null
                userActionFab.isEnabled = false

                // Update message states
                messages.add(Message(UUID.randomUUID().toString(), userSsg, true))
                lastAssistantMsg.clear()
                messages.add(Message(UUID.randomUUID().toString(), lastAssistantMsg.toString(), false))

                lifecycleScope.launch(Dispatchers.Default) {
                    engine.sendUserPrompt(userSsg)
                        .onCompletion {
                            withContext(Dispatchers.Main) {
                                userActionFab.isEnabled = true
                            }
                        }.collect { token ->
                            val messageCount = messages.size
                            check(messageCount > 0 && !messages[messageCount - 1].isUser)

                            messages.removeAt(messageCount - 1).copy(
                                content = lastAssistantMsg.append(token).toString()
                            ).let { messages.add(it) }

                            withContext(Dispatchers.Main) {
                                messageAdapter.notifyItemChanged(messages.size - 1)
                            }
                        }
                }
            }
        }
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName

        private const val BENCH_PROMPT_PROCESSING_TOKENS = 512
        private const val BENCH_TOKEN_GENERATION_TOKENS = 128
        private const val BENCH_SEQUENCE = 1
        private const val BENCH_REPETITION = 3
    }
}
