import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import static.FunctionProcessor

class FunctionProcessorProvider(): SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): FunctionProcessor {
        return FunctionProcessor(environment.codeGenerator, environment.logger, environment.options)
    }
}