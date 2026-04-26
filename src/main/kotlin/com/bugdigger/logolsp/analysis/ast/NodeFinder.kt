package com.bugdigger.logolsp.analysis.ast

import com.bugdigger.logolsp.analysis.Analysis
import com.bugdigger.logolsp.analysis.symbols.Symbol
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

object NodeFinder {

    // Returns the path of AST nodes from the Program root down to the deepest
    // node whose range contains the given LSP position. Empty if the position
    // is outside the program.
    fun pathAt(program: Program, position: Position): List<Node> {
        val path = mutableListOf<Node>()
        walk(program, position, path)
        return path
    }

    // Convenience: find the resolvable AST node at this position, plus the
    // Symbol it refers to. Returns null if the position doesn't fall on a
    // name (procedure call, variable read, declaration, etc.).
    fun resolvableAt(analysis: Analysis, position: Position): ResolvedHit? {
        val path = pathAt(analysis.ast, position)
        if (path.isEmpty()) return null
        // If the cursor lands on a bare Identifier, the resolvable wrapper
        // (Call, VarRef, Parameter, ...) is one step up. Otherwise the
        // deepest hit IS the resolvable.
        val target = when (val last = path.last()) {
            is Identifier -> path.getOrNull(path.size - 2) ?: return null
            else -> last
        }
        val symbol = analysis.resolution[target] ?: return null
        return ResolvedHit(target, symbol)
    }

    private fun walk(node: Node, position: Position, path: MutableList<Node>) {
        if (!node.range.contains(position)) return
        path.add(node)
        for (child in childrenOf(node)) walk(child, position, path)
    }

    private fun childrenOf(node: Node): List<Node> = when (node) {
        is Program -> node.statements
        is ProcedureDef -> buildList {
            add(node.name)
            addAll(node.parameters)
            addAll(node.body)
        }
        is Parameter -> listOf(node.name)
        is Call -> buildList {
            add(node.target)
            addAll(node.arguments)
        }
        is IfStmt -> listOf(node.condition, node.body)
        is IfElseStmt -> listOf(node.condition, node.thenBody, node.elseBody)
        is RepeatStmt -> listOf(node.count, node.body)
        is MakeStmt -> when (val t = node.target) {
            is VarRef -> listOf(t, node.value)
            is QuotedWord -> listOf(t, node.value)
        }
        is LocalStmt -> node.names
        is OutputStmt -> listOf(node.value)
        is StopStmt -> emptyList()
        is VarRef -> listOf(node.name)
        is QuotedWord -> listOf(node.name)
        is NumberLit -> emptyList()
        is ListLiteral -> node.elements
        is ParenCall -> listOf(node.call)
        is ParenExpr -> listOf(node.expr)
        is BinaryOp -> listOf(node.left, node.right)
        is UnaryMinus -> listOf(node.operand)
        is Identifier -> emptyList()
    }
}

data class ResolvedHit(val node: Node, val symbol: Symbol)

private fun Range.contains(position: Position): Boolean {
    val afterStart = position.line > start.line ||
        (position.line == start.line && position.character >= start.character)
    val beforeEnd = position.line < end.line ||
        (position.line == end.line && position.character <= end.character)
    return afterStart && beforeEnd
}
