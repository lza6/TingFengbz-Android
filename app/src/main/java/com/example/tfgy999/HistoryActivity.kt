package com.example.tfgy999

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            HistoryScreen()
        }
    }

    private suspend fun loadFrameDataFromFile(file: File, onProgress: (Int) -> Unit): List<FrameData> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            val jsonString = file.readText()
            val jsonArray = JSONArray(jsonString)
            val frameDataList = mutableListOf<FrameData>()
            val total = jsonArray.length()
            for (i in 0 until total) {
                val jsonObject = jsonArray.getJSONObject(i)
                val frameData = FrameData(
                    id = 0,
                    recordId = file.nameWithoutExtension.removePrefix("frame_data_").toLong(),
                    timestamp = jsonObject.getLong("timestamp"),
                    originalFps = jsonObject.optDouble("originalFps", 0.0).toFloat(),
                    interpolatedFps = jsonObject.optDouble("interpolatedFps", 0.0).toFloat()
                )
                frameDataList.add(frameData)
                onProgress(((i + 1) * 100) / total)
            }
            Timber.i("加载文件成功: ${file.name}, 记录数: ${frameDataList.size}")
            frameDataList
        } catch (e: Exception) {
            Timber.e(e, "解析JSON文件失败: ${file.absolutePath}")
            emptyList()
        }
    }

    private suspend fun clearHistoryFiles(onProgress: (Int) -> Unit) {
        val files = getSortedFiles()
        val total = files.size
        for (i in 0 until total) {
            val file = files[i]
            try {
                file.delete()
                onProgress(((i + 1) * 100) / total)
            } catch (e: Exception) {
                Timber.e(e, "删除文件失败: ${file.absolutePath}")
            }
        }
        Timber.i("清除历史文件完成")
    }

    @Composable
    fun HistoryScreen() {
        val scope = rememberCoroutineScope()
        var frameData by remember { mutableStateOf<List<FrameData>>(emptyList()) }
        var selectedFile by remember { mutableStateOf<File?>(null) }
        var averageOriginalFps by remember { mutableStateOf(0f) }
        var averageTotalFps by remember { mutableStateOf(0f) }

        LaunchedEffect(selectedFile) {
            selectedFile?.let { file ->
                scope.launch {
                    loadFrameDataWithProgress(file) { data ->
                        frameData = data
                        if (data.isNotEmpty()) {
                            averageOriginalFps = data.map { it.originalFps }.average().toFloat()
                            averageTotalFps = data.map { it.originalFps + it.interpolatedFps }.average().toFloat()
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text(
                    "历史补帧插帧记录",
                    fontSize = 24.sp,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(0.8f),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = { showFileSelectorDialog { file -> selectedFile = file } },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("选择历史记录文件", fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            AlertDialog.Builder(this@HistoryActivity)
                                .setTitle("确认")
                                .setMessage("确定要清除所有历史文件吗？")
                                .setPositiveButton("确定") { _, _ ->
                                    scope.launch { clearHistoryFilesWithProgress() }
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("清除历史文件", fontSize = 16.sp)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (selectedFile != null && frameData.isNotEmpty()) {
                item {
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    Text(
                        "文件: ${selectedFile!!.name}, 时间: ${dateFormat.format(Date(selectedFile!!.nameWithoutExtension.removePrefix("frame_data_").toLong()))}",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .background(Color.White)
                            .padding(8.dp),
                        factory = { context ->
                            LineChart(context).apply {
                                description.isEnabled = false
                                setTouchEnabled(true)
                                isDragEnabled = true
                                setScaleEnabled(true)
                                setPinchZoom(true)

                                xAxis.apply {
                                    position = XAxis.XAxisPosition.BOTTOM
                                    setDrawGridLines(false)
                                    labelCount = 5
                                    textColor = android.graphics.Color.BLACK
                                    textSize = 12f
                                    valueFormatter = object : ValueFormatter() {
                                        override fun getFormattedValue(value: Float): String {
                                            val seconds = value.toLong()
                                            val minutes = seconds / 60
                                            val remainingSeconds = seconds % 60
                                            return if (minutes >= 60) {
                                                val hours = minutes / 60
                                                val remainingMinutes = minutes % 60
                                                "${hours}h${remainingMinutes}m"
                                            } else {
                                                "${minutes}m${remainingSeconds}s"
                                            }
                                        }
                                    }
                                }

                                axisLeft.apply {
                                    setDrawGridLines(true)
                                    axisMinimum = 0f
                                    granularity = 15f
                                    textColor = android.graphics.Color.BLUE
                                    textSize = 12f
                                }

                                axisRight.apply {
                                    isEnabled = true
                                    setDrawGridLines(false)
                                    axisMinimum = 0f
                                    granularity = 15f
                                    textColor = android.graphics.Color.RED
                                    textSize = 12f
                                }

                                val originalFpsEntries = frameData.map {
                                    Entry((it.timestamp - frameData.first().timestamp).toFloat() / 1000, it.originalFps)
                                }
                                val totalFpsEntries = frameData.map {
                                    Entry((it.timestamp - frameData.first().timestamp).toFloat() / 1000, it.originalFps + it.interpolatedFps)
                                }

                                val originalDataSet = LineDataSet(originalFpsEntries, "原始帧率").apply {
                                    color = android.graphics.Color.BLUE
                                    setDrawCircles(false)
                                    lineWidth = 2f
                                    setDrawValues(false)
                                }
                                val totalDataSet = LineDataSet(totalFpsEntries, "补帧后帧率").apply {
                                    color = android.graphics.Color.RED
                                    setDrawCircles(false)
                                    lineWidth = 2f
                                    setDrawValues(false)
                                    axisDependency = YAxis.AxisDependency.RIGHT
                                }

                                data = LineData(originalDataSet, totalDataSet)
                                legend.textSize = 14f
                                invalidate()
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(0.8f),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "原始平均帧率: %.2f FPS".format(averageOriginalFps),
                                fontSize = 16.sp,
                                color = Color.Blue,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "补帧后平均帧率: %.2f FPS".format(averageTotalFps),
                                fontSize = 16.sp,
                                color = Color.Red,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            } else if (selectedFile == null) {
                item {
                    Text(
                        "暂无历史记录文件",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            } else {
                item {
                    Text(
                        "加载中...",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            item {
                Button(
                    onClick = { finish() },
                    modifier = Modifier.fillMaxWidth(0.8f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("返回首页", fontSize = 18.sp)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    private fun getSortedFiles(): List<File> {
        return filesDir.listFiles { file -> file.name.startsWith("frame_data_") && file.extension == "json" }
            ?.sortedByDescending { it.nameWithoutExtension.removePrefix("frame_data_").toLong() }
            ?: emptyList()
    }

    private fun showFileSelectorDialog(onFileSelected: (File) -> Unit) {
        val files = getSortedFiles()
        if (files.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("提示")
                .setMessage("暂无历史记录文件")
                .setPositiveButton("确定", null)
                .show()
            return
        }

        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = FileAdapter(files) { file ->
                onFileSelected(file)
                (parent as? AlertDialog)?.dismiss()
            }
        }
        AlertDialog.Builder(this)
            .setTitle("选择历史记录文件")
            .setView(recyclerView)
            .setNegativeButton("取消", null)
            .show()
    }

    private suspend fun loadFrameDataWithProgress(file: File, onComplete: (List<FrameData>) -> Unit) {
        val progressDialog = ProgressDialog(this).apply {
            setTitle("加载中")
            setMessage("正在加载文件: ${file.name}")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(true)
            setButton(AlertDialog.BUTTON_NEGATIVE, "取消") { dialog, _ -> dialog.dismiss() }
            show()
        }

        try {
            val frameData = loadFrameDataFromFile(file) { progress ->
                progressDialog.progress = progress
            }
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                onComplete(frameData)
            }
        } catch (e: Exception) {
            Timber.e(e, "加载文件失败: ${file.absolutePath}")
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                AlertDialog.Builder(this@HistoryActivity)
                    .setTitle("错误")
                    .setMessage("加载文件失败: ${e.message}")
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    private suspend fun clearHistoryFilesWithProgress() {
        val progressDialog = ProgressDialog(this).apply {
            setTitle("清除中")
            setMessage("正在清除历史文件")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            show()
        }

        try {
            clearHistoryFiles { progress ->
                progressDialog.progress = progress
            }
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                AlertDialog.Builder(this@HistoryActivity)
                    .setTitle("完成")
                    .setMessage("历史文件已清除")
                    .setPositiveButton("确定", null)
                    .show()
            }
        } catch (e: Exception) {
            Timber.e(e, "清除历史文件失败")
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                AlertDialog.Builder(this@HistoryActivity)
                    .setTitle("错误")
                    .setMessage("清除历史文件失败: ${e.message}")
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }
}

class FileAdapter(
    private val files: List<File>,
    private val onFileSelected: (File) -> Unit
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val file = files[position]
        holder.bind(file)
    }

    override fun getItemCount(): Int = files.size

    inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(file: File) {
            itemView.setOnClickListener { onFileSelected(file) }
            (itemView as TextView).text = file.name
        }
    }
}