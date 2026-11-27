@file:OptIn(ExperimentalSerializationApi::class)

package com.lockvoid.clickman

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/** A declared property as the catalog lists it (catalog.json). */
internal class PropertyDeclaration(
    val name: String,
    val description: String,
    /** `string`, `integer`, `number` or `boolean`. */
    val type: String,
    /** The only strings an enum property takes, or null. */
    val values: List<String>?,
    /** True on a free-text string. */
    val text: Boolean,
    val required: Boolean,
)

/**
 * A declared event: a `@Serializable` class or object with a [Description] whose
 * properties are booleans, integers (unsigned ones too), floating-point numbers,
 * enums or [FreeText] strings; a nullable property is optional. Names are the snake case of the class,
 * property and enum entry names or of their `@SerialName`.
 */
internal class EventDeclaration(val name: String, val description: String, val properties: List<PropertyDeclaration>) {
    companion object {
        private const val MAX_PROPERTIES = 128
        private val NAME = Regex("[a-z][a-z0-9_]*")

        /** The declaration of [descriptor], a class or an object; throws [IllegalArgumentException] for one the server would refuse. */
        fun of(descriptor: SerialDescriptor): EventDeclaration {
            val kind = descriptor.kind
            require(kind == StructureKind.CLASS || kind == StructureKind.OBJECT) {
                "${descriptor.serialName} is no event: declare events as @Serializable classes and objects"
            }
            val name = SnakeCase.of(descriptor.serialName.substringAfterLast('.'))
            require(isName(name, 200)) { "the event name \"$name\" of ${descriptor.serialName} ${nameRule(200)}" }
            require(name !in STANDARD_EVENTS) { "$name is a standard event ClickMan records itself; rename ${descriptor.serialName}" }
            val description = description(descriptor.annotations, "event $name")
            require(descriptor.elementsCount <= MAX_PROPERTIES) { "event $name has ${descriptor.elementsCount} properties; at most $MAX_PROPERTIES are allowed" }
            val properties = List(descriptor.elementsCount) { property(descriptor, it, name) }
            duplicate(properties.map { it.name })?.let { throw IllegalArgumentException("event $name declares the property $it twice") }
            return EventDeclaration(name, description, properties)
        }

        private fun property(event: SerialDescriptor, index: Int, eventName: String): PropertyDeclaration {
            val name = SnakeCase.of(event.getElementName(index))
            val where = "property $name of event $eventName"
            require(isName(name, 128)) { "the name \"$name\" of property ${event.getElementName(index)} of event $eventName ${nameRule(128)}" }
            val annotations = event.getElementAnnotations(index)
            val description = description(annotations, where)
            val text = annotations.any { it is FreeText }
            val type = event.getElementDescriptor(index)
            val values = if (type.kind == SerialKind.ENUM) values(type, where) else null
            val primitive = when {
                isUnsigned(type) -> "integer"
                else -> when (type.kind) {
                    PrimitiveKind.BOOLEAN -> "boolean"
                    PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "integer"
                    PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> "number"
                    PrimitiveKind.STRING, SerialKind.ENUM -> "string"
                    else -> throw IllegalArgumentException(
                        "$where is a ${type.serialName}, which a catalog cannot declare: use Boolean, Int, Long, Double, an enum or a @FreeText String",
                    )
                }
            }
            require(!text || type.kind == PrimitiveKind.STRING) { "$where is marked @FreeText but is no String" }
            require(text || type.kind != PrimitiveKind.STRING) {
                "$where is a String without @FreeText: declare its values with an enum or mark it @FreeText, since free text can carry personal data"
            }
            return PropertyDeclaration(name, description, primitive, values, text, required = !type.isNullable)
        }

        private fun values(enum: SerialDescriptor, where: String): List<String> {
            val values = enum.elementNames.map { SnakeCase.of(it) }
            require(values.size in 1..100) { "$where has ${values.size} values; 1 to 100 are allowed" }
            values.firstOrNull { it.codePointCount(0, it.length) !in 1..1024 }?.let {
                throw IllegalArgumentException("$where has the value \"$it\"; a value is 1 to 1024 characters")
            }
            duplicate(values)?.let { throw IllegalArgumentException("$where has the value $it twice") }
            return values
        }

        private fun description(annotations: List<Annotation>, where: String): String {
            val lines = annotations.filterIsInstance<Description>().singleOrNull()?.lines
                ?: throw IllegalArgumentException("$where has no @Description")
            val description = lines.flatMap { it.lines() }.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            require(description.codePointCount(0, description.length) in 1..1000) {
                "the @Description of $where is ${if (description.isEmpty()) "blank" else "longer than 1000 characters"}"
            }
            require(description.none { it.isISOControl() || it == ' ' || it == ' ' }) {
                "the @Description of $where is one line without control characters such as tabs"
            }
            return description
        }

        private fun isName(name: String, maxLength: Int): Boolean = name.length in 1..maxLength && NAME.matches(name)

        private fun nameRule(maxLength: Int) = "is not snake case: 1 to $maxLength of a-z, 0-9 and _, starting with a letter"
    }
}

private val UNSIGNED = setOf("kotlin.UByte", "kotlin.UShort", "kotlin.UInt", "kotlin.ULong")

/** `UInt` and the other unsigned integers, value classes the catalog declares as integers. */
private fun isUnsigned(type: SerialDescriptor): Boolean = type.isInline && type.serialName.removeSuffix("?") in UNSIGNED

/** The repeated element of [names], or null. */
private fun duplicate(names: List<String>): String? {
    val seen = HashSet<String>()
    return names.firstOrNull { !seen.add(it) }
}

/** The events the SDKs record themselves (protocol/standard-events.json); a catalog cannot redeclare them. */
internal val STANDARD_EVENTS = setOf("app_installed", "app_updated", "app_opened", "app_backgrounded", "page_view")

/** Names as the catalog writes them: `ExportCompleted`, `exportCompleted` and `EXPORT_COMPLETED` become `export_completed`. */
internal object SnakeCase {
    fun of(name: String): String {
        val snake = StringBuilder(name.length + 8)
        for ((index, char) in name.withIndex()) {
            when {
                !char.isLetterOrDigit() -> separate(snake)
                char.isUpperCase() -> {
                    val previous = name.getOrNull(index - 1)
                    val next = name.getOrNull(index + 1)
                    val startsWord = previous != null &&
                        (previous.isLowerCase() || previous.isDigit() || (previous.isUpperCase() && next != null && next.isLowerCase()))
                    if (startsWord) separate(snake)
                    snake.append(char.lowercaseChar())
                }
                else -> snake.append(char)
            }
        }
        return snake.trimEnd('_').toString()
    }

    private fun separate(snake: StringBuilder) {
        if (snake.isNotEmpty() && snake.last() != '_') snake.append('_')
    }
}

/** The catalog of declared events (docs/PROTOCOL.md, Event catalog), version 1. */
internal object Catalog {
    private const val MAX_EVENTS = 1000
    private val JSON = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun json(serializers: List<SerializationStrategy<*>>): String = JSON.encodeToString(JsonElement.serializer(), of(serializers))

    fun of(serializers: List<SerializationStrategy<*>>): JsonObject {
        val events = serializers.flatMap { events(it.descriptor) }.map(EventDeclaration::of)
        require(events.size <= MAX_EVENTS) { "the catalog declares ${events.size} events; at most $MAX_EVENTS are allowed" }
        duplicate(events.map { it.name })?.let { throw IllegalArgumentException("the catalog declares the event $it twice") }
        return buildJsonObject {
            put("version", 1)
            putJsonArray("events") {
                for (event in events) addJsonObject {
                    put("name", event.name)
                    put("description", event.description)
                    putJsonArray("properties") { for (property in event.properties) add(property.json()) }
                }
            }
        }
    }

    /** The event classes of [descriptor]: the class itself or the subclasses of a sealed hierarchy. */
    private fun events(descriptor: SerialDescriptor): List<SerialDescriptor> = when (descriptor.kind) {
        PolymorphicKind.SEALED -> descriptor.getElementDescriptor(1).elementDescriptors.toList()
        is PolymorphicKind -> throw IllegalArgumentException("${descriptor.serialName} is open: declare events as a sealed hierarchy")
        else -> listOf(descriptor)
    }

    private fun PropertyDeclaration.json(): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("type", type)
        values?.let { values -> putJsonArray("values") { values.forEach { add(it) } } }
        if (text) put("text", true)
        put("required", required)
    }
}

/** A declared event as it is tracked: its name and its properties, an optional one left out when null. */
internal class TypedEvent(val name: String, val properties: JsonObject) {
    companion object {
        fun <E> of(serializer: SerializationStrategy<E>, event: E): TypedEvent {
            val encoder = PropertiesEncoder()
            encoder.encodeSerializableValue(serializer, event)
            val declaration = encoder.declaration
                ?: throw IllegalArgumentException("${serializer.descriptor.serialName} is no event: declare events as @Serializable classes and objects")
            val properties = LinkedHashMap<String, JsonElement>()
            declaration.properties.forEachIndexed { index, property -> encoder.values[index]?.let { properties[property.name] = it } }
            return TypedEvent(declaration.name, JsonObject(properties))
        }
    }
}

/**
 * Records the properties of one event as its serializer writes them: the class of a
 * sealed hierarchy declares the event, a null is left out and an enum entry becomes its
 * catalog value. Anything a declaration cannot hold has failed [EventDeclaration.of] first.
 */
private class PropertiesEncoder : AbstractEncoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    var declaration: EventDeclaration? = null
        private set

    var values: Array<JsonElement?> = emptyArray()
        private set

    private var property = -1

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        if (descriptor.kind is PolymorphicKind) return this
        require(declaration == null) { "${descriptor.serialName} nested in an event cannot be tracked" }
        declaration = EventDeclaration.of(descriptor)
        values = arrayOfNulls(descriptor.elementsCount)
        return this
    }

    /** A polymorphic wrapper writes the class name, skipped, then the event itself. */
    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
        if (descriptor.kind is PolymorphicKind) return index == 1
        property = index
        return true
    }

    override fun encodeNull() = Unit

    override fun encodeBoolean(value: Boolean) = record(JsonPrimitive(value))

    override fun encodeByte(value: Byte) = record(JsonPrimitive(value))

    override fun encodeShort(value: Short) = record(JsonPrimitive(value))

    override fun encodeInt(value: Int) = record(JsonPrimitive(value))

    override fun encodeLong(value: Long) = record(JsonPrimitive(value))

    override fun encodeFloat(value: Float) {
        if (!value.isFinite()) throw ClickManException("$value is not a finite JSON number")
        record(JsonPrimitive(value))
    }

    override fun encodeDouble(value: Double) {
        if (!value.isFinite()) throw ClickManException("$value is not a finite JSON number")
        record(JsonPrimitive(value))
    }

    override fun encodeString(value: String) = record(JsonPrimitive(value))

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
        record(JsonPrimitive(checkNotNull(declared().properties[property].values)[index]))
    }

    override fun encodeValue(value: Any) {
        throw IllegalArgumentException("${value::class.simpleName} cannot be tracked as a property")
    }

    /** An unsigned integer arrives as the signed one of its bits. */
    override fun encodeInline(descriptor: SerialDescriptor): Encoder = if (isUnsigned(descriptor)) Unsigned() else this

    private inner class Unsigned : AbstractEncoder() {
        override val serializersModule: SerializersModule = this@PropertiesEncoder.serializersModule

        override fun encodeByte(value: Byte) = record(JsonPrimitive(value.toUByte().toInt()))

        override fun encodeShort(value: Short) = record(JsonPrimitive(value.toUShort().toInt()))

        override fun encodeInt(value: Int) = record(JsonPrimitive(value.toUInt().toLong()))

        override fun encodeLong(value: Long) = record(JsonPrimitive(value.toULong()))
    }

    private fun record(value: JsonPrimitive) {
        val declaration = declared()
        require(property in declaration.properties.indices) { "only the properties of an event class can be tracked" }
        values[property] = value
    }

    private fun declared(): EventDeclaration =
        declaration ?: throw IllegalArgumentException("only @Serializable classes and objects can be tracked as events")
}
