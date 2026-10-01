package com.nishu.app.llm.llamacpp

object LlamaBridge {
    init {
        System.loadLibrary("nishu_llama")
    }

    external fun buildInfo(): String
}
