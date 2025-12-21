@file:Suppress("FunctionName", "unused")

package io.github.sushkovpv.extendedmcp.extendedmcpintellij.mcp

import com.intellij.find.FindManager
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.externalSystem.ExternalSystemManager
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
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
import io.github.sushkovpv.extendedmcp.extendedmcpintellij.mcp.Constants.MAX_USAGE_TEXT_CHARS
import kotlinx.coroutines.*
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Simple demo toolset that proves plugin can extend MCP server.
 */
class ExtendedMcpToolset : McpToolset {
    @McpTool
    @McpDescription(
        """
        |Searches with a regex pattern within project dependencies (libraries) using IntelliJ's search engine.
        |Prefer this tool over reading files with command-line tools because it's much faster.
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
        linesBefore: Int = Constants.DEFAULT_LINES_AROUND,
        @McpDescription("Number of lines to show after the target line")
        linesAfter: Int = Constants.DEFAULT_LINES_AROUND,
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
    @McpDescription("Synchronizes all external systems (Gradle, Maven, NPM, etc.) for the project to apply changes in dependencies.")
    suspend fun sync_project() {
        val project = currentCoroutineContext().project
        syncProject(project)
    }

    suspend fun syncProject(project: Project) {
        withContext(Dispatchers.EDT) {
            FileDocumentManager.getInstance().saveAllDocuments()
        }

        val managers = ExternalSystemManager.EP_NAME.extensionList
        if (managers.isEmpty()) return

        coroutineScope {
            managers.map { manager ->
                launch {
                    suspendCancellableCoroutine { continuation ->
                        val spec = ImportSpecBuilder(project, manager.systemId)
                            .withCallback(object : ExternalProjectRefreshCallback {
                                override fun onSuccess(externalProject: DataNode<ProjectData>?) {
                                    continuation.resume(Unit)
                                }

                                override fun onFailure(errorMessage: String, errorDetails: String?) {
                                    val fullMessage = if (errorDetails != null) "$errorMessage: $errorDetails" else errorMessage
                                    continuation.resumeWithException(RuntimeException(fullMessage))
                                }
                            })
                            .build()

                        ExternalSystemUtil.refreshProjects(spec)
                    }
                }
            }.joinAll()
        }
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
        val probablyHasMoreMatchingEntries: Boolean = false,
        @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
        val timedOut: Boolean? = false,
    )
}
