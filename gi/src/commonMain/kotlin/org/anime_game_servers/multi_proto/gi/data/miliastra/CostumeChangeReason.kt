package org.anime_game_servers.multi_proto.gi.data.miliastra

import org.anime_game_servers.core.base.Version
import org.anime_game_servers.core.base.annotations.AddedIn
import org.anime_game_servers.core.base.annotations.proto.ProtoEnum

@AddedIn(Version.GI_6_1_0)
@ProtoEnum
internal enum class CostumeChangeReason {
    COSTUME_CHANGE_NONE,
    COSTUME_CHANGE_SAVE,
    COSTUME_CHANGE_SWITCH,
    COSTUME_CHANGE_DELETE,
    COSTUME_CHANGE_TRIAL_TIMEOUT,
    COSTUME_CHANGE_CLIENT_REQ,
}
