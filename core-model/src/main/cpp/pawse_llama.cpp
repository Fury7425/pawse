#include <jni.h>

#include <algorithm>
#include <string>
#include <vector>

#include "llama.h"

// The whole bridge: load a GGUF, write one reply to one prompt, free it. Pawse
// writes the chat template itself, so llama.cpp's common library is not linked.
// Text crosses as UTF-8 bytes, not jstring: JNI's modified UTF-8 mangles emoji.

namespace {

struct Session {
    llama_model *model;
    llama_context *ctx;
};

constexpr int kBatch = 512;

std::string bytes(JNIEnv *env, jbyteArray array) {
    const jsize n = env->GetArrayLength(array);
    std::string out(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(array, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

std::string piece(const llama_vocab *vocab, llama_token token) {
    char buf[128];
    const int n = llama_token_to_piece(vocab, token, buf, sizeof buf, 0, false);
    if (n >= 0) return std::string(buf, n);
    std::string big(static_cast<size_t>(-n), '\0');
    llama_token_to_piece(vocab, token, big.data(), -n, 0, false);
    return big;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_app_pawse_core_model_Llama_load(JNIEnv *env, jobject, jstring path, jint threads, jint contextSize) {
    static const bool started = [] {
        llama_backend_init();
        return true;
    }();
    (void) started;

    const char *file = env->GetStringUTFChars(path, nullptr);
    llama_model *model = llama_model_load_from_file(file, llama_model_default_params());
    env->ReleaseStringUTFChars(path, file);
    if (model == nullptr) return 0;

    llama_context_params params = llama_context_default_params();
    params.n_ctx = static_cast<uint32_t>(contextSize);
    params.n_batch = kBatch;
    params.n_threads = threads;
    params.n_threads_batch = threads;
    llama_context *ctx = llama_init_from_model(model, params);
    if (ctx == nullptr) {
        llama_model_free(model);
        return 0;
    }
    return reinterpret_cast<jlong>(new Session{model, ctx});
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_pawse_core_model_Llama_generate(
    JNIEnv *env, jobject, jlong handle, jbyteArray promptBytes, jint maxTokens, jfloat temperature, jint topK) {
    auto *session = reinterpret_cast<Session *>(handle);
    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    const std::string prompt = bytes(env, promptBytes);

    // The prompt carries its own <|startoftext|> and turn markers.
    const auto length = static_cast<int32_t>(prompt.size());
    const int count = -llama_tokenize(vocab, prompt.data(), length, nullptr, 0, false, true);
    if (count <= 0 || count + maxTokens > static_cast<int>(llama_n_ctx(session->ctx))) return nullptr;
    std::vector<llama_token> tokens(static_cast<size_t>(count));
    llama_tokenize(vocab, prompt.data(), length, tokens.data(), count, false, true);

    llama_memory_clear(llama_get_memory(session->ctx), true);
    for (int i = 0; i < count; i += kBatch) {
        if (llama_decode(session->ctx, llama_batch_get_one(tokens.data() + i, std::min(kBatch, count - i))) != 0) {
            return nullptr;
        }
    }

    // ponytail: no repeat penalty; two or three sentences rarely loop.
    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string out;
    for (int i = 0; i < maxTokens; i++) {
        llama_token token = llama_sampler_sample(sampler, session->ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        out += piece(vocab, token);
        if (llama_decode(session->ctx, llama_batch_get_one(&token, 1)) != 0) break;
    }
    llama_sampler_free(sampler);

    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_app_pawse_core_model_Llama_free(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    llama_free(session->ctx);
    llama_model_free(session->model);
    delete session;
}
