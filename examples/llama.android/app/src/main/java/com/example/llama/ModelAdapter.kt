package com.example.llama

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.ModelInfo
import com.example.llama.R

class ModelAdapter(
    private val onItemClick: (ModelInfo) -> Unit,
    private val onDeleteClick: (ModelInfo) -> Unit,
    private val onLoadClick: (ModelInfo) -> Unit
) : ListAdapter<ModelInfo, ModelAdapter.ModelViewHolder>(ModelDiffCallback()) {

    private var currentModelId: String? = null

    fun setCurrentModelId(modelId: String?) {
        currentModelId = modelId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_model, parent, false)
        return ModelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        val model = getItem(position)
        holder.bind(model, currentModelId)
    }

    inner class ModelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val modelNameText: TextView = itemView.findViewById(R.id.modelNameText)
        private val modelSizeText: TextView = itemView.findViewById(R.id.modelSizeText)
        private val statusText: TextView = itemView.findViewById(R.id.statusText)
        private val btnDelete: Button = itemView.findViewById(R.id.deleteButton)
        private val btnLoad: Button = itemView.findViewById(R.id.loadUnloadButton)

        fun bind(model: ModelInfo, currentModelId: String?) {
            modelNameText.text = model.displayName
            modelSizeText.text = formatFileSize(model.fileSize)
            
            // Set accessibility descriptions
            modelNameText.contentDescription = "模型名称：${model.displayName}"
            modelSizeText.contentDescription = "文件大小：${formatFileSize(model.fileSize)}"
            itemView.contentDescription = "模型：${model.displayName}，${formatFileSize(model.fileSize)}"
            
            val isCurrentModel = model.id == currentModelId
            
            // Update status indicator
            statusText.visibility = if (isCurrentModel) View.VISIBLE else View.GONE
            statusText.text = "已加载"
            statusText.contentDescription = if (isCurrentModel) "状态：已加载" else ""
            
            // Update load button text and accessibility based on state
            if (isCurrentModel) {
                btnLoad.text = "卸载"
                btnLoad.contentDescription = "卸载模型：${model.displayName}"
            } else {
                btnLoad.text = "加载"
                btnLoad.contentDescription = "加载模型：${model.displayName}"
            }

            // Set delete button accessibility
            btnDelete.text = "删除"
            btnDelete.contentDescription = "删除模型：${model.displayName}"

            // Click listeners
            itemView.setOnClickListener { onItemClick(model) }
            btnDelete.setOnClickListener { onDeleteClick(model) }
            btnLoad.setOnClickListener { onLoadClick(model) }
        }

        private fun formatFileSize(size: Long): String {
            return when {
                size < 1024 -> "$size B"
                size < 1024 * 1024 -> "${size / 1024} KB"
                size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
                else -> String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
            }
        }
    }

    class ModelDiffCallback : DiffUtil.ItemCallback<ModelInfo>() {
        override fun areItemsTheSame(oldItem: ModelInfo, newItem: ModelInfo): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ModelInfo, newItem: ModelInfo): Boolean {
            return oldItem == newItem
        }
    }
}
