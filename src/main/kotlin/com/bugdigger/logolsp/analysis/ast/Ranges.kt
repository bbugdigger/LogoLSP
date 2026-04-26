package com.bugdigger.logolsp.analysis.ast

import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

// ANTLR uses 1-based line numbers and 0-based column numbers.
// LSP uses 0-based line and character numbers.

internal fun Token.startPosition(): Position =
    Position(line - 1, charPositionInLine)

internal fun Token.endPosition(): Position =
    Position(line - 1, charPositionInLine + (text?.length ?: 0))

internal fun Token.toRange(): Range = Range(startPosition(), endPosition())

internal fun ParserRuleContext.toRange(): Range {
    val startTok = start ?: return Range(Position(0, 0), Position(0, 0))
    val endTok = stop ?: startTok
    return Range(startTok.startPosition(), endTok.endPosition())
}
