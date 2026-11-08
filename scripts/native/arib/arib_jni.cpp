#include <jni.h>
#include <android/log.h>
#include <aribcaption/aribcaption.hpp>
#include <cstring>
#include <memory>
#include <vector>
#include <algorithm>

using namespace aribcaption;
namespace {
constexpr size_t kMaxPacket = 1024 * 1024;
struct Session {
    Context context;
    Decoder decoder{context};
    std::unique_ptr<Renderer> renderer;
    std::unique_ptr<Caption> latest;
    CaptionType type;
    explicit Session(bool superimpose, int language) : type(superimpose ? CaptionType::kSuperimpose : CaptionType::kCaption) {
        context.SetLogcatCallback([](LogLevel level, const char* message) {
            if (level == LogLevel::kError) __android_log_write(ANDROID_LOG_WARN, "LiuguangArib", message);
        });
        if (!decoder.Initialize(EncodingScheme::kAuto, type, Profile::kProfileA,
                                language == 2 ? LanguageId::kSecond : LanguageId::kFirst)) throw std::bad_alloc();
    }
};
void integer(std::vector<uint8_t>& out, uint64_t value, int bytes) {
    for (int i = bytes - 1; i >= 0; --i) out.push_back(static_cast<uint8_t>(value >> (i * 8)));
}
jlong create(JNIEnv*, jobject, jboolean superimpose, jint language) {
    try { return reinterpret_cast<jlong>(new Session(superimpose, language)); }
    catch (...) { return 0; }
}
void destroy(JNIEnv*, jobject, jlong handle) { delete reinterpret_cast<Session*>(handle); }
jboolean decode(JNIEnv* env, jobject, jlong handle, jbyteArray bytes, jlong pts) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (!session || !bytes) return false;
    auto length = env->GetArrayLength(bytes);
    if (length < 10 || static_cast<size_t>(length) > kMaxPacket) return false;
    try {
        std::vector<uint8_t> data(length);
        env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(data.data()));
        if (env->ExceptionCheck()) return false;
        DecodeResult result;
        if (session->decoder.Decode(data.data(), data.size(), pts, result) != DecodeStatus::kGotCaption) return false;
        session->latest = std::move(result.caption);
        if (session->renderer && session->latest) {
            session->renderer->SetFrameSize(session->latest->plane_width, session->latest->plane_height);
            session->renderer->AppendCaption(*session->latest);
        }
        return session->latest != nullptr;
    } catch (...) { /* Malformed captions cannot terminate video playback. */ }
    return false;
}
jbyteArray render(JNIEnv* env, jobject, jlong handle, jlong pts) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (!session || !session->latest) return nullptr;
    try {
        const auto& caption = *session->latest;
        if (caption.plane_width <= 0 || caption.plane_height <= 0 ||
            caption.plane_width > 1920 || caption.plane_height > 1080) return nullptr;
        if (!session->renderer) {
            auto renderer = std::make_unique<Renderer>(session->context);
            if (!renderer->Initialize(session->type)) return nullptr;
            renderer->SetMergeRegionImages(true);
            renderer->SetFrameSize(caption.plane_width, caption.plane_height);
            renderer->AppendCaption(caption);
            session->renderer = std::move(renderer);
        }
        RenderResult result;
        auto status = session->renderer->Render(std::max<int64_t>(pts, caption.pts), result);
        if (status == RenderStatus::kError) return nullptr;
        std::vector<uint8_t> out;
        integer(out, 0x41423234, 4);  // AB24, big endian bounded snapshot
        integer(out, caption.plane_width, 4);
        integer(out, caption.plane_height, 4);
        integer(out, caption.pts, 8);
        integer(out, caption.wait_duration == DURATION_INDEFINITE ? -1 : caption.wait_duration, 8);
        integer(out, caption.text.size(), 4);
        out.insert(out.end(), caption.text.begin(), caption.text.end());
        integer(out, result.images.size(), 4);
        for (const auto& image : result.images) {
            if (image.width <= 0 || image.height <= 0 || image.width > 1920 || image.height > 1080 ||
                image.stride < image.width * 4 || image.bitmap.size() < static_cast<size_t>(image.stride * image.height)) return nullptr;
            integer(out, image.dst_x, 4); integer(out, image.dst_y, 4);
            integer(out, image.width, 4); integer(out, image.height, 4);
            for (int y = 0; y < image.height; ++y) {
                auto row = image.bitmap.begin() + y * image.stride;
                out.insert(out.end(), row, row + image.width * 4);
            }
        }
        if (out.size() > 16 * 1024 * 1024) return nullptr;
        auto array = env->NewByteArray(static_cast<jsize>(out.size()));
        if (array) env->SetByteArrayRegion(array, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte*>(out.data()));
        return array;
    } catch (...) { return nullptr; }
}
}
extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    auto cls = env->FindClass("top/yogiczy/mytv/tv/ui/screensold/videoplayer/player/arib/AribNativeDecoder");
    if (!cls) return JNI_ERR;
    JNINativeMethod methods[] = {
        {const_cast<char*>("nativeCreate"), const_cast<char*>("(ZI)J"), reinterpret_cast<void*>(create)},
        {const_cast<char*>("nativeDestroy"), const_cast<char*>("(J)V"), reinterpret_cast<void*>(destroy)},
        {const_cast<char*>("nativeDecode"), const_cast<char*>("(J[BJ)Z"), reinterpret_cast<void*>(decode)},
        {const_cast<char*>("nativeRender"), const_cast<char*>("(JJ)[B"), reinterpret_cast<void*>(render)},
    };
    return env->RegisterNatives(cls, methods, 4) == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
