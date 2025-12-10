package org.anime_game_servers.multi_proto.gi.data.miliastra

import org.anime_game_servers.core.base.Version
import org.anime_game_servers.core.base.annotations.AddedIn
import org.anime_game_servers.core.base.annotations.proto.CommandType
import org.anime_game_servers.core.base.annotations.proto.ProtoCommand

@AddedIn(Version.GI_6_1_0)
@ProtoCommand(CommandType.CLIENT)
internal interface BeyondUpdateCosmeticPlanReq {
    var cosmeticPlan: BeyondCosmeticPlan
    var avatarGuid: Long
    var updateToken: Int
    var elapsedTime: Int
}
