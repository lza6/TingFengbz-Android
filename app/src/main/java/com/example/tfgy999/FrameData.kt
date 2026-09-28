package com.example.tfgy999

// 纯数据载体：HistoryActivity 解析 frame_data_*.json 的最小模型（原 Room Entity 已随 Room 层移除）
data class FrameData(
    val id: Long,
    val recordId: Long,
    val timestamp: Long,
    val originalFps: Float,
    val interpolatedFps: Float
)