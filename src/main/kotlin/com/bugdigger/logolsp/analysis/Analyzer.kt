package com.bugdigger.logolsp.analysis

import com.bugdigger.logolsp.analysis.ast.AstBuilder
import com.bugdigger.logolsp.analysis.ast.Node
import com.bugdigger.logolsp.analysis.ast.Program
import com.bugdigger.logolsp.analysis.diagnostics.LogoErrorListener
import com.bugdigger.logolsp.analysis.diagnostics.SemanticDiagnostics
import com.bugdigger.logolsp.analysis.symbols.Symbol
import com.bugdigger.logolsp.analysis.symbols.SymbolTable
import com.bugdigger.logolsp.grammar.LogoLexer
import com.bugdigger.logolsp.grammar.LogoParser
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.eclipse.lsp4j.Diagnostic

// Immutable result of analysing one document. Feature providers read from
// this and never re-parse.
data class Analysis(
    val ast: Program,
    val symbolTable: SymbolTable,
    val resolution: Map<Node, Symbol>,
    val diagnostics: List<Diagnostic>,
)

object Analyzer {

    fun analyze(source: String): Analysis {
        val errorListener = LogoErrorListener()

        val lexer = LogoLexer(CharStreams.fromString(source)).apply {
            removeErrorListeners()
            addErrorListener(errorListener)
        }
        val parser = LogoParser(CommonTokenStream(lexer)).apply {
            removeErrorListeners()
            addErrorListener(errorListener)
        }

        val ast = AstBuilder.build(parser.program())
        val resolution = Resolver().resolve(ast)
        val semantic = SemanticDiagnostics.compute(ast, resolution)

        return Analysis(
            ast = ast,
            symbolTable = resolution.symbolTable,
            resolution = resolution.resolution,
            diagnostics = errorListener.diagnostics + semantic,
        )
    }
}
