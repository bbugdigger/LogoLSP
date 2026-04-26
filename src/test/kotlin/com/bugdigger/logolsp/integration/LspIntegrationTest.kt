package com.bugdigger.logolsp.integration

import com.bugdigger.logolsp.server.LogoLanguageServer
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LspIntegrationTest {

    private val uri = "file:///test.logo"

    private val source = """
        to square :size
          repeat 4 [forward :size right 90]
        end
        square 50
        unknown 5
    """.trimIndent()

    @Test
    fun `full LSP lifecycle - initialize, didOpen, definition, prepareRename, rename, shutdown`() {
        // Two pairs of piped streams: client → server and server → client.
        // Use a 64 KiB buffer so initialise + a small program don't block.
        val clientToServer = PipedOutputStream()
        val serverIn = PipedInputStream(clientToServer, 64 * 1024)
        val serverToClient = PipedOutputStream()
        val clientIn = PipedInputStream(serverToClient, 64 * 1024)

        val server = LogoLanguageServer()
        val serverLauncher = LSPLauncher.createServerLauncher(server, serverIn, serverToClient)
        server.connect(serverLauncher.remoteProxy)
        serverLauncher.startListening()

        val client = TestLanguageClient()
        val clientLauncher = LSPLauncher.createClientLauncher(client, clientIn, clientToServer)
        val remoteServer = clientLauncher.remoteProxy
        clientLauncher.startListening()

        try {
            // 1. initialize → capabilities
            val initResult = remoteServer.initialize(InitializeParams()).get(5, TimeUnit.SECONDS)
            val caps = initResult.capabilities
            assertNotNull(caps.textDocumentSync, "textDocumentSync should be advertised")
            assertEquals(true, caps.definitionProvider.left)
            assertNotNull(caps.semanticTokensProvider, "semanticTokensProvider should be advertised")
            assertNotNull(caps.renameProvider, "renameProvider should be advertised")
            remoteServer.initialized(InitializedParams())

            // 2. didOpen → diagnostics arrive asynchronously via publishDiagnostics
            remoteServer.textDocumentService.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(uri, "logo", 1, source)),
            )
            val diags = client.diagnostics.poll(5, TimeUnit.SECONDS)
            assertNotNull(diags, "expected diagnostics to be published within 5s")
            assertEquals(uri, diags.uri)
            assertTrue(
                diags.diagnostics.any { it.code.left == "LOGO002" },
                "expected LOGO002 (undefined procedure) for `unknown 5`",
            )

            // 3. definition: click on `square` call (line 3, col 2) → jumps to def at line 0
            val defParams = DefinitionParams(TextDocumentIdentifier(uri), Position(3, 2))
            val defResult = remoteServer.textDocumentService.definition(defParams).get(5, TimeUnit.SECONDS)
            val locations = defResult.left
            assertEquals(1, locations.size)
            assertEquals(0, locations[0].range.start.line)

            // 4. prepareRename on the same call site
            val prepResult = remoteServer.textDocumentService
                .prepareRename(PrepareRenameParams(TextDocumentIdentifier(uri), Position(3, 2)))
                .get(5, TimeUnit.SECONDS)
            assertNotNull(prepResult)
            val prepRR = prepResult.second
            assertNotNull(prepRR, "prepareRename should return a PrepareRenameResult (Either3.second)")
            assertEquals("square", prepRR.placeholder)

            // 5. rename `square` → `rect` everywhere (definition + 1 call = 2 edits)
            val renameResult = remoteServer.textDocumentService
                .rename(RenameParams(TextDocumentIdentifier(uri), Position(3, 2), "rect"))
                .get(5, TimeUnit.SECONDS)
            val edits = renameResult.changes[uri]
            assertNotNull(edits, "expected edits keyed by document uri")
            assertEquals(2, edits.size)
            for (e in edits) assertEquals("rect", e.newText)

            // 6. shutdown then exit
            remoteServer.shutdown().get(5, TimeUnit.SECONDS)
            remoteServer.exit()
        } finally {
            // Closing the client's output stream EOFs the server's input,
            // letting the listener thread exit cleanly.
            clientToServer.close()
            serverToClient.close()
        }
    }
}

private class TestLanguageClient : LanguageClient {
    val diagnostics = LinkedBlockingQueue<PublishDiagnosticsParams>()

    override fun publishDiagnostics(params: PublishDiagnosticsParams) {
        diagnostics.offer(params)
    }

    override fun showMessage(params: MessageParams) {}
    override fun logMessage(params: MessageParams) {}
    override fun telemetryEvent(value: Any?) {}
    override fun showMessageRequest(params: ShowMessageRequestParams): CompletableFuture<MessageActionItem> =
        CompletableFuture.completedFuture(null)
}
