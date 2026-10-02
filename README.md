# 华为投屏

[下载最新 APK](https://github.com/fenghengzhi/huawei-screen-cast/releases/latest) · [自动构建](https://github.com/fenghengzhi/huawei-screen-cast/actions/workflows/android.yml)

个人使用的 Android 应用，将华为手机的整个屏幕通过机顶盒已有的 DLNA 服务播放，不需要给电视安装浏览器或新的接收应用，不使用乐播收费 SDK，也无需电脑中转。

## 已确认的目标设备

已在中国电信投屏机顶盒和 Kodi 接收端测试。测试机顶盒的 DLNA 服务声明支持 `video/MP2T` 和 `video/m3u8`；华为 NOH-AN00 真机可发现并投送屏幕。

2026-10-02 在真机 + Kodi 上验证 1.2.0：实际编码器为海思硬件 `OMX.hisi.video.encoder.avc`。旧版在静止界面 15 秒仅发送 68 帧，出帧与网络到达间隔最大约 2.4 秒，导致接收端反复缓冲。加入固定帧率 GPU 渲染后，20 秒收到 400 帧，无 TS 连续计数错误；帧时间戳间隔 P95 约 51 ms、最大约 58 ms，网络到达间隔最大约 80 ms。用户确认画面正常、loading 明显减少或消失。上述间隔不代表端到端投屏延迟。

最初系统 Miracast 入口的方案不能满足这台机顶盒，已改成应用内实时录屏直播。系统无线投屏仅保留在帮助中的备用入口。

## 使用

1. 手机和机顶盒连接同一 Wi-Fi，打开机顶盒已有的投屏服务。
2. 打开应用，搜索并选择目标电视、机顶盒或 Kodi。
3. 点击“开始屏幕投屏”，首次使用允许录音权限，并同意系统屏幕共享授权。默认同步手机播放声音，不采集麦克风，无需额外开关。
4. 默认“低延迟”直接提供 MPEG-TS 直播；若机顶盒不出画面，先结束投屏，再切换“兼容模式”使用 HLS。
5. 点击“结束投屏”或通知中的“停止共享”结束录屏与本地直播服务。

默认输出 960×540、20 fps、约 1.2 Mbps 的 H.264 画面，其他参数可在“画质设置”调整。系统播放声音默认以 AAC-LC、48 kHz、立体声、128 kbps 传输，与视频共用单调时钟。Android 仅允许捕获同一用户下、允许被捕获的媒体/游戏等播放声音；通话、禁止音频捕获的应用及受保护内容可能无声，不会退回麦克风录音。权限拒绝或采集启动失败会明确提示。HTTP 读取合并多个 TS 包，单次额外等待上限 4 ms，减少 188 字节小块发送；直播队列为 512 包。GPU 按固定时钟将最新屏幕纹理提交给硬件编码器，即使画面静止也保持连续输出。播放器本身仍会缓冲，HLS 通常更慢。受保护的视频、密码输入或安全窗口可能黑屏。

可以单独分享照片、视频和音乐：选择设备后点击“选择媒体并投屏”。通过系统文件选择器读取所选文件，缓存到应用私有目录，通过随机 URL 的本地 HTTP 服务提供给电视。支持 GET、HEAD、HTTP Range、暂停、继续及结束。单个文件最大 2 GB，不转码；媒体兼容性由机顶盒决定。结束时关闭服务器、清除缓存并释放 Wi-Fi 锁。

应用的 mDNS 发现默认只用于协议诊断。乐联设备另有下文所述的独立实验入口，不会自动替代 DLNA。SSDP 使用实际 Wi-Fi 网络绑定，避免 VPN 或蜂窝网络误选接口。

## 乐联兼容性实验

当前实验分支不等于完整的 Lelink 私有协议实现，也没有引入乐播 SDK。设备列表的“乐联协议实验”入口会解析 `_leboremote._tcp` 广播，通过控制端口的 RTSP OPTIONS 和广播镜像端口的 `/stream.xml` 做只读检测。广播名称、UID 或端口存在均不被当成投屏成功。

部分乐播接收端同时提供旧版 AirPlay 兼容镜像入口。能力检测通过后，可明确选择“测试 30 秒”：经系统录屏授权，尝试发送 H.264 540p / 20 fps 画面，在同一 TCP 连接上先 GET `/stream.xml` 再 POST `/stream`，使用二进制 plist 握手、128 字节包头、avcC 参数集、AVCC 视频帧、心跳与 NTP 应答。该实验仅画面、无声音、无加密，30 秒自动结束；仅用于可信局域网，不应投送敏感内容。正常 DLNA 模式仍默认包含系统声音。

实验遇到授权拒绝或不支持的响应会停止，不提交乐播凭据、不绕过会员或认证。发送帧数只代表写入连接，不证明电视已显示画面；实际兼容性需要接收端确认。尚未实现私有 Lelink 会话协商、声音传输和加密协商，不对外发布为正式 Lelink 支持。

2026-10-02：电信机顶盒的 RTSP OPTIONS 和 `/stream.xml` 均返回 200，但未确认其实际画面。后续按用户指定改用“投屏电视G2”（接收端 8.20.56），已通过接收端截图确认显示华为手机画面，硬件解码日志连续约 20 fps。直接 POST 不触发播放；同连接 GET→POST 加保留零值的帧头扩展字段可正常显示。填写偏移 56/60 的输出尺寸反而使 G2 无法解码，故本实验保留这些未协商字段为零。接收端没有请求 NTP，不能据此认定 NTP 互操作已通过。能力查询在 G2 上可能短暂进入镜像界面，并非保证完全无界面副作用。

**G2 明确显示“限时体验 5 分钟，开通会员享不限时投屏”。该实验不能免除接收端的收费或限时策略，不提供自动重连重置试用时间等绕过功能。** 当前测试仍有较大黑边，声音、缩放、长期稳定性和其他接收端兼容性尚待完善，实验分支不触发正式 Release。

公开协议事实来源：[乐播发现字段](https://github.com/lebosdk/Lebo-mDNS-Client)、[旧版镜像握手](https://github.com/openairplay/airplay-spec/blob/master/src/screen_mirroring/http_requests.md)、[封包](https://github.com/openairplay/airplay-spec/blob/master/src/screen_mirroring/stream_packets.md)、[时钟同步](https://github.com/openairplay/airplay-spec/blob/master/src/screen_mirroring/time_synchronization.md)。未复制这些项目的代码。

## 构建与测试

1.5.0 默认采集系统播放声音，使用 AudioPlaybackCapture + AudioRecord + AAC 编码，与 H.264 / H.265 视频复用到 MPEG-TS 或 HLS；停止投屏时同步释放音频采集和编码器。

Android 可能将所需录音权限显示为“访问麦克风”，但应用仅创建播放捕获配置，不连接麦克风音源。真机 H.265 + AAC 的 15 秒采样得到 299 个视频帧、699 个音频帧，TS 连续计数无中断；HLS 分片也包含视频和音频两条轨道。统计不代表接收端实际听感或端到端同步效果。

1.4.0 在“画质设置”新增 H.264 / AVC 与 H.265 / HEVC。默认 H.264，历史配置自动沿用；编码选择与分辨率等设置一并保存，并于下次投屏生效。H.265 使用 `video/hevc` 硬件编码器、MPEG-TS HEVC 流类型和 VPS/SPS/PPS 参数集。不支持相应硬件编码器时会明确报错，不会静默改成其他编码。接收端需支持所选编码在 MPEG-TS / HLS 中的播放，兼容性不佳时可切回 H.264。

1.3.0 新增“画质设置”：分辨率可选 360p / 540p / 720p / 1080p，帧率可选 15 / 20 / 25 / 30 / 60 fps，码率为 0.5–12 Mbps、步进 0.1 Mbps。设置保存到手机本地，下次开始投屏时应用；取消不修改已有设置，恢复默认后点击保存即可回到 540p / 20 fps / 1.2 Mbps。硬件不支持的编码组合会显示启动错误，不保证每台手机都能达到所有组合。GPU 固定帧率调度保留了 30 / 60 fps 的小数毫秒间隔，避免整数除法造成时钟偏差。

需要 JDK 17+ 和 Android SDK 36：

```sh
cd android
ANDROID_HOME=/path/to/Android/sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

输出 `android/app/build/outputs/apk/debug/app-debug.apk`。支持 Android 10+、EMUI 和兼容安卓应用的 HarmonyOS，不支持 HarmonyOS NEXT 原生应用。

GitHub Actions 会在主分支和 Pull Request 自动运行构建、测试与 Lint。推送匹配应用版本的 `v*` 标签后，自动构建带固定发布签名的 APK 并上传到 GitHub Releases，附带 SHA-256 校验文件和第三方许可声明。签名密钥保存在仓库 Secrets 中，不进入源代码。具体流程见 [发布说明](docs/releasing.md)。

首次从本地调试版切换到 Release 版时，由于签名证书不同，需要卸载调试版再安装，原画质设置会被清除。之后的 Release 版本可直接覆盖更新。

测试涵盖 SSDP 解析、UPnP 设备描述与 URLBase、XML 实体拒绝、DIDL-Lite 转义、HTTP 字节范围、实际媒体 GET/HEAD/206/416、HLS 序列和分片过期，以及 MPEG-TS 包长、连续计数器、PAT/PMT CRC、时间戳和 PCR。

## 实现

`MediaProjection → VirtualDisplay → SurfaceTexture → EGL 固定帧率渲染 → MediaCodec H.264/H.265 → MPEG-TS → 本地 HTTP / HLS → DLNA AVTransport → 接收端播放器`。

可使用 `node tools/inspect-stream.mjs <当前直播 URL> 20` 检查 TS 连续性、出帧时间戳与网络到达间隔。工具仅输出统计，不保存屏幕内容；新增直播连接会请求下一个关键帧。

录屏使用 mediaProjection 前台服务并显示持续通知；注册系统停止回调；编码线程与网络请求不运行在 UI 线程；直播队列和 HLS 分片数量有内存上限；缓冲溢出时等待下一关键帧恢复；结束时释放录屏、编码器、Surface、服务器和 Wi-Fi 锁。机顶盒 45 秒内不取流会自动停止屏幕共享。播放状态来自机顶盒返回值，不使用模拟设备和虚假连接状态。

## 许可

项目源码公开，按 [PolyForm Noncommercial 1.0.0](LICENSE) 提供，个人非商业使用免费。录屏编码与 TS 封装使用 AirSonic 的两个组件；商业用途需取得相应权利人的授权。版权、来源版本和本地修改详见 [第三方说明](third_party/NOTICE.md) 和 [AirSonic 许可](third_party/AirSonic-LICENSE)。HTTP 服务使用 [BSD 许可的 NanoHTTPD](third_party/NanoHTTPD-LICENSE)。完整许可随 APK 和 Release 分发。

## 官方参考

- [UPnP AV 架构](https://upnp.org/specs/av/UPnP-av-AVArchitecture-v1-20020612.pdf)
- [Sony 官方 DLNA AVTransport 示例](https://github.com/sonydevworld/audio_control_api_examples/blob/master/DLNA/AVTransport/play_file.adoc)
- [Android MediaProjection](https://developer.android.com/media/grow/media-projection)
- [Android 音频播放捕获与限制](https://developer.android.com/media/platform/av-capture)
- [AirSonic 编码与封装组件](https://github.com/chunguangwei/AirSonic)
