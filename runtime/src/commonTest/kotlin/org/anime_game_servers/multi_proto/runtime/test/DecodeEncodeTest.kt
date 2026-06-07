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
import kotlin.test.Test
import kotlin.test.assertEquals

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

    @Test
    fun defaultEnumValue() {
        if (!fixturesPresent()) return
        val rt = buildRuntime(
            obfDesc,
            mapping = subMapping(
                "AbilityMetaModifierChange", "action",
            ),
        )
        val modifierChange = rt.decodeFromByteArray("AbilityMetaModifierChange", byteArrayOf()) as AbilityMetaModifierChange
        assertEquals(modifierChange.action, ModifierAction.ADDED)
    }
}
