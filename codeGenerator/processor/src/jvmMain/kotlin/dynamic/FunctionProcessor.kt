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
        generateRegistryAggregator((protoEnums.values + protoModels.values + protoCommands.values).toList())


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
     * Emit a single aggregator with registerAllModels() that registers every generated model/enum
     * registration into ProtoModelRegistry. The JS runtime calls this once via register(). Marked
     * aggregating so KSP regenerates it whenever any model changes.
     */
    private fun generateRegistryAggregator(infos: List<ClassInfo>) {
        if (infos.isEmpty()) return
        val files = infos.mapNotNull { it.definition.containingFile }.distinct()

        val sb = StringBuilder()
        sb.append("package org.anime_game_servers.multi_proto.gi\n\n")
        sb.append("fun registerAllModels() {\n")
        for (info in infos) {
            sb.append("    org.anime_game_servers.multi_proto.core.registry.ProtoModelRegistry.register(")
                .append(info.packageName).append('.').append(info.name).append("_Registration)\n")
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