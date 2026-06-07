package org.anime_game_servers.multi_proto.runtime.test

import org.anime_game_servers.multi_proto.runtime.engine.ProtobufDescriptor
import org.anime_game_servers.multi_proto.runtime.engine.loadDescriptor
import java.io.File

private const val DIR = "config/test"

actual fun loadTestDescriptor(fileName: String): ProtobufDescriptor = loadDescriptor("$DIR/$fileName")

actual fun readTestResource(fileName: String): String = File("$DIR/$fileName").readText()

actual fun fixturesPresent(): Boolean = File(DIR).isDirectory
