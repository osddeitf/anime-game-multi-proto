@file:OptIn(ExperimentalJsExport::class)
@file:Suppress("NON_EXPORTABLE_TYPE")

package org.anime_game_servers.multi_proto.gi

import org.anime_game_servers.core.base.Version
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

// Temporary re-export so TS/JS consumers can obtain a Version (an ags-core type) to pass into the
// generated models' encode/decode. Version itself is non-exportable (surfaces as `any` in .d.ts),
// but the instance is correct at runtime. Long-term: ags-core should publish its own typings.

/** Resolve a Version by its enum name, e.g. "GI_6_3_0". */
@JsExport
fun versionByName(name: String): Version = Version.valueOf(name)

/** All known version enum names (handy for discovery from JS). */
@JsExport
fun versionNames(): Array<String> = Version.entries.map { it.name }.toTypedArray()
