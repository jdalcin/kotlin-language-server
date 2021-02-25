package org.javacs.kt

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.services.JsonDelegate
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.javacs.kt.commands.ALL_COMMANDS
import org.javacs.kt.externalsources.JarClassContentProvider
import org.javacs.kt.util.AsyncExecutor
import org.javacs.kt.util.TemporaryDirectory
import org.javacs.kt.util.parseURI
import java.net.URI
import java.io.Closeable
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture

class KotlinLanguageServer : LanguageServer, LanguageClientAware, Closeable {
    private val config = Configuration()
    val classPath = CompilerClassPath(config.compiler)

    private val tempDirectory = TemporaryDirectory()
    private val uriContentProvider = URIContentProvider(JarClassContentProvider(config.externalSources, classPath, tempDirectory))
    val sourcePath = SourcePath(classPath, uriContentProvider)
    val sourceFiles = SourceFiles(sourcePath, uriContentProvider)

    private val textDocuments = KotlinTextDocumentService(sourceFiles, sourcePath, config, tempDirectory, uriContentProvider)
    private val workspaces = KotlinWorkspaceService(sourceFiles, sourcePath, classPath, textDocuments, config)
    private val protocolExtensions = KotlinProtocolExtensionService(uriContentProvider)

    private lateinit var client: LanguageClient
    private val async = AsyncExecutor()

    override fun connect(client: LanguageClient) {
        this.client = client
        connectLoggingBackend()

        workspaces.connect(client)
        textDocuments.connect(client)

        LOG.info("Connected to client")
    }

    override fun getTextDocumentService(): KotlinTextDocumentService = textDocuments

    override fun getWorkspaceService(): KotlinWorkspaceService = workspaces

    @JsonDelegate
    fun getProtocolExtensionService(): KotlinProtocolExtensions = protocolExtensions

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> = async.compute {
        val serverCapabilities = ServerCapabilities()
        serverCapabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental)
        serverCapabilities.workspace = WorkspaceServerCapabilities()
        serverCapabilities.workspace.workspaceFolders = WorkspaceFoldersOptions()
        serverCapabilities.workspace.workspaceFolders.supported = true
        serverCapabilities.workspace.workspaceFolders.changeNotifications = Either.forRight(true)
        serverCapabilities.hoverProvider = true
        serverCapabilities.completionProvider = CompletionOptions(false, listOf("."))
        serverCapabilities.signatureHelpProvider = SignatureHelpOptions(listOf("(", ","))
        serverCapabilities.definitionProvider = true
        serverCapabilities.documentSymbolProvider = true
        serverCapabilities.workspaceSymbolProvider = true
        serverCapabilities.referencesProvider = true
        serverCapabilities.codeActionProvider = Either.forLeft(true)
        serverCapabilities.documentFormattingProvider = true
        serverCapabilities.documentRangeFormattingProvider = true
        serverCapabilities.executeCommandProvider = ExecuteCommandOptions(ALL_COMMANDS)

        val clientCapabilities = params.capabilities
        config.completion.snippets.enabled = clientCapabilities?.textDocument?.completion?.completionItem?.snippetSupport ?: false

        val folders = params.workspaceFolders

        params.workDoneToken?.let {
            client.notifyProgress(ProgressParams(it, WorkDoneProgressBegin().apply {
                title = "Adding Kotlin workspace folders"
                percentage = 0
            }))
        }

        folders.forEachIndexed { i, folder ->
            LOG.info("Adding workspace folder {}", folder.name)
            val progressPrefix = "[${i + 1}/${folders.size}] ${folder.name}"
            val progressPercent = (100 * i) / folders.size

            params.workDoneToken?.let {
                client.notifyProgress(ProgressParams(it, WorkDoneProgressReport().apply {
                    message = "$progressPrefix: Updating source path"
                    percentage = progressPercent
                }))
            }

            val root = Paths.get(parseURI(folder.uri))
            sourceFiles.addWorkspaceRoot(root)

            params.workDoneToken?.let {
                client.notifyProgress(ProgressParams(it, WorkDoneProgressReport().apply {
                    message = "$progressPrefix: Updating class path"
                    percentage = progressPercent
                }))
            }

            val refreshed = classPath.addWorkspaceRoot(root)
            if (refreshed) {
                params.workDoneToken?.let {
                    client.notifyProgress(ProgressParams(it, WorkDoneProgressReport().apply {
                        message = "$progressPrefix: Refreshing source path"
                        percentage = progressPercent
                    }))
                }

                sourcePath.refresh()
            }
        }

        InitializeResult(serverCapabilities)
    }

    private fun connectLoggingBackend() {
        val backend: (LogMessage) -> Unit = {
            client.logMessage(MessageParams().apply {
                type = it.level.toLSPMessageType()
                message = it.message
            })
        }
        LOG.connectOutputBackend(backend)
        LOG.connectErrorBackend(backend)
    }

    private fun LogLevel.toLSPMessageType(): MessageType = when (this) {
        LogLevel.ERROR -> MessageType.Error
        LogLevel.WARN -> MessageType.Warning
        LogLevel.INFO -> MessageType.Info
        else -> MessageType.Log
    }

    override fun close() {
        textDocumentService.close()
        classPath.close()
        tempDirectory.close()
        async.shutdown(awaitTermination = true)
    }

    override fun shutdown(): CompletableFuture<Any> {
        close()
        return completedFuture(null)
    }

    override fun exit() {}
}
