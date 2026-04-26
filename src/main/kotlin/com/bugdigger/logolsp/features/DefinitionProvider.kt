package com.bugdigger.logolsp.features

import com.bugdigger.logolsp.analysis.Analysis
import com.bugdigger.logolsp.analysis.ast.NodeFinder
import com.bugdigger.logolsp.analysis.symbols.BuiltinSymbol
import com.bugdigger.logolsp.analysis.symbols.DefinedSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position

object DefinitionProvider {

    fun definition(analysis: Analysis, uri: String, position: Position): List<Location> {
        val hit = NodeFinder.resolvableAt(analysis, position) ?: return emptyList()
        return when (val sym = hit.symbol) {
            is DefinedSymbol -> listOf(Location(uri, sym.definitionRange))
            // Built-ins have no source location to jump to.
            is BuiltinSymbol -> emptyList()
        }
    }
}
