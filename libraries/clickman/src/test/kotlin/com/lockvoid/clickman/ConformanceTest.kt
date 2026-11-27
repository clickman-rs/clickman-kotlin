package com.lockvoid.clickman

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Every case of the shared client fixtures in protocol/fixtures (protocol/README.md). */
class ConformanceTest {
    private val directory: File = Files.createTempDirectory("clickman-conformance").toFile()
    private val none = JsonObject(emptyMap())

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun fixture(name: String): JsonObject =
        Json.parseToJsonElement(File(System.getProperty("clickman.fixtures"), name).readText()).jsonObject

    private fun cases(name: String): List<JsonObject> = fixture(name).cases(name)

    private fun JsonObject.cases(where: String): List<JsonObject> {
        val cases = children("cases")
        assertTrue(cases.isNotEmpty(), "$where has no cases")
        return cases
    }

    private fun <T> withStore(name: String, work: (Queue, Tracker) -> T): T =
        openQueue(File(directory, "$name.sqlite")).use { queue -> work(queue, Tracker(queue, 10_000, { TestClock.START }, Random(9))) }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun release(json: JsonObject) = Release(json.string("version"), json.string("build"))

    @Test
    fun events() = withStore("events") { queue, tracker ->
        for (case in cases("events.json")) {
            val value = case.string("value")
            val stored = when (val field = case.string("field")) {
                "event" -> storesEvent(queue, tracker, value)
                "externalId" -> storesExternalId(queue, tracker, value)
                else -> fail("${case.string("name")}: no field $field")
            }
            assertEquals(case.getValue("valid").jsonPrimitive.boolean, stored, case.string("name"))
        }
    }

    private fun storesEvent(queue: Queue, tracker: Tracker, name: String): Boolean {
        val before = queue.transaction { it.count() }
        try {
            tracker.track(name, none)
        } catch (refusal: ClickManException) {
            assertEquals(Validation.EVENT_RULE, refusal.message)
        }
        return queue.transaction { it.count() } == before + 1
    }

    private fun storesExternalId(queue: Queue, tracker: Tracker, id: String): Boolean {
        tracker.identify("someone-else")
        try {
            tracker.identify(id)
        } catch (refusal: ClickManException) {
            assertEquals(Validation.EXTERNAL_ID_RULE, refusal.message)
        }
        return queue.transaction { it.identity() }.externalId == id
    }

    @Test
    fun traits() = withStore("traits") { queue, tracker ->
        for (case in cases("traits.json")) {
            queue.transaction { it.setTraits(case.getValue("stored").jsonObject) }
            tracker.setTraits(case.getValue("changes").jsonObject)
            assertEquals(case.getValue("traits").jsonObject, queue.transaction { it.identity() }.traits, case.string("name"))
        }
    }

    @Test
    fun lifecycle() {
        for ((index, case) in cases("lifecycle.json").withIndex()) {
            val recorded = withStore("lifecycle-$index") { queue, tracker ->
                (case.getValue("last") as? JsonObject)?.let { last -> queue.transaction { it.setRelease(release(last)) } }
                tracker.launch(release(case.getValue("launch").jsonObject))
                queue.bodies().map { buildJsonObject { put("event", it.getValue("event")); put("properties", it.getValue("properties")) } }
            }
            assertEquals(case.getValue("events").jsonArray.toList(), recorded, case.string("name"))
        }
    }

    @Test
    fun batches() {
        for (case in cases("batches.json")) {
            val bodies = case.getValue("bodies").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(case.int("taken"), Batch.cut(bodies, case.int("maxEvents"), case.int("maxBytes")), case.string("name"))
        }
    }

    @Test
    fun outcomes() {
        for (case in cases("outcomes.json")) {
            assertEquals(case.string("outcome"), Outcome.of(case.int("status")).name.lowercase(), case.string("name"))
        }
    }

    @Test
    fun backoff() {
        val random = Random(5)
        for (case in cases("backoff.json")) {
            val name = case.string("name")
            val failures = case.int("failures")
            val retryAfter = case.getValue("retryAfter").let { if (it == JsonNull) null else it.jsonPrimitive.double.seconds }
            val (min, max) = case.getValue("min").jsonPrimitive.double to case.getValue("max").jsonPrimitive.double
            val drawn = List(1_000) { Backoff.delay(failures, retryAfter, random).seconds() }
            assertTrue(drawn.all { it in min..max }, "$name: ${drawn.minOrNull()}..${drawn.maxOrNull()} outside $min..$max")
            assertClose(min, Backoff.delay(failures, retryAfter, EdgeRandom(high = false)).seconds(), "$name, the low end")
            assertClose(max, Backoff.delay(failures, retryAfter, EdgeRandom(high = true)).seconds(), "$name, the high end")
        }
    }

    @Test
    fun identity() {
        for ((index, case) in cases("identity.json").withIndex()) {
            val name = case.string("name")
            val visitors = mutableListOf<String>()
            val (actor, queued) = withStore("identity-$index") { queue, tracker ->
                for (step in case.children("steps")) perform(step, queue, tracker, visitors, name)
                queue.transaction { it.identity() }.externalId to queue.bodies()
            }
            assertEquals(resolve(case.getValue("actor"), visitors), JsonPrimitive(actor), "$name: the actor")
            assertEquals(resolve(case.getValue("queue"), visitors), JsonArray(queued.map(::row)), "$name: the queue")
            for (visitor in visitors) {
                val uuid = UUID.fromString(visitor.removePrefix("anon:"))
                assertTrue(visitor.startsWith("anon:") && uuid.version() == 4 && uuid.variant() == 2, "$name: $visitor is no anon:<UUIDv4>")
            }
        }
    }

    private fun perform(step: JsonObject, queue: Queue, tracker: Tracker, visitors: MutableList<String>, name: String) {
        val before = queue.transaction { it.identity().externalId } to queue.bodies()
        when {
            "identifyInstallation" in step -> tracker.identifyInstallation()
            "track" in step -> tracker.track(step.string("track"), none)
            "reset" in step -> tracker.reset()
            "identify" in step && step["error"]?.jsonPrimitive?.boolean == true -> {
                assertFailsWith<ClickManException>("$name: $step") { tracker.identify(step.string("identify")) }
                assertEquals(before, queue.transaction { it.identity().externalId } to queue.bodies(), "$name: $step changed the store")
            }
            "identify" in step -> tracker.identify(step.string("identify"))
            else -> fail("$name: no step $step")
        }
        val actor = queue.transaction { it.identity() }.externalId
        if (actor.startsWith("anon:") && actor !in visitors) visitors += actor
    }

    /** A queued row as identity.json writes it; a link must be exactly the link row of the protocol. */
    private fun row(body: JsonObject): JsonObject = when (body.text("type")) {
        "identify" -> {
            assertEquals(listOf("type", "anonymousId", "externalId"), body.keys.toList(), "a link row")
            buildJsonObject { put("link", JsonObject(body - "type")) }
        }
        else -> buildJsonObject {
            put("track", body.getValue("event"))
            put("externalId", body.getValue("externalId"))
        }
    }

    /** [expected] with `visitor N` replaced by the Nth visitor the case started. */
    private fun resolve(expected: JsonElement, visitors: List<String>): JsonElement = when (expected) {
        is JsonObject -> JsonObject(expected.mapValues { resolve(it.value, visitors) })
        is JsonArray -> JsonArray(expected.map { resolve(it, visitors) })
        is JsonPrimitive -> VISITOR.matchEntire(expected.content)?.takeIf { expected.isString }
            ?.let { JsonPrimitive(visitors.getOrNull(it.groupValues[1].toInt() - 1) ?: fail("no ${expected.content} in $visitors")) }
            ?: expected
    }

    @Test
    fun takes() {
        val takes = fixture("identity.json").getValue("takes").jsonObject
        for (case in takes.cases("identity.json takes")) {
            val rows = case.getValue("rows").jsonArray.mapIndexed { index, row ->
                val body = when (val kind = row.jsonPrimitive.content) {
                    "track" -> """{"type":"track","event":"e$index"}"""
                    "link" -> Link.encode("anon:01926a2e-7b1c-4c3d-9f2e-5b1a2c3d4e5f", "user_$index")
                    else -> fail("no row $kind")
                }
                QueuedEvent(index.toLong() + 1, body)
            }
            val taken = mutableListOf<List<String>>()
            var waiting = rows
            while (waiting.isNotEmpty()) {
                val take = Take.of(waiting) ?: fail("${case.string("name")}: nothing taken of $waiting")
                val sent = when (take) {
                    is Take.Linked -> listOf(take.row)
                    is Take.Events -> take.rows
                }
                taken += sent.map { if (it.body.contains("identify")) "link" else "track" }
                waiting = waiting.drop(sent.size)
            }
            assertNull(Take.of(waiting))
            val expected = case.getValue("taken").jsonArray.map { take -> take.jsonArray.map { it.jsonPrimitive.content } }
            assertEquals(expected, taken, case.string("name"))
        }
    }

    @Test
    fun aTakeOfEventsStillObservesTheBatchLimits() {
        val rows = List(5) { QueuedEvent(it.toLong() + 1, """{"type":"track","event":"e$it"}""") } +
            QueuedEvent(6, Link.encode("anon:01926a2e-7b1c-4c3d-9f2e-5b1a2c3d4e5f", "user_42"))

        assertEquals(listOf(1L, 2L), (Take.of(rows, maxEvents = 2, maxBytes = 1_000) as Take.Events).rows.map { it.seq })
        assertEquals(listOf(5L), (Take.of(rows.drop(4), maxEvents = 2, maxBytes = 1_000) as Take.Events).rows.map { it.seq })
    }

    @Test
    fun linkRequest() {
        val server = StubServer.start()
        try {
            server.status = 204
            server.answer = ""
            val expected = fixture("identity.json").getValue("request").jsonObject.getValue("body").jsonObject
            withStore("link-request") { queue, _ ->
                queue.transaction { it.insert(TestClock.START, Link.encode(expected.string("anonymousId"), expected.string("externalId")), 10_000) }
                Delivery(queue, Sender(server.endpoint, "write-key"), 20, 30.seconds, { TestClock.START }, Random(1)).drain(force = true)
                assertEquals(0, queue.transaction { it.count() })
            }
            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/v1/identify", request.path)
            assertEquals("Bearer write-key", request.headers["authorization"])
            assertEquals("application/json", request.headers["content-type"])
            assertNull(request.headers["content-encoding"], "a link is sent uncompressed")
            assertEquals(expected, request.json)
        } finally {
            server.stop()
        }
    }

    @Test
    fun linkOutcomes() {
        val outcomes = fixture("identity.json").getValue("outcomes").jsonObject
        for (case in outcomes.cases("identity.json outcomes")) {
            assertEquals(case.string("outcome"), Outcome.ofLink(case.int("status")).name.lowercase(), case.string("name"))
        }
    }

    @Test
    fun catalog() {
        val fixture = fixture("catalog.json")
        assertEquals(fixture.getValue("catalog"), Json.parseToJsonElement(ClickMan.catalog<ReferenceEvent>()))
        assertEquals(fixture.getValue("catalog"), Json.parseToJsonElement(ClickMan.catalog(ReferenceEvent.serializer())))
    }

    /** The Kotlin declarations of ReferenceEvents.kt say what the fixture's `declarations` say. */
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun catalogDeclarations() {
        val declared = ReferenceEvent.serializer().descriptor.getElementDescriptor(1).elementDescriptors.toList()
        val declarations = fixture("catalog.json").children("declarations")
        assertEquals(declarations.map { it.string("event") }, declared.map { it.serialName.substringAfterLast('.') })
        for ((declaration, descriptor) in declarations.zip(declared)) {
            val event = declaration.string("event")
            val lines = declaration.getValue("description").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(lines, descriptor.annotations.filterIsInstance<Description>().single().lines.toList(), event)
            val properties = declaration.children("properties")
            assertEquals(properties.map { it.string("field") }, descriptor.elementNames.toList(), event)
            properties.forEachIndexed { index, property ->
                val annotations = descriptor.getElementAnnotations(index)
                assertEquals(property.string("description"), annotations.filterIsInstance<Description>().single().lines.single(), "$event.${property.string("field")}")
                assertEquals(property.getValue("required").jsonPrimitive.boolean, !descriptor.getElementDescriptor(index).isNullable, "$event.${property.string("field")}")
                assertEquals(property.string("type") == "free text", annotations.any { it is FreeText }, "$event.${property.string("field")}")
            }
        }
    }

    @Test
    fun catalogSamples() = withStore("catalog-samples") { queue, tracker ->
        val samples = fixture("catalog.json").children("samples")
        assertTrue(samples.isNotEmpty())
        for (sample in samples) {
            val event = referenceEvent(sample.getValue("value").jsonObject)
            val typed = TypedEvent.of(ReferenceEvent.serializer(), event)
            tracker.track(typed.name, typed.properties)
            val body = queue.bodies().last()
            assertEquals(sample.getValue("event"), body.getValue("event"), sample.string("name"))
            assertEquals(sample.getValue("properties"), body.getValue("properties"), sample.string("name"))
        }
    }

    private fun referenceEvent(value: JsonObject): ReferenceEvent = when (val event = value.string("event")) {
        "ExportCompleted" -> ReferenceEvent.ExportCompleted(
            format = entry(value.string("format")),
            mode = entry(value.string("mode")),
            tracks = value.int("tracks"),
            durationSeconds = (value["durationSeconds"] as? JsonPrimitive)?.double,
            normalized = (value["normalized"] as? JsonPrimitive)?.boolean,
            title = value.text("title"),
        )
        "ProjectCreated" -> ReferenceEvent.ProjectCreated
        else -> fail("no reference event $event")
    }

    /** The enum entry a sample names in the declaration's spelling, such as `perTrack` for `PER_TRACK`. */
    private inline fun <reified T : Enum<T>> entry(value: String): T =
        enumValues<T>().single { it.name.replace("_", "").equals(value, ignoreCase = true) }

    @Test
    fun standardEvents() {
        val file = File(System.getProperty("clickman.standardEvents"))
        val names = Json.parseToJsonElement(file.readText()).jsonObject.children("events").map { it.string("name") }
        assertEquals(names.toSet(), STANDARD_EVENTS)
    }

    private fun Duration.seconds(): Double = toDouble(DurationUnit.SECONDS)

    private fun assertClose(expected: Double, actual: Double, message: String) {
        assertTrue(abs(expected - actual) < 0.001, "$message: $actual is not $expected")
    }

    private companion object {
        val VISITOR = Regex("visitor (\\d+)")
    }
}
