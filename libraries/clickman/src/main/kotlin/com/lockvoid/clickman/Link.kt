package com.lockvoid.clickman

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A link of a visitor to an account, queued in `events` between the visitor's
 * and the account's events and sent alone (docs/PROTOCOL.md, Visitors and links).
 */
internal object Link {
    private const val TYPE = "identify"

    /** The row: `{"type": "identify", "anonymousId": …, "externalId": …}`. */
    fun encode(anonymousId: String, externalId: String): String = buildJsonObject {
        put("type", TYPE)
        put("anonymousId", anonymousId)
        put("externalId", externalId)
    }.toString()

    /** The row as JSON when it is a link; null for a track row and for any body that is no JSON object. */
    fun parse(body: String): JsonObject? {
        val json = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (error: SerializationException) {
            null
        }
        val type = json?.get("type") as? JsonPrimitive
        return json?.takeIf { type != null && type.isString && type.content == TYPE }
    }

    /** The body of `POST /v1/identify`: the link without its `type`. */
    fun request(link: JsonObject): ByteArray = JsonObject(link - "type").toString().encodeToByteArray()
}

/** What one request carries: one link alone or a run of track rows that stops before a link (identity.json, takes). */
internal sealed interface Take {
    class Linked(val row: QueuedEvent, val link: JsonObject) : Take

    class Events(val rows: List<QueuedEvent>) : Take

    companion object {
        /** The take at the head of [rows], the oldest waiting rows; batch limits apply within a run of events. */
        fun of(rows: List<QueuedEvent>, maxEvents: Int = Batch.MAX_EVENTS, maxBytes: Int = Batch.MAX_BYTES): Take? {
            val first = rows.firstOrNull() ?: return null
            Link.parse(first.body)?.let { return Linked(first, it) }
            val run = rows.takeWhile { Link.parse(it.body) == null }
            return Events(run.take(Batch.cut(run.map { it.body }, maxEvents, maxBytes)))
        }
    }
}
