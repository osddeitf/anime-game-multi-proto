package org.anime_game_servers.multi_proto.gi.data.miliastra

import org.anime_game_servers.core.base.Version
import org.anime_game_servers.core.base.annotations.AddedIn
import org.anime_game_servers.core.base.annotations.proto.ProtoEnum

@AddedIn(Version.GI_6_1_0)
@ProtoEnum("BeyondExpressionInfo")
internal enum class ExpressionType {
    EXPRESSION_NONE,
    EXPRESSION_EMOJI,
    EXPRESSION_POSE,
    EXPRESSION_SUIT_POSE,
    EXPRESSION_GOLDEN_POSE,
}
