package com.local.huaweicast

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.media.MediaCodecInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.*
import android.net.wifi.WifiManager
import android.os.*
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import com.airsonic.sender.screen.ScreenMirrorCaster
import java.net.DatagramSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Shared capture lifecycle for native Lelink and legacy compatibility mirroring. */
class LegacyMirrorService : Service() {
    companion object {
        @JvmField @Volatile var active = false
        @JvmField @Volatile var status = ""
        @JvmField @Volatile var details = ""
        @JvmField @Volatile var routeLabel = "乐播兼容镜像"
        @JvmStatic fun startIntent(context: Context, endpoint: LelinkEndpoint, quality: CastQuality): Intent =
            startIntent(context, endpoint, quality, false)
        @JvmStatic fun startIntent(context: Context, endpoint: LelinkEndpoint, quality: CastQuality, nativeLelink: Boolean): Intent {
            require(!nativeLelink || endpoint.advertisesFreeNativePairing()) { "接收端未声明免密码 Lelink 模式" }
            return Intent(context, LegacyMirrorService::class.java)
                .putExtra("name", endpoint.name()).putExtra("host", endpoint.address().hostAddress)
                .putExtra("control", endpoint.controlPort()).putExtra("mirror", endpoint.mirrorPort().orElse(0))
                .putExtra("raop", endpoint.raopPort().orElse(0))
                .putExtra("nativeLelink", nativeLelink)
                .putExtra("lelink", if (endpoint.advertisesFreeNativePairing()) endpoint.controlPort() else 0)
                .putExtra("htv", endpoint.metadata()["htv"]).putExtra("atv", endpoint.metadata()["atv"])
                .putExtra("height", quality.height()).putExtra("fps", quality.fps()).putExtra("kbps", quality.kbps())
                .putExtra("codec", quality.codec().id())
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var stopping = false
    @Volatile private var client: MirrorVideoTransport? = null
    @Volatile private var caster: ScreenMirrorCaster? = null
    @Volatile private var lastFrameAt = 0L
    @Volatile private var audioClient: RaopAudioClient? = null
    @Volatile private var audioEncryptor: LelinkMediaWire.AudioEncryptor? = null
    @Volatile private var audioCapture: PlaybackAudioCapture? = null
    @Volatile private var audioReady = false
    @Volatile private var lastAudioFrameAt = 0L
    @Volatile private var capturedAudioFrames = 0L
    @Volatile private var audioConfiguration: CompletableFuture<ByteArray>? = null
    private var audioStatus = "仅画面"
    private var projection: MediaProjection? = null
    private var receiverName = ""
    private var quality = CastQuality.DEFAULT
    private var dimensions: MirrorDimensions? = null
    private var desiredSize: Pair<Int, Int>? = null
    private var resizing = false
    private var startedAt = 0L
    private var renewWakeAt = 0L
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var boundNetwork: Network? = null
    private var networkRegistered = false
    private var displayRegistered = false
    private var cleanupScheduled = false
    private var capturedSize: Pair<Int, Int>? = null
    private val displayChanged = Runnable { resizeIfNeeded() }
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { main.post { finish("系统已结束屏幕共享") } }
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width <= 0 || height <= 0 || stopping) return
            capturedSize = width to height
            scheduleResize()
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            main.post { if (network == boundNetwork) finish("Wi-Fi 连接已断开，投屏已停止") }
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) scheduleResize()
        }
    }

    private fun publish(message: String) {
        status = if (receiverName.isEmpty()) message else "$receiverName\n$message"
        android.util.Log.i("HuaweiCastLegacy", status)
        sendBroadcast(Intent(MediaService.UPDATE).setPackage(packageName))
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("mirror-compat", "乐播兼容镜像", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "stop") { finish("投屏已停止"); return START_NOT_STICKY }
        if (active || stopping) return START_NOT_STICKY
        active = true
        val nativeLelink = intent.getBooleanExtra("nativeLelink", false)
        routeLabel = if (nativeLelink) "Lelink 免密码镜像" else "乐播兼容镜像"
        receiverName = intent.getStringExtra("name") ?: ""
        quality = CastQuality(intent.getIntExtra("height", 540), intent.getIntExtra("fps", 20),
            intent.getIntExtra("kbps", 1200), VideoCodec.fromId(intent.getStringExtra("codec")))
        details = "${quality.codec().label()} · 仅画面"
        val withAudio = nativeLelink || intent.getBooleanExtra("audioEligible", false)
        audioStatus = if (withAudio) "正在连接系统声音" else "仅画面：" +
            (intent.getStringExtra("audioUnavailableReason") ?: "接收端未声明兼容音频能力")
        startedAt = SystemClock.elapsedRealtime()
        startForeground(3, notification("正在连接"))
        try {
            val token = intent.getParcelableExtra<Intent>("permission") ?: error("缺少屏幕授权")
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, token)
            projection!!.registerCallback(projectionCallback, main)
            val manager = getSystemService(ConnectivityManager::class.java)
            val wifi = manager.allNetworks.firstOrNull {
                manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: error("请连接 Wi-Fi")
            boundNetwork = wifi
            manager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), networkCallback)
            networkRegistered = true
            getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
            displayRegistered = true
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HuaweiCast:legacy").apply { acquire() }
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HuaweiCast:legacy").apply {
                    setReferenceCounted(false)
                    acquire(120000)
                }
            renewWakeAt = SystemClock.elapsedRealtime() + 60000
            val host = intent.getStringExtra("host") ?: error("缺少接收端")
            require(InetAddresses.isNumericAddress(host)) { "无效接收地址" }
            val ports = mutableMapOf<String, ByteArray>()
            if (intent.getIntExtra("mirror", 0) > 0) ports["mirror"] = intent.getIntExtra("mirror", 0).toString().toByteArray(StandardCharsets.US_ASCII)
            if (intent.getIntExtra("raop", 0) > 0) ports["raop"] = intent.getIntExtra("raop", 0).toString().toByteArray(StandardCharsets.US_ASCII)
            if (intent.getIntExtra("lelink", 0) > 0) ports["lelinkport"] = intent.getIntExtra("lelink", 0).toString().toByteArray(StandardCharsets.US_ASCII)
            for (key in listOf("htv", "atv")) intent.getStringExtra(key)?.let { ports[key] = it.toByteArray(StandardCharsets.UTF_8) }
            val endpoint = LelinkEndpoint.from(receiverName, InetAddresses.parseNumericAddress(host), intent.getIntExtra("control", 0), ports)
            require(!nativeLelink || endpoint.advertisesFreeNativePairing()) { "接收端未声明免密码 Lelink 模式，连接已停止" }
            val source = sourceSize()
            desiredSize = source
            val size = encodingSize(source)
            dimensions = size
            updateDetails(size)
            publish("正在连接$routeLabel…")
            worker.execute {
                try {
                    if (stopping) return@execute
                    val session = LegacyMirrorSession.create()
                    val binder = object : LegacyMirrorClient.Binder {
                        override fun bind(socket: Socket) { wifi.bindSocket(socket) }
                        override fun bind(socket: DatagramSocket) { wifi.bindSocket(socket) }
                    }
                    var latencyMs = 90
                    var mirrorEndpoint = endpoint
                    val nativeTransport = if (nativeLelink) {
                        LelinkMirrorClient(size.width(), size.height(), { caster?.requestSyncFrame() },
                            { message -> main.post { finish(message) } }, session, quality.codec()).also { transport ->
                            client = transport
                            if (stopping) { transport.close(); return@execute }
                            transport.connect(endpoint, binder)
                            if (stopping) return@execute
                        }
                    } else null
                    if (withAudio) {
                        if (!nativeLelink) check(endpoint.raopPort().isPresent) { "接收端未广播音频端口" }
                        val rate = intent.getIntExtra("audioRate", 44100)
                        val configured = CompletableFuture<ByteArray>()
                        audioConfiguration = configured
                        val capture = PlaybackAudioCapture(
                            onFrame = { data, pts ->
                                lastAudioFrameAt = SystemClock.elapsedRealtime()
                                capturedAudioFrames++
                                if (audioReady) audioClient?.offer(data, pts)
                            }, onError = { message ->
                                configured.completeExceptionally(IllegalStateException(message))
                                main.post { finish(message) }
                            }, sampleRate = rate, aacProfile = MediaCodecInfo.CodecProfileLevel.AACObjectELD,
                            rawOutput = true, onFormat = { config -> configured.complete(config) }, useNativeEld = true)
                        audioCapture = capture
                        if (stopping) return@execute
                        capture.start(projection!!)
                        val config = configured.get(4, TimeUnit.SECONDS)
                        val format = AacEldConfig.parse(config)
                        check(format.sampleRate() == rate && format.channels() == 2) { "音频编码参数不匹配" }
                        if (stopping) return@execute
                        val audio = RaopAudioClient(session, rate, format.samplesPerFrame(), config,
                            { message -> main.post { finish("音频连接已结束：$message") } }, quality.codec())
                        audioClient = audio
                        if (stopping) { audio.close(); return@execute }
                        if (nativeTransport != null) {
                            val seed = nativeTransport.mediaSeed()
                            val encryptor = try { LelinkMediaWire.AudioEncryptor(seed) } finally { seed.fill(0) }
                            audioEncryptor = encryptor
                            if (stopping) return@execute
                            audio.connectNative(endpoint.address(), binder,
                                { control, timing -> nativeTransport.setupAudio(rate, control, timing) }, encryptor::encryptPayload)
                        } else {
                            audio.connect(endpoint.address(), endpoint.raopPort().asInt, binder)
                        }
                        if (stopping) return@execute
                        android.util.Log.i("HuaweiCastLegacy", "Audio negotiated: ${audio.description()}, mirrorPort=${audio.negotiatedMirrorPort().orElse(0)}")
                        latencyMs = audio.latencyMs()
                        if (audio.negotiatedMirrorPort().isPresent) {
                            val negotiated = ports.toMutableMap()
                            negotiated["mirror"] = audio.negotiatedMirrorPort().asInt.toString().toByteArray(StandardCharsets.US_ASCII)
                            mirrorEndpoint = LelinkEndpoint.from(receiverName, endpoint.address(), endpoint.controlPort(), negotiated)
                        }
                        audioReady = true
                        main.post { if (!stopping) { audioStatus = "系统声音 · AAC-ELD"; updateDetails(size) } }
                    }
                    val transport: MirrorVideoTransport = nativeTransport ?: LegacyMirrorClient(size.width(), size.height(), { caster?.requestSyncFrame() },
                        { message -> main.post { finish(message) } }, session, latencyMs, audioReady, quality.codec()).also {
                        client = it
                        if (stopping) { it.close(); return@execute }
                        it.connect(mirrorEndpoint, binder)
                    }
                    if (stopping) return@execute
                    val encoder = ScreenMirrorCaster(width = size.width(), height = size.height(), dpi = resources.displayMetrics.densityDpi,
                        bitRate = quality.bitRate(), frameRate = quality.fps(), videoCodec = quality.codec(),
                        emit = { true }, onCodecConfig = { sps, pps ->
                            runCatching { transport.configure(sps, pps) }.onFailure { error ->
                                main.post { finish("H.264 参数集不适合$routeLabel：${error.message}") }
                            }
                        },
                        onHevcCodecConfig = { vps, sps, pps ->
                            runCatching { transport.configureHevc(vps, sps, pps) }.onFailure { error ->
                                main.post { finish("H.265 参数集不适合$routeLabel，请切换 H.264：${error.message}") }
                            }
                        },
                        onCapturedVideoFrame = { bytes, pts, key ->
                            lastFrameAt = SystemClock.elapsedRealtime()
                            transport.offer(bytes, pts, key)
                        }, syncFrameIntervalMs = 1000)
                    caster = encoder
                    check(encoder.start(projection!!)) { encoder.lastError ?: "硬件编码启动失败" }
                    main.post {
                        if (!stopping) {
                            lastFrameAt = SystemClock.elapsedRealtime()
                            resizeIfNeeded()
                            tick()
                        }
                    }
                } catch (error: Exception) { main.post { finish("投屏失败：${error.message}") } }
            }
        } catch (error: Exception) { finish("投屏失败：${error.message}") }
        return START_NOT_STICKY
    }
    private fun notification(message: String): Notification {
        val stop = PendingIntent.getService(this, 3, Intent(this, LegacyMirrorService::class.java).setAction("stop"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "mirror-compat").setSmallIcon(R.drawable.ic_cast)
            .setContentTitle("$routeLabel · $receiverName").setContentText(message).setContentIntent(open)
            .setOngoing(true).addAction(Notification.Action.Builder(null, "停止投屏", stop).build()).build()
    }
    @Suppress("DEPRECATION")
    private fun sourceSize(): Pair<Int, Int> {
        capturedSize?.let { return it }
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = DisplayMetrics()
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }
    private fun encodingSize(source: Pair<Int, Int>): MirrorDimensions {
        val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            it.isEncoder && it.isHardwareAccelerated && it.supportedTypes.any { type -> type.equals(quality.codec().mime(), true) }
        } ?: error("此手机没有可用的 ${quality.codec().label()} 硬件编码器")
        val limits = info.getCapabilitiesForType(quality.codec().mime()).videoCapabilities
            ?: error("无法读取硬件编码尺寸能力")
        val size = MirrorDimensions.fit(source.first, source.second, quality.height(),
            maxOf(2, limits.widthAlignment), maxOf(2, limits.heightAlignment))
        require(limits.areSizeAndRateSupported(size.width(), size.height(), quality.fps().toDouble())) {
            "硬件编码器不支持 ${size.width()}×${size.height()} / ${quality.fps()} fps，请降低画质或帧率"
        }
        return size
    }
    private fun updateDetails(size: MirrorDimensions) {
        details = "${quality.codec().label()} · ${size.width()}×${size.height()} · ${quality.fps()} fps · " +
            String.format(Locale.US, "%.1f Mbps", quality.kbps() / 1000.0) + " · $audioStatus"
    }
    private fun scheduleResize() {
        if (stopping) return
        main.removeCallbacks(displayChanged)
        main.postDelayed(displayChanged, 350)
    }
    private fun resizeIfNeeded() {
        if (stopping || resizing || caster == null) return
        try {
            val source = sourceSize()
            if (source == desiredSize) return
            val size = encodingSize(source)
            desiredSize = source
            if (size == dimensions) return
            resizing = true
            publish("正在调整画面方向…")
            worker.execute {
                try {
                    if (stopping) return@execute
                    val encoder = caster ?: return@execute
                    val transport = client ?: return@execute
                    check(encoder.resize(size.width(), size.height()) { transport.resize(size.width(), size.height()) }) {
                        encoder.lastError ?: "画面方向调整失败"
                    }
                    main.post {
                        if (!stopping) {
                            dimensions = size
                            updateDetails(size)
                            lastFrameAt = SystemClock.elapsedRealtime()
                            resizing = false
                            resizeIfNeeded()
                        }
                    }
                } catch (error: Exception) { main.post { finish("画面调整失败：${error.message}") } }
            }
        } catch (error: Exception) { finish("画面调整失败：${error.message}") }
    }
    private fun tick() {
        if (stopping) return
        val now = SystemClock.elapsedRealtime()
        if (!resizing && now - lastFrameAt > 15000) { finish("编码器停止产生画面，投屏已结束"); return }
        if (audioReady && now - lastAudioFrameAt > 15000) { finish("系统声音采集停止输出，投屏已结束"); return }
        if (!resizing) {
            // Renew only while producing frames; a stuck service cannot hold the CPU awake indefinitely.
            if (now >= renewWakeAt) {
                wakeLock?.acquire(120000)
                renewWakeAt = now + 60000
            }
            val elapsed = (now - startedAt) / 1000
            val duration = String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60)
            val audioFrames = if (audioReady) " · 音频 ${audioClient?.sentFrames() ?: 0} 包" else ""
            if (audioReady && elapsed % 10L == 0L) audioClient?.let { audio ->
                android.util.Log.i("HuaweiCastAudio", "Transport captured=$capturedAudioFrames ${audio.diagnostics()} resent=${audio.resentFrames()} timing=${audio.timingReplies()}")
            }
            publish("正在发送 · $duration · ${client?.sentFrames() ?: 0} 帧$audioFrames\n$details")
            if (elapsed % 5L == 0L) getSystemService(NotificationManager::class.java).notify(3, notification("$duration · $details"))
        }
        main.postDelayed({ tick() }, 1000)
    }
    private fun finish(message: String) {
        if (stopping) return
        stopping = true
        main.removeCallbacksAndMessages(null)
        audioReady = false
        audioConfiguration?.cancel(true)
        audioClient?.close()
        client?.close()
        publish(message)
        cleanup {
            active = false
            publish(message)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
    private fun cleanup(after: () -> Unit = {}) {
        if (cleanupScheduled) return
        cleanupScheduled = true
        if (networkRegistered) runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        if (displayRegistered) runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
        networkRegistered = false; displayRegistered = false
        worker.execute {
            runCatching { audioCapture?.stop() }; audioCapture = null
            runCatching { audioEncryptor?.close() }; audioEncryptor = null
            runCatching { caster?.stop() }; caster = null
            projection?.let { runCatching { it.unregisterCallback(projectionCallback) }; runCatching { it.stop() } }; projection = null
            runCatching { wifiLock?.let { if (it.isHeld) it.release() } }; wifiLock = null
            runCatching { wakeLock?.let { if (it.isHeld) it.release() } }; wakeLock = null
            main.post(after)
        }
        worker.shutdown()
    }
    override fun onDestroy() {
        if (!stopping) finish("屏幕共享已结束")
        // Keep the queued cleanup completion: it releases the busy state only after the encoder stops.
        main.removeCallbacks(displayChanged)
        audioReady = false
        audioConfiguration?.cancel(true)
        audioClient?.close()
        client?.close()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
