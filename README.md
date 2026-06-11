# anime-game-multi-proto (WIP)
project to allow supporting multiple versions easier

TODOs:
* add more definitons for the protos
* improve code
* add actual protos for as many versions as possible
* apply annotations to created models
* add info about building and adding things to the README
* add info about using multiproto

# Getting Started
Add the [ags maven repository](https://mvn.animegameservers.org/#/releases) to your build script, e.g. for gradle kt: 
```kt
maven {
    name = "agsmvnReleases"
    url = uri("https://mvn.animegameservers.org/releases")
}
```

Then add the game module you want to use to your dependencies, e.g. for the gi module:
```kt
implementation("org.anime_game_servers.multi_proto:gi-jvm:0.2.32")
```

# Building the JavaScript packages

The JS distribution is two npm packages (Node, ESM):

1. **`@<scope>/multi-proto-runtime`** — the descriptor-driven decode/encode engine (compiled from Kotlin/JS).
2. **`@<scope>/multi-proto-gi`** — the GI models as **pure JS + TypeScript types** (`registry.js` + `index.d.ts`), with no Kotlin/stdlib.

A consumer installs both: the gi package ships the model `registry` (plain data) and the `.d.ts` types; you hand that `registry` to `PlainProtoRuntime` from the runtime package, then decode/encode plain objects (e.g. `resolveModels(rt, version).SomeModel.decode(bytes)`).

`-Pnpm.scope=<your-org>` sets the scoped package name (`@<your-org>/...`); omit it for an unscoped name. A JDK 17 toolchain is required — Gradle provisions Node automatically.

### Build

Runtime engine package:
```sh
./gradlew :runtime:jsNodeProductionLibraryDistribution -Pnpm.scope=<your-org>
# output: runtime/build/dist/js/productionLibrary/
```

gi pure-JS data package:
```sh
./gradlew :gi:assembleJsData \
  -Porg.anime_game_servers.dynamicRuntime=true \
  -Porg.anime_game_servers.emitTypescript=true \
  -Pnpm.scope=<your-org>
# output: gi/build/dist/js-data/   (registry.js, index.d.ts, package.json)
```

> ⚠️ The `build/dist/...` output folders are **not** cleaned between builds. Delete the target folder (or run `./gradlew clean`) before rebuilding so stale files from a previous build aren't published.

### Publish

Each output folder already contains a ready `package.json`. Publish from inside it (run each from the repo root):
```sh
(cd runtime/build/dist/js/productionLibrary && npm publish --access public)
(cd gi/build/dist/js-data && npm publish --access public)
```
(`--access public` is required the first time you publish a scoped package publicly. The package version comes from the Gradle build — set a real version before a non-snapshot release.)

Licensing
=====

This software library is licensed und the terms of the MIT license, with the exemptions noted below.

You can find a copy of the license in the [LICENSE file](LICENSE).

Exemptions:
* miHoYo and its subsidiaries are exempt from the MIT licensing and may instead license any source code authored for the AnimeGameServer projects under the Zero-Clause BSD license.
