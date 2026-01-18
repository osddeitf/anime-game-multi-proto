package org.anime_game_servers.multi_proto.runtime.reflection

import org.anime_game_servers.multi_proto.runtime.common.toPascalCase
import java.lang.reflect.Method
import kotlin.reflect.KType
import kotlin.reflect.jvm.jvmErasure
import kotlin.reflect.typeOf

private fun getSetterMethod(builderClass: Class<*>, field: String, type: KType): Method {
    val name = field.toPascalCase()
    val methodName = when (type.jvmErasure) {
        Map::class -> "putAll${name}"   // putAll(map of (key,proto))
        //        Map::class -> "put${name}"
        //        List::class -> "addAll${name}"
        List::class -> "add${name}"     // add(builder)
        else -> "set$name"
    }
    val overloads = builderClass.declaredMethods.filter { it.name == methodName }
    return when (overloads.size) {
        1 -> overloads.first()
        else -> overloads.firstOrNull {
            // in case of embedded, or list embedded
            // find the overload with Builder parameter
            it.parameterTypes.firstOrNull()?.simpleName == "Builder"
        } ?: error("Reflection failed to find an overload of proto setter for '$field' ")
    }
}

private fun getGetterMethod(protoClass: Class<*>, field: String, type: KType): Method? {
    val name = field.toPascalCase()
    val methodName = when (type.jvmErasure) {
        Map::class -> "get${name}Map"
        List::class -> "get${name}List"
        else -> "get$name"
    }
    return protoClass.declaredMethods.firstOrNull { it.name == methodName }
}

private fun getHasMethod(protoClass: Class<*>, field: String): Method? {
    val methodName = "has${field.toPascalCase()}"
    return runCatching { protoClass.getMethod(methodName) }.getOrNull()
}

/** Return (getter, setter, has) */
fun getPropertyMethods(protoClass: Class<*>, builderClass: Class<*>, field: String, alt: Set<String>?, type: KType): Triple<Method, Method, Method?> {
    val names = alt.orEmpty() + field
    for (name in names) {
        val getter = getGetterMethod(protoClass, name, type) ?: continue
        val setter = getSetterMethod(builderClass, name, type)
        val has = getHasMethod(protoClass, name)
        return Triple(getter, setter, has)
    }
    error("Reflection failed, missing proto field '$field'")
}

fun getOneOfCaseMethod(protoClass: Class<*>, field: String, alt: Set<String>?): Method {
    val names = alt.orEmpty() + field
    val method = names.firstNotNullOfOrNull {
        val methodName = "get${it.toPascalCase()}Case"
        protoClass.declaredMethods.firstOrNull { it.name == methodName }
    }
    return method ?: error("Reflection failed to find proto oneOf case method for '$field'")
}

fun getEnumValueMethod(protoClass: Class<*>): Method? {
    return getGetterMethod(protoClass, "number", typeOf<Int>())
}
