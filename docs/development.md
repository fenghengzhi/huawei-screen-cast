# Development And Quality Checks

## Toolchain

Use the checked-in Gradle wrapper and JDK 21, as CI does. Install Android SDK platform 36, build-tools 36.0.0, NDK 28.2.13676358, and CMake 3.22.1. Set `JAVA_HOME` and `ANDROID_HOME` for your machine; do not commit `local.properties`, signing material, or machine-specific paths.

Run the Android checks from `android/`:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Run release-packaging tests from the repository root:

```sh
node --test tools/prepare-release.test.mjs
git diff --check
```

The packaging tests use temporary fixture directories. They do not replace local APKs, use signing keys, or contact GitHub. APK builds and test reports are under `android/app/build/`; `dist/` is local output only.

## Lint Gate

Android Lint errors and new warnings fail the build, including CI. The checked-in `android/app/lint-baseline.xml` records only explicitly deferred existing findings; it is not regenerated automatically. Review each new warning instead of disabling a category or accepting a larger baseline to make CI pass.

The initial baseline defers the target-SDK and Gradle-upgrade advisories, because those upgrades require compatibility verification, and the `MediaService` free-space advisory. The latter retains a conservative 20 MiB free-space reserve without requesting that Android reclaim other applications' cache. These are acknowledged findings, not claims that the code is warning-free.

After deliberately resolving a baseline entry, refresh it with `./gradlew :app:updateLintBaseline`, inspect the XML diff, keep file locations repository-relative, and rerun the ordinary checks. See the [Android Lint baseline guidance](https://developer.android.com/studio/write/lint#snapshot). Do not use baseline updates as part of the normal CI build.

## Ownership And Boundaries

- `MainActivity` selects devices and settings, then obtains Android audio and screen-sharing permission. Network mirror negotiation must not precede screen-sharing consent.
- `MirrorService` owns a DLNA screen-sharing session; `MirrorEngine` owns its capture and HTTP server. `MediaService` is the separate selected-file playback path.
- `DlnaPlaybackSession` serializes DLNA screen-mirroring commands on one process-local control queue. Closing a session cancels future work immediately and orders receiver Stop after any in-flight command. Ownership is scoped to the receiver endpoint so a retired session cannot stop a newer screen-mirroring session; this coordination does not yet include `MediaService`.
- `LegacyMirrorService` owns both the legacy compatibility and native Lelink capture lifecycles. The two wire protocols stay separate behind `MirrorVideoTransport`; native authentication failure must not trigger protocol fallback.
- `ScreenMirrorCaster` owns each hardware encoder run. Rotation creates a new run while preserving the screen-sharing session. Intentional stop and late errors from a retired encoder must not terminate its replacement.
- `FrameRepeater` owns EGL resources on its render thread. `ThreadBoundCleanup` makes repeated and concurrent cleanup requests share one execution and completion signal.
- Network clients own their sockets, bounded queues, cipher state, and deadlines. Watchdogs use elapsed time and fixed-delay scheduling; they must not replay overdue ticks after a scheduling pause.
- `MirrorHttpServer` enforces a bounded live-client count and a terminal stopped state. Do not invoke encoder callbacks while holding its admission/segment lock.

Keep wire parsing and lifecycle coordination testable without Android hardware. Tests should cover actual behavior with fake transports, controllable queues, and latches, rather than searching source text for implementation details. Do not introduce a framework or cross-protocol abstraction just to remove a few superficially similar lines.

## Regression Checklist

For capture, lifecycle, or protocol changes, verify the relevant cases on a real phone and a receiver that permits playback:

1. Cancel audio or screen-sharing permission: no receiver session, capture service, or held lock.
2. Start each affected route and confirm the receiver's actual picture and sound, not only sent-frame counters. Check H.264 and H.265 separately.
3. Rotate portrait to landscape and back within the same session; check picture, sound, and hardware encoder replacement.
4. Stop during connection, during playback, and twice in quick succession. Confirm capture, sockets, foreground notification, and locks are released; the receiver must not resume from a late Play command.
5. Disconnect the receiver or Wi-Fi and revoke projection permission. Errors must terminate the current session without silent fallback or automatic reconnection.

Unit tests cannot verify hardware encoder behavior, receiver display, audible glitches, or end-to-end delay. Record which cases were actually exercised and keep unverified cases explicit. Never remove a receiver's password, membership, or trial restrictions for testing.

## Verification Record (2026-10-03)

The engineering-quality changes passed the debug build, 411 JVM tests (zero failures, errors, or skips), Android Lint with the three reviewed baseline findings, and 24 release-packaging tests. A temporary unused-resource warning caused `lintDebug` to fail as expected; the probe was removed and the ordinary build/test/lint command then passed without any baseline-generation override. Independent read-only reviews found no blocking regression in the changed lifecycle code.

The initial engineering checks did not include hardware testing. The subsequent physical regression below installed and exercised the same build. No release was published.

### Physical Regression (2026-10-03)

Devices: Huawei NOH-AN00 running Android 12 and an Android Lebo receiver version 8.20.56, currently named S8 (the same receiver previously named G2/H2/M2). The quality-build APK SHA-256 is `1172922ae3d7afba44e9d54a5190222143dcc1432477c324b163b4f4a87d2f72`; the installed APK hash was checked after restoring this build following the baseline comparison. Tests used the user's saved 1080p, 15 fps, and 3 Mbps settings, changing only the codec and DLNA mode for individual cases.

| Case | Result | Evidence And Limit |
| --- | --- | --- |
| Cancel screen-sharing consent | Passed | Cancelled native, compatibility, and DLNA requests. No capture service or MediaProjection remained. Audio permission was already granted; denial of that separate permission was not re-tested. |
| Native Lelink H.265 | Passed for picture and sound | User confirmed both and no audible pops during this test. A receiver screenshot confirmed landscape output. The decoder also rebuilt on return to portrait, but the later portrait screenshot was obscured by the receiver's trial limit, so uninterrupted visible playback past that point is not claimed. |
| Native Lelink H.264 and rotation | Passed | Receiver screenshots confirm portrait, landscape, and portrait again; logs confirm hardware encoder/decoder replacement in one session. User confirmed sound without pops. |
| Normal and repeated stop | Passed for exercised mirror sessions | Consecutive stop-button taps caused no crash or residual service. Native H.264 sent audio/video TEARDOWN; receiver audio and video resources released. Compatibility H.264 also stopped and released its tracks/decoder. |
| Compatibility H.264 | Passed for picture and sound | Actual receiver screenshot and user confirmation of audible playback without pops. |
| Compatibility H.265 | Incomplete: receiver policy | HEVC hardware decoding started, but a membership prompt paused playback immediately. Not counted as a full picture/sound pass; no attempt was made to bypass the restriction. |
| Wi-Fi loss and restoration | Passed for cleanup | During that connected compatibility session, Wi-Fi loss produced a network-unreachable error and stopped encoding. Capture resources were released; re-enabling Wi-Fi did not automatically reconnect or restart projection. |
| DLNA low-latency TS | Failed on this receiver, reproduced before changes | The new build and the pre-quality APK both reported `Connection reset`. Receiver logs show SetAVTransportURI followed by failure-path Stop while playback was preparing. This is not evidence of a new lifecycle regression or proof that the decoder cannot play TS; the failing SOAP action/response still needs isolation. |
| DLNA HLS | Failed on this receiver | The quality build also reported `Connection reset`; capture was cleaned up. Successful DLNA playback, live HTTP rejoin, and stop/restart ordering on a playing receiver remain unverified on hardware. |

At the end, the quality build was installed, no app capture service or MediaProjection remained, the `airsonic-mirror` virtual display and capture threads were absent, and power/Wi-Fi lock checks were clear. Wi-Fi connectivity, H.265/1080p/15 fps/3 Mbps, the low-latency DLNA selection, and the original portrait rotation settings were restored. No application data or receiver policy was reset. Log collection and synthetic tone processes were stopped.

Continuous logs for the later cases contained no fatal app crash, GPU/EGL render failure, encoder-output failure, old-encoder shutdown timeout, or audio-cleanup timeout. Early H.265 logs were partially overwritten before continuous collection began; they are not a complete startup/rotation trace. Synthetic tone generation completed, but its standalone diagnostic process later exited through its existing timeout path after releasing the track; this is not an app playback failure.

This is a partial device regression, not a complete pass of every route, quality combination, receiver, or fault condition. In particular, these 15 fps tone tests do not establish that the previously reported Douyin startup pops under higher load have been fixed. Raw logs, screenshots, and device identifiers remain under ignored `dist/regression-quality/`, not in source control.

## Known Follow-Up Work

- Short audio pops at Douyin startup remain unlocalized; sender scheduling, capture overruns, and receiver buffering need measured comparisons before changing audio parameters.
- S8 resets a DLNA control connection in both the quality build and its baseline. Add action-level SOAP diagnostics and compare with a known-working receiver before altering error handling or treating the reset as a successful command.
- The current HLS path can truncate a segment at its 4 MiB cap and advertises a fixed target duration. Long or oversized GOPs need a dedicated bounded-segment/discontinuity design and receiver regression tests.
- Selected-file preparation in `MediaService` queues stop behind file copying. Cancellation of large-file preparation needs its own lifecycle work; the DLNA screen-sharing changes do not fix that separate path.

## Releases And Private Artifacts

See [releasing.md](releasing.md). Packaging validates the application, release variant, universal APK, version, and required nonempty license/source inputs before replacing output files. GitHub uploads only the named APK, FDK source archive, notices, and checksums. APK signature verification is a separate mandatory workflow step.

Do not commit receiver APKs, decompiled material, captured media, device logs, real device identifiers, private keys, or credentials. Android backup rules exclude application data from cloud and device transfer, matching the app's no-backup policy; see [Android backup rules](https://developer.android.com/identity/data/autobackup#xml-syntax-android-12).
