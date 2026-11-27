package com.lockvoid.clickman

import kotlinx.serialization.Serializable

/** The reference events of catalog.json, declared the way an app declares its events. */
@Serializable
sealed interface ReferenceEvent {
    @Serializable
    @Description(
        "Export wrote every file",
        "and they are ready to use.",
    )
    data class ExportCompleted(
        @Description("File format of the export.")
        val format: Format,
        @Description("  Whether one mix or one file per track was written.  ")
        val mode: Mode,
        @Description("How many tracks the export contains.")
        val tracks: Int,
        @Description("Length of the exported audio in seconds.")
        val durationSeconds: Double? = null,
        @Description("Whether loudness normalization was applied.")
        val normalized: Boolean? = null,
        @Description("Title the user gave the export.")
        @FreeText
        val title: String? = null,
    ) : ReferenceEvent

    @Serializable
    @Description("A new empty project was created.")
    data object ProjectCreated : ReferenceEvent

    enum class Format { WAV, FLAC }

    enum class Mode { FULL_MIX, PER_TRACK }
}
