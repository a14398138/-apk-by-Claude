#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <atomic>
#include <algorithm>
#include <string>
#include <vector>
#include "llama.h"

#define TAG "PlamoTranslate"
static llama_model* g_model = nullptr;
static std::atomic<bool> g_cancel{false};
static bool g_backend = false;

static void log_cb(enum ggml_log_level, const char* text, void*) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", text);
}

static void throw_rt(JNIEnv* env, const char* msg) {
    jclass c = env->FindClass("java/lang/RuntimeException");
    env->ThrowNew(c, msg);
}

// 末尾の不完全なUTF-8を除いた長さ
static size_t utf8_prefix(const std::string& s) {
    size_t n = s.size();
    size_t i = n;
    for (int back = 0; i > 0 && back < 4; back++) {
        i--;
        unsigned char c = (unsigned char)s[i];
        if ((c & 0xC0) != 0x80) {
            size_t need = c < 0x80 ? 1 : (c >> 5) == 6 ? 2 : (c >> 4) == 14 ? 3 : (c >> 3) == 30 ? 4 : 1;
            return need > n - i ? i : n;
        }
    }
    return n;
}

static jbyteArray to_bytes(JNIEnv* env, const std::string& s, size_t len) {
    jbyteArray a = env->NewByteArray((jsize)len);
    env->SetByteArrayRegion(a, 0, (jsize)len, (const jbyte*)s.data());
    return a;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_plamotranslate_Engine_nativeLoad(JNIEnv* env, jobject, jstring jpath) {
    if (!g_backend) {
        llama_log_set(log_cb, nullptr);
        llama_backend_init();
        g_backend = true;
    }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.use_mmap = true;
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    return g_model != nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_app_plamotranslate_Engine_nativeCancel(JNIEnv*, jobject) { g_cancel = true; }

extern "C" JNIEXPORT void JNICALL
Java_app_plamotranslate_Engine_nativeFree(JNIEnv*, jobject) {
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_plamotranslate_Engine_nativeTranslate(JNIEnv* env, jobject, jbyteArray jprompt, jint maxNew, jobject cb) {
    if (!g_model) { throw_rt(env, "モデルが読み込まれていません"); return nullptr; }

    jsize plen = env->GetArrayLength(jprompt);
    std::string prompt((size_t)plen, '\0');
    env->GetByteArrayRegion(jprompt, 0, plen, (jbyte*)prompt.data());
    g_cancel = false;

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    // BOSはllama.cppが自動付与(add_special=true)、PFML3の特殊トークンは parse_special=true で解釈
    std::vector<llama_token> toks(prompt.size() + 16);
    int n = llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(), toks.data(), (int32_t)toks.size(), true, true);
    if (n < 0) {
        toks.resize((size_t)-n);
        n = llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(), toks.data(), (int32_t)toks.size(), true, true);
    }
    if (n <= 0) { throw_rt(env, "トークナイズに失敗しました"); return nullptr; }
    toks.resize((size_t)n);

    const int n_ctx = 4096, n_batch = 512;
    if (n + maxNew > n_ctx) { throw_rt(env, "文章が長すぎます。短く区切ってください"); return nullptr; }

    int threads = (int)std::max<long>(2, std::min<long>(4, sysconf(_SC_NPROCESSORS_ONLN)));
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx; cp.n_batch = n_batch; cp.n_ubatch = n_batch;
    cp.n_threads = threads; cp.n_threads_batch = threads;
    llama_context* ctx = llama_init_from_model(g_model, cp);
    if (!ctx) { throw_rt(env, "コンテキストの作成に失敗しました"); return nullptr; }

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    auto cleanup = [&]() { llama_sampler_free(smpl); llama_free(ctx); };

    for (int i = 0; i < n; i += n_batch) {
        int c = std::min(n_batch, n - i);
        llama_batch b = llama_batch_get_one(toks.data() + i, c);
        if (llama_decode(ctx, b) != 0) { cleanup(); throw_rt(env, "推論(prefill)に失敗しました"); return nullptr; }
    }

    jclass cls = env->GetObjectClass(cb);
    jmethodID mid = env->GetMethodID(cls, "onPartial", "([B)V");

    std::string out;
    for (int i = 0; i < maxNew && !g_cancel; i++) {
        llama_token tok = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char buf[256];
        int len = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
        if (len < 0) {
            std::vector<char> big((size_t)-len);
            len = llama_token_to_piece(vocab, tok, big.data(), (int32_t)big.size(), 0, true);
            if (len > 0) out.append(big.data(), (size_t)len);
        } else {
            out.append(buf, (size_t)len);
        }

        size_t stop = out.find("<|plamo:");
        if (stop != std::string::npos) { out.resize(stop); break; }

        size_t valid = utf8_prefix(out);
        jbyteArray arr = to_bytes(env, out, valid);
        env->CallVoidMethod(cb, mid, arr);
        env->DeleteLocalRef(arr);

        llama_batch b = llama_batch_get_one(&tok, 1);
        if (llama_decode(ctx, b) != 0) break;
    }

    cleanup();
    return to_bytes(env, out, utf8_prefix(out));
}
