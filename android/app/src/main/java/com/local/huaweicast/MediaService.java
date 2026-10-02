package com.local.huaweicast;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.*;
import android.provider.OpenableColumns;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public final class MediaService extends Service {
    public static final String UPDATE = "com.local.huaweicast.UPDATE";
    public record State(String title, String device, String status, boolean active, boolean paused) {}
    public static volatile State state = new State("尚未选择媒体", "", "", false, false);
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private MediaServer server;
    private File media;
    private Dlna.Device device;
    private WifiManager.WifiLock wifiLock;
    private volatile boolean shuttingDown;
    private boolean busy;
    private void publish(String status, boolean active, boolean paused) {
        state = new State(state.title(), device == null ? "" : device.name(), status, active, paused);
        sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));
    }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("media", "媒体投屏", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if ("stop".equals(intent.getAction())) { worker.execute(this::finish); return START_NOT_STICKY; }
        if ("pause".equals(intent.getAction()) || "play".equals(intent.getAction())) {
            String action = "pause".equals(intent.getAction()) ? "Pause" : "Play";
            worker.execute(() -> { try { if (device != null) { Dlna.action(device, action, action.equals("Play") ? Map.of("Speed", "1") : Map.of()); publish("已发送控制请求", true, action.equals("Pause")); } } catch (Exception error) { publish(error.getMessage(), state.active(), state.paused()); } });
            return START_NOT_STICKY;
        }
        if (busy) return START_NOT_STICKY;
        busy = true;
        MirrorService.status = "";
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, MediaService.class).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "media").setContentTitle("华为投屏 · 媒体共享中").setContentText("保持与电视处于同一 Wi-Fi").setSmallIcon(com.local.huaweicast.R.drawable.ic_cast).setContentIntent(pending).setOngoing(true).addAction(new Notification.Action.Builder(null, "结束投屏", stop).build()).build();
        startForeground(1, notification);
        device = new Dlna.Device(intent.getStringExtra("id"), intent.getStringExtra("device"), "", intent.getStringExtra("control"), intent.getStringExtra("type"));
        Uri uri = intent.getData();
        state = new State("正在准备媒体", device.name(), "正在读取文件", true, false);
        publish("正在读取文件", true, false);
        worker.execute(() -> prepare(uri));
        return START_NOT_STICKY;
    }
    private void prepare(Uri uri) {
        try {
            String mime = getContentResolver().getType(uri);
            if (mime == null || !(mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/"))) throw new IOException("请选择照片、视频或音乐");
            String title = "手机媒体";
            try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) { if (cursor != null && cursor.moveToFirst()) title = cursor.getString(0); }
            media = new File(getCacheDir(), "shared-media.bin");
            state = new State(title, device.name(), "正在准备文件", true, false); publish("正在准备文件", true, false);
            try (InputStream input = getContentResolver().openInputStream(uri); OutputStream output = new FileOutputStream(media)) {
                if (input == null) throw new IOException("无法读取文件");
                byte[] buffer = new byte[65536]; long total = 0; int n;
                while ((n = input.read(buffer)) != -1) {
                    if (shuttingDown) return;
                    total += n;
                    if (total > 2L * 1024 * 1024 * 1024 || getCacheDir().getUsableSpace() < 20 * 1024 * 1024) throw new IOException("文件过大或存储空间不足（最大 2 GB）");
                    output.write(buffer, 0, n);
                }
            }
            if (media.length() == 0) throw new IOException("文件为空");
            String host;
            URL control = new URL(device.controlUrl());
            try (DatagramSocket route = new DatagramSocket()) { route.connect(InetAddress.getByName(control.getHost()), control.getPort() > 0 ? control.getPort() : 80); host = route.getLocalAddress().getHostAddress(); }
            if (host.equals("0.0.0.0") || host.equals("127.0.0.1")) throw new IOException("无法获取 Wi-Fi 地址");
            if (host.contains(":")) host = "[" + host + "]";
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HuaweiCast:media"); wifiLock.acquire();
            server = new MediaServer(media, mime); server.start(5000, true);
            String mediaUrl = "http://" + host + ":" + server.getListeningPort() + server.mediaPath;
            publish("正在连接电视", true, false);
            Dlna.action(device, "SetAVTransportURI", Map.of("CurrentURI", mediaUrl, "CurrentURIMetaData", Dlna.metadata(title, mime, mediaUrl, media.length())));
            Dlna.action(device, "Play", Map.of("Speed", "1"));
            publish("播放请求已发送 · 请查看电视", true, false);
        } catch (Exception error) {
            publish("投屏失败：" + error.getMessage(), false, false); release(); stopSelf();
        }
    }
    private void finish() {
        shuttingDown = true;
        try { if (device != null && server != null) Dlna.action(device, "Stop", Map.of()); }
        catch (Exception ignored) {}
        publish("投屏已结束", false, false); release(); stopSelf();
    }
    private void release() {
        if (server != null) { server.stop(); server = null; }
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (media != null) media.delete();
        stopForeground(STOP_FOREGROUND_REMOVE);
    }
    @Override public void onDestroy() { shuttingDown = true; worker.shutdownNow(); if (state.active()) publish("共享已结束", false, false); release(); super.onDestroy(); }
    @Override public void onTimeout(int startId, int fgsType) { shuttingDown = true; publish("共享已达系统时长限制，请重新开始", false, false); release(); stopSelf(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
