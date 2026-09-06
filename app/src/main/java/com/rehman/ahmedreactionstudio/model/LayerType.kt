package com.rehman.ahmedreactionstudio.model

enum class LayerType(val label: String, val icon: String, val hasAudio: Boolean, val visual: Boolean) {
    VIDEO("Local Video", "▣", true, true),
    CAMERA("Camera", "◉", true, true),
    SCREEN("Screen Recording", "▤", true, true),
    IMAGE("Image", "▧", false, true),
    TEXT("Text", "T", false, true),
    AUDIO_MUSIC("Background Music", "♫", true, false),
    AUDIO_MIC("External Mic", "🎙", true, false);

    companion object {
        fun fromName(name: String?): LayerType = entries.firstOrNull { it.name == name } ?: VIDEO
    }
}
