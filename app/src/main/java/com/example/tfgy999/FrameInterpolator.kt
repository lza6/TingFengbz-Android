package com.example.tfgy999

import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import timber.log.Timber
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class FrameInterpolator(
    private var screenRefreshRate: Float,
    private var targetFrameRate: Int,
    private val handler: Handler,
    private val serviceRef: WeakReference<AutoFrameBoostService>,
    private val onBufferReleased: (ByteBuffer) -> Unit = {}
) {
    private val isRunning = AtomicBoolean(false)
    private val isStopped = AtomicBoolean(false)
    private var lastFrameTime = 0L
    private var lastFpsUpdateTime = 0L
    private var originalFrameCount = 0L
    private var interpolatedFrameCount = 0L
    var currentFps = 0
        private set
    var currentInterpolatedFrames = 0
        private set
    private var width = 0
    private var height = 0
    private var interpolationProgram = 0
    private var prevTextureId = 0
    private val maxQueueSize = max(2, Runtime.getRuntime().availableProcessors() / 2)
    private val frameQueue = ConcurrentLinkedDeque<Pair<ByteBuffer, Long>>()
    private var fboId = 0
    private var fboTextureId = 0
    private var droppedFrames = 0L
    private val textureUploadThread = HandlerThread("TextureUploadThread", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val textureUploadHandler = Handler(textureUploadThread.looper)
    private var frameCount = 0
    private var frameIntervalMs = (1000f / screenRefreshRate).toLong()
    private val renderLock = Any()

    fun startInterpolation() {
        if (isRunning.compareAndSet(false, true)) {
            isStopped.set(false)
            lastFrameTime = System.nanoTime()
            lastFpsUpdateTime = lastFrameTime
            handler.post { initInterpolationShader() }
            handler.post {
                try {
                    val choreographerCallback = object : Choreographer.FrameCallback {
                        override fun doFrame(frameTimeNanos: Long) {
                            if (!isRunning.get() || isStopped.get()) return
                            val currentTime = System.nanoTime()
                            val factor = calculateInterpolationFactor(currentTime)
                            renderFrame(factor)
                            ensureTargetFrameRate(currentTime)
                            if (isRunning.get() && !isStopped.get()) {
                                Choreographer.getInstance().postFrameCallback(this)
                            }
                        }
                    }
                    Choreographer.getInstance().postFrameCallback(choreographerCallback)
                    startFpsCalculation()
                    Timber.i("插帧开始成功，屏幕刷新率: $screenRefreshRate, 目标帧率: $targetFrameRate")
                } catch (e: Exception) {
                    Timber.e(e, "启动插帧失败")
                    isRunning.set(false)
                }
            }
        }
    }

    fun stopInterpolation() {
        isRunning.set(false)
        isStopped.set(true)
        handler.removeCallbacksAndMessages(null)
        textureUploadHandler.removeCallbacksAndMessages(null)
        // 归还所有仍在队列中的帧缓冲区，避免内存泄漏
        while (frameQueue.isNotEmpty()) {
            val (buffer, _) = frameQueue.removeFirst()
            buffer.rewind()
            buffer.clear()
            onBufferReleased(buffer)
        }
        handler.post {
            if (interpolationProgram != 0) {
                GLES20.glDeleteProgram(interpolationProgram)
                interpolationProgram = 0
            }
            if (prevTextureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(prevTextureId), 0)
                prevTextureId = 0
            }
            if (fboId != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
                fboId = 0
            }
            if (fboTextureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(fboTextureId), 0)
                fboTextureId = 0
            }
        }
        textureUploadThread.quitSafely()
        Timber.i("插帧停止成功，所有缓冲区已释放")
    }

    fun processFrameBuffer(buffer: ByteBuffer?, frameWidth: Int, frameHeight: Int) {
        if (buffer == null || isStopped.get()) {
            buffer?.rewind()
            buffer?.clear()
            // isStopped 分支同样归还池化缓冲区，避免池容量缩水
            buffer?.let { onBufferReleased(it) }
            return
        }
        var enqueued = false
        try {
            val requiredSize = frameWidth * frameHeight * 4
            if (buffer.capacity() < requiredSize) {
                buffer.rewind()
                buffer.clear()
                onBufferReleased(buffer)
                Timber.w("缓冲区容量不足，已释放，所需: $requiredSize, 实际: ${buffer.capacity()}")
                return
            }
            if (width != frameWidth || height != frameHeight) {
                width = frameWidth
                height = frameHeight
                handler.post { setupFBO() }
            }
            val currentTime = System.nanoTime()
            frameQueue.addLast(Pair(buffer, currentTime))
            enqueued = true
            originalFrameCount++
            while (frameQueue.size > maxQueueSize) {
                val oldFrame = frameQueue.removeFirst()
                oldFrame.first.rewind()
                oldFrame.first.clear()
                // 归还投递到上传线程 FIFO 执行：保证该缓冲的 GL 上传（含渲染期重传）全部
                // 执行完毕后才回池，避免复用竞态导致错帧（审查 HIGH-1）
                textureUploadHandler.post { onBufferReleased(oldFrame.first) }
            }
            frameCount++
            preloadNextFrame(buffer)
            Timber.d("帧缓冲区处理成功，队列大小: ${frameQueue.size}, 原始帧计数: $originalFrameCount")
        } catch (e: Exception) {
            Timber.e(e, "处理帧缓冲区失败")
            buffer.rewind()
            buffer.clear()
            // 仅归还“尚未入队”的缓冲区，避免与队列 drain/淘汰路径重复归还同一实例（审查#6）
            if (!enqueued) onBufferReleased(buffer)
        }
    }

    private fun preloadNextFrame(buffer: ByteBuffer) {
        val nextTextureId = createTextureId()
        textureUploadHandler.post {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, nextTextureId)
            buffer.rewind()
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        }
    }

    private fun renderFrame(factor: Float) {
        if (isStopped.get() || frameQueue.size < 2) {
            Timber.w("跳过渲染，队列大小: ${frameQueue.size}")
            return
        }
        val startTime = System.nanoTime()
        try {
            val (prevFrame, _) = frameQueue.elementAt(frameQueue.size - 2)
            val (latestFrame, _) = frameQueue.last()
            renderInterpolatedFrame(prevFrame, latestFrame, factor)
            interpolatedFrameCount++
            val renderTimeMs = (System.nanoTime() - startTime) / 1_000_000f
            Timber.i("渲染插帧成功，因子: $factor, 耗时: $renderTimeMs ms, 原始帧率: $currentFps FPS, 补帧帧率: $currentInterpolatedFrames")
        } catch (e: Exception) {
            Timber.e(e, "渲染帧失败")
        }
    }

    private fun renderInterpolatedFrame(prevBuffer: ByteBuffer, latestBuffer: ByteBuffer, factor: Float) {
        synchronized(renderLock) {
            val service = serviceRef.get() ?: throw IllegalStateException("服务引用丢失")
            try {
                if (!service.makeCurrent()) {
                    Timber.w("渲染插帧时EGL上下文无效")
                    return
                }
                if (prevTextureId == 0) prevTextureId = createTextureId()

                textureUploadHandler.post {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTextureId)
                    prevBuffer.rewind()
                    GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, prevBuffer)
                }

                val latestTextureId = service.getRenderTextureId()
                textureUploadHandler.post {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, latestTextureId)
                    latestBuffer.rewind()
                    GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, latestBuffer)
                }

                GLES20.glUseProgram(interpolationProgram)
                val factorHandle = GLES20.glGetUniformLocation(interpolationProgram, "uFactor")
                GLES20.glUniform1f(factorHandle, factor)
                val prevTextureHandle = GLES20.glGetUniformLocation(interpolationProgram, "uPrevTexture")
                val latestTextureHandle = GLES20.glGetUniformLocation(interpolationProgram, "uLatestTexture")
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTextureId)
                GLES20.glUniform1i(prevTextureHandle, 0)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, latestTextureId)
                GLES20.glUniform1i(latestTextureHandle, 1)

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, service.getFboId())
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

                if (!service.swapBuffers()) {
                    Timber.w("EGL缓冲区交换失败")
                }
            } catch (e: Exception) {
                Timber.e(e, "渲染插值帧失败")
            }
        }
    }

    private fun startFpsCalculation() {
        handler.post(object : Runnable {
            private var lastUpdateTime = System.nanoTime()
            override fun run() {
                val now = System.nanoTime()
                val elapsed = (now - lastUpdateTime) / 1e9
                if (elapsed >= 0.5) { // 更新间隔缩短至500ms
                    currentInterpolatedFrames = (interpolatedFrameCount / elapsed).toInt()
                    interpolatedFrameCount = 0
                    lastUpdateTime = now
                    serviceRef.get()?.updateFloatingWindow(currentFps, currentInterpolatedFrames)
                    Timber.d("FPS计算: 原始帧率=$currentFps, 补帧帧率=$currentInterpolatedFrames, 经过时间=$elapsed")
                }
                if (isRunning.get()) {
                    handler.postDelayed(this, 500)
                }
            }
        })
    }

    fun updateOriginalFps(fps: Double) {
        currentFps = fps.toInt()
    }

    private fun calculateInterpolationFactor(currentTime: Long): Float {
        if (frameQueue.size < 2) return 0f
        val targetFrameTime = 1_000_000_000L / targetFrameRate
        val actualFrameTime = currentTime - frameQueue.last().second
        val factor = (actualFrameTime.toFloat() / targetFrameTime.toFloat()).coerceIn(0f, 1.0f)
        return smoothFactor(factor)
    }

    private fun smoothFactor(factor: Float): Float {
        val previousFactor = frameQueue.lastOrNull()?.let {
            (it.second - frameQueue.elementAt(frameQueue.size - 2).second).toFloat() / (1_000_000_000L / targetFrameRate)
        } ?: factor
        return (0.7f * previousFactor + 0.3f * factor).coerceIn(0f, 1.0f)
    }

    private fun ensureTargetFrameRate(currentTime: Long) {
        val elapsedTimeMs = (currentTime - lastFrameTime) / 1_000_000L
        val expectedFrames = (elapsedTimeMs / frameIntervalMs).toInt()
        val maxDroppedFrames = screenRefreshRate.toInt()
        if (expectedFrames > 1) {
            val framesToDrop = min(expectedFrames - 1, maxDroppedFrames)
            droppedFrames += framesToDrop.toLong()
            Timber.w("检测到丢帧: $droppedFrames，立即补帧")
            for (i in 1..framesToDrop) {
                val factor = i.toFloat() / expectedFrames
                renderFrame(factor)
            }
        }
        lastFrameTime = currentTime
    }

    fun updateScreenRefreshRate(newRefreshRate: Float) {
        screenRefreshRate = newRefreshRate
        frameIntervalMs = (1000f / screenRefreshRate).toLong()
        // 目标帧率尊重用户选择（构造函数传入），刷新率变化仅调整帧间隔
        Timber.i("屏幕刷新率更新: $screenRefreshRate, 目标帧率: $targetFrameRate, 每帧间隔: $frameIntervalMs ms")
    }

    private fun initInterpolationShader() {
        val service = serviceRef.get() ?: return
        if (!service.makeCurrent()) {
            Timber.w("初始化着色器时EGL上下文无效")
            return
        }
        try {
            val vertexShader = service.loadAndCompileShader(GLES20.GL_VERTEX_SHADER, """
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = aTexCoord;
                }
            """.trimIndent())
            val fragmentShader = service.loadAndCompileShader(GLES20.GL_FRAGMENT_SHADER, """
                precision mediump float;
                varying vec2 vTexCoord;
                uniform sampler2D uPrevTexture;
                uniform sampler2D uLatestTexture;
                uniform float uFactor;
                void main() {
                    gl_FragColor = mix(texture2D(uPrevTexture, vTexCoord), texture2D(uLatestTexture, vTexCoord), uFactor);
                }
            """.trimIndent())
            interpolationProgram = GLES20.glCreateProgram()
            GLES20.glAttachShader(interpolationProgram, vertexShader)
            GLES20.glAttachShader(interpolationProgram, fragmentShader)
            GLES20.glLinkProgram(interpolationProgram)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(interpolationProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                Timber.e("插值着色器链接失败: ${GLES20.glGetProgramInfoLog(interpolationProgram)}")
                GLES20.glDeleteProgram(interpolationProgram)
                interpolationProgram = 0
            }
            setupFBO()
        } catch (e: Exception) {
            Timber.e(e, "初始化插值着色器失败")
        }
    }

    private fun setupFBO() {
        if (fboId != 0) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            return
        }
        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        fboId = fboIds[0]
        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        fboTextureId = textureIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTextureId, 0)
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("FBO设置失败")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun createTextureId(): Int {
        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        val id = textureIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id
    }
}