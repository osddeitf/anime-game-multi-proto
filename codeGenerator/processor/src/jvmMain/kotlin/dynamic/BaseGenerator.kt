package dynamic

import common.Generator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.*
import org.anime_game_servers.core.base.Version
import org.anime_game_servers.core.base.annotations.AddedIn
import org.anime_game_servers.core.base.annotations.RemovedIn
import java.io.OutputStream

/** Package of the compile-time model registry (in :base) consumed by the JS runtime. */
internal const val REGISTRY_PACKAGE = "org.anime_game_servers.multi_proto.core.registry"

//TODO keep AddedIn/RemovedIn annotations
abstract class BaseGenerator(
    logger: KSPLogger,
    resolver: Resolver,
    classInfoCache: MutableMap<KSType, ClassInfo>,
): Generator(logger, resolver, classInfoCache) {
    override fun createClassForProto(file: OutputStream, classInfo:ClassInfo) {
        // File-level opt-in for @JsExport (TS typings) + silence the per-field non-exportable noise
        // (Long is exportable; List/Map/oneof/Version degrade to `any`). No-op on JVM/native.
        file += "@file:kotlin.OptIn(kotlin.js.ExperimentalJsExport::class)\n"
        file += "@file:kotlin.Suppress(\"NON_EXPORTABLE_TYPE\")\n\n"
        file += "package ${classInfo.packageName}\n"
        addImports(file, classInfo)
        addConstructor(file, classInfo)
        addBody(file, classInfo)
        addEncodeMethods(file, classInfo)
        addCompanionObject(file, classInfo)
        addClosure(file, classInfo)
        addRegistration(file, classInfo)
        file.close()
    }

    open fun addImports(file:OutputStream, classInfo: ClassInfo){
        file += "import ${Version::class.java.canonicalName}\n"+
                "import ${AddedIn::class.java.canonicalName}\n"+
                "import ${RemovedIn::class.java.canonicalName}\n"+
                "import org.anime_game_servers.multi_proto.gi.ProtoModelRegistry\n"+
                "import kotlin.jvm.JvmStatic\n"+
                "import kotlin.jvm.JvmOverloads\n"
    }

    open fun addConstructor(file:OutputStream, classInfo:ClassInfo){
        //add the kdoc if it exists //TODO
        classInfo.definition.docString?.let {
            file += "/**${it.replace("\n","\n  * ")}*/\n"
        }
    }
    open fun addBody(file:OutputStream, classInfo:ClassInfo){

    }

    open fun addEncodeMethods(file:OutputStream, classInfo: ClassInfo){

    }

    open fun addParsingMethods(file:OutputStream, classInfo:ClassInfo){
    }

    open fun addCompanionObject(file: OutputStream, classInfo:ClassInfo){

        file.id(4) +="companion object {\n"
        addParsingMethods(file, classInfo)
        file.id(4) += "}\n"
    }

    open fun addClosure(file: OutputStream, classInfo: ClassInfo) {}

    /**
     * Emit a top-level registration object (after the model/enum) carrying the Kotlin-side metadata
     * the JS runtime needs in place of reflection. No-op by default; implemented for data/enum models.
     */
    open fun addRegistration(file: OutputStream, classInfo: ClassInfo) {}


    protected fun String.getVariableName():String{
        return this.snakeToLowerCamelCase().replaceFirstChar { it.lowercaseChar() }
    }
    protected fun String.getClassName():String{
        return this.snakeToLowerCamelCase().replaceFirstChar { it.uppercaseChar() }
    }

    protected fun OutputStream.id(indentation: Int): OutputStream {
        this += " ".repeat(indentation)
        return this
    }

    operator fun OutputStream.plusAssign(str: String) {
        this.write(str.toByteArray())
    }

    companion object {
        val snakeRegex = "_[a-zA-Z]".toRegex()
        fun String.snakeToLowerCamelCase(): String {
            return snakeRegex.replace(this) {
                it.value.replace("_","")
                    .uppercase()
            }
        }
    }
}