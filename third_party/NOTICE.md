# Third Party Code

`com.airsonic.sender.screen.ScreenMirrorCaster` and `com.airsonic.sender.streaming.TsMuxer`, and the latter's tests, come from [Chunguang Wei's AirSonic](https://github.com/chunguangwei/AirSonic), commit `97e98120686c0d96673e7f3138956450ee6c486c`.

Local changes to ScreenMirrorCaster: feed its input through a fixed-rate EGL renderer, disable B frames, request real-time priority, release the renderer and encoder input surface, select H.264/H.265 hardware encoders, and collect codec-specific parameter sets by NAL type. TsMuxer adds HEVC stream type 0x24, VPS/SPS/PPS insertion and a HEVC access-unit delimiter. Original TsMuxer tests are unchanged; additional codec regression tests are part of this project.

Required Notice: Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei).

ScreenMirrorCaster also accepts captured AAC with a shared monotonic video timestamp origin and serializes audio/video writes across HLS segment boundaries. It exposes captured video callbacks, including separate raw HEVC VPS/SPS/PPS callbacks, and can replace its encoder and input surface while resizing the same virtual display, with bounded worker shutdown for orientation changes.

These files are licensed under the PolyForm Noncommercial License 1.0.0. See `AirSonic-LICENSE` for the complete license. Personal, noncommercial use is permitted; commercial use requires the author's written consent. This app is being built for personal use, without the Lebo SDK or its subscription services.

The local HTTP servers use [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd), version 2.3.1, under its BSD license.

The legacy AirPlay compatibility handshake uses [dd-plist](https://github.com/3breadt/dd-plist), version 1.30, under its MIT license; see `dd-plist-LICENSE`. Discovery fields and legacy wire formats were independently implemented from public protocol descriptions, not copied from the proprietary Lebo SDK.

Legacy compatible audio uses [FDK-AAC](https://github.com/mstorsjo/fdk-aac), version 2.0.3, for real AAC-ELD 480-sample encoding. Its full upstream source and license are retained in `third_party/fdk-aac`; provenance and wrapper build changes are documented alongside it. The APK includes the complete source archive at `assets/licenses/fdk-aac-2.0.3-source.zip` and the unabridged notice at `assets/licenses/FDK-AAC-LICENSE`. This library has its own license, not the project's PolyForm license. Its software license grants no AAC patent license; no claim of patent clearance or Fraunhofer endorsement is made. The application-owned JNI wrapper does not alter the upstream source.
