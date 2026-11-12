/**
 * JNI bridge between Kotlin and llama.cpp.
 *
 * Everything here is deliberately thin: model loading, prompt prefill, token
 * sampling and detokenisation are all done by llama.cpp. Kotlin only decides
 * *what* to generate and renders the streamed pieces.
 */

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "MobileLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *        model = nullptr;
    llama_context *      ctx   = nullptr;
    const llama_vocab *  vocab = nullptr;

    std::mutex       gen_mutex;
    std::atomic<bool> cancel{false};

    bool loaded = false;
};

Engine * from_handle(jlong handle) {
    return reinterpret_cast<Engine *>(static_cast<intptr_t>(handle));
}

jlong to_handle(Engine * engine) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(engine));
}

void throw_illegal_state(JNIEnv * env, const char * message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
        env->DeleteLocalRef(cls);
    }
}

/** Decodes a Java string as real UTF-8 (JNI's GetStringUTFChars uses modified UTF-8). */
std::string to_utf8(JNIEnv * env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const jsize length = env->GetStringLength(value);
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }

    std::string out;
    out.reserve(static_cast<size_t>(length) * 2);

    for (jsize i = 0; i < length; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < length) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000u + ((cp - 0xD800u) << 10) + (low - 0xDC00u);
                ++i;
            }
        }

        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }

    env->ReleaseStringChars(value, chars);
    return out;
}

void log_callback(ggml_log_level level, const char * text, void * /*user_data*/) {
    if (text == nullptr) {
        return;
    }
    int priority = ANDROID_LOG_DEBUG;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: priority = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  priority = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  priority = ANDROID_LOG_INFO;  break;
        default:                   priority = ANDROID_LOG_DEBUG; break;
    }
    __android_log_write(priority, "llama.cpp", text);
}

std::vector<llama_token> tokenize_text(
        const llama_vocab * vocab, const std::string & text, bool add_special) {
    if (vocab == nullptr || text.empty()) {
        return {};
    }

    int32_t needed = -llama_tokenize(
            vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0, add_special, true);
    if (needed <= 0) {
        return {};
    }

    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    const int32_t written = llama_tokenize(
            vocab, text.c_str(), static_cast<int32_t>(text.size()),
            tokens.data(), needed, add_special, true);
    if (written < 0) {
        return {};
    }
    tokens.resize(static_cast<size_t>(written));
    return tokens;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_example_model_internal_LlamaBridge_nativeBackendInit(JNIEnv *, jobject) {
    static std::once_flag once;
    std::call_once(once, [] {
        llama_log_set(log_callback, nullptr);
        llama_backend_init();
        LOGI("llama.cpp backend initialised");
    });
}

JNIEXPORT jlong JNICALL
Java_com_example_model_internal_LlamaBridge_nativeLoadModel(
        JNIEnv * env, jobject, jstring j_path, jint n_ctx, jint n_threads) {
    const std::string path = to_utf8(env, j_path);
    if (path.empty()) {
        throw_illegal_state(env, "Model path is empty");
        return 0;
    }

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.load_mode    = LLAMA_LOAD_MODE_MMAP;
    model_params.use_extra_bufts = true;

    const int64_t started = llama_time_us();
    llama_model * model = llama_model_load_from_file(path.c_str(), model_params);
    if (model == nullptr) {
        throw_illegal_state(env, "llama.cpp could not load the GGUF file");
        return 0;
    }
    LOGI("model loaded in %.1f s", (llama_time_us() - started) / 1e6);

    const llama_vocab * vocab = llama_model_get_vocab(model);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx           = static_cast<uint32_t>(std::max(512, static_cast<int>(n_ctx)));
    ctx_params.n_batch         = 512;
    ctx_params.n_ubatch        = 512;
    ctx_params.n_seq_max       = 1;
    ctx_params.n_threads       = n_threads;
    ctx_params.n_threads_batch = n_threads;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    ctx_params.no_perf         = false;

    llama_context * ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        llama_model_free(model);
        throw_illegal_state(env, "llama.cpp could not create a context (out of memory?)");
        return 0;
    }

    llama_set_n_threads(ctx, n_threads, n_threads);

    auto * engine = new Engine();
    engine->model  = model;
    engine->ctx    = ctx;
    engine->vocab  = vocab;
    engine->loaded = true;

    char description[256] = {0};
    llama_model_desc(model, description, sizeof(description));
    LOGI("model: %s | n_ctx=%u | threads=%d | vocab=%d",
         description, llama_n_ctx(ctx), n_threads, llama_vocab_n_tokens(vocab));

    return to_handle(engine);
}

JNIEXPORT void JNICALL
Java_com_example_model_internal_LlamaBridge_nativeFree(JNIEnv *, jobject, jlong handle) {
    Engine * engine = from_handle(handle);
    if (engine == nullptr) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(engine->gen_mutex);
        engine->cancel = true;
        engine->loaded = false;
        if (engine->ctx != nullptr) {
            llama_free(engine->ctx);
            engine->ctx = nullptr;
        }
        if (engine->model != nullptr) {
            llama_model_free(engine->model);
            engine->model = nullptr;
        }
        engine->vocab = nullptr;
    }
    delete engine;
    LOGI("model unloaded");
}

JNIEXPORT jstring JNICALL
Java_com_example_model_internal_LlamaBridge_nativeModelDescription(
        JNIEnv * env, jobject, jlong handle) {
    Engine * engine = from_handle(handle);
    if (engine == nullptr || engine->model == nullptr) {
        return env->NewStringUTF("");
    }
    char description[256] = {0};
    llama_model_desc(engine->model, description, sizeof(description));
    return env->NewStringUTF(description);
}

JNIEXPORT jint JNICALL
Java_com_example_model_internal_LlamaBridge_nativeContextSize(JNIEnv *, jobject, jlong handle) {
    Engine * engine = from_handle(handle);
    if (engine == nullptr || engine->ctx == nullptr) {
        return 0;
    }
    return static_cast<jint>(llama_n_ctx(engine->ctx));
}

JNIEXPORT jint JNICALL
Java_com_example_model_internal_LlamaBridge_nativeCountTokens(
        JNIEnv * env, jobject, jlong handle, jstring j_text) {
    Engine * engine = from_handle(handle);
    if (engine == nullptr || engine->vocab == nullptr) {
        return 0;
    }
    const std::string text = to_utf8(env, j_text);
    return static_cast<jint>(tokenize_text(engine->vocab, text, false).size());
}

JNIEXPORT void JNICALL
Java_com_example_model_internal_LlamaBridge_nativeCancel(JNIEnv *, jobject, jlong handle) {
    Engine * engine = from_handle(handle);
    if (engine != nullptr) {
        engine->cancel = true;
    }
}

/**
 * Runs prefill + sampling and streams every token piece to the Java callback.
 *
 * @return number of generated tokens, -1 on error, -2 when cancelled.
 */
JNIEXPORT jint JNICALL
Java_com_example_model_internal_LlamaBridge_nativeGenerate(
        JNIEnv * env, jobject, jlong handle, jstring j_prompt,
        jint max_tokens, jfloat temperature, jint top_k, jfloat top_p,
        jfloat min_p, jfloat repeat_penalty, jint seed,
        jint max_reasoning_tokens, jobject callback) {
    Engine * engine = from_handle(handle);
    if (engine == nullptr || !engine->loaded || engine->ctx == nullptr) {
        throw_illegal_state(env, "No model is loaded");
        return -1;
    }

    jclass callback_class = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(callback_class, "onToken", "([B)Z");
    env->DeleteLocalRef(callback_class);
    if (on_token == nullptr) {
        throw_illegal_state(env, "TokenCallback.onToken([B)Z not found");
        return -1;
    }

    std::lock_guard<std::mutex> lock(engine->gen_mutex);
    engine->cancel = false;

    const std::string prompt = to_utf8(env, j_prompt);

    llama_memory_clear(llama_get_memory(engine->ctx), true);

    // ---- prefill -----------------------------------------------------------
    const bool add_bos = llama_vocab_get_add_bos(engine->vocab);
    std::vector<llama_token> prompt_tokens = tokenize_text(engine->vocab, prompt, add_bos);
    if (prompt_tokens.empty()) {
        LOGW("prompt tokenisation produced no tokens");
        return 0;
    }

    // Never let prompt + generation overflow the context window. Shrink the
    // generation budget first: truncating the prompt would drop the system
    // header (and its `/think` switch), which silently disables reasoning.
    const int32_t ctx_size = static_cast<int32_t>(llama_n_ctx(engine->ctx));
    const int32_t prompt_limit = std::max(32, ctx_size - 8);
    if (static_cast<int32_t>(prompt_tokens.size()) > prompt_limit) {
        const size_t drop = prompt_tokens.size() - static_cast<size_t>(prompt_limit);
        LOGW("prompt has %zu tokens, dropping the oldest %zu to fit n_ctx=%d",
             prompt_tokens.size(), drop, ctx_size);
        prompt_tokens.erase(prompt_tokens.begin(), prompt_tokens.begin() + static_cast<long>(drop));
    }
    const int32_t effective_max_tokens = std::max(
            16, std::min(max_tokens, ctx_size - static_cast<int32_t>(prompt_tokens.size()) - 8));
    if (effective_max_tokens < max_tokens) {
        LOGW("reducing generation budget from %d to %d tokens to fit n_ctx=%d",
             max_tokens, effective_max_tokens, ctx_size);
    }

    const int32_t n_batch = static_cast<int32_t>(llama_n_batch(engine->ctx));
    for (size_t offset = 0; offset < prompt_tokens.size(); offset += static_cast<size_t>(n_batch)) {
        if (engine->cancel.load()) {
            return -2;
        }
        const int32_t count = static_cast<int32_t>(
                std::min<size_t>(static_cast<size_t>(n_batch), prompt_tokens.size() - offset));

        llama_batch batch = llama_batch_get_one(prompt_tokens.data() + offset, count);
        if (llama_decode(engine->ctx, batch) != 0) {
            LOGE("llama_decode failed while evaluating the prompt");
            throw_illegal_state(env, "llama_decode failed during prompt evaluation");
            return -1;
        }
    }

    // ---- sampler -----------------------------------------------------------
    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    sampler_params.no_perf = true;
    llama_sampler * sampler = llama_sampler_chain_init(sampler_params);

    if (repeat_penalty > 1.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
                llama_vocab_n_tokens(engine->vocab), 192, repeat_penalty, 0.0f, 0.0f));
    }
    if (top_k > 0) {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k));
    }
    if (top_p > 0.0f && top_p < 1.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
    }
    if (min_p > 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_min_p(min_p, 1));
    }
    if (temperature > 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    }

    // ---- decode loop -------------------------------------------------------
    int32_t generated = 0;
    int32_t result = 0;

    // The prompt is prefilled with `<think>` for reasoning levels, so the
    // stream starts inside the reasoning block. Track it here so the budget
    // can be enforced and the block closed before the answer is generated.
    bool inside_thinking = max_reasoning_tokens > 0;
    int32_t reasoning_tokens = 0;
    std::string think_tail;

    auto token_to_text = [&](llama_token token, std::string & out) {
        char small[512];
        int32_t n_piece = llama_token_to_piece(
                engine->vocab, token, small, static_cast<int32_t>(sizeof(small)), 0, false);
        out.clear();
        if (n_piece < 0) {
            std::vector<char> big(static_cast<size_t>(-n_piece));
            n_piece = llama_token_to_piece(
                    engine->vocab, token, big.data(), static_cast<int32_t>(big.size()), 0, false);
            if (n_piece > 0) {
                out.assign(big.data(), static_cast<size_t>(n_piece));
            }
        } else if (n_piece > 0) {
            out.assign(small, static_cast<size_t>(n_piece));
        }
    };

    auto emit_text = [&](const std::string & text) -> int {
        if (text.empty()) {
            return 1;
        }
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(text.size()));
        env->SetByteArrayRegion(
                bytes, 0, static_cast<jsize>(text.size()),
                reinterpret_cast<const jbyte *>(text.data()));
        const jboolean keep_going = env->CallBooleanMethod(callback, on_token, bytes);
        env->DeleteLocalRef(bytes);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            return -1;
        }
        return keep_going ? 1 : 0;
    };

    auto decode_one = [&](llama_token token) -> bool {
        llama_batch batch = llama_batch_get_one(&token, 1);
        return llama_decode(engine->ctx, batch) == 0;
    };

    for (int32_t i = 0; i < effective_max_tokens; ++i) {
        if (engine->cancel.load()) {
            result = -2;
            break;
        }

        // Once the reasoning budget is exhausted, force `</think>` into the
        // context so the model has to start the final answer.
        if (inside_thinking && reasoning_tokens >= max_reasoning_tokens) {
            const std::string closing = "\n</think>\n";
            const std::vector<llama_token> close_tokens =
                    tokenize_text(engine->vocab, closing, false);
            bool failed = false;
            for (llama_token close_token : close_tokens) {
                std::string text;
                token_to_text(close_token, text);
                const int emit = emit_text(text);
                if (emit <= 0) {
                    result = emit == 0 ? -2 : -1;
                    failed = true;
                    break;
                }
                if (!decode_one(close_token)) {
                    LOGE("llama_decode failed while closing the think block");
                    throw_illegal_state(env, "llama_decode failed while closing the think block");
                    result = -1;
                    failed = true;
                    break;
                }
                generated++;
            }
            if (failed) {
                break;
            }
            inside_thinking = false;
            think_tail.clear();
            continue;
        }

        const llama_token token = llama_sampler_sample(sampler, engine->ctx, -1);
        llama_sampler_accept(sampler, token);

        if (llama_vocab_is_eog(engine->vocab, token)) {
            break;
        }

        std::string text;
        token_to_text(token, text);
        if (!text.empty()) {
            const int emit = emit_text(text);
            if (emit <= 0) {
                result = emit == 0 ? -2 : -1;
                break;
            }
            if (inside_thinking) {
                think_tail += text;
                if (think_tail.find("</think>") != std::string::npos) {
                    inside_thinking = false;
                    think_tail.clear();
                } else if (think_tail.size() > 32) {
                    think_tail.erase(0, think_tail.size() - 32);
                }
            }
        }

        if (inside_thinking) {
            reasoning_tokens++;
        }
        generated++;

        if (!decode_one(token)) {
            LOGE("llama_decode failed during generation");
            throw_illegal_state(env, "llama_decode failed during generation");
            result = -1;
            break;
        }
    }

    llama_sampler_free(sampler);

    if (result != 0) {
        return result;
    }
    return generated;
}

} // extern "C"
