package com.bugdigger.logolsp.features

import com.bugdigger.logolsp.analysis.Analysis
import com.bugdigger.logolsp.analysis.ast.Call
import com.bugdigger.logolsp.analysis.ast.Node
import com.bugdigger.logolsp.analysis.ast.NodeFinder
import com.bugdigger.logolsp.analysis.ast.Parameter
import com.bugdigger.logolsp.analysis.ast.ProcedureDef
import com.bugdigger.logolsp.analysis.ast.QuotedWord
import com.bugdigger.logolsp.analysis.ast.VarRef
import com.bugdigger.logolsp.analysis.symbols.DefinedSymbol
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

object RenameProvider {

    // Returns the rename UX range (just the identifier, not surrounding `:`
    // or `"`) plus the current name as a placeholder. Returns null when the
    // cursor isn't on a user-renameable symbol — built-ins, literals, etc.
    fun prepareRename(analysis: Analysis, position: Position): PrepareRenameResult? {
        val hit = NodeFinder.resolvableAt(analysis, position) ?: return null
        val symbol = hit.symbol as? DefinedSymbol ?: return null
        val identRange = identifierRangeOf(hit.node) ?: return null
        return PrepareRenameResult(identRange, symbol.name)
    }

    // Range of the identifier portion of a clickable node, used as the
    // "what's being edited" highlight during rename.
    private fun identifierRangeOf(node: Node): Range? = when (node) {
        is Call -> node.target.range
        is ProcedureDef -> node.name.range
        is Parameter -> node.name.range
        is VarRef -> node.name.range
        is QuotedWord -> node.name.range
        else -> null
    }
}
