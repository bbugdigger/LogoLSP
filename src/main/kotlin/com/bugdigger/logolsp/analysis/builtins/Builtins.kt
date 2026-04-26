package com.bugdigger.logolsp.analysis.builtins

import com.bugdigger.logolsp.analysis.symbols.BuiltinSymbol

// LOGO built-in procedure catalog (UCB / Berkeley dialect subset, matching
// what turtleacademy.com accepts). Each name resolves to a BuiltinSymbol
// carrying its arity and one-line documentation. Aliases (`fd` ↔ `forward`)
// are registered as separate entries with identical metadata so that
// diagnostics report whatever name the user actually wrote.
//
// Keywords handled by the parser as dedicated statements are NOT catalogued
// here: `make`, `local`, `if`, `ifelse`, `repeat`, `output`/`op`, `stop`.
object Builtins {

    private val byName: Map<String, BuiltinSymbol> = buildMap {
        // ---- Turtle motion ----
        register(1, "Move turtle forward by N units", "forward", "fd")
        register(1, "Move turtle backward by N units", "back", "bk")
        register(1, "Turn turtle left by N degrees", "left", "lt")
        register(1, "Turn turtle right by N degrees", "right", "rt")
        register(0, "Move turtle to origin facing up", "home")
        register(1, "Move turtle to position [x y]", "setpos")
        register(2, "Move turtle to coordinates X Y", "setxy")
        register(1, "Set turtle heading to N degrees", "setheading", "seth")

        // ---- Pen ----
        register(0, "Lift the pen so the turtle stops drawing", "penup", "pu")
        register(0, "Lower the pen so the turtle resumes drawing", "pendown", "pd")
        register(1, "Set the pen colour", "setpencolor", "setpc")
        register(1, "Set the pen thickness", "setpensize")

        // ---- Screen ----
        register(0, "Erase drawings and recentre the turtle", "clearscreen", "cs")
        register(0, "Erase drawings without moving the turtle", "clean")
        register(0, "Hide the turtle cursor", "hideturtle", "ht")
        register(0, "Show the turtle cursor", "showturtle", "st")

        // ---- I/O ----
        register(1, "Print the value followed by a newline", "print", "pr")
        register(1, "Print a list as it would be entered", "show")
        register(1, "Print without trailing newline", "type")

        // ---- Arithmetic ----
        register(2, "Add two numbers", "sum", variadic = true)
        register(2, "Subtract second number from first", "difference")
        register(2, "Multiply two numbers", "product", variadic = true)
        register(2, "Divide first by second", "quotient")
        register(2, "Integer remainder of first divided by second", "remainder")
        register(1, "Negate the number", "minus")

        // ---- Comparison & logic ----
        register(2, "True if both arguments are equal", "equalp")
        register(2, "True if first is less than second", "lessp")
        register(2, "True if first is greater than second", "greaterp")
        register(2, "Logical AND", "and", variadic = true)
        register(2, "Logical OR", "or", variadic = true)
        register(1, "Logical NOT", "not")

        // ---- List operations ----
        register(1, "First element of a list", "first")
        register(1, "All but the first element", "butfirst", "bf")
        register(1, "Last element of a list", "last")
        register(1, "Number of elements", "count")
    }

    fun lookup(name: String): BuiltinSymbol? = byName[name.lowercase()]

    fun isBuiltin(name: String): Boolean = byName.containsKey(name.lowercase())

    fun all(): Collection<BuiltinSymbol> = byName.values

    private fun MutableMap<String, BuiltinSymbol>.register(
        parameterCount: Int,
        documentation: String,
        vararg names: String,
        variadic: Boolean = false,
    ) {
        for (name in names) {
            put(name, BuiltinSymbol(name, parameterCount, variadic, documentation))
        }
    }
}
