package dynamic

import common.Generator.*
import common.BaseProcessor
import com.google.devtools.ksp.*
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import org.anime_game_servers.core.base.Version

const val BASE_ANNOTATION_PATH = "org.anime_game_servers.core.base.annotations"
const val BASE_PROTO_ANNOTATION_PATH = "$BASE_ANNOTATION_PATH.proto"
const val PROTO_MODEL_ANNOTATION = "$BASE_PROTO_ANNOTATION_PATH.ProtoModel"
const val PROTO_ENUM_ANNOTATION = "$BASE_PROTO_ANNOTATION_PATH.ProtoEnum"
const val PROTO_COMMAND_ANNOTATION = "$BASE_PROTO_ANNOTATION_PATH.ProtoCommand"
const val PROTO_VERSION_ENUM_ANNOTATION = "$BASE_PROTO_ANNOTATION_PATH.ProtoVersionEnum"
const val PROTO_ONE_OF_ANNOTATION = "$BASE_PROTO_ANNOTATION_PATH.OneOf"

//const val VERSION_ENUM_CLASS = "messages.VERSION"
val VERSION_ENUM_CLASS_NAME : String = Version::class.java.simpleName

/**
 * TODOs
 * - Add OneOf handling
 */
@OptIn(KspExperimental::class)
class FunctionProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) : BaseProcessor(codeGenerator, logger) {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.info("[time] start getting annotated classes")
        val wrapperEnumSymbols = resolver.getClassSymbolsByAnnotation(PROTO_ENUM_ANNOTATION)
        val wrapperModelSymbols = resolver.getClassSymbolsByAnnotation(PROTO_MODEL_ANNOTATION)
        val wrapperCommandSymbols = resolver.getClassSymbolsByAnnotation(PROTO_COMMAND_ANNOTATION)

        logger.info("[time] handling compiled protos classes")
        val compiledProtosMap = mutableMapOf<String, MutableSet<ProtoData>>()

        logger.info("[time] sorting stuff")
        // targetClassInfo based from our interfaces
        val classInfoCache = mutableMapOf<KSType, ClassInfo>()
        addBaseTypesToCache(resolver, classInfoCache)
        val protoEnums = getClassInfo(wrapperEnumSymbols, classInfoCache, compiledProtosMap)
        val protoModels = getClassInfo(wrapperModelSymbols, classInfoCache, compiledProtosMap)
        val protoCommands = getClassInfo(wrapperCommandSymbols, classInfoCache, compiledProtosMap)


        logger.info("[time] create generators")
        val enumGenerator = EnumGenerator(logger, resolver, classInfoCache)
        val dataGenerator = DataGenerator(logger, resolver, classInfoCache)
        val commandGenerator = CommandGenerator(logger, resolver, classInfoCache)

        logger.info("[time] generate enums")
        generateFiles(enumGenerator, protoEnums)
        logger.info("[time] generate models")
        generateFiles(dataGenerator, protoModels)
        logger.info("[time] generate commands")
        generateFiles(commandGenerator, protoCommands)

        logger.info("[time] generate registry aggregator")
        generateRegistryAggregator(
            models = (protoModels.values + protoCommands.values).toList(),
            enums = protoEnums.values.toList(),
        )

        if (options["emitTypescript"] == "true") {
            logger.info("[time] generate typescript types + plain-JS registry")
            generateTypescript(
                gen = dataGenerator,
                models = (protoModels.values + protoCommands.values).toList(),
                enums = protoEnums.values.toList(),
            )
        }


        /*val symbols = resolver.getSymbolsWithAnnotation("org.anime_game_servers.annotations.ProtoModel")
            .filterIsInstance<KSClassDeclaration>()
        val protos = resolver.getSymbolsWithAnnotation("pbandk.Export")
            .filterIsInstance<KSClassDeclaration>()
        symbols.forEach { symbol ->
            logger.warn("Found symbol: ${symbol.simpleName.asString()}")
            symbol.declarations.forEach {
                logger.warn("Found declarations: $it ")
                (it as? KSPropertyDeclaration)?.getter?.returnType?.resolve()?.declaration?.let { decl ->
                    logger.warn("Found return type: ${decl.simpleName.asString()}")
                }
                it.typeParameters.forEach { type ->
                    logger.warn("Found type: ${type.name.asString()}")
                }
            }
        }

        versionClass?.declarations?.forEach {
            logger.warn("Found versions: $it with namespace $it")
        }

        if (!symbols.iterator().hasNext()) return emptyList()

        val protosMap = mutableMapOf<String, MutableSet<BaseGenerator.ProtoData>>()
        protos.forEach {
            protosMap.compute(it.simpleName.asString()) { _, v ->
                if (v == null) {
                    mutableSetOf(BaseGenerator.ProtoData(it))
                } else {
                    v+=BaseGenerator.ProtoData(it)
                    v
                }
            }
        }

        symbols.forEach {
            val versionProtos = protosMap[it.simpleName.asString()]
            if (versionProtos != null) {
                logger.warn("Found ${versionProtos.size} protos for ${it.simpleName.asString()} ")
                versionProtos.forEach { proto ->
                    logger.warn("versions: ${proto.versionPackage}")
                }
                createClassForProto(resolver, it, versionProtos)
            }
        }*/
        //TODO
        logger.info("[time] finish")
        val unableToProcess = wrapperModelSymbols.filterNot { it.validate() }.toList()
        return unableToProcess
    }

    override fun getTargetPackageName(symbol: KSClassDeclaration): String {
        return symbol.packageName.asString().replaceFirst("data.","messages.")
    }

    /**
     * Emit allModels()/allEnums() listing every generated registration, consumed by
     * ProtoModelRegistry.getModels()/getEnums(). Aggregating so KSP regenerates on any model change.
     */
    private fun generateRegistryAggregator(models: List<ClassInfo>, enums: List<ClassInfo>) {
        val all = models + enums
        if (all.isEmpty()) return
        val files = all.mapNotNull { it.definition.containingFile }.distinct()

        val sb = StringBuilder()
        sb.append("package org.anime_game_servers.multi_proto.gi\n\n")
        sb.append("internal fun allModels(): List<org.anime_game_servers.multi_proto.core.registry.ModelRegistration> = buildList {\n")
        for (info in models) {
            sb.append("    add(").append(info.packageName).append('.').append(info.name).append("_Registration)\n")
        }
        sb.append("}\n\n")
        sb.append("internal fun allEnums(): List<org.anime_game_servers.multi_proto.core.registry.EnumRegistration> = buildList {\n")
        for (info in enums) {
            sb.append("    add(").append(info.packageName).append('.').append(info.name).append("_Registration)\n")
        }
        sb.append("}\n")

        val output = codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = true, sources = files.toTypedArray()),
            packageName = "org.anime_game_servers.multi_proto.gi",
            fileName = "GeneratedModelRegistry"
        )
        output.write(sb.toString().toByteArray())
        output.close()
    }

    /**
     * Emit the pure-JS gi data package: `index.d.ts` (TS enums + model interfaces typing the plain decoded
     * objects) and `registry.js` (the plain-data model/enum registry the runtime's PlainProtoRuntime ingests).
     * No Kotlin/stdlib — only plain data crosses the gi<->runtime boundary.
     */
    private fun generateTypescript(gen: DataGenerator, models: List<ClassInfo>, enums: List<ClassInfo>) {
        if (models.isEmpty() && enums.isEmpty()) return
        val files = (models + enums).mapNotNull { it.definition.containingFile }.distinct()

        val dts = StringBuilder("// Generated TypeScript types for multi-proto models (plain decoded objects).\n\n")
        enums.forEach { dts.append(gen.tsEnum(it)).append("\n") }
        models.forEach { dts.append(gen.tsModelInterface(it)).append("\n") }
        // Facade type for runtime.models(version): MultiprotoModels.<Model>.decode/encode.
        dts.append("/**\n")
        dts.append(" * Recursive partial: every field optional at every depth. Used for `encode` input so callers need\n")
        dts.append(" * only set the fields they care about — omitted scalars encode as the proto3 default (0/\"\"/false),\n")
        dts.append(" * omitted lists/maps as empty. `decode` output, by contrast, is the full (non-partial) type.\n")
        dts.append(" */\n")
        dts.append("type Builtin = Uint8Array | string | number | bigint | boolean | undefined;\n")
        dts.append("export type DeepPartial<T> = T extends Builtin ? T\n")
        dts.append("  : T extends Array<infer U> ? Array<DeepPartial<U>>\n")
        dts.append("  : T extends ReadonlyArray<infer U> ? ReadonlyArray<DeepPartial<U>>\n")
        dts.append("  : T extends {} ? { [K in keyof T]?: DeepPartial<T[K]> }\n")
        dts.append("  : Partial<T>;\n\n")
        dts.append("/**\n")
        dts.append(" * Decode/encode codec for one model `T` (operating on plain decoded objects). `decode` returns the\n")
        dts.append(" * full type (per proto3 presence, scalars/enums/lists/maps are always set); `encode` accepts a\n")
        dts.append(" * {@link DeepPartial} so you don't have to populate every field to build a message.\n")
        dts.append(" */\n")
        dts.append("export interface ModelCodec<T> {\n")
        dts.append("  decode(bytes: Uint8Array): T;\n")
        dts.append("  encode(model: DeepPartial<T>): Uint8Array;\n")
        dts.append("}\n\n")
        dts.append("/** Every model keyed by name, each a typed {@link ModelCodec}. Obtain one via {@link resolveModels}. */\n")
        dts.append("export interface MultiprotoModels {\n")
        models.forEach {
            // Nested messages are keyed by their parent-qualified name (e.g. "Parent.Child"); quote keys
            // that contain a dot so they're valid TS property names, and reference the qualified type.
            val q = gen.qualifiedName(it)
            val key = if (q.contains('.')) "\"$q\"" else q
            dts.append("  $key: ModelCodec<$q>;\n")
        }
        dts.append("}\n\n")
        dts.append("/**\n")
        dts.append(" * Build a typed model facade for a version, e.g.\n")
        dts.append(" * `resolveModels(rt, \"GI_6_5_0\").EnterSceneReadyRsp.decode(bytes)`.\n")
        dts.append(" *\n")
        dts.append(" * @param runtime A `PlainProtoRuntime` from the multi-proto-runtime package. Typed `any` on\n")
        dts.append(" * purpose: this data package is intentionally decoupled from the runtime package (no import\n")
        dts.append(" * dependency), so it cannot name that type — just pass your `PlainProtoRuntime` instance.\n")
        dts.append(" * @param version The version namespace, e.g. \"GI_6_5_0\".\n")
        dts.append(" */\n")
        dts.append("export declare function resolveModels(runtime: any, version: string): MultiprotoModels;\n\n")
        dts.append("/**\n")
        dts.append(" * Generated model/enum metadata. Opaque to consumers — pass it straight to the runtime's\n")
        dts.append(" * `PlainProtoRuntime` constructor; you never read it directly. Typed `any` because its shape is\n")
        dts.append(" * an internal contract between this package and the runtime, not a public API.\n")
        dts.append(" */\n")
        dts.append("export declare const registry: any;\n")
        writeGenerated(files, "index", "d.ts", dts.toString())

        val js = StringBuilder("// Generated plain-data model registry consumed by multi-proto-runtime PlainProtoRuntime.\n")
        js.append("export const registry = {\n  models: [\n")
        models.forEach { js.append("    ").append(gen.jsRegistryModel(it)).append(",\n") }
        js.append("  ],\n  enums: [\n")
        enums.forEach { js.append("    ").append(gen.jsRegistryEnum(it)).append(",\n") }
        js.append("  ],\n};\n")
        js.append("\nexport function resolveModels(runtime, version) { return runtime.models(version); }\n")
        writeGenerated(files, "registry", "js", js.toString())
    }

    private fun writeGenerated(files: List<KSFile>, fileName: String, extension: String, content: String) {
        val out = codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = true, sources = files.toTypedArray()),
            packageName = "",
            fileName = fileName,
            extensionName = extension,
        )
        out.write(content.toByteArray())
        out.close()
    }

    /*private fun createClassForProto(resolver: Resolver, classInfo: BaseGenerator.ClassInfo, generator: BaseGenerator) {
        val file: OutputStream = codeGenerator.createNewFile(
            // Make sure to associate the generated file with sources to keep/maintain it across incremental builds.
            // Learn more about incremental processing in KSP from the official docs:
            // https://kotlinlang.org/docs/ksp-incremental.html
            dependencies = Dependencies(false, *classInfo.dependencies.toTypedArray()),
            packageName = classInfo.packageName,
            fileName = classInfo.name
        )

        generator.createClassForProto(file, classInfo)
        file.close()
    }*/
}