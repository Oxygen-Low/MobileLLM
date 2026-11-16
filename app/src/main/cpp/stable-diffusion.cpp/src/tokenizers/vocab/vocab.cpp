#include "vocab.h"
#include "clip_merges.hpp"

// Only the CLIP tokenizer is bundled: the image models this app downloads are
// SD 1.x/2.x checkpoints, which use CLIP BPE. The other tokenizers belong to
// text and video models the app does not sample, so their multi-megabyte data
// tables are left out of the APK. The symbols stay here so the rest of the
// library still links.

std::string load_clip_merges() {
    std::string merges_utf8_str(reinterpret_cast<const char*>(clip_merges_utf8_c_str), sizeof(clip_merges_utf8_c_str));
    return merges_utf8_str;
}

std::string load_qwen2_merges() { return std::string(); }
std::string load_mistral_merges() { return std::string(); }
std::string load_mistral_vocab_json() { return std::string(); }
std::string load_t5_tokenizer_json() { return std::string(); }
std::string load_umt5_tokenizer_json() { return std::string(); }
std::string load_gemma_merges() { return std::string(); }
std::string load_gemma_vocab_json() { return std::string(); }
