package com.bugdigger.logolsp.analysis.symbols

// A lexical scope holding variable bindings. Scopes form a parent chain;
// lookup walks the chain from innermost outward.
//
// In our LOGO dialect there are only two scope kinds:
//   * the global file scope (parent = null), populated by `make`, and
//   * a per-procedure scope (parent = global), populated by parameters
//     and `local` declarations within that procedure body.
class Scope(val parent: Scope? = null) {

    private val bindings = mutableMapOf<String, VariableSymbol>()

    fun define(symbol: VariableSymbol) {
        bindings[symbol.name] = symbol
    }

    fun lookupLocal(name: String): VariableSymbol? = bindings[name]

    fun lookup(name: String): VariableSymbol? =
        bindings[name] ?: parent?.lookup(name)

    fun variables(): Collection<VariableSymbol> = bindings.values
}
