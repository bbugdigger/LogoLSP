package com.bugdigger.logolsp.analysis.diagnostics

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Range

const val DIAGNOSTIC_SOURCE = "logo-lsp"

enum class DiagnosticRule(val code: String, val severity: DiagnosticSeverity) {
    SYNTAX_ERROR        ("LOGO001", DiagnosticSeverity.Error),
    UNDEFINED_PROCEDURE ("LOGO002", DiagnosticSeverity.Error),
    UNDEFINED_VARIABLE  ("LOGO003", DiagnosticSeverity.Error),
    WRONG_ARITY         ("LOGO004", DiagnosticSeverity.Warning),
    REDEFINED_PROCEDURE ("LOGO005", DiagnosticSeverity.Warning),
}

fun diagnostic(rule: DiagnosticRule, range: Range, message: String): Diagnostic =
    Diagnostic(range, message, rule.severity, DIAGNOSTIC_SOURCE, rule.code)
