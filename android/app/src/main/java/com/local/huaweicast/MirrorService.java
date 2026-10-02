package com.local.huaweicast;

import android.app.*;
import android.content.*;
import android.media.projection.*;
import android.net.wifi.WifiManager;
import android.os.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public final class MirrorService extends Service {
    public static volatile boolean active;
    public static volatile String status = "";
    public static volatile String deviceName = "";
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean ending;
    private boolean started;
    private MirrorEngine engine;
    private MediaProjection projection;
    private WifiManager.WifiLock wifiLock;
    private Dlna.Device device;
    private long startedAt;
    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() { @Override public void onStop() { handler.post(() -> finish("系统已结束录屏")); } };
    private void publish(String message) { status = message; sendBroadcast(new Intent(MediaService.UPDATE).setPackage(getPackageName())); }
    @Override public void onCreate() { super.onCreate(); getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("mirror", "屏幕投屏", NotificationManager.IMPORTANCE_LOW)); }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if ("stop".equals(intent.getAction())) { finish("屏幕投屏已结束"); return START_NOT_STICKY; }
        if (started) return START_NOT_STICKY;
        started = true; active = true; ending = false;
        device = new Dlna.Device(intent.getStringExtra("id"), intent.getStringExtra("device"), "", intent.getStringExtra("control"), intent.getStringExtra("type")); deviceName = device.name();
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, MirrorService.class).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        startForeground(2, new Notification.Builder(this, "mirror").setSmallIcon(R.drawable.ic_cast).setContentTitle("手机屏幕共享中").setContentText(device.name() + " · 点击通知可结束投屏").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null, "停止共享", stop).build()).build());
        try {
            Intent permission = intent.getParcelableExtra("permission");
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK, permission);
            projection.registerCallback(projectionCallback, handler);
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HuaweiCast:mirror"); wifiLock.acquire();
            engine = new MirrorEngine(message -> { handler.post(() -> finish(message)); return kotlin.Unit.INSTANCE; });
            CastQuality quality = new CastQuality(intent.getIntExtra("height", 540), intent.getIntExtra("fps", 20), intent.getIntExtra("kbps", 1200), VideoCodec.fromId(intent.getStringExtra("codec")));
            android.util.Log.i("HuaweiCastMirror", "quality=" + quality.summary());
            if (!engine.start(projection, getResources().getDisplayMetrics().densityDpi, quality)) throw new IllegalStateException(engine.error());
            startedAt = System.currentTimeMillis(); publish("正在生成实时画面…");
            String mode = intent.getStringExtra("mode");
            worker.execute(() -> connect(mode));
        } catch (Exception error) { finish("无法开始录屏：" + error.getMessage()); }
        return START_NOT_STICKY;
    }
    private void connect(String mode) {
        try {
            long deadline = System.currentTimeMillis() + 12000;
            while (!ending && !engine.ready() && System.currentTimeMillis() < deadline) Thread.sleep(100);
            if (ending) return;
            if (!engine.ready()) throw new IllegalStateException("编码器没有生成画面，请重新授权录屏");
            String host;
            URL control = new URL(device.controlUrl());
            try (DatagramSocket route = new DatagramSocket()) { route.connect(InetAddress.getByName(control.getHost()), control.getPort() > 0 ? control.getPort() : 80); host = route.getLocalAddress().getHostAddress(); }
            if (host.contains(":")) host = "[" + host + "]";
            MirrorHttpServer server = engine.getServer();
            boolean hls = "hls".equals(mode);
            String url = "http://" + host + ":" + server.getListeningPort() + server.basePath + (hls ? "/screen.m3u8" : "/screen.ts");
            String mime = hls ? "video/m3u8" : "video/MP2T";
            android.util.Log.i("HuaweiCastMirror", "stream=" + url);
            publish("正在通知机顶盒播放手机屏幕…");
            Dlna.action(device, "SetAVTransportURI", Map.of("CurrentURI", url, "CurrentURIMetaData", Dlna.metadata("华为手机实时屏幕", mime, url, -1)));
            if (ending) return;
            Dlna.action(device, "Play", Map.of("Speed", "1"));
            publish("已发送直播播放请求 · 等待机顶盒取流");
            worker.scheduleWithFixedDelay(() -> {
                if (ending) return;
                long last = server.lastRequest();
                long now = System.currentTimeMillis();
                String transport = "";
                try { transport = Dlna.invoke(device, "GetTransportInfo", Map.of()).getOrDefault("CurrentTransportState", ""); } catch (Exception ignored) {}
                if (last > 0 && now - last < 10000) publish(("PLAYING".equals(transport) ? "机顶盒正在播放" : "机顶盒正在接收") + " · " + (now-startedAt)/1000 + " 秒 · " + server.bytes()/1048576 + " MB · 系统声音");
                else if (now - Math.max(last, startedAt) > 45000) handler.post(() -> finish("机顶盒未持续取流。可切换直播兼容模式再试。"));
            }, 1, 2, TimeUnit.SECONDS);
        } catch (Exception error) { if (!ending) handler.post(() -> finish("连接失败：" + error.getMessage())); }
    }
    private void finish(String message) {
        if (ending) return;
        ending = true; active = false; publish(message);
        if (device != null) new Thread(() -> { try { Dlna.action(device, "Stop", Map.of()); } catch (Exception ignored) {} }, "mirror-stop").start();
        release(); stopSelf();
    }
    private void release() {
        worker.shutdownNow();
        if (engine != null) { engine.stop(); engine = null; }
        if (projection != null) { projection.unregisterCallback(projectionCallback); projection.stop(); projection = null; }
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
    }
    @Override public void onDestroy() { if (!ending) { ending = true; active = false; publish("屏幕共享已结束"); } release(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
