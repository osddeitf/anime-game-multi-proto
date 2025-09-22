package org.anime_game_servers.multi_proto.gi.data.team.avatar.upgrade

import org.anime_game_servers.core.base.Version
import org.anime_game_servers.core.base.annotations.AddedIn
import org.anime_game_servers.core.base.annotations.proto.CommandType.REQUEST
import org.anime_game_servers.core.base.annotations.proto.ProtoCommand
import org.anime_game_servers.multi_proto.gi.data.general.item.ItemParam

@AddedIn(Version.GI_4_3_0)
@ProtoCommand(REQUEST)
internal interface AvatarUpgradeReqV2 {
    var avatarGuid: Long
    var itemParamList: List<ItemParam>
}
