package org.anime_game_servers.multi_proto.gi.data.miliastra

import org.anime_game_servers.core.base.Version
import org.anime_game_servers.core.base.annotations.AddedIn
import org.anime_game_servers.core.base.annotations.proto.CommandType
import org.anime_game_servers.core.base.annotations.proto.ProtoCommand

@AddedIn(Version.GI_6_1_0)
@ProtoCommand(CommandType.NOTIFY)
internal interface AvatarCosmeticDataNotify {
    var cosmeticInfo: List<AvatarCosmeticInfo>
}