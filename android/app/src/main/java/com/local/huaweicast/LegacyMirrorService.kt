package com.local.huaweicast

import android.app.*
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.InetAddresses
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.airsonic.sender.screen.ScreenMirrorCaster
import java.net.DatagramSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** Bounded video-only interoperability experiment. Normal DLNA casting is unchanged. */
class LegacyMirrorService : Service() {
    companion object {
        @JvmField @Volatile var active = false
        @JvmField @Volatile var status = ""
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var stopping = false
    private var receiverName = ""
    @Volatile private var client: LegacyMirrorClient? = null
    private var projection: MediaProjection? = null
    @Volatile private var caster: ScreenMirrorCaster? = null
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { main.post { finish("实验屏幕共享已结束") } }
    }
    private fun publish(message: String) {
        status = if (receiverName.isEmpty()) message else "$receiverName\n$message"
        android.util.Log.i("HuaweiCastExperiment", status)
        sendBroadcast(Intent(MediaService.UPDATE).setPackage(packageName))
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("mirror-experiment", "实验屏幕共享", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "stop") { finish("实验镜像已停止"); return START_NOT_STICKY }
        if (active || stopping) return START_NOT_STICKY
        active = true
        receiverName = intent.getStringExtra("name") ?: ""
        val stop = PendingIntent.getService(this, 3, Intent(this, LegacyMirrorService::class.java).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(3, Notification.Builder(this, "mirror-experiment").setSmallIcon(R.drawable.ic_cast)
            .setContentTitle("实验镜像 · 仅画面 · 30 秒")
            .setContentText("旧版兼容接口测试，非完整乐联协议")
            .setOngoing(true).addAction(Notification.Action.Builder(null, "停止", stop).build()).build())
        try {
            val token = intent.getParcelableExtra<Intent>("permission") ?: error("缺少屏幕授权")
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, token)
            projection!!.registerCallback(projectionCallback, main)
            val manager = getSystemService(ConnectivityManager::class.java)
            val wifi = manager.allNetworks.firstOrNull {
                manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: error("请连接 Wi-Fi")
            val host = intent.getStringExtra("host") ?: error("缺少接收端")
            // Only numeric addresses from resolved mDNS are accepted; no hostnames or DNS lookup.
            require(InetAddresses.isNumericAddress(host)) { "无效接收地址" }
            val endpoint = LelinkEndpoint.from(intent.getStringExtra("name"), InetAddresses.parseNumericAddress(host), intent.getIntExtra("control", 0),
                mapOf("mirror" to intent.getIntExtra("mirror", 0).toString().toByteArray(StandardCharsets.US_ASCII)))
            val quality = CastQuality(540, 20, 1200, VideoCodec.H264)
            publish("正在连接实验镜像接口…")
            worker.execute {
                try {
                    if (stopping) return@execute
                    val transport = LegacyMirrorClient(quality.width(), quality.height(), { caster?.requestSyncFrame() }, { message -> main.post { finish(message) } })
                    client = transport
                    if (stopping) { transport.close(); return@execute }
                    transport.connect(endpoint, object : LegacyMirrorClient.Binder {
                        override fun bind(socket: Socket) { wifi.bindSocket(socket) }
                        override fun bind(socket: DatagramSocket) { wifi.bindSocket(socket) }
                    })
                    if (stopping) return@execute
                    val encoder = ScreenMirrorCaster(width = quality.width(), height = quality.height(), dpi = resources.displayMetrics.densityDpi,
                        bitRate = quality.bitRate(), frameRate = quality.fps(), videoCodec = VideoCodec.H264,
                        emit = { true }, onCodecConfig = transport::configure, onCapturedVideoFrame = transport::offer,
                        syncFrameIntervalMs = 1000)
                    caster = encoder
                    check(encoder.start(projection!!)) { encoder.lastError ?: "硬件编码启动失败" }
                    if (!stopping) main.post { tick() }
                } catch (error: Exception) { main.post { finish("实验失败：${error.message}") } }
            }
            main.postDelayed({ finish("30 秒实验已结束，请确认电视是否显示画面") }, 30000)
        } catch (error: Exception) { finish("实验失败：${error.message}") }
        return START_NOT_STICKY
    }
    private fun tick() {
        if (stopping) return
        val count = client?.sentFrames() ?: 0
        val clocks = client?.timingReplies() ?: 0
        publish("实验发送 $count 帧 · 时钟应答 $clocks 次 · 仅画面 · 接收显示未确认")
        main.postDelayed({ tick() }, 1000)
    }
    private fun finish(message: String) {
        if (stopping) return
        stopping = true; active = false; main.removeCallbacksAndMessages(null)
        client?.close(); publish(message); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        stopping = true; active = false; main.removeCallbacksAndMessages(null); client?.close()
        worker.execute {
            caster?.stop(); caster = null
            projection?.let { runCatching { it.unregisterCallback(projectionCallback) }; runCatching { it.stop() } }; projection = null
        }
        worker.shutdown()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
