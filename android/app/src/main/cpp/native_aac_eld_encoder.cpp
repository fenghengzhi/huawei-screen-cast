#include <jni.h>
#include <aacenc_lib.h>

#include <array>
#include <cstdint>
#include <cstdio>
#include <new>

namespace {
constexpr int kChannels = 2;
constexpr int kFrameSamples = 480;
constexpr int kPcmBytes = kFrameSamples * kChannels * 2;
static_assert(sizeof(INT_PCM) == 2, "AAC-ELD wrapper requires PCM16 input");

struct Encoder {
    HANDLE_AACENCODER codec = nullptr;
    AACENC_InfoStruct info{};
    std::array<INT_PCM, kFrameSamples * kChannels> pcm{};
    std::array<UCHAR, 4096> output{};

    ~Encoder() {
        if (codec != nullptr) aacEncClose(&codec);
    }
};

void fail(JNIEnv* env, const char* type, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass exception = env->FindClass(type);
    if (exception != nullptr) env->ThrowNew(exception, message);
}

bool check(JNIEnv* env, AACENC_ERROR error, const char* operation) {
    if (error == AACENC_OK) return true;
    char message[160];
    std::snprintf(message, sizeof(message), "AAC-ELD %s failed (0x%x)", operation,
                  static_cast<unsigned int>(error));
    fail(env, "java/lang/IllegalStateException", message);
    return false;
}

Encoder* encoderFor(JNIEnv* env, jlong handle) {
    if (handle == 0) {
        fail(env, "java/lang/IllegalStateException", "AAC-ELD encoder is closed");
        return nullptr;
    }
    return reinterpret_cast<Encoder*>(static_cast<intptr_t>(handle));
}

jbyteArray bytes(JNIEnv* env, const UCHAR* data, jsize size) {
    jbyteArray result = env->NewByteArray(size);
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte*>(data));
    }
    return result;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_local_huaweicast_NativeAacEldEncoder_nativeCreate(JNIEnv* env, jclass, jint sampleRate) {
    if (sampleRate != 44100 && sampleRate != 48000) {
        fail(env, "java/lang/IllegalArgumentException", "Unsupported AAC-ELD sample rate");
        return 0;
    }
    Encoder* encoder = new (std::nothrow) Encoder();
    if (encoder == nullptr) {
        fail(env, "java/lang/OutOfMemoryError", "Unable to allocate AAC-ELD encoder");
        return 0;
    }
    bool initialized = check(env, aacEncOpen(&encoder->codec, 0, kChannels), "open");
    const struct { AACENC_PARAM parameter; UINT value; } parameters[] = {
        {AACENC_AOT, 39},
        {AACENC_SAMPLERATE, static_cast<UINT>(sampleRate)},
        {AACENC_CHANNELMODE, MODE_2},
        {AACENC_CHANNELORDER, 1},
        {AACENC_BITRATE, 128000},
        {AACENC_SBR_MODE, 0},
        {AACENC_GRANULE_LENGTH, kFrameSamples},
        {AACENC_TRANSMUX, TT_MP4_RAW}
    };
    for (const auto& setting : parameters) {
        if (!initialized) break;
        initialized = check(env, aacEncoder_SetParam(encoder->codec, setting.parameter, setting.value), "configure");
    }
    if (initialized) initialized = check(env, aacEncEncode(encoder->codec, nullptr, nullptr, nullptr, nullptr), "initialize");
    if (initialized) initialized = check(env, aacEncInfo(encoder->codec, &encoder->info), "read configuration");
    if (initialized && (encoder->info.frameLength != kFrameSamples
            || encoder->info.inputChannels != kChannels || encoder->info.confSize == 0
            || encoder->info.confSize > sizeof(encoder->info.confBuf)
            || encoder->info.maxOutBufBytes > encoder->output.size())) {
        fail(env, "java/lang/IllegalStateException", "Unexpected AAC-ELD encoder configuration");
        initialized = false;
    }
    if (!initialized) {
        delete encoder;
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(encoder));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_local_huaweicast_NativeAacEldEncoder_nativeSamplesPerFrame(JNIEnv* env, jclass, jlong handle) {
    Encoder* encoder = encoderFor(env, handle);
    return encoder == nullptr ? 0 : static_cast<jint>(encoder->info.frameLength);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_local_huaweicast_NativeAacEldEncoder_nativeDelaySamples(JNIEnv* env, jclass, jlong handle) {
    Encoder* encoder = encoderFor(env, handle);
    return encoder == nullptr ? 0 : static_cast<jint>(encoder->info.nDelay);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_local_huaweicast_NativeAacEldEncoder_nativeConfiguration(JNIEnv* env, jclass, jlong handle) {
    Encoder* encoder = encoderFor(env, handle);
    return encoder == nullptr ? nullptr : bytes(env, encoder->info.confBuf, encoder->info.confSize);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_local_huaweicast_NativeAacEldEncoder_nativeEncode(JNIEnv* env, jclass, jlong handle,
                                                        jbyteArray pcm, jint size) {
    Encoder* encoder = encoderFor(env, handle);
    if (encoder == nullptr) return nullptr;
    if (pcm == nullptr || size != kPcmBytes || env->GetArrayLength(pcm) < size) {
        fail(env, "java/lang/IllegalArgumentException", "AAC-ELD requires exactly 1920 PCM bytes per frame");
        return nullptr;
    }
    std::array<jbyte, kPcmBytes> input{};
    env->GetByteArrayRegion(pcm, 0, size, input.data());
    if (env->ExceptionCheck()) return nullptr;
    for (size_t sample = 0; sample < encoder->pcm.size(); ++sample) {
        unsigned int low = static_cast<uint8_t>(input[sample * 2]);
        unsigned int high = static_cast<uint8_t>(input[sample * 2 + 1]);
        encoder->pcm[sample] = static_cast<int16_t>(low | (high << 8));
    }
    void* inData = encoder->pcm.data();
    INT inId = IN_AUDIO_DATA, inBytes = sizeof(encoder->pcm), inElement = sizeof(INT_PCM);
    AACENC_BufDesc inBuffer{1, &inData, &inId, &inBytes, &inElement};
    void* outData = encoder->output.data();
    INT outId = OUT_BITSTREAM_DATA, outBytes = encoder->output.size(), outElement = 1;
    AACENC_BufDesc outBuffer{1, &outData, &outId, &outBytes, &outElement};
    AACENC_InArgs inArgs{};
    AACENC_OutArgs outArgs{};
    inArgs.numInSamples = encoder->pcm.size();
    if (!check(env, aacEncEncode(encoder->codec, &inBuffer, &outBuffer, &inArgs, &outArgs), "encode")) return nullptr;
    if (outArgs.numInSamples != kFrameSamples * kChannels || outArgs.numOutBytes < 0
            || static_cast<size_t>(outArgs.numOutBytes) > encoder->output.size()) {
        fail(env, "java/lang/IllegalStateException", "Unexpected AAC-ELD frame consumption");
        return nullptr;
    }
    return outArgs.numOutBytes == 0 ? nullptr : bytes(env, encoder->output.data(), outArgs.numOutBytes);
}

extern "C" JNIEXPORT void JNICALL
Java_com_local_huaweicast_NativeAacEldEncoder_nativeClose(JNIEnv*, jclass, jlong handle) {
    // Java serializes every native operation and clears the handle before close.
    delete reinterpret_cast<Encoder*>(static_cast<intptr_t>(handle));
}
