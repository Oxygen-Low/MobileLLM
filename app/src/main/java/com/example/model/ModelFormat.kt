package com.example.model

enum class ModelFormat {
    GGUF,
    SAFETENSORS
}

enum class ModelLoadState {
    UNLOADED,
    LOADING,
    LOADED,
    UNLOADING,
    ERROR
}
