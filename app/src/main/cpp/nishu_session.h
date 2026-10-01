#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#include "llama.h"

namespace nishu {

enum FinishReason : int { STOP = 0, LENGTH = 1, CANCELLED = 2, CONTEXT_FULL = 3, ERROR = 4 };
enum SamplerMode : int { CHAT = 0, GREEDY = 1, GRAMMAR = 2, CHAT_WITH_TOOL_SWITCH = 3 };

struct GenStats {
    int finish = ERROR;
    int tokens = 0;
    int64_t ttft_us = 0;
    int64_t total_us = 0;
};

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_ctx = 0;
    int n_batch = 512;
    int n_past = 0;
    int prefix_len = 0;
    std::atomic<bool> cancel{false};
};

// Returns nullptr and fills `error` on failure.
Session *load(const std::string &path, int n_ctx, int n_threads, std::string &error);
void release(Session *s);

std::vector<llama_token> tokenize(Session *s, const std::string &text);

// Clears the KV cache and evaluates `tokens` as the cacheable prefix. Returns 0 on success.
int eval_prefix(Session *s, const std::vector<llama_token> &tokens);

// Drops everything after the prefix and evaluates the turn tokens. Returns 0 ok, 1 context full, -1 error.
int eval_turn(Session *s, const std::vector<llama_token> &tokens);

// Pieces are delivered as complete UTF-8 byte strings. Return false from the sink to stop.
using PieceSink = std::function<bool(const std::string &)>;

GenStats generate(Session *s, int mode, int max_tokens, const std::string &grammar, uint32_t seed, const PieceSink &sink);

bool save_state(Session *s, const std::string &path, const std::vector<llama_token> &tokens);
// Returns true only if the stored tokens equal `expected`; otherwise the cache is cleared.
bool load_state(Session *s, const std::string &path, const std::vector<llama_token> &expected);

}  // namespace nishu
