# Third Party Code

`com.airsonic.sender.screen.ScreenMirrorCaster` and `com.airsonic.sender.streaming.TsMuxer`, and the latter's tests, come from [Chunguang Wei's AirSonic](https://github.com/chunguangwei/AirSonic), commit `97e98120686c0d96673e7f3138956450ee6c486c`.

Local changes to ScreenMirrorCaster: feed its input through a fixed-rate EGL renderer, disable B frames, request real-time priority, and explicitly release the renderer and encoder input surface. TsMuxer and its tests are unchanged.

Required Notice: Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei).

These files are licensed under the PolyForm Noncommercial License 1.0.0. See `AirSonic-LICENSE` for the complete license. Personal, noncommercial use is permitted; commercial use requires the author's written consent. This app is being built for personal use, without the Lebo SDK or its subscription services.

The local HTTP servers use [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd), version 2.3.1, under its BSD license.
