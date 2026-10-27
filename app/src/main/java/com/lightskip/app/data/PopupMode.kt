package com.lightskip.app.data

enum class PopupMode {
    OFF,
    LEARNED;

    companion object {
        fun from(value: String?): PopupMode = when (value) {
            "STRICT", "POSITION", "LEARNED" -> LEARNED
            else -> OFF
        }
    }
}