# FDK AAC Provenance

`fdk-aac/` contains the complete, unmodified upstream v2.0.3 source tree,
including its complete `NOTICE` license. No binary codec is checked in.

- Upstream: https://github.com/mstorsjo/fdk-aac
- Release tag: `v2.0.3`
- Tag object: `cac04476081a23c870fed05c36228cd02c5e7c3d`
- Source commit: `716f4394641d53f0d79c9ddac3fa93b03a49f278`
- Source archive: https://codeload.github.com/mstorsjo/fdk-aac/tar.gz/716f4394641d53f0d79c9ddac3fa93b03a49f278
- Archive SHA-256: `63048f3c3595f161309ea967531d2b99de71c41ecb1f173d44eaccd89c8133c3`
- `NOTICE` SHA-256: `95ec80da40b4af12ad4c4f3158c9cfb80f2479f3246e4260cb600827cc8c7836`

The application builds FDK as a static dependency of its JNI encoder wrapper.
It does not alter upstream source. The CMake wrapper selects the portable code
path only for `libSBRdec/src/lpp_tran.cpp`, because its Android-platform-private
logging header is not part of the NDK. This omits two SafetyNet event log writes
but preserves the bounds checks. Only encoder code is referenced by the app.

The project's JNI wrapper is separate from FDK. It configures AAC-ELD object type
39, stereo PCM16LE, 128 kbps, no SBR, 480 samples per channel per access unit,
and raw AAC output. Encoder-reported configuration and delay are used directly.

The FDK license permits copyright redistribution subject to its conditions,
including the complete license and free availability of complete codec source
and any modifications to binary recipients. This repository retains the complete
codec source. The license does not grant any patent license. Availability of this
source or an Android device's licensing does not establish that a particular app
or distribution has all required patent authorization. See `fdk-aac/NOTICE` for
the authoritative terms; do not describe this component as patent-cleared.
