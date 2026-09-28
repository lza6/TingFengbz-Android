package com.example.tfgy999

import java.nio.ByteBuffer

/**
 * 将 RGBA_8888 图像平面从源缓冲区按行拷贝到目标缓冲区。
 *
 * 某些设备的 ImageReader 平面存在行填充（rowStride > width * pixelStride）。
 * 逐行拷贝会跳过每行末尾的填充字节，避免整块拷贝在填充存在时因字节数超限而整帧丢弃。
 *
 * @return 成功写入目标缓冲区的行数；完整拷贝时为 height，数据不足时为已写入行数（< height）。
 */
fun copyFrameRows(
    source: ByteBuffer,
    dest: ByteBuffer,
    width: Int,
    height: Int,
    pixelStride: Int,
    rowStride: Int
): Int {
    val rowBytes = width * pixelStride
    if (rowBytes <= 0 || rowStride < rowBytes || pixelStride <= 0) return 0
    source.clear()
    dest.clear()
    val row = ByteArray(rowBytes)
    var y = 0
    while (y < height && source.remaining() >= rowStride) {
        source.get(row, 0, rowBytes)
        dest.put(row, 0, rowBytes)
        if (rowStride > rowBytes) source.position(source.position() + (rowStride - rowBytes))
        y++
    }
    dest.rewind()
    return y
}