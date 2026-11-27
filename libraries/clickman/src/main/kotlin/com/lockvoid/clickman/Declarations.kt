@file:OptIn(ExperimentalSerializationApi::class)

package com.lockvoid.clickman

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/**
 * What a declared event or property means, for the catalog (docs/PROTOCOL.md, Event catalog).
 * Every event class and every property of one needs a description. The lines are trimmed
 * and joined with single spaces, so a description may be split over several strings:
 *
 * ```kotlin
 * @Serializable
 * @Description("Export wrote every file", "and they are ready to use.")
 * data class ExportCompleted(@Description("File format of the export.") val format: Format) : AppEvent
 * ```
 */
@SerialInfo
@MustBeDocumented
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class Description(vararg val lines: String)

/**
 * Declares a `String` property of an event as free text. Free text can carry personal data,
 * so ClickMan refuses a `String` property without it; use an enum for a set of values instead.
 */
@SerialInfo
@MustBeDocumented
@Target(AnnotationTarget.PROPERTY)
annotation class FreeText
