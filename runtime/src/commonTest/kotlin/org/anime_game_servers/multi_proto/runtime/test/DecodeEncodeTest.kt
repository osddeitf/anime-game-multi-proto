package org.anime_game_servers.multi_proto.runtime.test

import org.anime_game_servers.multi_proto.gi.messages.ability.AbilityInvokeArgument
import org.anime_game_servers.multi_proto.gi.messages.ability.AbilityInvokeEntry
import org.anime_game_servers.multi_proto.gi.messages.ability.ModifierAction
import org.anime_game_servers.multi_proto.gi.messages.ability.meta.AbilityMetaModifierChange
import org.anime_game_servers.multi_proto.gi.messages.battle.ForwardType
import org.anime_game_servers.multi_proto.gi.messages.general.Retcode
import org.anime_game_servers.multi_proto.gi.messages.general.Vector
import org.anime_game_servers.multi_proto.gi.messages.general.entity.SceneReliquaryInfo
import org.anime_game_servers.multi_proto.gi.messages.scene.EnterSceneReadyRsp
import org.anime_game_servers.multi_proto.gi.messages.scene.entity.MotionInfo
import org.anime_game_servers.multi_proto.gi.messages.scene.entity.ProtEntityType
import org.anime_game_servers.multi_proto.gi.messages.scene.entity.SceneAvatarInfo
import org.anime_game_servers.multi_proto.gi.messages.scene.entity.SceneEntityInfo
import org.anime_game_servers.core.base.Version
import org.anime_game_servers.multi_proto.runtime.common.ProtoMappingConfig
import org.anime_game_servers.multi_proto.runtime.engine.EngineLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Per-field e2e: instantiate a gi model with a small number of fields set, encode, decode, compare.
 * Each case picks a descriptor (plain = deobfuscated, no mapping; obf = obfuscated, needs a sub-mapping)
 * and slices only the names it touches out of the full mapping/encryption.
 *
 * Cases skip until you drop the fixtures into config/test/ (see Fixtures.kt). These two are templates —
 * adjust the model/fields and the subMapping(...) names to match the real descriptors, then add one case
 * per field kind toward full coverage.
 */
class DecodeEncodeTest {

    @Test
    fun enterSceneReadyRsp_plain() {
        if (!fixturesPresent()) return
        val rt = buildRuntime(plainDesc) // deobfuscated names match the model — no mapping needed
        val original = EnterSceneReadyRsp(Retcode.RET_SUCC, enterSceneToken = 1111)
        assertEquals(original, rt.roundTrip(original))
    }

    @Test
    fun enterSceneReadyRsp_obfuscated() {
        if (!fixturesPresent()) return
        val rt = buildRuntime(
            obfDesc,
            mapping = subMapping("EnterSceneReadyRsp", "retCode", "enterSceneToken", "Retcode", "RET_SUCC"),
        )
        val original = EnterSceneReadyRsp(Retcode.RET_SUCC, enterSceneToken = 1111)
        assertEquals(original, rt.roundTrip(original))
    }

    @Test
    fun sceneEntityInfo_obfuscated() {
        if (!fixturesPresent()) return
        val rt = buildRuntime(
            obfDesc,
            mapping = subMapping(
                "SceneEntityInfo", "entityId", "entityType", "entity", "motion_info", 
                "MotionInfo", "pos", "rot",
                "Vector", "x", "y", "z",
                "SceneAvatarInfo", "avatar_id", "guid", "equip_id_list", "skill_level_map", "reliquary_list",
                "SceneReliquaryInfo", "item_id", "level"
            )
        )
        val entityInfo = SceneEntityInfo(
            entityId = 4196017,
            entityType = ProtEntityType.PROT_ENTITY_AVATAR,
            motionInfo = MotionInfo(
                pos = Vector(3348.5f, 104f, 9487.75f),
                rot = Vector(y = 75.25f)
            ),
            entity = SceneEntityInfo.Entity.Avatar(SceneAvatarInfo(
                avatarId = 10000123,
                guid = 3668614330982730847,
                equipIdList = listOf(81544, 81553, 81534, 11409),
                skillLevelMap = mapOf(11235 to 5, 11231 to 2, 11232 to 4),
                reliquaryList = listOf(
                    SceneReliquaryInfo(
                        itemId = 81544,
                        guid = 3668614330981632019,
                        level = 9,
                    ),
                    SceneReliquaryInfo(
                        itemId = 81553,
                        guid = 3668614330981884198,
                        level = 17,
                    )
                )
            ))
        )
        assertEquals(entityInfo, rt.roundTrip(entityInfo))
    }

    @Test
    fun abilityInvokeEntry_obfuscated() {
        if (!fixturesPresent()) return
        val rt = buildRuntime(
            obfDesc,
            mapping = subMapping(
                "AbilityInvokeEntry", "argumentType", "forwardType", "entityId", "abilityData",
                "AbilityMetaModifierChange", "action", "modifierLocalId",
            ),
        )
        val abilityData = AbilityMetaModifierChange(
            action = ModifierAction.ADDED,
            modifierLocalId = 3,
        )
        val original = AbilityInvokeEntry(
            argumentType = AbilityInvokeArgument.ABILITY_META_MODIFIER_CHANGE,
            forwardType = ForwardType.FORWARD_TO_ALL_EXCEPT_CUR,
            entityId = 4196019,
            abilityData = rt.encodeToByteArray("AbilityMetaModifierChange", abilityData)
        )
        val decoded = rt.roundTrip(original)
        val empty = ByteArray(0)
        assertEquals(
            original.copy(abilityData = empty),
            decoded.copy(abilityData = empty),
        )
        assertEquals(
            abilityData,
            rt.decodeFromByteArray("AbilityMetaModifierChange", decoded.abilityData),
        )
    }

    /**
     * proto3 field presence. In proto3 a scalar/string/enum/repeated/map field has *implicit* presence: it
     * is never "absent" on the wire — an omitted field decodes to its default (0 / "" / false / empty / the
     * enum's zero-value). Only message fields and oneofs have *explicit* presence and can be genuinely unset.
     *
     * The generated models encode this exactly: Kotlin makes scalars/enum/list/map non-null (with defaults)
     * and messages/oneofs nullable; the TS .d.ts mirrors the split as required vs optional (`?`). This test
     * locks in the runtime side of that contract — decoding an EMPTY payload must yield all-defaults (incl.
     * the enum's proto3 zero-value, subsuming the old per-enum default check), with only message/oneof null.
     */
    @Test
    fun proto3Presence_emptyPayloadFillsDefaultsNotNulls() {
        if (!fixturesPresent()) return
        // plain.desc stores the real proto enum value names (the @AltName form, e.g. PROT_ENTITY_TYPE_NONE);
        // @AltName isn't resolved by the runtime itself, so map the zero-value Kotlin name to its proto name
        // for the enum to bind. (Field names need no mapping — snake_case normalization handles those.)
        val rt = buildRuntime(
            plainDesc,
            mapping = ProtoMappingConfig(
                enums = mapOf("ProtEntityType" to mapOf("PROT_ENTITY_NONE" to "PROT_ENTITY_TYPE_NONE")),
            ),
        )
        val info = rt.decodeFromByteArray("SceneEntityInfo", byteArrayOf()) as SceneEntityInfo

        // scalars: always present, defaulted (never null)
        assertEquals(0, info.entityId)
        assertEquals("", info.name)
        assertEquals(0, info.lifeState)
        // enum: present, defaulted to the proto3 zero-value (PROT_ENTITY_NONE), NOT the UNRECOGNISED sentinel
        assertEquals(ProtEntityType.PROT_ENTITY_NONE, info.entityType)
        // repeated & map: present, empty
        assertTrue(info.propList.isEmpty())
        assertTrue(info.tagList.isEmpty())
        assertTrue(info.propMap.isEmpty())
        assertTrue(info.fightPropMap.isEmpty())
        // message & oneof: the only kinds with explicit presence → null when absent
        assertNull(info.motionInfo)
        assertNull(info.abilityInfo)
        assertNull(info.entity)

        // Inverse direction: a model left at its defaults encodes to ZERO bytes — proto3 omits default-valued
        // scalars/enums on the wire, which is exactly why decode must re-materialize them as defaults above.
        assertEquals(0, rt.encodeToByteArray("SceneEntityInfo", SceneEntityInfo()).size)
    }

    /** Collects engine debug/warn lines so a test can assert which fields/enums were skipped or missed. */
    private class CapturingLogger : EngineLogger {
        val debugs = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        override fun debug(message: () -> String) { debugs += message() }
        override fun warn(message: () -> String) { warnings += message() }
    }

    /**
     * Version-aware resolution (@RemovedIn, field level). The version-agnostic registry carries fields that
     * exist only in older versions; the modern plain.desc has none of them. Without a version the engine logs
     * a "no proto field" warning for each (noise); told it's decoding a modern version, it skips them silently.
     */
    @Test
    fun versionAware_skipsRemovedFields_silencesWarnings() {
        if (!fixturesPresent()) return
        // SceneEntityInfo references ProtEntityType; map its zero-value so the enum binds (see proto3 test).
        val mapping = ProtoMappingConfig(
            enums = mapOf("ProtEntityType" to mapOf("PROT_ENTITY_NONE" to "PROT_ENTITY_TYPE_NONE")),
        )
        // questInfo was @RemovedIn GI_0_9_0; propMap/fightPropMap @RemovedIn GI_1_0_0 — gone from any modern desc.
        val removed = listOf("SceneEntityInfo.questInfo", "SceneEntityInfo.propMap", "SceneEntityInfo.fightPropMap")

        // Baseline (no version → legacy behavior): the removed-field warnings fire, proving they're genuinely
        // absent from plain.desc and that filtering is what suppresses them (not a coincidental match).
        val unaware = CapturingLogger()
        buildRuntime(plainDesc, mapping = mapping).also { it.logger = unaware }
            .decodeFromByteArray("SceneEntityInfo", byteArrayOf())
        removed.forEach { f -> assertTrue(unaware.warnings.any { it.contains(f) }, "expected baseline warning for $f") }

        // Version-aware (GI_6_5_0): those fields fall outside their @RemovedIn range → skipped, no warning.
        val aware = CapturingLogger()
        val rt = buildRuntime(plainDesc, mapping = mapping, version = Version.GI_6_5_0).also { it.logger = aware }
        val info = rt.decodeFromByteArray("SceneEntityInfo", byteArrayOf()) as SceneEntityInfo
        removed.forEach { f -> assertFalse(aware.warnings.any { it.contains(f) }, "did not expect warning for $f") }
        // ...and instead each skip is traced at debug level so a consumer can see why a field was dropped.
        removed.forEach { f -> assertTrue(aware.debugs.any { it.contains("skip field $f") }, "expected debug skip for $f") }

        // Decode integrity is preserved: kept fields still materialize their proto3 defaults, and the skipped
        // (removed) fields simply stay at their model defaults (null message / empty map).
        assertEquals(0, info.entityId)
        assertEquals(ProtEntityType.PROT_ENTITY_NONE, info.entityType)
        assertNull(info.motionInfo)
        assertNull(info.questInfo)
        assertTrue(info.propMap.isEmpty())
    }

    /**
     * Version-aware resolution (@AddedIn, behavioral). SceneAvatarInfo.skillLevelMap was @AddedIn GI_CB2, so it
     * exists in the descriptor but NOT in versions before it. Filtering at GI_CB1 must skip the field for both
     * encode and decode (it round-trips away); including its version (GI_6_5_0) round-trips it intact.
     */
    @Test
    fun versionAware_skipsFieldAddedLater_roundTrip() {
        if (!fixturesPresent()) return
        val mapping = subMapping("SceneAvatarInfo", "avatar_id", "guid", "skill_level_map")
        val original = SceneAvatarInfo(
            avatarId = 10000123,
            guid = 3668614330982730847,
            skillLevelMap = mapOf(11235 to 5, 11231 to 2),
        )

        // Included (GI_6_5_0 >= GI_CB2): skillLevelMap is kept → preserved through the round-trip.
        val included = buildRuntime(obfDesc, mapping = mapping, version = Version.GI_6_5_0)
        assertEquals(original.skillLevelMap, included.roundTrip(original).skillLevelMap)

        // Excluded (GI_CB1 < GI_CB2): the field is skipped on both encode and decode → comes back empty, while
        // the unannotated fields (avatarId, guid) still round-trip normally.
        val excluded = buildRuntime(obfDesc, mapping = mapping, version = Version.GI_CB1)
        val back = excluded.roundTrip(original)
        assertTrue(back.skillLevelMap.isEmpty())
        assertEquals(original.avatarId, back.avatarId)
        assertEquals(original.guid, back.guid)
    }
}
