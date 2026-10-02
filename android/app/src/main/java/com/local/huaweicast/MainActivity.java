package com.local.huaweicast;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.net.wifi.WifiManager;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private final int RED = Color.rgb(203, 51, 69), DARK = Color.rgb(36, 46, 51), MUTED = Color.rgb(125, 139, 145), LINE = Color.rgb(229, 234, 237);
    private final ExecutorService scanner = Executors.newSingleThreadExecutor();
    private final LinkedHashMap<String, Dlna.Device> devices = new LinkedHashMap<>();
    private Dlna.Device selected;
    private boolean scanning, destroyed;
    private TextView scanStatus, mediaStatus, networkLabel;
    private Button scanButton, chooseButton, pauseButton, stopButton, mirrorButton;
    private String mirrorMode = "ts";
    private CastQuality quality;
    private Button qualityButton;
    private Dlna.Device permissionTarget;
    private LinearLayout deviceList;
    private WifiManager.MulticastLock multicastLock;
    private ServiceDiscovery serviceDiscovery;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, String> otherServices = new LinkedHashMap<>();
    private final BroadcastReceiver updates = new BroadcastReceiver() { @Override public void onReceive(Context context, Intent intent) { updateMedia(); } };
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable background(int color, int stroke, float radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); if (stroke != 0) drawable.setStroke(dp(1), stroke); return drawable; }
    private TextView text(String value, int size, int color, boolean bold) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setLineSpacing(dp(3), 1); if (bold) view.setTypeface(null, Typeface.BOLD); return view; }
    private void add(LinearLayout parent, View view, int top) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(top); parent.addView(view, params); }
    private Button button(String label, boolean primary) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(14); button.setTextColor(primary ? Color.WHITE : DARK); button.setMinHeight(dp(48)); button.setPadding(dp(12), dp(8), dp(12), dp(8)); button.setStateListAnimator(null); button.setBackground(background(primary ? RED : Color.WHITE, primary ? 0 : LINE, 6)); return button;
    }
    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        if (bundle != null) mirrorMode = bundle.getString("mirrorMode", "ts");
        SharedPreferences preferences = getSharedPreferences("cast", MODE_PRIVATE);
        quality = new CastQuality(preferences.getInt("height", 540), preferences.getInt("fps", 20), preferences.getInt("kbps", 1200));
        getWindow().setStatusBarColor(Color.rgb(246,247,248)); getWindow().setNavigationBarColor(Color.WHITE);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(246,247,248));
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(24), dp(20), dp(24), dp(28)); scroll.addView(root); setContentView(scroll);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this); icon.setImageResource(R.drawable.ic_cast); header.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView brand = text("华为投屏", 20, DARK, true); LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(0, -2, 1); brandParams.leftMargin = dp(12); header.addView(brand, brandParams);
        ImageButton help = new ImageButton(this); help.setImageResource(android.R.drawable.ic_menu_help); help.setBackground(background(Color.WHITE, LINE, 5)); help.setContentDescription("连接帮助"); help.setTooltipText("连接帮助"); header.addView(help, new LinearLayout.LayoutParams(dp(38), dp(38))); help.setOnClickListener(v -> showHelp()); add(root, header, 0);
        networkLabel = text("本地网络 · 无需电视安装应用", 11, MUTED, false); add(root, networkLabel, 16);
        add(root, new ScreenArt(this), 16);
        LinearLayout mediaHeader = new LinearLayout(this); mediaHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView mediaTitle = text("接收设备", 18, DARK, true); mediaHeader.addView(mediaTitle, new LinearLayout.LayoutParams(0, -2, 1));
        scanButton = button("搜索", false); mediaHeader.addView(scanButton, new LinearLayout.LayoutParams(dp(72), dp(39))); scanButton.setOnClickListener(v -> scan()); add(root, mediaHeader, 24);
        add(root, text("DLNA 媒体投屏 · 同时检测乐联 / AirPlay 服务", 11, MUTED, false), 8);
        scanStatus = text("尚未搜索接收设备", 12, MUTED, false); add(root, scanStatus, 18);
        deviceList = new LinearLayout(this); deviceList.setOrientation(LinearLayout.VERTICAL); add(root, deviceList, 8);
        RadioGroup modes = new RadioGroup(this); modes.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton low = new RadioButton(this); low.setId(View.generateViewId()); low.setText("低延迟"); low.setTextSize(12);
        RadioButton compatible = new RadioButton(this); compatible.setId(View.generateViewId()); compatible.setText("兼容模式"); compatible.setTextSize(12);
        modes.addView(low, new RadioGroup.LayoutParams(0, dp(44), 1)); modes.addView(compatible, new RadioGroup.LayoutParams(0, dp(44), 1)); modes.check("hls".equals(mirrorMode) ? compatible.getId() : low.getId());
        modes.setOnCheckedChangeListener((g,id) -> mirrorMode = id == compatible.getId() ? "hls" : "ts"); add(root, modes, 16);
        qualityButton = button("画质设置\n" + quality.summary(), false); qualityButton.setOnClickListener(v -> editQuality()); add(root, qualityButton, 4);
        mirrorButton = button("开始屏幕投屏", true); mirrorButton.setEnabled(false); add(root, mirrorButton, 8); mirrorButton.setOnClickListener(v -> startMirror());
        TextView mirrorNote = text("画质设置于下次投屏生效 · 不包含系统音频", 10, MUTED, false); mirrorNote.setGravity(Gravity.CENTER); add(root, mirrorNote, 10);
        chooseButton = button("选择媒体并投屏", false); chooseButton.setEnabled(false); add(root, chooseButton, 16); chooseButton.setOnClickListener(v -> chooseMedia());
        mediaStatus = text("", 12, MUTED, false); add(root, mediaStatus, 12);
        LinearLayout controls = new LinearLayout(this); controls.setGravity(Gravity.CENTER);
        pauseButton = button("暂停", false); stopButton = button("结束投屏", false);
        LinearLayout.LayoutParams controlParams = new LinearLayout.LayoutParams(0, dp(44), 1); controls.addView(pauseButton, controlParams);
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(44), 1); stopParams.leftMargin = dp(12); controls.addView(stopButton, stopParams); add(root, controls, 8);
        pauseButton.setOnClickListener(v -> startService(new Intent(this, MediaService.class).setAction(MediaService.state.paused() ? "play" : "pause")));
        stopButton.setOnClickListener(v -> { stopButton.setEnabled(false); startService(new Intent(this, MirrorService.active ? MirrorService.class : MediaService.class).setAction("stop")); });
        add(root, text("本地连接 · 无需账号 · 个人使用免费", 10, MUTED, false), 27);
        if (bundle != null) {
            String control = bundle.getString("control");
            if (control != null) { selected = new Dlna.Device(bundle.getString("id"), bundle.getString("name"), bundle.getString("maker"), control, bundle.getString("type")); devices.put(selected.id().isEmpty() ? selected.controlUrl() : selected.id(), selected); renderDevices(); }
            if (bundle.getBoolean("projectionPending")) permissionTarget = selected;
        }
        updateMedia(); updateNetwork();
        if (bundle == null) root.post(this::scan);
        if (getIntent().hasExtra("description")) root.post(() -> addByAddress(getIntent().getStringExtra("description")));
    }
    private void openMirror() {
        if (Build.MANUFACTURER.equalsIgnoreCase("HUAWEI") || Build.BRAND.equalsIgnoreCase("HONOR")) {
            new AlertDialog.Builder(this).setTitle("华为系统无线投屏").setMessage("打开“设置 → 更多连接 → 手机投屏 / 无线投屏”，开启无线投屏并选择机顶盒。\n\n也可以从手机控制中心开启无线投屏。连接后选择手机模式即可镜像整个屏幕。\n\n此机型的专用入口受系统权限保护，需要在系统页面操作。").setPositiveButton("前往系统设置", (d,w) -> startActivity(new Intent(Settings.ACTION_SETTINGS))).setNegativeButton("关闭", null).show();
            return;
        }
        try { startActivity(new Intent(Settings.ACTION_CAST_SETTINGS)); }
        catch (ActivityNotFoundException | SecurityException error) {
            new AlertDialog.Builder(this).setTitle("打开华为无线投屏").setMessage("此系统未提供直接入口。请在“设置 → 更多连接 → 手机投屏 / 无线投屏”中连接机顶盒，或从控制中心开启无线投屏。").setPositiveButton("打开设置", (d,w) -> startActivity(new Intent(Settings.ACTION_SETTINGS))).setNegativeButton("关闭", null).show();
        }
    }
    private void showHelp() {
        new AlertDialog.Builder(this).setTitle("连接中国电信机顶盒").setMessage("手机与机顶盒连接同一 Wi-Fi，打开机顶盒已有的投屏服务。选择接收设备，点击开始屏幕投屏，并同意系统录屏授权。\n\n本应用通过 DLNA 实时视频流投送屏幕，不需要电视安装应用。若低延迟模式不出画面，结束后切换兼容模式。画面通常会有数秒延迟，具体取决于机顶盒播放器。\n\n当前只传画面。受保护内容可能黑屏。\n\n使用 AirSonic 的录屏编码与 TS 封装组件，Copyright © 2026 Chunguang Wei，PolyForm Noncommercial 1.0.0 许可，仅限非商业用途。").setPositiveButton("知道了", null).setNeutralButton("系统无线投屏", (d,w) -> openMirror()).show();
    }
    private void updateNetwork() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = manager.getNetworkCapabilities(manager.getActiveNetwork());
        boolean wifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        networkLabel.setText(wifi ? "Wi-Fi 已连接 · 无需电视安装应用" : "请连接与机顶盒相同的 Wi-Fi");
    }
    private void scan() {
        if (scanning || MirrorService.active || MediaService.state.active()) return;
        scanning = true; devices.clear(); otherServices.clear(); selected = null; renderDevices(); updateMedia(); scanButton.setEnabled(false); scanStatus.setText("正在通过手机网络搜索接收端…");
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        multicastLock = wifi.createMulticastLock("HuaweiCast:discovery"); multicastLock.setReferenceCounted(false);
        try { multicastLock.acquire(); } catch (Exception error) { scanning = false; scanButton.setEnabled(true); scanStatus.setText("无法启用组播搜索：" + error.getMessage()); return; }
        if (serviceDiscovery != null) serviceDiscovery.stop();
        serviceDiscovery = new ServiceDiscovery(this, new ServiceDiscovery.Listener() {
            @Override public void diagnostic(String message) { android.util.Log.i("HuaweiCastDiscovery", message); }
            @Override public void found(android.net.nsd.NsdServiceInfo info) {
                String protocol = info.getServiceType().contains("leboremote") ? "乐联" : "AirPlay";
                String name = info.getServiceName();
                for (String key : new String[]{"name", "fn", "deviceName"}) { byte[] value = info.getAttributes().get(key); if (value != null) { name = new String(value, java.nio.charset.StandardCharsets.UTF_8); break; } }
                String address = info.getHost() == null ? "" : info.getHost().getHostAddress();
                android.util.Log.i("HuaweiCastDiscovery", "mDNS: " + name + " " + protocol + " " + address + ":" + info.getPort() + " attrs=" + info.getAttributes().keySet());
                if (name.contains("@")) name = name.substring(name.indexOf('@') + 1);
                otherServices.put(address, name + "\n" + protocol + " · 已发现服务，未接入此协议"); renderDevices();
            }
        });
        serviceDiscovery.start();
        mainHandler.postDelayed(() -> {
            if (serviceDiscovery != null) serviceDiscovery.stop();
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
            scanning = false; scanButton.setEnabled(true);
        }, 12000);
        scanner.execute(() -> {
            String failure = null;
            try {
                Dlna.discover(new Dlna.Listener() {
                    @Override public void diagnostic(String message) { android.util.Log.i("HuaweiCastDiscovery", message); }
                    @Override public void found(Dlna.Device device) { android.util.Log.i("HuaweiCastDiscovery", "DLNA: " + device.name() + " " + device.controlUrl()); runOnUiThread(() -> {
                    if (destroyed) return;
                    devices.put(device.id().isEmpty() ? device.controlUrl() : device.id(), device);
                    if (selected == null || device.name().contains("电信")) selected = device;
                    renderDevices(); updateMedia();
                    scanStatus.setText("已发现 " + devices.size() + " 个 DLNA 接收端");
                }); }
                }, socket -> {
                    ConnectivityManager manager = getSystemService(ConnectivityManager.class);
                    for (Network network : manager.getAllNetworks()) { NetworkCapabilities capabilities = manager.getNetworkCapabilities(network); if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { network.bindSocket(socket); break; } }
                });
            } catch (Exception error) { failure = error.getMessage(); }
            String result = failure;
            runOnUiThread(() -> {
                if (destroyed) return;
                scanStatus.setText(result != null ? "搜索失败：" + result : devices.isEmpty() ? "未发现 DLNA，正在继续检测其他投屏协议。" : "已发现 " + devices.size() + " 个 DLNA 接收端，请选择设备");
                mainHandler.postDelayed(() -> { if (!destroyed && devices.isEmpty()) scanStatus.setText(otherServices.isEmpty() ? "未发现接收端，请确认机顶盒投屏服务已打开。" : "仅发现专有 / AirPlay 服务，不能直接使用 DLNA。需要验证镜像协议。" ); }, 6500);
            });
        });
    }
    private void renderDevices() {
        deviceList.removeAllViews();
        for (Dlna.Device device : devices.values()) {
            RadioButton row = new RadioButton(this); row.setText(device.name() + "\nDLNA 接收端"); row.setTextSize(13); row.setTextColor(DARK); row.setPadding(dp(10),dp(14),dp(10),dp(14)); row.setBackground(background(Color.WHITE, LINE, 5));
            row.setChecked(selected != null && selected.controlUrl().equals(device.controlUrl()));
            row.setOnClickListener(v -> { selected = device; renderDevices(); updateMedia(); }); add(deviceList, row, 7);
        }
        if (devices.isEmpty()) for (String description : otherServices.values()) {
            TextView row = text(description, 12, MUTED, false); row.setPadding(dp(12), dp(12), dp(12), dp(12)); row.setBackground(background(Color.WHITE, LINE, 5)); add(deviceList, row, 7);
        }
    }
    private void chooseMedia() {
        if (selected == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*"); intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*", "audio/*"});
        try { startActivityForResult(intent, 10); } catch (ActivityNotFoundException error) { Toast.makeText(this, "未找到文件选择器", Toast.LENGTH_LONG).show(); }
    }
    private void startMirror() {
        if (selected == null || MirrorService.active || MediaService.state.active()) return;
        permissionTarget = selected;
        mirrorButton.setEnabled(false);
        startActivityForResult(getSystemService(android.media.projection.MediaProjectionManager.class).createScreenCaptureIntent(), 11);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 11) {
            if (result != RESULT_OK || data == null || permissionTarget == null) { updateMedia(); Toast.makeText(this, "未授权屏幕共享", Toast.LENGTH_SHORT).show(); return; }
            Dlna.Device target = permissionTarget; permissionTarget = null;
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 20);
            Intent service = new Intent(this, MirrorService.class).putExtra("permission", data).putExtra("mode", mirrorMode).putExtra("height", quality.height()).putExtra("fps", quality.fps()).putExtra("kbps", quality.kbps());
            service.putExtra("id", target.id()).putExtra("device", target.name()).putExtra("control", target.controlUrl()).putExtra("type", target.serviceType()); startForegroundService(service); return;
        }
        if (request != 10 || result != RESULT_OK || data == null || data.getData() == null || selected == null) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 20);
        Intent service = new Intent(this, MediaService.class).setData(data.getData()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        service.putExtra("id", selected.id()).putExtra("device", selected.name()).putExtra("control", selected.controlUrl()).putExtra("type", selected.serviceType());
        startForegroundService(service);
    }
    private void addByAddress(String address) {
        scanner.execute(() -> {
            try {
                Dlna.Device device = Dlna.describe(address);
                if (device == null) throw new IllegalArgumentException("该设备没有 DLNA AVTransport 服务");
                runOnUiThread(() -> { if (!destroyed) { devices.put(device.id().isEmpty() ? device.controlUrl() : device.id(), device); selected = device; renderDevices(); updateMedia(); scanStatus.setText("已验证设备：" + device.name()); } });
            } catch (Exception error) { runOnUiThread(() -> { if (!destroyed) scanStatus.setText("设备地址不可用：" + error.getMessage()); }); }
        });
    }
    private void updateMedia() {
        MediaService.State state = MediaService.state;
        boolean active = state.active() || MirrorService.active;
        mediaStatus.setText(MirrorService.active || (!MirrorService.status.isEmpty() && !state.active()) ? MirrorService.deviceName + "\n" + MirrorService.status : state.status().isEmpty() ? "尚未开始投屏" : state.title() + "\n" + state.device() + " · " + state.status());
        mirrorButton.setEnabled(selected != null && !active); chooseButton.setEnabled(selected != null && !active); scanButton.setEnabled(!active && !scanning); pauseButton.setEnabled(state.active()); stopButton.setEnabled(active); pauseButton.setText(state.paused() ? "继续播放" : "暂停");
    }
    @Override protected void onStart() { super.onStart(); registerReceiver(updates, new IntentFilter(MediaService.UPDATE), "com.local.huaweicast.INTERNAL", null, Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0); updateMedia(); updateNetwork(); }
    @Override protected void onStop() { unregisterReceiver(updates); super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle bundle) { super.onSaveInstanceState(bundle); bundle.putString("mirrorMode", mirrorMode); bundle.putBoolean("projectionPending", permissionTarget != null); if (selected != null) { bundle.putString("id", selected.id()); bundle.putString("name", selected.name()); bundle.putString("maker", selected.manufacturer()); bundle.putString("control", selected.controlUrl()); bundle.putString("type", selected.serviceType()); } }
    private void editQuality() {
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(24), dp(8), dp(24), dp(12));
        add(content, text("分辨率", 13, DARK, true), 8);
        Spinner resolution = new Spinner(this);
        resolution.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"360p · 640 × 360", "540p · 960 × 540", "720p · 1280 × 720", "1080p · 1920 × 1080"}));
        for (int i=0; i<CastQuality.HEIGHTS.length; i++) if (CastQuality.HEIGHTS[i] == quality.height()) resolution.setSelection(i);
        add(content, resolution, 8);
        add(content, text("帧率", 13, DARK, true), 16);
        Spinner rate = new Spinner(this); rate.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"15 fps", "20 fps", "25 fps", "30 fps", "60 fps"}));
        for (int i=0; i<CastQuality.FRAME_RATES.length; i++) if (CastQuality.FRAME_RATES[i] == quality.fps()) rate.setSelection(i);
        add(content, rate, 8);
        TextView bitrateLabel = text("", 13, DARK, true); add(content, bitrateLabel, 16);
        SeekBar bitrate = new SeekBar(this); bitrate.setMax(115); bitrate.setProgress((quality.kbps()-500)/100);
        Runnable showRate = () -> bitrateLabel.setText(String.format(java.util.Locale.US, "码率 · %.1f Mbps", (500 + bitrate.getProgress()*100)/1000.0));
        bitrate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) { showRate.run(); }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        showRate.run(); add(content, bitrate, 12);
        add(content, text("0.5–12 Mbps · 实际帧率与画质取决于手机、网络和接收端。", 11, MUTED, false), 6);
        ScrollView viewport = new ScrollView(this); viewport.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("画质设置").setView(viewport).setNegativeButton("取消", null).setNeutralButton("恢复默认", null).setPositiveButton("保存", (d,w) -> {
            quality = new CastQuality(CastQuality.HEIGHTS[resolution.getSelectedItemPosition()], CastQuality.FRAME_RATES[rate.getSelectedItemPosition()], 500 + bitrate.getProgress()*100);
            getSharedPreferences("cast", MODE_PRIVATE).edit().putInt("height", quality.height()).putInt("fps", quality.fps()).putInt("kbps", quality.kbps()).apply();
            qualityButton.setText("画质设置\n" + quality.summary());
        }).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> { resolution.setSelection(1); rate.setSelection(1); bitrate.setProgress(7); }));
        dialog.show();
    }
    @Override protected void onDestroy() { destroyed = true; scanner.shutdownNow(); mainHandler.removeCallbacksAndMessages(null); if (serviceDiscovery != null) serviceDiscovery.stop(); if (multicastLock != null && multicastLock.isHeld()) multicastLock.release(); super.onDestroy(); }
    private final class ScreenArt extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ScreenArt(Context context) { super(context); setMinimumHeight(dp(110)); setBackground(background(Color.rgb(32,43,48), 0, 7)); }
        @Override protected void onMeasure(int w, int h) { super.onMeasure(w, MeasureSpec.makeMeasureSpec(dp(110), MeasureSpec.EXACTLY)); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); float center = getWidth()/2f, top=dp(15); paint.setColor(Color.rgb(156,178,186)); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2));
            canvas.scale(.75f, .75f, center, 0);
            canvas.drawRoundRect(center-dp(78),top,center+dp(70),top+dp(85),dp(4),dp(4),paint);
            canvas.drawLine(center-dp(4),top+dp(85),center-dp(4),top+dp(100),paint); canvas.drawLine(center-dp(29),top+dp(100),center+dp(21),top+dp(100),paint);
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.rgb(32,43,48)); canvas.drawRoundRect(center+dp(44),top+dp(44),center+dp(81),top+dp(113),dp(5),dp(5),paint);
            paint.setStyle(Paint.Style.STROKE); paint.setColor(Color.rgb(211,224,230)); canvas.drawRoundRect(center+dp(44),top+dp(44),center+dp(81),top+dp(113),dp(5),dp(5),paint); canvas.drawLine(center+dp(57),top+dp(106),center+dp(68),top+dp(106),paint);
            paint.setColor(Color.rgb(64,187,150)); canvas.drawArc(center-dp(33),top+dp(28),center+dp(10),top+dp(71),270,90,false,paint); canvas.drawArc(center-dp(24),top+dp(37),center+dp(1),top+dp(62),270,90,false,paint);
            paint.setStyle(Paint.Style.FILL); canvas.drawCircle(center-dp(12),top+dp(49),dp(2),paint);
        }
    }
}
