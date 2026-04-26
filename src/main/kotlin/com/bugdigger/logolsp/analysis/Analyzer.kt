package com.bugdigger.logolsp.analysis

import com.bugdigger.logolsp.analysis.ast.AstBuilder
import com.bugdigger.logolsp.analysis.ast.Node
import com.bugdigger.logolsp.analysis.ast.Program
import com.bugdigger.logolsp.analysis.diagnostics.LogoErrorListener
import com.bugdigger.logolsp.analysis.diagnostics.SemanticDiagnostics
import com.bugdigger.logolsp.analysis.symbols.Symbol
import com.bugdigger.logolsp.analysis.symbols.SymbolTable
import com.bugdigger.logolsp.analysis.ast.toRange
import com.bugdigger.logolsp.grammar.LogoLexer
import com.bugdigger.logolsp.grammar.LogoParser
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Token
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Range

// Immutable result of analysing one document. Feature providers read from
// this and never re-parse.
data class Analysis(
    val ast: Program,
    val symbolTable: SymbolTable,
    val resolution: Map<Node, Symbol>,
    val diagnostics: List<Diagnostic>,
    // Comment token ranges (`;...`). These are not in the AST because they
    // sit on ANTLR's hidden channel, but the semantic-tokens provider needs
    // them to highlight comments.
    val commentRanges: List<Range>,
)

object Analyzer {

    fun analyze(source: String): Analysis {
        val errorListener = LogoErrorListener()

        val lexer = LogoLexer(CharStreams.fromString(source)).apply {
            removeErrorListeners()
            addErrorListener(errorListener)
        }
        val tokenStream = CommonTokenStream(lexer)
        val parser = LogoParser(tokenStream).apply {
            removeErrorListeners()
            addErrorListener(errorListener)
        }

        val ast = AstBuilder.build(parser.program())
        val resolution = Resolver().resolve(ast)
        val semantic = SemanticDiagnostics.compute(ast, resolution)

        // Pull hidden-channel comment tokens from the now-fully-consumed
        // token stream. Channel HIDDEN (1) is where Logo.g4 routes `;...`.
        tokenStream.fill()
        val commentRanges = tokenStream.tokens
            .filter { it.channel == Token.HIDDEN_CHANNEL }
            .map { it.toRange() }

        return Analysis(
            ast = ast,
            symbolTable = resolution.symbolTable,
            resolution = resolution.resolution,
            diagnostics = errorListener.diagnostics + semantic,
            commentRanges = commentRanges,
        )
    }
}
