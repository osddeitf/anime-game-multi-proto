package org.anime_game_servers.multi_proto.core.registry

/**
 * Compile-time model metadata emitted by the KSP processor (dynamic runtime mode) to replace
 * JVM reflection on platforms that lack kotlin-reflect (notably Kotlin/JS).
 *
 * The registry carries ONLY the Kotlin-side structure that reflection would otherwise provide:
 * how to construct a model, how to read its properties, the kind of each property, the referenced
 * model/enum simple names, oneof wrapping/unwrapping, and enum entries. It deliberately does NOT
 * carry proto-side information (field numbers, wire types, zigzag, packed, map-entry, obfuscation,
 * encryption) — those come from the protobuf descriptor + mapping.json at runtime and are married
 * to this metadata by property name, exactly as the JVM reflection path does.
 *
 * The generated registrations reference the real classes/constructors/properties directly, so they
 * compile to plain field access and constructor calls on every target (no reflection). They are
 * inert on the JVM, whose runtime keeps using reflection.
 */
enum class PropertyKind {
    INT, LONG, FLOAT, DOUBLE, BOOLEAN, STRING, BYTE_ARRAY,
    ENUM, DATA, LIST, MAP, ONEOF
}

/** One case of a proto `oneof`, i.e. one subclass of the generated sealed wrapper. */
class OneOfCase(
    /** The case class simple name (e.g. "AddWindBulletNotify"); married to the proto oneof field by name. */
    val caseName: String,
    /** Simple name of the model type wrapped by this case (for marrying to the proto message). */
    val wrappedTypeName: String,
    /** Wrap an inner value into the case subclass instance. */
    val wrap: (Any?) -> Any,
)

class OneOf(
    val cases: List<OneOfCase>,
    /** Given a wrapper instance, return its active case class simple name. */
    val caseNameOf: (Any) -> String,
    /** Given a wrapper instance, return the wrapped value (`.value`). */
    val unwrap: (Any) -> Any?,
)

/**
 * Metadata for one constructor property. The position in [ModelRegistration.properties] IS the property's
 * data index: [ModelRegistration.create]/[ModelRegistration.read] exchange values in this same order, so no
 * explicit index is carried.
 */
class Property(
    val name: String,
    val altNames: List<String>,
    val kind: PropertyKind,
    /** LIST element kind / MAP value kind. */
    val elementKind: PropertyKind? = null,
    /** MAP key kind. */
    val keyKind: PropertyKind? = null,
    /** Referenced model/enum simple name for ENUM/DATA, or the value type of a LIST/MAP. */
    val modelTypeName: String? = null,
    /** Referenced model/enum simple name for a MAP key. */
    val keyModelTypeName: String? = null,
    val oneOf: OneOf? = null,
)

interface ModelRegistration {
    val simpleName: String
    val properties: List<Property>
    /** Build a model instance from values in constructor order; a null slot keeps the property default. */
    fun create(values: Array<Any?>): Any
    /** Read property values from a model instance, in constructor order. */
    fun read(model: Any): Array<Any?>
}

class EnumEntry(val name: String, val value: Any, val isUnrecognised: Boolean)

interface EnumRegistration {
    val simpleName: String
    /** Enum entries in declaration order, including UNRECOGNISED. */
    val entries: List<EnumEntry>
    val unrecognised: Any
}
