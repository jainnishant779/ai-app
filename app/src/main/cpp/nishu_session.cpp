#include "nishu_session.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>

#define TAG "NishuSession"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace nishu {

namespace {

int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

// Length of the longest prefix of `s` that does not end in the middle of a UTF-8 sequence.
size_t utf8_complete_prefix(const std::string &s) {
    size_t n = s.size();
    size_t back = 0;
    while (back < 4 && back < n) {
        unsigned char c = static_cast<unsigned char>(s[n - 1 - back]);
        if ((c & 0xC0) == 0x80) {  // continuation byte, keep looking for the lead byte
            back++;
            continue;
        }
        size_t need = 1;
        if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        size_t have = back + 1;
        return have < need ? n - have : n;
    }
    return n;
}

bool decode_slices(Session *s, const llama_token *data, int count) {
    int done = 0;
    while (done < count) {
        int n = std::min(s->n_batch, count - done);
        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(data + done), n);
        if (llama_decode(s->ctx, batch) != 0) return false;
        done += n;
        s->n_past += n;
    }
    return true;
}

llama_sampler *build_chat_chain(uint32_t seed) {
    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(20));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.8f, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(seed));
    return chain;
}

llama_sampler *build_greedy_chain() {
    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    return chain;
}

// Grammar sampler first so it masks logits before greedy picks.
llama_sampler *build_grammar_chain(const llama_vocab *vocab, const std::string &grammar) {
    llama_sampler *g = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
    if (g == nullptr) return nullptr;
    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(chain, g);
    llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    return chain;
}

}  // namespace

Session *load(const std::string &path, int n_ctx, int n_threads, int n_threads_batch, std::string &error) {
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (model == nullptr) {
        error = "llama_model_load_from_file failed";
        return nullptr;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads_batch;
    cp.type_k = GGML_TYPE_Q8_0;
    cp.type_v = GGML_TYPE_Q8_0;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;  // a quantized V cache needs flash attention
    cp.no_perf = true;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        llama_model_free(model);
        error = "llama_init_from_model failed (q8_0 KV cache + flash attention)";
        return nullptr;
    }
    auto *s = new Session();
    s->model = model;
    s->ctx = ctx;
    s->vocab = llama_model_get_vocab(model);
    s->n_ctx = static_cast<int>(llama_n_ctx(ctx));
    s->n_batch = static_cast<int>(llama_n_batch(ctx));
    LOGI("loaded %s n_ctx=%d n_batch=%d", path.c_str(), s->n_ctx, s->n_batch);
    return s;
}

void release(Session *s) {
    if (s == nullptr) return;
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

std::vector<llama_token> tokenize(Session *s, const std::string &text) {
    std::vector<llama_token> out(text.size() + 8);
    int n = llama_tokenize(s->vocab, text.data(), static_cast<int>(text.size()), out.data(),
                           static_cast<int>(out.size()), /*add_special=*/false, /*parse_special=*/true);
    if (n < 0) {
        out.resize(static_cast<size_t>(-n));
        n = llama_tokenize(s->vocab, text.data(), static_cast<int>(text.size()), out.data(),
                           static_cast<int>(out.size()), false, true);
    }
    out.resize(n < 0 ? 0 : static_cast<size_t>(n));
    return out;
}

int eval_prefix(Session *s, const std::vector<llama_token> &tokens) {
    llama_memory_clear(llama_get_memory(s->ctx), true);
    s->n_past = 0;
    s->prefix_len = 0;
    if (!decode_slices(s, tokens.data(), static_cast<int>(tokens.size()))) return -1;
    s->prefix_len = s->n_past;
    return 0;
}

int eval_turn(Session *s, const std::vector<llama_token> &tokens) {
    llama_memory_seq_rm(llama_get_memory(s->ctx), 0, s->prefix_len, -1);
    s->n_past = s->prefix_len;
    s->cancel = false;
    if (s->n_past + static_cast<int>(tokens.size()) >= s->n_ctx) return 1;
    return decode_slices(s, tokens.data(), static_cast<int>(tokens.size())) ? 0 : -1;
}

GenStats generate(Session *s, int mode, int max_tokens, const std::string &grammar, uint32_t seed,
                  const PieceSink &sink) {
    GenStats st;
    const int64_t t0 = now_us();

    llama_sampler *smpl = nullptr;
    switch (mode) {
        case GREEDY: smpl = build_greedy_chain(); break;
        case GRAMMAR: smpl = build_grammar_chain(s->vocab, grammar); break;
        default: smpl = build_chat_chain(seed); break;
    }
    if (smpl == nullptr) {
        LOGE("could not build sampler (bad grammar?)");
        st.finish = ERROR;
        return st;
    }

    llama_token tool_call_token = -1;
    if (mode == CHAT_WITH_TOOL_SWITCH) {
        auto t = tokenize(s, "<tool_call>");
        if (t.size() == 1) tool_call_token = t[0];
    }

    std::string pending;
    char buf[256];
    int finish = LENGTH;

    for (int i = 0; i < max_tokens; i++) {
        if (s->cancel.load()) { finish = CANCELLED; break; }

        llama_token tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (i == 0) st.ttft_us = now_us() - t0;
        if (llama_vocab_is_eog(s->vocab, tok)) { finish = STOP; break; }

        int n = llama_token_to_piece(s->vocab, tok, buf, sizeof(buf), 0, /*special=*/true);
        if (n > 0) pending.append(buf, static_cast<size_t>(n));
        size_t cut = utf8_complete_prefix(pending);
        if (cut > 0) {
            std::string out = pending.substr(0, cut);
            pending.erase(0, cut);
            if (!sink(out)) { finish = CANCELLED; break; }
        }
        st.tokens++;

        if (mode == CHAT_WITH_TOOL_SWITCH && tool_call_token >= 0 && tok == tool_call_token) {
            llama_sampler *g = build_grammar_chain(s->vocab, grammar);
            if (g != nullptr) {
                llama_sampler_free(smpl);
                smpl = g;
                llama_sampler_reset(smpl);  // without this the grammar sampler yields empty output
            }
            mode = GRAMMAR;
        }

        if (s->n_past + 1 >= s->n_ctx) { finish = CONTEXT_FULL; break; }
        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(s->ctx, batch) != 0) { finish = ERROR; break; }
        s->n_past++;
    }

    if (!pending.empty()) sink(pending);
    llama_sampler_free(smpl);
    st.finish = finish;
    st.total_us = now_us() - t0;
    return st;
}

bool save_state(Session *s, const std::string &path, const std::vector<llama_token> &tokens) {
    size_t n = llama_state_seq_save_file(s->ctx, path.c_str(), 0, tokens.data(), tokens.size());
    return n > 0;
}

bool load_state(Session *s, const std::string &path, const std::vector<llama_token> &expected) {
    llama_memory_clear(llama_get_memory(s->ctx), true);
    s->n_past = 0;
    s->prefix_len = 0;
    std::vector<llama_token> loaded(expected.size() + 16);
    size_t count = 0;
    size_t n = llama_state_seq_load_file(s->ctx, path.c_str(), 0, loaded.data(), loaded.size(), &count);
    if (n == 0 || count != expected.size() ||
        !std::equal(expected.begin(), expected.end(), loaded.begin())) {
        llama_memory_clear(llama_get_memory(s->ctx), true);
        return false;
    }
    s->n_past = static_cast<int>(count);
    s->prefix_len = s->n_past;
    return true;
}

}  // namespace nishu
