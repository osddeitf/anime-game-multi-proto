package org.anime_game_servers.multi_proto.runtime.test

import org.anime_game_servers.multi_proto.runtime.engine.ProtobufDescriptor
import org.anime_game_servers.multi_proto.runtime.engine.fs
import org.anime_game_servers.multi_proto.runtime.engine.loadDescriptorJs

// Node runs the test bundle from a Kotlin build dir, so the relative config/test/ paths won't resolve
// until we chdir to the repo root. TODO: replace this hardcoded stopgap with a Gradle-injected path
// (system property / env) so it isn't machine-specific.
private const val REPO_ROOT = "C:/Users/osddeitf/Desktop/grasscutter/dev/multiproto"

// Node global; referenced as Kotlin so REPO_ROOT is passed as a real argument (a js("...") string
// literal would not resolve the Kotlin const).
private external object process {
    fun chdir(path: String)
}

private var cwdSet = false
private fun ensureCwd() {
    if (cwdSet) return
    process.chdir(REPO_ROOT)
    cwdSet = true
}

private const val DIR = "config/test"

actual fun loadTestDescriptor(fileName: String): ProtobufDescriptor {
    ensureCwd()
    return loadDescriptorJs(fs.readFileSync("$DIR/$fileName"))
}

actual fun readTestResource(fileName: String): String {
    ensureCwd()
    return fs.readFileSync("$DIR/$fileName", "utf8")
}

actual fun fixturesPresent(): Boolean {
    ensureCwd()
    return fs.existsSync(DIR)
}
