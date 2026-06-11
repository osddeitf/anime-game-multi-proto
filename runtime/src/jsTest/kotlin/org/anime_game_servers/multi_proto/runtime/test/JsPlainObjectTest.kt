package org.anime_game_servers.multi_proto.runtime.test

import org.anime_game_servers.multi_proto.runtime.common.ConfigPaths
import org.anime_game_servers.multi_proto.runtime.engine.ByteArrayProtoBufferFactory
import org.anime_game_servers.multi_proto.runtime.engine.JsModelDesc
import org.anime_game_servers.multi_proto.runtime.engine.JsModelRegistration
import org.anime_game_servers.multi_proto.runtime.engine.JsRegistry
import org.anime_game_servers.multi_proto.runtime.engine.PlainProtoRuntime
import org.anime_game_servers.multi_proto.runtime.engine.ProtoDescriptorRuntime
import org.anime_game_servers.multi_proto.runtime.engine.enumRegistrationsFromJs
import org.anime_game_servers.multi_proto.runtime.engine.modelRegistrationsFromJs
import org.khronos.webgl.Uint8Array
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * POC for the plain-JS-object approach: a plain-data registry (as a gi data package would ship) drives the
 * engine to decode/encode PLAIN JS OBJECTS — no Kotlin model classes. Proves the registry-as-data contract +
 * the create/read conversions without any cross-package Kotlin objects.
 */
class JsPlainObjectTest {

    // A plain-JS registry, exactly the shape the gi data package would export (pure data, no Kotlin).
    private val registry: JsRegistry = js(
        """({
            models: [{
                simpleName: "EnterSceneReadyRsp",
                properties: [
                    { name: "retCode", kind: "ENUM", modelTypeName: "Retcode" },
                    { name: "enterSceneToken", kind: "INT" }
                ]
            }],
            enums: [{
                simpleName: "Retcode",
                unrecognised: -1,
                entries: [ { name: "RET_SUCC", value: 0 }, { name: "RET_FAIL", value: 1 } ]
            }]
        })"""
    ).unsafeCast<JsRegistry>()

    @Test
    fun enterSceneReadyRsp_plainObject_roundTrip() {
        if (!fixturesPresent()) return
        val models = modelRegistrationsFromJs(registry).associateBy { it.simpleName }
        val enums = enumRegistrationsFromJs(registry).associateBy { it.simpleName }
        val rt = ProtoDescriptorRuntime(null, loadTestDescriptor("plain.desc"), ByteArrayProtoBufferFactory, models, enums)

        val original: Any = js("({ retCode: 0, enterSceneToken: 1111 })")
        val bytes = rt.encodeToByteArray("EnterSceneReadyRsp", original)
        val decoded = rt.decodeFromByteArray("EnterSceneReadyRsp", bytes)!!

        assertEquals(1111, decoded.asDynamic().enterSceneToken.unsafeCast<Int>())
        assertEquals(0, decoded.asDynamic().retCode.unsafeCast<Int>()) // RET_SUCC (proto3 enum-0 default)
    }

    @Test
    fun adapter_convertsListAndMap() {
        // synthetic model: a repeated<int> and a map<int,int> — exercises the create/read conversions
        // (Kotlin List <-> JS array, Kotlin Map <-> JS object) without needing the engine/descriptor.
        val desc: JsModelDesc = js(
            """({ simpleName: "Syn", properties: [
                { name: "ints", kind: "LIST", elementKind: "INT" },
                { name: "m", kind: "MAP", keyKind: "INT", elementKind: "INT" }
            ]})"""
        ).unsafeCast<JsModelDesc>()
        val reg = JsModelRegistration(desc)

        // create: engine values (Kotlin List/Map) -> plain JS object (JS array / JS object)
        val obj = reg.create(arrayOf(listOf(1, 2, 3), mapOf(7 to 8))).asDynamic()
        assertTrue(js("Array.isArray(obj.ints)").unsafeCast<Boolean>(), "ints should be a JS array")
        assertEquals(3, obj.ints.length.unsafeCast<Int>())
        assertEquals(2, obj.ints[1].unsafeCast<Int>())
        assertEquals(8, obj.m["7"].unsafeCast<Int>())

        // read: plain JS object -> engine values (Kotlin List/Map)
        val back = reg.read(obj)
        assertEquals(listOf(1, 2, 3), back[0])
        assertEquals(mapOf(7 to 8), back[1])
    }

    /**
     * Oneof round-trip through the PUBLIC PlainProtoRuntime facade, with a registry shaped exactly as codegen
     * now emits it: the case discriminator is camelCase (`"avatar"`, via getVariableName), matching the TS
     * `case: "avatar"`. Guards that the runtime stores/reads that same camelCase string end to end — the only
     * automated coverage of a oneof through the generated-registry shape (DecodeEncodeTest goes through the
     * Kotlin/PascalCase registrations instead).
     *
     * SceneEntityInfo.entity (avatar -> SceneAvatarInfo) is a real oneof present in plain.desc. The minimal
     * registry declares only the oneof + the one wrapped field it asserts on; unlisted wire fields are simply
     * ignored on decode and never written on encode. (Also exercises the trimmed registry shape: no dataIndex
     * — the adapter derives it from position — and no altNames.)
     */
    @Test
    fun oneOf_camelCaseDiscriminator_roundTripsViaFacade() {
        if (!fixturesPresent()) return
        val registry: JsRegistry = js(
            """({
                models: [
                    { simpleName: "SceneEntityInfo", properties: [
                        { name: "entity", kind: "ONEOF", oneOf: { cases: [
                            { caseName: "avatar", wrappedTypeName: "SceneAvatarInfo" }
                        ]}}
                    ]},
                    { simpleName: "SceneAvatarInfo", properties: [
                        { name: "avatarId", kind: "INT" }
                    ]}
                ],
                enums: []
            })"""
        ).unsafeCast<JsRegistry>()

        // descriptor pinned to the plain fixture (no {version} placeholder); mapping/encryption pointed at
        // absent paths so their config/test/ defaults (which are the OBF fixtures) don't load against plain.
        val rt = PlainProtoRuntime(
            registry,
            ConfigPaths(
                descriptor = "config/test/plain.desc",
                mapping = "config/test/__absent_for_plain__.json",
                encryption = "config/test/__absent_for_plain__.json",
            ),
        )

        val models: dynamic = rt.models("test")
        val codec: dynamic = models["SceneEntityInfo"]
        val original: Any = js("""({ entity: { case: "avatar", value: { avatarId: 10000123 } } })""")

        val bytes = codec.encode(original).unsafeCast<Uint8Array>()
        val decoded: dynamic = codec.decode(bytes)

        assertEquals("avatar", decoded.entity.case.unsafeCast<String>())            // camelCase discriminator
        assertEquals(10000123, decoded.entity.value.avatarId.unsafeCast<Int>())
    }
}
