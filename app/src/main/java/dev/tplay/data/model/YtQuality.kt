package dev.tplay.data.model

/**
 * Quality ceiling for YouTube streams.
 *
 * The selected level is the *maximum* quality that may be used: the resolver picks the
 * best stream at or below the ceiling (falling down), and only when nothing lower is
 * available does it go up to the closest higher stream.
 */
enum class YtQuality(val label: String, val maxBitrate: Int) {
    HIGH("HIGH", Int.MAX_VALUE),
    MED("MED", 128_000),
    LOW("LOW", 64_000);

    companion object {
        fun fromName(name: String?): YtQuality =
            entries.firstOrNull { it.name == name } ?: HIGH
    }
}

/** Live pipeline status shown as a single strip on the YouTube tab. */
enum class YtStage { IDLE, FETCH, PARSE, READY, PLAYING }
