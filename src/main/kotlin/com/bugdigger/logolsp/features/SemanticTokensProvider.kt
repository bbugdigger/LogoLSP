package com.bugdigger.logolsp.features

import com.bugdigger.logolsp.analysis.Analysis
import com.bugdigger.logolsp.analysis.ast.Atom
import com.bugdigger.logolsp.analysis.ast.BinaryOp
import com.bugdigger.logolsp.analysis.ast.Call
import com.bugdigger.logolsp.analysis.ast.Expression
import com.bugdigger.logolsp.analysis.ast.IfElseStmt
import com.bugdigger.logolsp.analysis.ast.IfStmt
import com.bugdigger.logolsp.analysis.ast.ListLiteral
import com.bugdigger.logolsp.analysis.ast.LocalStmt
import com.bugdigger.logolsp.analysis.ast.MakeStmt
import com.bugdigger.logolsp.analysis.ast.MakeTarget
import com.bugdigger.logolsp.analysis.ast.NumberLit
import com.bugdigger.logolsp.analysis.ast.OutputStmt
import com.bugdigger.logolsp.analysis.ast.ParenCall
import com.bugdigger.logolsp.analysis.ast.ParenExpr
import com.bugdigger.logolsp.analysis.ast.ProcedureDef
import com.bugdigger.logolsp.analysis.ast.QuotedWord
import com.bugdigger.logolsp.analysis.ast.RepeatStmt
import com.bugdigger.logolsp.analysis.ast.Statement
import com.bugdigger.logolsp.analysis.ast.StopStmt
import com.bugdigger.logolsp.analysis.ast.UnaryMinus
import com.bugdigger.logolsp.analysis.ast.VarRef
import com.bugdigger.logolsp.analysis.symbols.BuiltinSymbol
import com.bugdigger.logolsp.analysis.symbols.VariableScope
import com.bugdigger.logolsp.analysis.symbols.VariableSymbol
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensLegend

object SemanticTokensProvider {

    // The legend MUST be advertised in server capabilities and used here.
    // Indices below must match this list order exactly.
    val legend: SemanticTokensLegend = SemanticTokensLegend(
        listOf("keyword", "function", "parameter", "variable", "string", "number", "comment"),
        listOf("declaration", "defaultLibrary"),
    )

    private const val TYPE_KEYWORD   = 0
    private const val TYPE_FUNCTION  = 1
    private const val TYPE_PARAMETER = 2
    private const val TYPE_VARIABLE  = 3
    private const val TYPE_STRING    = 4
    private const val TYPE_NUMBER    = 5
    private const val TYPE_COMMENT   = 6

    private const val MOD_DECLARATION     = 1 shl 0
    private const val MOD_DEFAULT_LIBRARY = 1 shl 1

    fun semanticTokens(analysis: Analysis): SemanticTokens {
        val tokens = mutableListOf<RawToken>()
        for (stmt in analysis.ast.statements) emitStatement(stmt, analysis, tokens)
        for (range in analysis.commentRanges) emit(range, TYPE_COMMENT, 0, tokens)
        // LSP requires tokens sorted by (line, char). Document-order walk is
        // already sorted, but comments come from a separate stream — sort
        // defensively to merge them in.
        tokens.sortWith(compareBy({ it.line }, { it.char }))
        return SemanticTokens(encode(tokens).toMutableList())
    }

    // ---------------------------------------------------------------- emitters

    private fun emitStatement(stmt: Statement, analysis: Analysis, out: MutableList<RawToken>) {
        when (stmt) {
            is ProcedureDef -> {
                emit(stmt.toKeywordRange, TYPE_KEYWORD, 0, out)
                emit(stmt.name.range, TYPE_FUNCTION, MOD_DECLARATION, out)
                for (param in stmt.parameters) {
                    emit(param.name.range, TYPE_PARAMETER, MOD_DECLARATION, out)
                }
                for (s in stmt.body) emitStatement(s, analysis, out)
                emit(stmt.endKeywordRange, TYPE_KEYWORD, 0, out)
            }
            is Call -> emitCall(stmt, analysis, out)
            is IfStmt -> {
                emit(stmt.keywordRange, TYPE_KEYWORD, 0, out)
                emitExpression(stmt.condition, analysis, out)
                emitListLiteral(stmt.body, analysis, out)
            }
            is IfElseStmt -> {
                emit(stmt.keywordRange, TYPE_KEYWORD, 0, out)
                emitExpression(stmt.condition, analysis, out)
                emitListLiteral(stmt.thenBody, analysis, out)
                emitListLiteral(stmt.elseBody, analysis, out)
            }
            is RepeatStmt -> {
                emit(stmt.keywordRange, TYPE_KEYWORD, 0, out)
                emitExpression(stmt.count, analysis, out)
                emitListLiteral(stmt.body, analysis, out)
            }
            is MakeStmt -> {
                emit(stmt.keywordRange, TYPE_KEYWORD, 0, out)
                emitMakeTarget(stmt.target, analysis, out)
                emitExpression(stmt.value, analysis, out)
            }
            is LocalStmt -> {
                emit(stmt.keywordRange, TYPE_KEYWORD, 0, out)
                for (qw in stmt.names) emit(qw.name.range, TYPE_VARIABLE, MOD_DECLARATION, out)
            }
            is OutputStmt -> {
                emit(stmt.keywordRange, TYPE_KEYWORD, 0, out)
                emitExpression(stmt.value, analysis, out)
            }
            is StopStmt -> emit(stmt.keywordRange, TYPE_KEYWORD, 0, out)
        }
    }

    private fun emitCall(call: Call, analysis: Analysis, out: MutableList<RawToken>) {
        val resolved = analysis.resolution[call]
        val mods = if (resolved is BuiltinSymbol) MOD_DEFAULT_LIBRARY else 0
        emit(call.target.range, TYPE_FUNCTION, mods, out)
        for (arg in call.arguments) emitAtom(arg, analysis, out)
    }

    private fun emitExpression(expr: Expression, analysis: Analysis, out: MutableList<RawToken>) {
        when (expr) {
            is Atom -> emitAtom(expr, analysis, out)
            is BinaryOp -> {
                emitExpression(expr.left, analysis, out)
                emitExpression(expr.right, analysis, out)
            }
            is UnaryMinus -> emitExpression(expr.operand, analysis, out)
        }
    }

    private fun emitAtom(atom: Atom, analysis: Analysis, out: MutableList<RawToken>) {
        when (atom) {
            is VarRef -> {
                val sym = analysis.resolution[atom] as? VariableSymbol
                val type = if (sym?.scope == VariableScope.PARAMETER) TYPE_PARAMETER else TYPE_VARIABLE
                emit(atom.name.range, type, 0, out)
            }
            is QuotedWord -> emit(atom.name.range, TYPE_STRING, 0, out)
            is NumberLit -> emit(atom.range, TYPE_NUMBER, 0, out)
            is ListLiteral -> emitListLiteral(atom, analysis, out)
            is ParenCall -> emitCall(atom.call, analysis, out)
            is ParenExpr -> emitExpression(atom.expr, analysis, out)
        }
    }

    private fun emitMakeTarget(target: MakeTarget, analysis: Analysis, out: MutableList<RawToken>) {
        when (target) {
            // `make "x ...` — the quoted name is acting as a variable
            // declaration, not a string literal; highlight as variable.
            is QuotedWord -> emit(target.name.range, TYPE_VARIABLE, MOD_DECLARATION, out)
            is VarRef -> emitAtom(target, analysis, out)
        }
    }

    private fun emitListLiteral(list: ListLiteral, analysis: Analysis, out: MutableList<RawToken>) {
        for (elem in list.elements) {
            when (elem) {
                is Statement -> emitStatement(elem, analysis, out)
                is Atom -> emitAtom(elem, analysis, out)
            }
        }
    }

    private fun emit(range: Range, tokenType: Int, modifiers: Int, out: MutableList<RawToken>) {
        // We only emit single-line tokens — every LOGO terminal fits on one
        // line in our grammar — so length is a column delta.
        out.add(
            RawToken(
                line = range.start.line,
                char = range.start.character,
                length = range.end.character - range.start.character,
                tokenType = tokenType,
                modifiers = modifiers,
            ),
        )
    }

    private fun encode(tokens: List<RawToken>): IntArray {
        val out = IntArray(tokens.size * 5)
        var prevLine = 0
        var prevChar = 0
        for ((i, t) in tokens.withIndex()) {
            val deltaLine = t.line - prevLine
            val deltaChar = if (deltaLine == 0) t.char - prevChar else t.char
            val base = i * 5
            out[base]     = deltaLine
            out[base + 1] = deltaChar
            out[base + 2] = t.length
            out[base + 3] = t.tokenType
            out[base + 4] = t.modifiers
            prevLine = t.line
            prevChar = t.char
        }
        return out
    }

    private data class RawToken(
        val line: Int,
        val char: Int,
        val length: Int,
        val tokenType: Int,
        val modifiers: Int,
    )
}

private fun IntArray.toMutableList(): MutableList<Int> = toCollection(ArrayList(size))
