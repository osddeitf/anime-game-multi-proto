package org.anime_game_servers.multi_proto.runtime.test

import org.anime_game_servers.multi_proto.runtime.engine.ProtobufDescriptor

// The only platform-specific test code: parsing a .desc and reading a config file. Everything else
// (harness, selectors, assertions) lives in commonTest and runs identically on JVM and JS.
//
// Fixtures live under <repoRoot>/config/test/. JVM resolves them relative to the repo root (the Test
// task's workingDir, set in build.gradle.kts); JS chdir's there first (Node's cwd is a build dir).

/** Parse a binary FileDescriptorSet (.desc) from config/test/<fileName>. */
expect fun loadTestDescriptor(fileName: String): ProtobufDescriptor

/** Read a UTF-8 text fixture (mapping/encryption json) from config/test/<fileName>. */
expect fun readTestResource(fileName: String): String

/** True when config/test/ fixtures are present; e2e cases skip cleanly when they are not. */
expect fun fixturesPresent(): Boolean
