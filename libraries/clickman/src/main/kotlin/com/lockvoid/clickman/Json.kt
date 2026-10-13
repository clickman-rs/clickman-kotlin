package com.lockvoid.clickman

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Properties, traits and context as JSON; dates as ISO 8601, anything else unknown as its string. */
internal fun Map<String, Any?>.toJsonObject(): JsonObject = JsonConversion().encode(this) as JsonObject

/** Conversion rejects cycles and extreme nesting before they can overflow the host's stack. */
private class JsonConversion {
    private val ancestors = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    fun encode(value: Any?, depth: Int = 0): JsonElement {
        if (depth > 64) throw ClickManException("JSON nesting must not exceed 64 levels")
        return when (value) {
            null -> JsonNull
            is JsonPrimitive -> primitive(value)
            is String -> JsonPrimitive(value)
            is Number -> primitive(JsonPrimitive(value))
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> container(value) { JsonObject(value.entries.associate { (key, child) -> key.toString() to encode(child, depth + 1) }) }
            is Iterable<*> -> container(value) { JsonArray(value.map { encode(it, depth + 1) }) }
            is Array<*> -> container(value) { JsonArray(value.map { encode(it, depth + 1) }) }
            is Instant -> JsonPrimitive(DateTimeFormatter.ISO_INSTANT.format(value))
            is Date -> JsonPrimitive(DateTimeFormatter.ISO_INSTANT.format(value.toInstant()))
            is Enum<*> -> JsonPrimitive(value.name)
            else -> JsonPrimitive(value.toString())
        }
    }

    private fun container(value: Any, encode: () -> JsonElement): JsonElement {
        if (!ancestors.add(value)) throw ClickManException("JSON properties must not contain cycles")
        try {
            return encode()
        } finally {
            ancestors.remove(value)
        }
    }

    private fun primitive(value: JsonPrimitive): JsonPrimitive {
        if (value !== JsonNull && !value.isString && value.booleanOrNull == null && value.doubleOrNull?.isFinite() != true) {
            throw ClickManException("${value.content} is not a finite JSON number")
        }
        return value
    }
}
