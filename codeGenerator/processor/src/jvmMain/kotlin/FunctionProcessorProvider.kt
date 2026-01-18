import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import common.BaseProcessor

class FunctionProcessorProvider(): SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): BaseProcessor {
        val isDynamicRuntime = environment.options["isDynamicRuntime"] == "true"
        environment.logger.warn("Using dynamic runtime: $isDynamicRuntime")

        return if (isDynamicRuntime) {
            dynamic.FunctionProcessor(environment.codeGenerator, environment.logger, environment.options)
        }
        else {
            static.FunctionProcessor(environment.codeGenerator, environment.logger, environment.options)
        }
    }
}