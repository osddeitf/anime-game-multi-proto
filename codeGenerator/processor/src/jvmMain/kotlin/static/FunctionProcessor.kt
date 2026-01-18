package static

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.validate
import common.BaseProcessor
import common.Generator.ClassInfo
import common.Generator.ProtoData
import org.anime_game_servers.core.base.Version
import org.anime_game_servers.multi_proto.core.annotations.ModuleMetaData
import java.io.File

const val BASE_ANNOTATION_PATH = "org.anime_game_servers.core.base.annotations"
const val BASE_PROTO_ANNOTATION_PATH = "$BASE_ANNOTATION_PATH.proto"
const val PROTO_MODEL_ANNOTATION = "$BASE_PROTO_ANNOTATION_PATH.ProtoModel"
const val PROTO_ENUM_ANNOTATION = "$BASE_PROTO_ANNOTATION_PATH.ProtoEnum"
const val PROTO_COMMAND_ANNOTATION = "$BASE_PROTO_ANNOTATION_PATH.ProtoCommand"
const val PROTO_VERSION_ENUM_ANNOTATION = "$BASE_PROTO_ANNOTATION_PATH.ProtoVersionEnum"
const val PROTO_ONE_OF_ANNOTATION = "$BASE_PROTO_ANNOTATION_PATH.OneOf"

const val COMPILED_PROTO_ANNOTATION = "pbandk.Export"
val VERSION_ENUM_CLASS : String = Version::class.java.canonicalName


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

    fun generatePackageIdFile(logger: KSPLogger,
                              versionPackageIdMap: Map<String, PacketIdGenerator.PacketIdResult>){
        val basePacket = options["basePacket"] ?: ""
        val versionGenerator = PacketIdGenerator(logger, basePacket)
        versionPackageIdMap.forEach { (versionName, packageIdMaps) ->
            logger.info("generating packageIds files: ${packageIdMaps.dependencies.joinToString { it.toString() }}")
            val file = codeGenerator.createNewFile(
                // Make sure to associate the generated file with sources to keep/maintain it across incremental builds.
                // Learn more about incremental processing in KSP from the official docs:
                // https://kotlinlang.org/docs/ksp-incremental.html
                dependencies = Dependencies(true, *packageIdMaps.dependencies.toTypedArray()),
                packageName = "$basePacket.packet_id",
                fileName = versionName
            )
            logger.info("generating ${packageIdMaps.dependencies.joinToString { it.toString() }}")

            versionGenerator.createClassForProto(file, versionName, packageIdMaps)
        }

        val versions = versionPackageIdMap.keys
        logger.info("generating packageId version mapping: ${versions.size} ${versions.joinToString { it }}")
        val file = codeGenerator.createNewFile(
            // Make sure to associate the generated file with sources to keep/maintain it across incremental builds.
            // Learn more about incremental processing in KSP from the official docs:
            // https://kotlinlang.org/docs/ksp-incremental.html
            dependencies = Dependencies(true, *versionPackageIdMap.values.first().dependencies.toTypedArray()),
            packageName = "$basePacket.packet_id",
            fileName = "PackageIds"
        )

        versionGenerator.createClassForVersionMapper(file, versions)
    }

    override fun getTargetPackageName(symbol: KSClassDeclaration): String {
        return symbol.packageName.asString().replaceFirst("gi.data.", "proto.")
    }

    fun readPackageIds(resourcesBaseDir: File, versionClass: KSClassDeclaration) : Map<String, PacketIdGenerator.PacketIdResult>{
        val packageIdDir = File(resourcesBaseDir, "package_ids")
        val idFiles = packageIdDir.listFiles { dir, name ->
            name.endsWith(".csv")
        } ?: run {
            logger.error("[resources] Unable to read package_ids dir")
            return emptyMap()
        }

        val versionMap = mutableMapOf<String,PacketIdGenerator.PacketIdResult >()

        val versionsList = versionClass.declarations.filter { it is KSClassDeclaration }.map { prop ->
            prop.simpleName.asString()
        }
        val dependencies = mutableSetOf<KSFile>().apply{
            //add(versionClass.containingFile!!)
        }

        idFiles.forEach {
            logger.info("[resources] ${it.name}")
            val versionName = it.nameWithoutExtension
            if(!versionsList.contains(versionName)){
                logger.error("[resources] Unable to find version entry for $versionName in ${versionClass.simpleName.asString()}")
                return@forEach
            }
            val nameIdMap = mutableMapOf<String, Int>()
            val idNameMap = mutableMapOf<Int, String>()

            it.readLines().forEach readLine@{ line ->
                val split = line.split(",")
                if (split.size != 2) {
                    logger.error("[resources] Unable to parse line $line")
                    return@readLine
                }
                val packageName = split[0]
                val packageId = split[1].toIntOrNull() ?: run {
                    logger.error("[resources] Unable to parse packageId ${split[1]} for $packageName")
                    return@readLine
                }
                nameIdMap[packageName] = packageId
                idNameMap[packageId] = packageName
            }
            versionMap[versionName] = PacketIdGenerator.PacketIdResult( dependencies,  nameIdMap, idNameMap)
            // todo find way to add resources as dependency
        }
        return versionMap
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.info("[time] start getting annotated classes")
        val wrapperEnumSymbols = resolver.getClassSymbolsByAnnotation(PROTO_ENUM_ANNOTATION)
        val wrapperModelSymbols = resolver.getClassSymbolsByAnnotation(PROTO_MODEL_ANNOTATION)
        val wrapperCommandSymbols = resolver.getClassSymbolsByAnnotation(PROTO_COMMAND_ANNOTATION)

        val compiledProtos = resolver.getClassSymbolsByAnnotation(COMPILED_PROTO_ANNOTATION)

        val versionClassWorkaround = resolver.getClassSymbolsByAnnotation(ModuleMetaData::class.java.canonicalName).firstOrNull()
        val versionClass = resolver.getClassDeclarationByName(VERSION_ENUM_CLASS) ?: run {
            logger.error("[resources] Unable to find version class $VERSION_ENUM_CLASS")
            return emptyList()
        }

        val resourcesPath = versionClassWorkaround?.let {
            it.containingFile?.let { file ->
                val basePath = file.filePath.removeSuffix("kotlin/${file.fileName}")
                logger.warn("[resources] BasePath: $basePath")
                basePath+"resources"
            }?: run {
                logger.error("[resources] Unable to find resources dir fir packageIds")
                return emptyList()
            }
        }
        logger.info("[resources] $resourcesPath")

        val resourcesDir = resourcesPath?.let {
            File(resourcesPath)
        }
        //if (!resourcesDir?.exists()) {
            //logger.error("[resources] Unable to find resources dir fir packageIds")
            //return emptyList()
        //}

        val packageIdMaps = resourcesDir?.let {
            readPackageIds(resourcesDir, versionClass)
        }

        logger.info("[time] handling compiled protos classes")
        val compiledProtosMap = mutableMapOf<String, MutableSet<ProtoData>>()
        compiledProtos.forEach {
            val children = it.declarations.filterIsInstance<KSClassDeclaration>().filter { child ->
                child.superTypes.filter {
                    it.element.toString() == "Message" ||
                    it.element.toString() == "Enum"
                }.count() > 0
            }.map { child ->
                //logger.warn("Found child: ${child.simpleName.asString()}")
                val protoPackage = child.packageName.asString()
                ProtoData(child, protoPackage + "." + it.simpleName.asString(), protoPackage)
            }
            compiledProtosMap.compute(it.simpleName.asString()) { _, v ->
                if (v == null) {
                    mutableSetOf(ProtoData(it))
                } else {
                    v += ProtoData(it)
                    v
                }
            }
            children.forEach { child ->
                // TODO handle sub names for Mapping child classes
                /*if(child.className == "Status") {
                    logger.error("Found Status: ${child.className} ${child}")
                }*/
                compiledProtosMap.compute(it.simpleName.asString() + "." + child.className) { _, v ->
                    if (v == null) {
                        mutableSetOf(child)
                    } else {
                        v += child
                        v
                    }
                }
            }
        }

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

        packageIdMaps?.let {
            logger.info("[time] generate version")
            generatePackageIdFile(logger, it)
        }
        logger.info("[time] generate enums ${protoEnums.count()}")
        generateFiles(enumGenerator, protoEnums)
        logger.info("[time] generate models ${protoModels.count()}")
        generateFiles(dataGenerator, protoModels)
        logger.info("[time] generate commands")
        generateFiles(commandGenerator, protoCommands)

        //TODO
        logger.info("[time] finish")
        val unableToProcess = wrapperModelSymbols.filterNot { it.validate() }.toList()
        return unableToProcess
    }
}