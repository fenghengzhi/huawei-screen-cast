# Native Lelink FREE Mode

This implementation targets the receiver's native Lelink protocol-6 FREE branch. It is independent of the older AirPlay/PC compatibility route. Password and on-screen-code pairing are deliberately unsupported. No vendor SDK, extracted credential, patched receiver, or subscription bypass is used.

## Mode And Consent

Only an explicit `lelinkport` advertisement with `htv=1` and `atv=0` is eligible. Missing, conflicting, unknown, password, and code modes are not treated as FREE. The same connection reads `/lelink-player-info` and checks the receiver's current mode again before starting the handshake.

FREE means no human-entered password or verification code. It still requires a cryptographic handshake. Starting media is a separate operation and must happen only after Android screen-sharing consent. The diagnostic probe in `tools/LelinkHandshakeProbe.java` performs control verification only; it never captures or sends screen or audio content.

## Control Contract

Requests use the discovered native control port and the application's own `LeLink-Platform: Android`, `LeLink-Session-ID`, and `LeLink-Client-UID` headers. They do not impersonate an official SDK application or select another protocol after rejection.

1. Read `/lelink-player-info` on the connection that will be paired. Besides the current mode, this initializes the receiver's native media negotiation context.
2. POST the FREE type-1 setup message to `/lelink-setup`. Authentication fields have a little-endian 32-bit tag, little-endian 32-bit length, and raw value, with no padding. This is not TLV8.
3. Complete the two `/lelink-verify` exchanges using freshly generated Ed25519 and X25519 keys and nonces. Verify the receiver signature and final nonce confirmation before exposing any derived control or media key material.
4. The final M6 response is ordinary HTTP. After fully reading and validating it, explicitly switch this connection to the native encrypted record format. Never retry the stream as plaintext.

Each control record has a little-endian 32-bit plaintext length, encrypted payload, and a 16-byte Poly1305 tag. Each direction retains its original ChaCha20 block counter; each record consumes 64 bytes for its one-time MAC key, then whole 64-byte blocks for the payload. Unused bytes in the final payload block are discarded, not reused by the next record. The native MAC covers plaintext. The decoder verifies the entire record before releasing plaintext to the HTTP parser. Requests, records, XML, and authentication fields have strict length/count limits and bounded network deadlines.

The receiver's legacy construction initializes both directions with the same key and nonce, and its exchanged public keys have no out-of-band identity verification. Therefore this is protocol-compatible encryption, not a claim of modern secure-channel properties. Use only on a trusted local network and do not cast sensitive content.

## Media Contract

Native media negotiation uses encrypted `SETUP /` requests with `application/plist-binary` bodies, one stream per request. The AirPlay MIME `application/x-apple-binary-plist` is not accepted by this native handler. Video stream type 97 negotiates a new raw TCP data port; it does not reuse port 7100 or send an HTTP upgrade on that media socket. TCP responses may advertise unused UDP and sequence fields as zero. `/lelink-streaming` is a pass-through control route, not the native video byte stream.

The native video stream uses a 128-byte clear header, actual encoded dimensions, timestamps, clear codec configuration, and length-prefixed access units. H.264 configuration is avcC; H.265 uses an hvc1 sample entry containing hvcC, rather than the old compatibility path's special NAL-array envelope. Native parameter sets are bounded before transmission. Video keyframe encryption maintains the receiver's expected CBC chaining across frames; a failed write ends the session instead of resetting the cipher and retrying.

Audio stream type 96 negotiates raw AAC-ELD over RTP/UDP. Its full AES-CBC blocks are encrypted with a fresh derived IV for each packet; the trailing partial block follows the receiver's plaintext-tail convention. Audio timestamps, clock replies, and bounded retransmission retain the shared monotonic media clock. Neither native transport changes the receiver's authorization or duration policy.

The tested receiver shares the timing endpoint supplied in the first video SETUP with audio. It sends 32-byte RAOP timing requests (PT 82); replies use PT 83 and the request's source port. The video transport owns that shared responder. The separate audio transport's zero timing-request counter is therefore not evidence of a missing shared clock. A 48-byte NTP request format is also supported but was not the observed native timing format.

Normal close attempts separate encrypted TEARDOWN requests for accepted audio (96) and video (97) streams on the existing control connection. A shared 500 ms deadline closes resources even if the receiver never responds. Failed or cancelled sessions close immediately without re-pairing, reconnecting, or changing protocol.

## Verification

On 2026-10-03, the connected H2 receiver (formerly named G2), version 8.20.56, advertised FREE mode. The independent probe received HTTP 200 for the initial player-info request and each M2/M4/M6 response, verified both signatures/nonce confirmation, and received three successive valid HTTP 200 player-info responses over the encrypted control channel. This multi-record test caught and verified the fix for discarded ChaCha tail-block bytes. No media was requested in that probe.

After the user restored normal receiver operation, the same device was advertised as M2. A separate, consented application session used native protocol 6 with H.265 and default system audio. Huawei NOH-AN00 used the HiSilicon HEVC hardware encoder at the 1080p setting, 60 fps target, and 3 Mbps. The receiver selected its Qualcomm HEVC decoder; actual screenshots confirmed displayed content, and the user confirmed audible sound. Codec logs showed 928x1920 to 1920x928 and back on the same session, but no landscape screenshot was captured before the foreground application forced portrait again.

When opening Douyin, the user heard a few initial pops that subsequently disappeared. Receiver diagnostic output briefly fell to roughly 34-49 fps before recovering to about 58 fps. Sender audio diagnostics reported no queue, stale-frame, or invalid-frame discards. These observations do not localize the cause to sender load: capture scheduling, network arrival jitter, and receiver buffering were not independently measured. Startup pops remain an unresolved performance issue, not a verified fix.

The sender session ran about 5 minutes 42 seconds before a manual stop, with 19,286 video frames and 31,295 audio packets sent. Service shutdown and receiver audio/video release were observed. Sender counters and decoder activity do not prove uninterrupted displayed playback beyond the receiver's five-minute trial policy, which remains in force. The final best-effort encrypted TEARDOWN addition was unit-tested after this device session and has not yet been re-tested on hardware.

The final build passed 377 JVM tests, Android Lint, and four release-packaging tests. Native H.264 is covered by unit tests but has not yet been played on hardware. Hour-scale stability, other receiver versions, and end-to-end audiovisual latency remain unverified. Control-handshake success alone is not proof of a displayed screen, audible sound, or synchronization. Local reverse-analysis artifacts and test captures remain excluded from Git.
