package common

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getKotlinClassByName
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import common.Generator.ClassInfo
import common.Generator.ProtoData
import org.anime_game_servers.core.base.annotations.AddedIn
import org.anime_game_servers.core.base.annotations.RemovedIn
import java.io.OutputStream

@OptIn(KspExperimental::class)
abstract class BaseProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    fun KSClassDeclaration.getProtoAnnotation() = annotations.firstOrNull { it.shortName.asString().startsWith("Proto") }
    fun KSAnnotation.getParentClass() = arguments.firstOrNull { it.name?.asString() == "parentClass" }?.value?.toString()
    fun KSAnnotation.getAltNames() = (arguments.firstOrNull { it.name?.asString() == "alternativeNames" }?.value) as? List<String>
    fun String.getProtoName(parameterName: String?) = parameterName?.let { if(it.isBlank()) this else "$it.$this" } ?: this
    fun KSAnnotation.getVersionName() = (arguments.firstOrNull()?.value as? KSClassDeclaration)?.simpleName?.asString() ?:""

    abstract fun getTargetPackageName(symbol: KSClassDeclaration): String

    fun Resolver.getClassSymbolsByAnnotation(annotationName: String): Sequence<KSClassDeclaration>{
        return getSymbolsWithAnnotation(annotationName)
            .filterIsInstance<KSClassDeclaration>()
    }

    fun addBaseTypesToCache(resolver: Resolver, fullClassInfoCache: MutableMap<KSType, ClassInfo>){
        addBaseTypeToCache("Float", resolver, fullClassInfoCache)
        addBaseTypeToCache("Int", resolver, fullClassInfoCache)
        addBaseTypeToCache("UInt", resolver, fullClassInfoCache)
        addBaseTypeToCache("String", resolver, fullClassInfoCache)
        addBaseTypeToCache("Char", resolver, fullClassInfoCache)
        addBaseTypeToCache("Double", resolver, fullClassInfoCache)
        addBaseTypeToCache("Long", resolver, fullClassInfoCache)
    }
    fun addBaseTypeToCache(classname: String, resolver: Resolver, fullClassInfoCache: MutableMap<KSType, ClassInfo>){
        val kClass = resolver.getKotlinClassByName("kotlin.$classname") ?: run {
            logger.error("Unable to find class $classname")
            return
        }

        val info = ClassInfo(classname, "kotlin", kClass, emptySet(), emptySet(), false)
        fullClassInfoCache[kClass.asStarProjectedType()] = info
    }

    fun getClassInfo(symbols: Sequence<KSClassDeclaration>,
                     fullClassInfoCache: MutableMap<KSType, ClassInfo>,
                     compileProtoMap: Map<String, MutableSet<ProtoData>>
    ): Map<KSType, ClassInfo> {
        val typeMap = mutableMapOf<KSType, ClassInfo>()
        symbols.forEach {
            val annotation = it.getProtoAnnotation()
            val parentClassName = annotation?.getParentClass()
            val altNames = annotation?.getAltNames() ?: emptyList()
            val name = it.simpleName.asString()
            val names = (altNames+name).toSet()

            val protoNames = mutableListOf(name.getProtoName(parentClassName))
            protoNames.addAll(altNames.map { it.getProtoName(parentClassName) })
            val protoName = parentClassName?.let { if(it.isBlank()) name else "$it.$name" } ?: name
            logger.info("Found $name with protoName $protoName")
            val versionProtoSet = protoNames.firstNotNullOfOrNull { protoName ->
                return@firstNotNullOfOrNull compileProtoMap[protoName]
            } ?: run {
                val addedIn = it.annotations.firstOrNull { it.shortName.asString() == AddedIn::class.simpleName }?.getVersionName()
                val removedIn = it.annotations.firstOrNull { it.shortName.asString() == RemovedIn::class.simpleName }?.getVersionName()
                logger.warn("No proto found for $name addedIn $addedIn removedIn $removedIn")
                mutableSetOf()
            }
            val targetPackage = it.packageName.asString().replaceFirst("data.","messages.")
            val dependencies = mutableSetOf<KSFile>().apply{
                add(it.containingFile!!)
                versionProtoSet.mapTo(this) { it.classDeclaration.containingFile!! }
            }

            val info = ClassInfo(name, targetPackage, it, dependencies, versionProtoSet, true, names = names)
            logger.info("ClassInfo $info")

            typeMap[it.asStarProjectedType()] = info
            fullClassInfoCache[it.asStarProjectedType()] = info
        }
        return typeMap
    }

    fun generateFiles(generator: Generator, classInfoMap: Map<KSType, ClassInfo>){
        logger.info("generating files: ${classInfoMap.size}")
        classInfoMap.values.forEach { classInfo ->
            val dependingfiles = classInfo.protoSet.map { it.classDeclaration.containingFile!! } + classInfo.definition.containingFile!!
            val file: OutputStream = codeGenerator.createNewFile(
                // Make sure to associate the generated file with sources to keep/maintain it across incremental builds.
                // Learn more about incremental processing in KSP from the official docs:
                // https://kotlinlang.org/docs/ksp-incremental.html
                dependencies = Dependencies(true, sources = dependingfiles.toTypedArray()),
                packageName = classInfo.packageName,
                fileName = classInfo.name
            )
            //logger.warn("generating ${classInfo.name} ${classInfo.packageName} ${classInfo.definition.containingFile} ${classInfo.dependencies?.joinToString { it.filePath }}")
            generator.createClassForProto(file, classInfo)
        }
    }
}
