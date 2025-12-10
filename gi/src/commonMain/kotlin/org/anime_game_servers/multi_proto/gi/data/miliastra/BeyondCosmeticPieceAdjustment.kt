package org.anime_game_servers.multi_proto.gi.data.miliastra

import org.anime_game_servers.core.base.Version
import org.anime_game_servers.core.base.annotations.AddedIn
import org.anime_game_servers.core.base.annotations.proto.ProtoModel

@AddedIn(Version.GI_6_1_0)
@ProtoModel
internal interface BeyondCosmeticPieceAdjustment {
    var adjustKey: Int
    var adjustValue: Int
}
