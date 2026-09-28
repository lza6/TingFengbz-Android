package com.example.tfgy999

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.*
import android.view.Choreographer
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.*
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class AutoFrameBoostService : Service() {
    private val isRunning = AtomicBoolean(false)
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenRefreshRate = 60f
    private var targetFrameRate = 75
    private var frameWidth = 0
    private var frameHeight = 0
    private var wakeLock: PowerManager.WakeLock? = null

    private var sharedEglDisplay: EGLDisplay? = null
    private var sharedEglContext: EGLContext? = null
    private var renderEglSurface: EGLSurface? = null
    private var textureId = 0
    private val eglLock = java.util.concurrent.locks.ReentrantLock()

    private lateinit var renderThread: HandlerThread
    private lateinit var renderHandler: Handler
    private var frameInterpolator: FrameInterpolator? = null
    private lateinit var mainHandler: Handler

    private var programHandle = 0
    private var vboId = 0
    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texCoordBuffer: FloatBuffer
    private val shaderCache = mutableMapOf<String, Int>()

    private var fboId = 0
    private var fboTextureId = 0

    private val scope = CoroutineScope(Dispatchers.Default)
    private lateinit var frameDataFile: File
    private val frameDataEntries = ArrayDeque<JSONObject>()
    private var frameDataTick = 0
    private var frameDataTimer: Timer? = null
    private var startTime: Long = 0L
    private var lastChoreographerTime = 0L
    private var choreographerFrameCount = 0L

    companion object {
        const val NOTIFICATION_ID = 1
        const val NOTIFICATION_CHANNEL_ID = "AutoFrameBoostChannel"
        const val FLOATING_WINDOW_UPDATE_ACTION = "com.example.tfgy999.FLOATING_WINDOW_UPDATE"
        // T4: 帧数据历史上限（1 小时 @ 1Hz）与落盘间隔，防止文件无限增长 + 每秒全量重写
        const val MAX_FRAME_DATA_ENTRIES = 3600
        const val FRAME_DATA_FLUSH_SECONDS = 60
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        mainHandler = Handler(Looper.getMainLooper())
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        renderThread = HandlerThread("FrameBoostRenderThread", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        renderHandler = Handler(renderThread.looper)
        frameDataFile = File(filesDir, "frame_data_${System.currentTimeMillis()}.json")
        Timber.i("服务创建完成")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("服务运行中")
            .setContentText(buildNotificationText())
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notificationBuilder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notificationBuilder.build())
        }
        Timber.i("前台服务启动，通知ID: $NOTIFICATION_ID")

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoFrameBoost:WakeLock").apply { acquire() }

        isRunning.set(true)
        targetFrameRate = intent?.getIntExtra("targetFrameRate", 75) ?: 75

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")
        if (resultCode != Activity.RESULT_OK || data == null) {
            Timber.w("无效的resultCode或data为空，停止服务")
            stopSelfSafely()
            return START_NOT_STICKY
        }

        try {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Timber.i("MediaProjection停止，服务关闭")
                    stopSelfSafely()
                }
            }, null)
            Timber.i("MediaProjection创建成功")
        } catch (e: Exception) {
            Timber.e(e, "创建MediaProjection失败")
            stopSelfSafely()
            return START_NOT_STICKY
        }

        val displayMetrics = resources.displayMetrics
        frameWidth = displayMetrics.widthPixels
        frameHeight = displayMetrics.heightPixels

        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        screenRefreshRate = display.mode.refreshRate
        targetFrameRate = calculateTargetFrameRate(screenRefreshRate, targetFrameRate)

        startTime = System.currentTimeMillis()

        val displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    val newRefreshRate = displayManager.getDisplay(displayId).mode.refreshRate
                    frameInterpolator?.updateScreenRefreshRate(newRefreshRate)
                    Timber.i("屏幕刷新率变更: $newRefreshRate")
                }
            }
        }, null)

        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (isRunning.get()) {
                    choreographerFrameCount++
                    val elapsedSec = (frameTimeNanos - lastChoreographerTime) / 1_000_000_000.0
                    if (elapsedSec >= 1.0) {
                        frameInterpolator?.updateOriginalFps(choreographerFrameCount / elapsedSec)
                        choreographerFrameCount = 0
                        lastChoreographerTime = frameTimeNanos
                    }
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        })

        renderHandler.post {
            try {
                initRenderOpenGL()
                frameInterpolator = FrameInterpolator(
                    screenRefreshRate,
                    targetFrameRate,
                    renderHandler,
                    WeakReference(this),
                    onBufferReleased = { buffer -> DirectBufferPool.release(buffer) }
                )
                setupImageReader()
                virtualDisplay = createVirtualDisplay() ?: throw RuntimeException("VirtualDisplay创建失败")
                frameInterpolator?.startInterpolation()
                startFrameDataCollection()
                Timber.i("渲染线程初始化完成，屏幕刷新率: $screenRefreshRate, 目标帧率: $targetFrameRate")
            } catch (e: Exception) {
                Timber.e(e, "渲染线程初始化失败")
                recoverEGLResources(3)
            }
        }

        return START_STICKY
    }

    private fun calculateTargetFrameRate(refreshRate: Float, selectedRate: Int): Int {
        return when {
            refreshRate <= 60f -> 75
            refreshRate <= 90f -> 120
            else -> 144
        }
    }

    private fun startFrameDataCollection() {
        frameDataTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    if (!isRunning.get()) return
                    val currentTime = System.currentTimeMillis()
                    val originalFps = frameInterpolator?.currentFps ?: 0
                    val interpolatedFps = frameInterpolator?.currentInterpolatedFrames ?: 0
                    scope.launch(Dispatchers.IO) {
                        appendFrameData(currentTime, originalFps, interpolatedFps)
                    }
                }
            }, 0, 1000)
        }
    }

    @Synchronized
    private fun appendFrameData(timestamp: Long, originalFps: Number, interpolatedFps: Number) {
        frameDataEntries.addLast(JSONObject().apply {
            put("timestamp", timestamp)
            put("originalFps", originalFps)
            put("interpolatedFps", interpolatedFps)
        })
        if (frameDataEntries.size > MAX_FRAME_DATA_ENTRIES) frameDataEntries.removeFirst()
        // 按固定间隔落盘，避免每秒全量重写整个 JSON 文件（原实现为 O(n²)）
        if (++frameDataTick % FRAME_DATA_FLUSH_SECONDS == 0) flushFrameData()
    }

    @Synchronized
    private fun flushFrameData() {
        val jsonArray = JSONArray()
        frameDataEntries.forEach { jsonArray.put(it) }
        try {
            FileOutputStream(frameDataFile).use { fos ->
                fos.write(jsonArray.toString().toByteArray())
                fos.flush()
            }
            Timber.i("帧数据写入成功，记录数: ${frameDataEntries.size}")
        } catch (e: Exception) {
            Timber.e(e, "写入JSON文件失败")
        }
    }

    private fun buildNotificationText(): String {
        val memoryUsageMb = Runtime.getRuntime().totalMemory() / (1024.0 * 1024.0)
        return "插帧补帧 (目标 ${targetFrameRate}FPS), FPS: ${frameInterpolator?.currentFps ?: 0}, 补帧: ${frameInterpolator?.currentInterpolatedFrames ?: 0}, 内存: ${String.format("%.2f", memoryUsageMb)} MB"
    }

    private fun stopSelfSafely() {
        isRunning.set(false)
        mainHandler.post { stopSelf() }
        Timber.i("服务安全停止")
    }

    private fun setupImageReader() {
        if (!isRunning.get()) return
        imageReader?.close()
        imageReader = ImageReader.newInstance(frameWidth, frameHeight, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ reader ->
                if (!isRunning.get()) return@setOnImageAvailableListener
                scope.launch {
                    try {
                        val image = reader.acquireLatestImage() ?: return@launch
                        val buffer = captureFrameFromImage(image)
                        if (buffer != null) {
                            frameInterpolator?.processFrameBuffer(buffer, frameWidth, frameHeight)
                        }
                        image.close()
                    } catch (e: Exception) {
                        Timber.e(e, "帧处理发生错误")
                        recoverEGLResources(3)
                    }
                }
            }, null)
        }
        Timber.i("ImageReader设置完成，分辨率: ${frameWidth}x${frameHeight}")
    }

    private fun captureFrameFromImage(image: Image): ByteBuffer? {
        val planes = image.planes
        val buffer = planes[0].buffer
        val width = image.width
        val height = image.height
        val rowStride = planes[0].rowStride
        val pixelStride = planes[0].pixelStride
        val requiredSize = width * height * 4

        // T2: 优先从 DirectBufferPool 复用帧缓冲区，避免每帧 8MB+ 的直接内存申请/回收
        val outputBuffer = DirectBufferPool.acquire(requiredSize)
            ?: ByteBuffer.allocateDirect(requiredSize).order(ByteOrder.nativeOrder())

        buffer.rewind()
        if (rowStride == width * pixelStride) {
            // 无行填充：整块拷贝（常见路径）
            if (buffer.remaining() > outputBuffer.remaining()) {
                DirectBufferPool.release(outputBuffer)
                return null
            }
            outputBuffer.put(buffer)
        } else {
            // T3: 某些设备 RGBA_8888 平面存在行填充（rowStride > width*4）。
            // 原先整块拷贝会因剩余字节超限而整帧丢弃；改为按行拷贝并跳过填充字节。
            val copiedRows = copyFrameRows(buffer, outputBuffer, width, height, pixelStride, rowStride)
            if (copiedRows != height) {
                DirectBufferPool.release(outputBuffer)
                return null
            }
        }
        outputBuffer.rewind()
        return outputBuffer
    }

    fun makeCurrent(): Boolean {
        eglLock.lock()
        try {
            if (sharedEglDisplay == null || renderEglSurface == null || sharedEglContext == null) return false
            val success = EGL14.eglMakeCurrent(sharedEglDisplay, renderEglSurface, renderEglSurface, sharedEglContext)
            if (!success) Timber.w("eglMakeCurrent失败，错误码: ${EGL14.eglGetError()}")
            return success
        } finally {
            eglLock.unlock()
        }
    }

    fun swapBuffers(): Boolean {
        eglLock.lock()
        try {
            return sharedEglDisplay != null && renderEglSurface != null && EGL14.eglSwapBuffers(sharedEglDisplay, renderEglSurface)
        } finally {
            eglLock.unlock()
        }
    }

    private fun initRenderOpenGL() {
        eglLock.lock()
        try {
            if (sharedEglDisplay == null) {
                sharedEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                if (sharedEglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("无法获取EGL默认显示设备")
                val version = IntArray(2)
                if (!EGL14.eglInitialize(sharedEglDisplay, version, 0, version, 1)) throw RuntimeException("EGL初始化失败")
            }

            val configAttributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(sharedEglDisplay, configAttributes, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
                throw RuntimeException("EGL配置选择失败")
            }
            val config = configs[0]!!

            if (renderEglSurface == null) {
                val pbufferAttribs = intArrayOf(
                    EGL14.EGL_WIDTH, frameWidth,
                    EGL14.EGL_HEIGHT, frameHeight,
                    EGL14.EGL_NONE
                )
                renderEglSurface = EGL14.eglCreatePbufferSurface(sharedEglDisplay, config, pbufferAttribs, 0)
                if (renderEglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("创建PBuffer表面失败")
            }

            if (sharedEglContext == null) {
                val contextAttributes = intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
                )
                sharedEglContext = EGL14.eglCreateContext(sharedEglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttributes, 0)
                if (sharedEglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("创建EGL上下文失败")
            }

            if (!makeCurrent()) throw RuntimeException("设置当前EGL上下文失败")

            textureId = createTextureId(frameWidth, frameHeight)
            preloadShaders()
            setupFBO()

            programHandle = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, shaderCache["vertex"]!!)
                GLES20.glAttachShader(it, shaderCache["fragment"]!!)
                GLES20.glLinkProgram(it)
                val linkStatus = IntArray(1)
                GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, linkStatus, 0)
                if (linkStatus[0] == 0) {
                    GLES20.glDeleteProgram(it)
                    throw RuntimeException("链接OpenGL程序失败")
                }
            }

            val vertices = floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f)
            val texCoords = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
            vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(vertices)
                position(0)
            }
            texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(texCoords)
                position(0)
            }

            initVbo()
        } finally {
            eglLock.unlock()
        }
    }

    private fun setupFBO() {
        if (fboId != 0) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, frameWidth, frameHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            return
        }
        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        fboId = fboIds[0]

        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        fboTextureId = textureIds[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, frameWidth, frameHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTextureId, 0)

        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("FBO设置失败")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun initVbo() {
        val vboIds = IntArray(2)
        GLES20.glGenBuffers(2, vboIds, 0)
        vboId = vboIds[0]
        val texVboId = vboIds[1]

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        vertexBuffer.rewind()
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertexBuffer.capacity() * 4, vertexBuffer, GLES20.GL_STATIC_DRAW)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, texVboId)
        texCoordBuffer.rewind()
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, texCoordBuffer.capacity() * 4, texCoordBuffer, GLES20.GL_STATIC_DRAW)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun preloadShaders() {
        shaderCache["vertex"] = loadAndCompileShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        shaderCache["fragment"] = loadAndCompileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
    }

    fun loadAndCompileShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            GLES20.glDeleteShader(shader)
            throw RuntimeException("编译着色器失败")
        }
        return shader
    }

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """.trimIndent()

    private fun createVirtualDisplay(): VirtualDisplay? {
        eglLock.lock()
        try {
            if (mediaProjection == null || imageReader == null) return null
            return mediaProjection?.createVirtualDisplay(
                "FrameBoost", frameWidth, frameHeight, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, renderHandler
            )
        } finally {
            eglLock.unlock()
        }
    }

    private fun createTextureId(width: Int, height: Int): Int {
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

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
        frameDataTimer?.cancel()
        // 停止前尽力落盘帧数据历史（HistoryActivity 依赖此文件展示图表）
        scope.launch(Dispatchers.IO) { flushFrameData() }

        // T1: 先清空渲染线程上的回调，再让 FrameInterpolator 发布其 GL 资源清理。
        // 原顺序会导致下方 post 的清理被 removeCallbacksAndMessages 从队列中移除，
        // 使插值 program/纹理/FBO 每次启动停止循环后泄漏。
        renderHandler.removeCallbacksAndMessages(null)
        frameInterpolator?.stopInterpolation()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        wakeLock?.release()

        renderHandler.post {
            eglLock.lock()
            try {
                GLES20.glDeleteBuffers(2, intArrayOf(vboId, vboId + 1), 0)
                GLES20.glDeleteTextures(2, intArrayOf(textureId, fboTextureId), 0)
                GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
                GLES20.glDeleteProgram(programHandle)
                if (sharedEglDisplay != null && sharedEglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(sharedEglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (renderEglSurface != null) EGL14.eglDestroySurface(sharedEglDisplay, renderEglSurface)
                    if (sharedEglContext != null) EGL14.eglDestroyContext(sharedEglDisplay, sharedEglContext)
                    EGL14.eglTerminate(sharedEglDisplay)
                }
            } finally {
                eglLock.unlock()
            }
        }
        renderThread.quitSafely()
        updateNotification("服务已停止")
        Timber.i("服务销毁完成")
    }

    private fun recoverEGLResources(maxRetries: Int) {
        var retries = 0
        while (retries < maxRetries && isRunning.get()) {
            eglLock.lock()
            try {
                if (sharedEglDisplay != null && renderEglSurface != null) {
                    val attribs = IntArray(1)
                    if (!EGL14.eglQuerySurface(sharedEglDisplay, renderEglSurface, EGL14.EGL_WIDTH, attribs, 0)) {
                        recreateEntireEGLContext()
                        virtualDisplay?.release()
                        virtualDisplay = createVirtualDisplay()
                        return
                    }
                }
                if (!GLES20.glIsTexture(textureId)) recreateTextures()
                return
            } catch (e: Exception) {
                retries++
                Timber.e(e, "EGL资源恢复失败，第 $retries 次重试")
                Thread.sleep(200)
            } finally {
                eglLock.unlock()
            }
        }
        if (retries >= maxRetries) {
            Timber.e("EGL资源恢复超过最大重试次数，停止服务")
            stopSelfSafely()
        }
    }

    private fun recreateEntireEGLContext() {
        eglLock.lock()
        try {
            if (sharedEglDisplay != null && sharedEglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(sharedEglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (renderEglSurface != null) EGL14.eglDestroySurface(sharedEglDisplay, renderEglSurface)
                if (sharedEglContext != null) EGL14.eglDestroyContext(sharedEglDisplay, sharedEglContext)
                EGL14.eglTerminate(sharedEglDisplay)
            }
            sharedEglDisplay = null
            sharedEglContext = null
            renderEglSurface = null
            initRenderOpenGL()
        } finally {
            eglLock.unlock()
        }
    }

    private fun recreateTextures() {
        if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        textureId = createTextureId(frameWidth, frameHeight)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "自动补帧服务", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification(contentText: String? = null) {
        val notificationText = contentText ?: buildNotificationText()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("服务运行中")
            .setContentText(notificationText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(contentText == null)

        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notificationBuilder.build())
    }

    fun getTextureId(): Int = textureId
    fun getEglDisplay(): EGLDisplay? = sharedEglDisplay
    fun getEglSurface(): EGLSurface? = renderEglSurface
    fun getRenderTextureId(): Int = textureId
    fun getFboId(): Int = fboId
    fun getProgramHandle(): Int = programHandle

    fun updateFloatingWindow(fps: Int, interpolatedFrames: Int) {
        val intent = Intent(FLOATING_WINDOW_UPDATE_ACTION)
        intent.putExtra("fps", fps.toFloat())
        intent.putExtra("interpolatedFrames", interpolatedFrames)
        intent.putExtra("status", "运行中")
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}