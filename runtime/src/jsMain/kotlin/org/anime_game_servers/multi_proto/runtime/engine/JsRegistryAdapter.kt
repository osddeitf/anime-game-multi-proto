@file:OptIn(ExperimentalJsExport::class)

package org.anime_game_servers.multi_proto.runtime.engine

import org.anime_game_servers.multi_proto.core.registry.EnumEntry
import org.anime_game_servers.multi_proto.core.registry.EnumRegistration
import org.anime_game_servers.multi_proto.core.registry.ModelRegistration
import org.anime_game_servers.multi_proto.core.registry.OneOf
import org.anime_game_servers.multi_proto.core.registry.OneOfCase
import org.anime_game_servers.multi_proto.core.registry.Property
import org.anime_game_servers.multi_proto.core.registry.PropertyKind
import org.anime_game_servers.multi_proto.runtime.common.ConfigPaths
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

/**
 * Bridges a PLAIN-JS model registry (shipped by the gi data package — pure data, no Kotlin) into the
 * engine's [ModelRegistration]/[EnumRegistration] contract, with create/read producing and consuming PLAIN
 * JS OBJECTS instead of Kotlin model instances. Because these registrations are built here (inside runtime)
 * and only plain JS values cross the gi↔runtime boundary, there is no cross-package Kotlin interop.
 *
 * Decoded shape per kind: scalars as JS primitives (Long -> bigint), bytes -> Uint8Array, enum -> the numeric
 * value from the registry, repeated -> JS array, map -> plain JS object, nested message -> nested plain object,
 * oneof -> a tagged union `{ case, value }`.
 */

// ---- external shapes of the plain-JS registry (accessed by literal names) ----

external interface JsRegistry {
    val models: Array<JsModelDesc>
    val enums: Array<JsEnumDesc>
}
external interface JsModelDesc {
    val simpleName: String
    val properties: Array<JsPropertyDesc>
}
external interface JsPropertyDesc {
    val name: String
    val altNames: Array<String>?
    val kind: String
    val elementKind: String?
    val keyKind: String?
    val modelTypeName: String?
    val keyModelTypeName: String?
    val oneOf: JsOneOfDesc?
}
external interface JsOneOfDesc { val cases: Array<JsOneOfCaseDesc> }
external interface JsOneOfCaseDesc { val caseName: String; val wrappedTypeName: String }
external interface JsEnumDesc {
    val simpleName: String
    val entries: Array<JsEnumEntryDesc>
    val unrecognised: Int
}
external interface JsEnumEntryDesc { val name: String; val value: Int }

// ---- public entry points ----

fun modelRegistrationsFromJs(registry: JsRegistry): List<ModelRegistration> =
    registry.models.map { JsModelRegistration(it) }

fun enumRegistrationsFromJs(registry: JsRegistry): List<EnumRegistration> =
    registry.enums.map { JsEnumRegistration(it) }

/**
 * The JS entry point for the plain-object approach: hand it the gi data package's plain `registry` and the
 * config file locations; decode/encode by model `simpleName` to/from plain JS objects. Nothing Kotlin crosses
 * the package boundary — only the plain `registry` data and plain decoded objects.
 */
@JsExport
class PlainProtoRuntime(registry: JsRegistry, configPaths: ConfigPaths? = null) {
    private val delegate = JsProtoRuntime(
        modelRegistrationsFromJs(registry),
        enumRegistrationsFromJs(registry),
        configPaths,
    )

    /** Opt into logging (null = silent default); pass [ConsoleEngineLogger] to see engine errors. */
    fun setLogger(logger: EngineLogger?) = delegate.setLogger(logger)

    /** Decode wire bytes for `simpleName` (version namespace, e.g. "GI_6_5_0") into a plain JS object. */
    fun decode(version: String, simpleName: String, bytes: Uint8Array): Any? =
        delegate.decodeModel(version, simpleName, bytes)

    /** Encode a plain JS object for `simpleName` to wire bytes. */
    fun encode(version: String, simpleName: String, model: Any): Uint8Array? =
        delegate.encodeModel(version, simpleName, model)

    /**
     * Facade for a given version: returns an object where `models.<ModelName>.decode(bytes)` /
     * `.encode(obj)` proxy to [decode]/[encode]. Cast to the generated `MultiprotoModels` type for full typing,
     * e.g. `const M = rt.models("GI_6_5_0") as MultiprotoModels; M.SceneWeatherForecastRsp.decode(bytes)`.
     */
    fun models(version: String): dynamic {
        val rt = this
        val handler: dynamic = js("({})")
        handler.get = fun(_: dynamic, name: dynamic, _: dynamic): dynamic {
            val n = name as? String ?: return undefined
            val entry: dynamic = js("({})")
            entry.decode = { bytes: Uint8Array -> rt.decode(version, n, bytes) }
            entry.encode = { obj: Any -> rt.encode(version, n, obj) }
            return entry
        }
        return JsProxy(js("({})"), handler)
    }
}

/** The JS global `Proxy` constructor. */
@JsName("Proxy")
private external class JsProxy(target: Any, handler: Any)

// ---- registration implementations operating on plain JS objects ----

private fun newJsObject(): dynamic = js("({})")
private fun kindOf(name: String) = PropertyKind.valueOf(name)

class JsModelRegistration(desc: JsModelDesc) : ModelRegistration {
    override val simpleName: String = desc.simpleName
    // The registry omits per-property bookkeeping the position already implies: data index (the property's
    // position; create/read exchange values in this order) and an empty altNames (defaulted to none here).
    override val properties: List<Property> = desc.properties.map { p ->
        Property(
            name = p.name,
            altNames = p.altNames?.toList() ?: emptyList(),
            kind = kindOf(p.kind),
            elementKind = p.elementKind?.let { kindOf(it) },
            keyKind = p.keyKind?.let { kindOf(it) },
            modelTypeName = p.modelTypeName,
            keyModelTypeName = p.keyModelTypeName,
            oneOf = p.oneOf?.let { buildOneOf(it) },
        )
    }

    override fun create(values: Array<Any?>): Any {
        val o = newJsObject()
        properties.forEachIndexed { index, prop -> o[prop.name] = toJs(values.getOrNull(index), prop) }
        return o
    }

    override fun read(model: Any): Array<Any?> {
        val o = model.asDynamic()
        val out = arrayOfNulls<Any?>(properties.size)
        properties.forEachIndexed { index, prop -> out[index] = fromJs(o[prop.name], prop) }
        return out
    }
}

class JsEnumRegistration(desc: JsEnumDesc) : EnumRegistration {
    override val simpleName: String = desc.simpleName
    override val unrecognised: Any = desc.unrecognised
    override val entries: List<EnumEntry> =
        desc.entries.map { e -> EnumEntry(e.name, e.value, e.value == desc.unrecognised) }
}

private fun buildOneOf(d: JsOneOfDesc): OneOf {
    val cases = d.cases.map { c ->
        OneOfCase(c.caseName, c.wrappedTypeName, wrap = { v ->
            val o = newJsObject(); o["case"] = c.caseName; o["value"] = v; o
        })
    }
    return OneOf(
        cases = cases,
        caseNameOf = { w -> w.asDynamic()["case"].unsafeCast<String>() },
        unwrap = { w -> w.asDynamic()["value"] },
    )
}

// ---- value conversion: engine value <-> plain JS ----

/** engine value -> plain JS (for decode/create output) */
private fun toJs(value: Any?, prop: Property): Any? {
    if (value == null) return defaultForKind(prop.kind)
    return when (prop.kind) {
        PropertyKind.LIST -> (value as List<Any?>).map { toJsElement(it, prop.elementKind) }.toTypedArray()
        PropertyKind.MAP -> {
            val o = newJsObject()
            for ((k, v) in (value as Map<Any?, Any?>)) o[k.toString()] = toJsElement(v, prop.elementKind)
            o
        }
        PropertyKind.BYTE_ARRAY -> byteArrayToUint8(value as ByteArray)
        else -> value // INT/LONG/FLOAT/DOUBLE/BOOLEAN/STRING/ENUM(number)/DATA(plain obj)/ONEOF(tagged)
    }
}

/** plain JS -> engine value (for encode/read input) */
private fun fromJs(value: Any?, prop: Property): Any? {
    if (value == null || value == undefined) return null
    return when (prop.kind) {
        PropertyKind.LIST -> value.unsafeCast<Array<Any?>>().map { fromJsElement(it, prop.elementKind) }
        PropertyKind.MAP -> {
            val m = mutableMapOf<Any?, Any?>()
            val o = value.asDynamic()
            val keys = jsObjectKeys(o)
            for (k in keys) m[keyFromJs(k, prop.keyKind)] = fromJsElement(o[k], prop.elementKind)
            m
        }
        PropertyKind.BYTE_ARRAY -> uint8ToByteArray(value)
        else -> value
    }
}

private fun toJsElement(value: Any?, elementKind: PropertyKind?): Any? = when (elementKind) {
    PropertyKind.BYTE_ARRAY -> if (value == null) null else byteArrayToUint8(value as ByteArray)
    else -> value // scalar / nested plain object pass through
}

private fun fromJsElement(value: Any?, elementKind: PropertyKind?): Any? = when (elementKind) {
    PropertyKind.BYTE_ARRAY -> if (value == null) null else uint8ToByteArray(value)
    else -> value
}

private fun defaultForKind(kind: PropertyKind): Any? = when (kind) {
    PropertyKind.INT, PropertyKind.FLOAT, PropertyKind.DOUBLE -> 0
    PropertyKind.LONG -> 0L
    PropertyKind.BOOLEAN -> false
    PropertyKind.STRING -> ""
    PropertyKind.BYTE_ARRAY -> byteArrayToUint8(ByteArray(0))
    PropertyKind.LIST -> emptyArray<Any?>()
    PropertyKind.MAP -> newJsObject()
    PropertyKind.ENUM, PropertyKind.DATA, PropertyKind.ONEOF -> null
}

private fun keyFromJs(key: String, keyKind: PropertyKind?): Any = when (keyKind) {
    PropertyKind.INT -> key.toInt()
    PropertyKind.LONG -> key.toLong()
    PropertyKind.BOOLEAN -> key.toBoolean()
    else -> key
}

private fun jsObjectKeys(o: dynamic): Array<String> = js("Object.keys(o)").unsafeCast<Array<String>>()

internal fun byteArrayToUint8(b: ByteArray): Uint8Array {
    val i8 = b.unsafeCast<Int8Array>()
    return Uint8Array(i8.buffer, i8.byteOffset, i8.length)
}

internal fun uint8ToByteArray(v: Any): ByteArray {
    val u8 = v.unsafeCast<Uint8Array>()
    return Int8Array(u8.buffer, u8.byteOffset, u8.length).unsafeCast<ByteArray>()
}
