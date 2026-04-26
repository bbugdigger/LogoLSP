package com.bugdigger.logolsp.analysis.diagnostics

import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

// Collects ANTLR syntax errors (from both the lexer and the parser) into LSP
// Diagnostics. Attach to the lexer/parser by `removeErrorListeners()` followed
// by `addErrorListener(listener)`; read `listener.diagnostics` after parsing.
class LogoErrorListener : BaseErrorListener() {

    private val collected = mutableListOf<Diagnostic>()
    val diagnostics: List<Diagnostic> get() = collected

    override fun syntaxError(
        recognizer: Recognizer<*, *>?,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String?,
        e: RecognitionException?,
    ) {
        val message = msg ?: "syntax error"
        collected += diagnostic(
            rule = DiagnosticRule.SYNTAX_ERROR,
            range = rangeFor(offendingSymbol, line, charPositionInLine),
            message = message,
        )
    }

    private fun rangeFor(offendingSymbol: Any?, line: Int, charPositionInLine: Int): Range {
        // Prefer the offending token's actual span; fall back to a zero-width
        // marker at the reported position when no token is available (e.g.
        // for lexer errors).
        val tok = offendingSymbol as? Token
        if (tok != null && tok.text != null) {
            val start = Position(tok.line - 1, tok.charPositionInLine)
            val end = Position(tok.line - 1, tok.charPositionInLine + tok.text.length)
            return Range(start, end)
        }
        val start = Position(line - 1, charPositionInLine)
        return Range(start, start)
    }
}
