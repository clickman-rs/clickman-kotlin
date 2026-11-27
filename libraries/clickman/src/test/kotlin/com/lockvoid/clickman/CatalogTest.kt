package com.lockvoid.clickman

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class CatalogTest {
    @Serializable
    data class Undescribed(@Description("A.") val a: Int)

    @Serializable
    @Description("Has a property without a description.")
    data class UndescribedProperty(@Description("A.") val a: Int, val b: Boolean)

    @Serializable
    @Description("Has a string without @FreeText.")
    data class UndeclaredText(@Description("A name.") val name: String)

    @Serializable
    @Description("Marks a number as free text.")
    data class TextNumber(@Description("A count.") @FreeText val count: Int)

    @Serializable
    @Description("Has a list.")
    data class Listed(@Description("Some numbers.") val numbers: List<Int>)

    @Serializable
    @Description("Redeclares a standard event.")
    data object AppOpened

    @Serializable
    @Description("   ")
    data object BlankDescription

    @Serializable
    @Description("Is described with a\ttab.")
    data object Tabbed

    @Serializable
    @Description("Is named in another script.")
    @SerialName("Экспорт")
    data object Cyrillic

    @Serializable
    @Description(
        """Is described over
        several indented lines
        of a raw string.""",
    )
    data object RawLines

    @Serializable
    @SerialName("legacyExportDone")
    @Description("Is renamed.")
    data class Renamed(
        @SerialName("fileFormat")
        @Description("Renamed property.")
        val format: Kind,
        @Description("Every other number type.")
        val sizes: Long,
        @Description("A float.")
        val ratio: Float? = null,
    ) {
        enum class Kind {
            @SerialName("MP3File")
            MP3,
            OggVorbis,
        }
    }

    @Serializable
    @Description("Repeats a property name after snake casing.")
    data class RepeatedProperty(
        @Description("One.") val fullMix: Boolean,
        @Description("Two.") @SerialName("full_mix") val other: Boolean,
    )

    @Serializable
    @Description("Has a default value.")
    data class Defaulted(@Description("A count.") val count: Int = 3, @Description("A flag.") val flag: Boolean? = true)

    @Serializable
    @Description("Counts with unsigned integers.")
    data class Unsigned(
        @Description("A count.") val count: UInt,
        @Description("A large count.") val large: ULong? = null,
        @Description("A small count.") val small: UByte = 255u,
    )

    private fun catalog(vararg events: kotlinx.serialization.SerializationStrategy<*>): JsonObject =
        Json.parseToJsonElement(ClickMan.catalog(*events)).jsonObject

    private fun refusal(event: kotlinx.serialization.SerializationStrategy<*>): String =
        assertFailsWith<IllegalArgumentException> { ClickMan.catalog(event) }.message.orEmpty()

    @Test
    fun namesBecomeSnakeCase() {
        val names = mapOf(
            "ExportCompleted" to "export_completed",
            "exportCompleted" to "export_completed",
            "EXPORT_COMPLETED" to "export_completed",
            "durationSeconds" to "duration_seconds",
            "PER_TRACK" to "per_track",
            "PerTrack" to "per_track",
            "fullMix" to "full_mix",
            "wav" to "wav",
            "already_snake" to "already_snake",
            "HTTPServer" to "http_server",
            "MP3Export" to "mp3_export",
            "track2" to "track2",
            "_private" to "private",
        )
        for ((name, snake) in names) assertEquals(snake, SnakeCase.of(name), name)
    }

    @Test
    fun anEventWithoutADescriptionIsRefused() {
        assertTrue(refusal(Undescribed.serializer()).contains("event undescribed has no @Description"))
        assertTrue(refusal(BlankDescription.serializer()).contains("is blank"))
        assertTrue(refusal(Tabbed.serializer()).contains("is one line without control characters"))
    }

    @Test
    fun aPropertyWithoutADescriptionIsRefused() {
        assertTrue(refusal(UndescribedProperty.serializer()).contains("property b of event undescribed_property has no @Description"))
    }

    @Test
    fun aStringWithoutFreeTextIsRefused() {
        val message = refusal(UndeclaredText.serializer())
        assertTrue(message.contains("property name of event undeclared_text is a String without @FreeText"), message)
    }

    @Test
    fun freeTextOnAnythingButAStringIsRefused() {
        assertTrue(refusal(TextNumber.serializer()).contains("is marked @FreeText but is no String"))
    }

    @Test
    fun aTypeACatalogCannotDeclareIsRefused() {
        val message = refusal(Listed.serializer())
        assertTrue(message.contains("property numbers of event listed is a kotlin.collections.ArrayList"), message)
    }

    @Test
    fun aStandardEventCannotBeRedeclared() {
        assertTrue(refusal(AppOpened.serializer()).contains("app_opened is a standard event"))
    }

    @Test
    fun aNameThatIsNoSnakeCaseIsRefused() {
        assertTrue(refusal(Cyrillic.serializer()).contains("is not snake case"))
    }

    @Test
    fun repeatedNamesAreRefused() {
        assertTrue(refusal(RepeatedProperty.serializer()).contains("declares the property full_mix twice"))
        val message = assertFailsWith<IllegalArgumentException> { ClickMan.catalog(RawLines.serializer(), RawLines.serializer()) }.message
        assertEquals("the catalog declares the event raw_lines twice", message)
    }

    @Test
    fun theLinesOfADescriptionAreTrimmedAndJoinedWithSingleSpaces() {
        val event = catalog(RawLines.serializer()).getValue("events").jsonArray.single().jsonObject
        assertEquals("Is described over several indented lines of a raw string.", event.text("description"))
    }

    @Test
    fun serialNamesOverrideTheNamesOfEventsPropertiesAndValues() {
        val event = catalog(Renamed.serializer()).getValue("events").jsonArray.single().jsonObject
        assertEquals("legacy_export_done", event.text("name"))
        val (format, sizes, ratio) = event.getValue("properties").jsonArray.map { it.jsonObject }
        assertEquals("file_format", format.text("name"))
        assertEquals(listOf("mp3_file", "ogg_vorbis"), format.getValue("values").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(buildJsonObject { put("name", "sizes"); put("description", "Every other number type."); put("type", "integer"); put("required", true) }, sizes)
        assertEquals("number", ratio.text("type"))

        val typed = TypedEvent.of(Renamed.serializer(), Renamed(Renamed.Kind.MP3, 7, 0.1f))
        assertEquals("legacy_export_done", typed.name)
        assertEquals("""{"file_format":"mp3_file","sizes":7,"ratio":0.1}""", typed.properties.toString())
    }

    @Test
    fun eventsFollowTheArgumentsAndTheirHierarchiesTheQualifiedNames() {
        fun names(vararg events: kotlinx.serialization.SerializationStrategy<*>) =
            catalog(*events).getValue("events").jsonArray.map { it.jsonObject.text("name") }

        assertEquals(listOf("raw_lines", "export_completed", "project_created"), names(RawLines.serializer(), ReferenceEvent.serializer()))
        assertEquals(listOf("project_created", "raw_lines"), names(ReferenceEvent.ProjectCreated.serializer(), RawLines.serializer()))
    }

    @Test
    fun aDefaultIsTrackedLikeAnyValueAndANullIsLeftOut() {
        assertEquals("""{"count":3,"flag":true}""", TypedEvent.of(Defaulted.serializer(), Defaulted()).properties.toString())
        assertEquals("""{"count":3}""", TypedEvent.of(Defaulted.serializer(), Defaulted(flag = null)).properties.toString())
    }

    @Test
    fun aNumberThatIsNotFiniteIsRefused() {
        val error = assertFailsWith<ClickManException> {
            TypedEvent.of(ReferenceEvent.serializer(), ReferenceEvent.ExportCompleted(ReferenceEvent.Format.WAV, ReferenceEvent.Mode.FULL_MIX, 1, Double.NaN))
        }
        assertEquals("NaN is not a finite JSON number", error.message)
    }

    @Test
    fun aClassOrHierarchyTypedAsTheEventItselfTracksTheSame() {
        val event = ReferenceEvent.ExportCompleted(ReferenceEvent.Format.FLAC, ReferenceEvent.Mode.PER_TRACK, 2, title = "Демо")
        val bySealed = TypedEvent.of(ReferenceEvent.serializer(), event)
        val byClass = TypedEvent.of(ReferenceEvent.ExportCompleted.serializer(), event)

        assertEquals("export_completed", byClass.name)
        assertEquals(bySealed.properties, byClass.properties)
        assertEquals("""{"format":"flac","mode":"per_track","tracks":2,"title":"Демо"}""", byClass.properties.toString())
    }

    @Test
    fun unsignedIntegersAreIntegers() {
        val properties = catalog(Unsigned.serializer()).getValue("events").jsonArray.single().jsonObject.getValue("properties").jsonArray
        assertEquals(listOf("integer", "integer", "integer"), properties.map { it.jsonObject.text("type") })
        assertEquals(listOf(true, false, true), properties.map { it.jsonObject.getValue("required").jsonPrimitive.content.toBoolean() })

        val typed = TypedEvent.of(Unsigned.serializer(), Unsigned(4_000_000_000u, ULong.MAX_VALUE))
        assertEquals("""{"count":4000000000,"large":18446744073709551615,"small":255}""", typed.properties.toString())
    }

    @Test
    fun theCatalogIsIndentedJson() {
        val json = ClickMan.catalog<ReferenceEvent>()
        assertTrue(json.startsWith("{\n  \"version\": 1,\n  \"events\": ["), json)
    }
}
