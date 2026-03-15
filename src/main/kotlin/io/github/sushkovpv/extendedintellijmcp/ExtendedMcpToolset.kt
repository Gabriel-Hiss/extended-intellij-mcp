@file:Suppress("FunctionName", "unused")

package io.github.sushkovpv.extendedintellijmcp

import com.intellij.find.FindManager
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.externalSystem.ExternalSystemManager
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemSettings
import com.intellij.openapi.externalSystem.settings.ExternalProjectSettings
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.search.ProjectScope
import com.intellij.usageView.UsageInfo
import com.intellij.usages.FindUsagesProcessPresentation
import com.intellij.usages.UsageViewPresentation
import com.intellij.util.Processor
import io.github.sushkovpv.extendedintellijmcp.Constants.MAX_USAGE_TEXT_CHARS
import kotlinx.coroutines.*
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentLinkedQueue
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

class ExtendedMcpToolset : McpToolset {
    @McpTool
    @McpDescription(
        """
        |Searches with a regex pattern within the files of project dependencies (libraries) using IntelliJ's search engine.
        |
        |The result occurrences are surrounded with || characters, e.g. `some text ||substring|| text`
    """
    )
    suspend fun search_in_dependencies_by_regex(
        @McpDescription("Regex pattern to search for")
        regexPattern: String,
        @McpDescription("File mask to search for. If not specified, searches for all files. Example: `*.java`")
        fileMask: String? = null,
        @McpDescription("Whether to search for the text in a case-sensitive manner")
        caseSensitive: Boolean = true,
        @McpDescription("Maximum number of entries to return.")
        maxUsageCount: Int = 100,
        @McpDescription(Constants.TIMEOUT_MILLISECONDS_DESCRIPTION)
        timeout: Int = Constants.MEDIUM_TIMEOUT_MILLISECONDS_VALUE,
    ): UsageInfoResult {
        val project = currentCoroutineContext().project
        return searchDependenciesByRegexForProject(project, regexPattern, fileMask, caseSensitive, maxUsageCount, timeout)
    }

    suspend fun searchDependenciesByRegexForProject(
        project: Project,
        regexPattern: String,
        fileMask: String? = null,
        caseSensitive: Boolean = true,
        maxUsageCount: Int = 100,
        timeout: Int = Constants.MEDIUM_TIMEOUT_MILLISECONDS_VALUE,
    ): UsageInfoResult {
        if (regexPattern.isBlank()) mcpFail("Search text is empty")

        val findModel = FindManager.getInstance(project).findInProjectModel.clone().apply {
            stringToFind = regexPattern
            isCaseSensitive = caseSensitive
            isWholeWordsOnly = false
            isRegularExpressions = true
            isProjectScope = false
            isCustomScope = true
            customScopeName = "Libraries"
            customScope = ProjectScope.getLibrariesScope(project)
            fileFilter = fileMask
        }

        val usages = CopyOnWriteArrayList<UsageInfo>()

        val timedOut = withTimeoutOrNull(timeout.milliseconds) {
            val processor = Processor<UsageInfo> { usageInfo ->
                usages.add(usageInfo)
                usages.size < maxUsageCount
            }

            withBackgroundProgress(project, "Searching dependencies with regex", cancellable = true) {
                coroutineToIndicator { indicator ->
                    FindInProjectUtil.findUsages(
                        findModel,
                        project,
                        indicator,
                        FindUsagesProcessPresentation(UsageViewPresentation()),
                        setOf(),
                        processor,
                    )
                }
            }
        } == null

        val entries = usages.mapNotNull { usage ->
            val file = usage.virtualFile ?: return@mapNotNull null
            val document = readAction { FileDocumentManager.getInstance().getDocument(file) } ?: return@mapNotNull null
            val textRange = usage.navigationRange ?: return@mapNotNull null
            val startLineNumber = document.getLineNumber(textRange.startOffset)
            val startLineStartOffset = document.getLineStartOffset(startLineNumber)
            val endLineNumber = document.getLineNumber(textRange.endOffset)
            val endLineEndOffset = document.getLineEndOffset(endLineNumber)
            val textBeforeOccurrence = document.getText(TextRange(startLineStartOffset, textRange.startOffset)).take(MAX_USAGE_TEXT_CHARS)
            val textInner = document.getText(TextRange(textRange.startOffset, textRange.endOffset)).take(MAX_USAGE_TEXT_CHARS)
            val textAfterOccurrence = document.getText(TextRange(textRange.endOffset, endLineEndOffset)).take(MAX_USAGE_TEXT_CHARS)
            UsageInfoEntry(file.url, startLineNumber + 1, "$textBeforeOccurrence||$textInner||$textAfterOccurrence")
        }

        return UsageInfoResult(entries = entries, probablyHasMoreMatchingEntries = usages.size >= maxUsageCount, timedOut = timedOut)
    }

    @McpTool
    @McpDescription(
        """
        |Returns the text content of a dependency file (library).
        |Use the URL returned by `search_in_dependencies_by_regex`.
    """
    )
    suspend fun get_dependency_file_text(
        @McpDescription("URL to the file (as returned by search_in_dependencies_by_regex)")
        url: String,
        @McpDescription("Line number to center the view on (1-based)")
        lineNumber: Int,
        @McpDescription("Number of lines to show before the target line")
        linesBefore: Int = Constants.DEFAULT_LINES_BEFORE,
        @McpDescription("Number of lines to show after the target line")
        linesAfter: Int = Constants.DEFAULT_LINES_AFTER,
    ): String {
        val file = VirtualFileManager.getInstance().findFileByUrl(url)
            ?: mcpFail("File $url not found")

        return readAction {
            val document = FileDocumentManager.getInstance().getDocument(file)
                ?: mcpFail("Could not get document for $url")

            val totalLines = document.lineCount
            if (totalLines == 0) return@readAction ""

            val zeroBasedLine = (lineNumber - 1).coerceIn(0, totalLines - 1)
            val startLine = (zeroBasedLine - linesBefore).coerceIn(0, totalLines - 1)
            val endLine = (zeroBasedLine + linesAfter).coerceIn(0, totalLines - 1)

            val startOffset = document.getLineStartOffset(startLine)
            val endOffset = document.getLineEndOffset(endLine)

            document.getText(TextRange(startOffset, endOffset))
        }
    }

    @McpTool
    @McpDescription("Synchronizes all external systems (Gradle, Maven, NPM, etc.) and reloads CMake projects in CLion to apply dependency and project model changes. Returns raw sync output when it fits in the MCP response size; otherwise saves the full log to a file and returns that file path.")
    suspend fun sync_project(): String {
        val project = currentCoroutineContext().project
        return syncProject(project)
    }

    suspend fun syncProject(project: Project): String {
        withContext(Dispatchers.EDT) {
            FileDocumentManager.getInstance().saveAllDocuments()
        }

        val managers = ExternalSystemManager.EP_NAME.extensionList
            .filter { manager -> manager.hasLinkedProjects(project) }
        val hasCMakeSupport = hasCMakeWorkspaceSupport()
        val looksLikeCMakeProject = hasCMakeSupport && project.looksLikeCMakeProject()
        val projectId = ExternalSystemTaskId.getProjectId(project)
        val systemIds = managers.map { it.systemId }.toSet()
        val shouldPrefixOutput = systemIds.size > 1
        val acceptAnySystemId = managers.isEmpty() && looksLikeCMakeProject
        val logEntries = ConcurrentLinkedQueue<String>()
        val progressManager = ExternalSystemProgressNotificationManager.getInstance()
        val listener = object : ExternalSystemTaskNotificationListener {
            override fun onTaskOutput(id: ExternalSystemTaskId, text: String, stdOut: Boolean) {
                if (!id.belongsToCurrentSync(projectId, systemIds, acceptAnySystemId)) return
                if (text.isEmpty()) return

                logEntries.add(text.withOptionalPrefix(id, shouldPrefixOutput))
            }
        }

        progressManager.addNotificationListener(listener)
        try {
            if (managers.isNotEmpty()) {
                supervisorScope {
                    managers.map { manager ->
                        async {
                            runCatching<Unit> {
                                withTimeout(Constants.SYNC_PROJECT_TIMEOUT_MILLISECONDS_VALUE.milliseconds) {
                                    suspendCancellableCoroutine { continuation ->
                                        val spec = ImportSpecBuilder(project, manager.systemId)
                                            .withCallback(object : ExternalProjectRefreshCallback {
                                                override fun onSuccess(externalProject: DataNode<ProjectData>?) {
                                                    continuation.resume(Unit)
                                                }

                                                override fun onFailure(errorMessage: String, errorDetails: String?) {
                                                    val fullMessage = listOfNotNull(errorMessage, errorDetails)
                                                        .joinToString(": ")
                                                        .ifBlank { "Unknown sync error" }
                                                    continuation.resumeWith(Result.failure(RuntimeException(fullMessage)))
                                                }
                                            })
                                            .build()

                                        ExternalSystemUtil.refreshProjects(spec)
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }
            }

            if ((managers.isEmpty() || logEntries.isEmpty()) && hasCMakeSupport) {
                triggerCMakeReload(project)?.takeIf(String::isNotBlank)?.let(logEntries::add)
            }

            return logEntries.joinToString(separator = "").normalizeLineEndings().ifBlank {
                if (hasCMakeSupport) {
                    "CMake reload completed with no output."
                } else {
                    ""
                }
            }.prepareSyncProjectResponse(project)
        } finally {
            progressManager.removeNotificationListener(listener)
        }
    }

    private fun ExternalSystemTaskId.belongsToCurrentSync(
        projectId: String,
        systemIds: Set<com.intellij.openapi.externalSystem.model.ProjectSystemId>,
        acceptAnySystemId: Boolean,
    ): Boolean {
        return type == ExternalSystemTaskType.RESOLVE_PROJECT &&
            ideProjectId == projectId &&
            (acceptAnySystemId || projectSystemId in systemIds)
    }

    private fun String.withOptionalPrefix(id: ExternalSystemTaskId, shouldPrefixOutput: Boolean): String {
        if (!shouldPrefixOutput) return this
        val prefix = "[${id.projectSystemId.readableName}] "
        return lineSequence()
            .joinToString(separator = "\n", postfix = if (endsWith('\n')) "\n" else "") { line ->
                if (line.isEmpty()) line else "$prefix$line"
            }
    }

    private fun String.normalizeLineEndings(): String = replace("\r\n", "\n").replace('\r', '\n')

    private fun String.prepareSyncProjectResponse(project: Project): String {
        if (length <= Constants.MAX_TOOL_RESPONSE_CHARS) return this

        val logFile = saveSyncProjectOutputToFile(project, this)
        return buildString {
            append("Sync output exceeded the safe MCP response size and was saved to a file.\n")
            append("Use this file to inspect the full log:\n")
            append(logFile.toAbsolutePath())
        }
    }

    private fun String.truncateForToolResponse(maxChars: Int = Constants.MAX_TOOL_RESPONSE_CHARS): String {
        if (length <= maxChars) return this

        val separator = "\n\n... output truncated (${length - maxChars} chars omitted) ...\n\n"
        val availableChars = (maxChars - separator.length).coerceAtLeast(0)
        val headChars = availableChars / 4
        val tailChars = availableChars - headChars
        val head = take(headChars)
        val tail = takeLast(tailChars)
        return buildString(maxChars) {
            append(head)
            append(separator)
            append(tail)
        }
    }

    private fun saveSyncProjectOutputToFile(project: Project, output: String): Path {
        val logsDir = getSyncProjectLogsDir(project)
        Files.createDirectories(logsDir)

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val outputFile = logsDir.resolve("sync-project-$timestamp.log")
        Files.writeString(
            outputFile,
            output,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        return outputFile
    }

    private fun getSyncProjectLogsDir(project: Project): Path {
        val projectBasePath = project.basePath
        if (projectBasePath != null) {
            return Path.of(projectBasePath)
        }

        return Path.of(PathManager.getLogPath(), "extended-mcp", "sync-project-logs")
    }

    private suspend fun triggerCMakeReload(project: Project): String? {
        val cmakeWorkspaceClass = findCMakeWorkspaceClass() ?: return null

        val cmakeWorkspaceListenerClass = runCatching {
            Class.forName("com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspaceListener")
        }.getOrNull() ?: return null

        val logBuffer = StringBuilder()
        val result = withTimeoutOrNull(Constants.SYNC_PROJECT_TIMEOUT_MILLISECONDS_VALUE.milliseconds) {
            withContext(Dispatchers.EDT) {
            suspendCancellableCoroutine { continuation ->
                val disposable = Disposer.newDisposable("extended-mcp-cmake-reload")
                continuation.invokeOnCancellation { Disposer.dispose(disposable) }

                val listener = Proxy.newProxyInstance(
                    cmakeWorkspaceListenerClass.classLoader,
                    arrayOf(cmakeWorkspaceListenerClass),
                ) { _, method, args ->
                    when (method.name) {
                        "generationCMakeExited" -> {
                            val output = args?.firstOrNull()
                            extractCMakeOutputLog(output)?.let(logBuffer::append)
                            appendCurrentCMakeConsoleLog(cmakeWorkspaceClass, project, logBuffer)
                            if (didCMakeGenerationFail(output) && continuation.isActive) {
                                continuation.resume(logBuffer.toString().ifBlank { "CMake reload failed with no output." })
                                Disposer.dispose(disposable)
                            }
                        }

                        "reloadingFinished" -> {
                            if (continuation.isActive) {
                                continuation.resume(logBuffer.toString().ifBlank { "CMake reload completed with no output." })
                            }
                            Disposer.dispose(disposable)
                        }
                    }

                    null
                }

                try {
                    subscribeToCMakeWorkspace(project, disposable, cmakeWorkspaceListenerClass, listener)

                    invokeCMakeReload(cmakeWorkspaceClass, project)
                } catch (error: Throwable) {
                    Disposer.dispose(disposable)
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(error))
                    }
                }
            }
            }
        }

        if (result != null) return result

        appendCurrentCMakeConsoleLog(cmakeWorkspaceClass, project, logBuffer)
        return logBuffer.toString().ifBlank { "CMake reload timed out with no output." }
    }

    private fun subscribeToCMakeWorkspace(
        project: Project,
        disposable: Disposable,
        listenerClass: Class<*>,
        listener: Any,
    ) {
        val topic = listenerClass.getField("TOPIC").get(null) as com.intellij.util.messages.Topic<Any>
        project.messageBus.connect(disposable).subscribe(topic, listener)
    }

    private fun extractCMakeOutputLog(output: Any?): String? {
        if (output == null) return null

        val text = runCatching {
            output.javaClass.methods
                .firstOrNull { it.name == "getOutput" && it.parameterCount == 0 }
                ?.invoke(output)
                ?.toString()
        }.getOrNull()?.takeIf(String::isNotBlank)

        if (text != null) return text

        return output.toString().takeIf(String::isNotBlank)
    }

    private fun didCMakeGenerationFail(output: Any?): Boolean {
        if (output == null) return false

        runCatching {
            output.javaClass.methods
                .firstOrNull { it.name == "isSuccess" && it.parameterCount == 0 }
                ?.invoke(output) as? Boolean
        }.getOrNull()?.let { success ->
            return !success
        }

        runCatching {
            output.javaClass.methods
                .firstOrNull { it.name == "getExitCode" && it.parameterCount == 0 }
                ?.invoke(output) as? Number
        }.getOrNull()?.let { exitCode ->
            return exitCode.toInt() != 0
        }

        return false
    }

    private fun appendCurrentCMakeConsoleLog(cmakeWorkspaceClass: Class<*>, project: Project, logBuffer: StringBuilder) {
        extractCurrentCMakeConsoleLog(cmakeWorkspaceClass, project)
            ?.takeIf(String::isNotBlank)
            ?.let { consoleLog ->
                if (!logBuffer.endsWith(consoleLog)) {
                    if (logBuffer.isNotEmpty() && !logBuffer.endsWith("\n") && !consoleLog.startsWith("\n")) {
                        logBuffer.append('\n')
                    }
                    logBuffer.append(consoleLog)
                }
            }
    }

    private fun extractCurrentCMakeConsoleLog(cmakeWorkspaceClass: Class<*>, project: Project): String? {
        val workspace = runCatching {
            cmakeWorkspaceClass.getMethod("getInstance", Project::class.java).invoke(null, project)
        }.getOrNull() ?: return null

        val console = runCatching {
            cmakeWorkspaceClass.methods
                .firstOrNull { it.name == "getConsole" && it.parameterCount == 0 }
                ?.invoke(workspace)
        }.getOrNull() ?: return null

        val consoleClass = runCatching {
            Class.forName("com.jetbrains.cidr.cpp.cmake.workspace.CMakeOutputConsole")
        }.getOrNull() ?: return null

        val flushedConsoleTextMethod = consoleClass.methods.firstOrNull { method ->
            method.name == "getFlushedConsoleText" && method.parameterCount == 1
        } ?: return null

        val consoles = runCatching {
            console.javaClass.methods
                .firstOrNull { it.name == "getConsoles" && it.parameterCount == 0 }
                ?.invoke(console) as? Iterable<*>
        }.getOrNull() ?: return null

        return consoles.mapNotNull { consoleView ->
            runCatching { flushedConsoleTextMethod.invoke(null, consoleView)?.toString() }.getOrNull()
        }
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n")
            .ifBlank { null }
    }

    private fun hasCMakeWorkspaceSupport(): Boolean = findCMakeWorkspaceClass() != null

    private fun findCMakeWorkspaceClass(): Class<*>? = runCatching {
        Class.forName("com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace")
    }.getOrNull()

    private fun invokeCMakeReload(cmakeWorkspaceClass: Class<*>, project: Project) {
        runCatching {
            val baseDir = project.baseDir
            if (baseDir != null) {
                cmakeWorkspaceClass.methods
                    .firstOrNull { method ->
                        method.name == "forceReloadOnOpening" &&
                            method.parameterTypes.size == 1 &&
                            method.parameterTypes[0].isAssignableFrom(baseDir.javaClass)
                    }
                    ?.invoke(null, baseDir)
            }
        }

        val workspace = cmakeWorkspaceClass.getMethod("getInstance", Project::class.java).invoke(null, project)
        val scheduleReloadMethod = cmakeWorkspaceClass.methods.firstOrNull { method ->
            method.name == "scheduleReload" &&
                (method.parameterCount == 0 || (method.parameterCount == 1 && method.parameterTypes[0] == Boolean::class.javaPrimitiveType))
        } ?: error("CMakeWorkspace.scheduleReload was not found")

        when (scheduleReloadMethod.parameterCount) {
            0 -> scheduleReloadMethod.invoke(workspace)
            1 -> scheduleReloadMethod.invoke(workspace, true)
        }
    }

    private fun Project.looksLikeCMakeProject(): Boolean {
        val basePath = basePath ?: return false
        val root = Path.of(basePath)
        if (!Files.isDirectory(root)) return false

        return sequenceOf(
            root.resolve("CMakeLists.txt"),
            root.resolve("CMakePresets.json"),
            root.resolve("CMakeUserPresets.json"),
        ).any(Files::exists)
    }

    @Suppress("UNCHECKED_CAST")
    private fun ExternalSystemManager<*, *, *, *, *>.hasLinkedProjects(project: Project): Boolean {
        val settings = settingsProvider.`fun`(project) as? AbstractExternalSystemSettings<*, ExternalProjectSettings, *> ?: return false
        return settings.linkedProjectsSettings.isNotEmpty()
    }

    @Serializable
    data class UsageInfoEntry(
        val fileUrl: String,
        val lineNumber: Int,
        val lineText: String,
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class UsageInfoResult(
        val entries: List<UsageInfoEntry>,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val probablyHasMoreMatchingEntries: Boolean? = false,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val timedOut: Boolean? = false,
    )
}
