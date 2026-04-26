package com.bugdigger.logolsp.analysis

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.ConcurrentHashMap

// Holds the latest Analysis for every open document, indexed by LSP URI
// string. The text-document service forwards `didOpen` / `didChange` /
// `didClose` here; feature providers later read `analysis(uri)` to answer
// requests. Diagnostics are published to the client on every analysis.
//
// Thread-safety: lifecycle notifications and request handlers may arrive on
// different threads from LSP4J's executor. The map is concurrent; the
// client reference is volatile and set once at connect() time.
class DocumentManager {

    private val analyses = ConcurrentHashMap<String, Analysis>()

    @Volatile
    private var client: LanguageClient? = null

    fun attach(client: LanguageClient) {
        this.client = client
    }

    fun openOrUpdate(uri: String, text: String) {
        val analysis = Analyzer.analyze(text)
        analyses[uri] = analysis
        publish(uri, analysis.diagnostics)
    }

    fun close(uri: String) {
        analyses.remove(uri)
        // Clear the editor's squiggles for the file we no longer track.
        publish(uri, emptyList())
    }

    fun analysis(uri: String): Analysis? = analyses[uri]

    private fun publish(uri: String, diagnostics: List<Diagnostic>) {
        client?.publishDiagnostics(PublishDiagnosticsParams(uri, diagnostics))
    }
}
