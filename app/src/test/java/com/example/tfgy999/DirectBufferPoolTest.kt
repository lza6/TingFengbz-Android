package com.example.tfgy999

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class DirectBufferPoolTest {

    @Test
    fun `acquire returns a direct buffer with enough capacity`() {
        val buffer = DirectBufferPool.acquire(1024)
        assertTrue(buffer != null)
        assertTrue(buffer!!.isDirect)
        assertTrue(buffer.capacity() >= 1024)
        DirectBufferPool.release(buffer)
    }

    @Test
    fun `released buffer is reused instead of reallocating`() {
        val first = DirectBufferPool.acquire(4096)!!
        DirectBufferPool.release(first)

        val second = DirectBufferPool.acquire(4096)!!
        // 池应直接复用刚释放的同一实例，而不是新建
        assertSame(first, second)
        DirectBufferPool.release(second)
    }

    @Test
    fun `reused buffer can be written again`() {
        val b = DirectBufferPool.acquire(256)!!
        DirectBufferPool.release(b)

        val reused = DirectBufferPool.acquire(256)!!
        reused.putInt(0, 12345)
        assertEquals(12345, reused.getInt(0))
        DirectBufferPool.release(reused)
    }

    @Test
    fun `release null or heap buffer is a no-op`() {
        DirectBufferPool.release(null)
        DirectBufferPool.release(ByteBuffer.allocate(64)) // 堆缓冲区不回收
    }

    @Test
    fun `acquire with invalid size returns null`() {
        assertNull(DirectBufferPool.acquire(0))
        assertNull(DirectBufferPool.acquire(-1))
    }

    @Test
    fun `acquire prefers a pooled buffer large enough over a new allocation`() {
        val small = DirectBufferPool.acquire(512)!!
        DirectBufferPool.release(small)

        val wantBigger = DirectBufferPool.acquire(1024)!!
        assertTrue(wantBigger.capacity() >= 1024)
        DirectBufferPool.release(wantBigger)
    }
}

class FrameBufferCopyTest {

    @Test
    fun `no padding - full copy`() {
        val w = 4
        val h = 3
        val pixelStride = 4
        val rowStride = w * pixelStride // 16，无填充
        val source = ByteBuffer.allocateDirect(h * rowStride)
        for (i in 0 until h * rowStride) source.put(i, i.toByte())
        val dest = ByteBuffer.allocateDirect(w * h * pixelStride)

        val rows = copyFrameRows(source, dest, w, h, pixelStride, rowStride)

        assertEquals(h, rows)
        assertEquals(h * rowStride, dest.remaining())
        for (i in 0 until h * rowStride) assertEquals(i.toByte(), dest.get(i))
    }

    @Test
    fun `with row padding - rows are copied and padding skipped`() {
        val w = 4
        val h = 3
        val pixelStride = 4
        val rowBytes = w * pixelStride
        val rowStride = rowBytes + 8 // 每行末尾有 8 字节填充
        val source = ByteBuffer.allocateDirect(h * rowStride)
        // 每行第 y 行的有效像素填成固定值，填充区填 0x7F
        for (y in 0 until h) {
            for (x in 0 until rowBytes) source.put(y * rowStride + x, (y + 1).toByte())
            for (p in rowBytes until rowStride) source.put(y * rowStride + p, 0x7F)
        }
        val dest = ByteBuffer.allocateDirect(h * rowBytes)

        val rows = copyFrameRows(source, dest, w, h, pixelStride, rowStride)

        assertEquals(h, rows)
        assertEquals(h * rowBytes, dest.remaining())
        for (y in 0 until h) {
            for (x in 0 until rowBytes) {
                assertEquals("row=$y x=$x 应只保留有效像素", (y + 1).toByte(), dest.get(y * rowBytes + x))
            }
        }
    }

    @Test
    fun `truncated source returns partial rows`() {
        val source = ByteBuffer.allocateDirect(1) // 数据不足一行
        val dest = ByteBuffer.allocateDirect(64)
        val rows = copyFrameRows(source, dest, 4, 3, 4, 16)
        assertEquals(0, rows)
    }

    @Test
    fun `invalid geometry returns zero`() {
        val source = ByteBuffer.allocateDirect(64)
        val dest = ByteBuffer.allocateDirect(64)
        assertEquals(0, copyFrameRows(source, dest, 4, 3, 0, 16))       // pixelStride 非法
        assertEquals(0, copyFrameRows(source, dest, 4, 3, 4, 8))        // rowStride < rowBytes
        assertEquals(0, copyFrameRows(source, dest, 0, 3, 4, 16))       // 宽度非法
    }
}