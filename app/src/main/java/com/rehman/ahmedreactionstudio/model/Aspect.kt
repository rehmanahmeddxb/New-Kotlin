package com.rehman.ahmedreactionstudio.model

enum class Aspect(val label: String, val width: Int, val height: Int) {
    LANDSCAPE_16_9("16:9", 16, 9),
    PORTRAIT_9_16("9:16", 9, 16),
    SQUARE_1_1("1:1", 1, 1),
    CLASSIC_4_3("4:3", 4, 3);

    companion object {
        fun fromName(name: String?): Aspect = entries.firstOrNull { it.name == name } ?: LANDSCAPE_16_9
    }
}
