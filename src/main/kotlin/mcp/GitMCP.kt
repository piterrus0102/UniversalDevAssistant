package mcp

import kotlinx.serialization.Serializable
import mu.KotlinLogging
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Git MCP - Model Context Protocol для работы с Git
 * Выполняет git команды в контексте проекта
 * 
 * Реализует MCPServer для предоставления Git инструментов через MCP протокол
 */
class GitMCP(private val projectPath: String) : MCPServer {
    
    override suspend fun listTools(): MCPToolsResponse {
        return MCPToolsResponse(
            tools = listOf(
                MCPTool(
                    name = "get_git_status",
                    description = "Получить текущий статус Git репозитория (измененные файлы, текущая ветка)",
                    inputSchema = MCPToolSchema(
                        type = "object",
                        properties = emptyMap(),
                        required = emptyList()
                    )
                ),
                MCPTool(
                    name = "get_git_branch",
                    description = "Получить название текущей Git ветки",
                    inputSchema = MCPToolSchema(
                        type = "object",
                        properties = emptyMap(),
                        required = emptyList()
                    )
                ),
                MCPTool(
                    name = "get_git_commits",
                    description = "Получить список последних коммитов",
                    inputSchema = MCPToolSchema(
                        type = "object",
                        properties = mapOf(
                            "limit" to MCPPropertySchema(
                                type = "number",
                                description = "Количество коммитов (по умолчанию 5)"
                            )
                        ),
                        required = emptyList()
                    )
                ),
                MCPTool(
                    name = "get_git_diff",
                    description = "Получить diff (изменения) для конкретного файла",
                    inputSchema = MCPToolSchema(
                        type = "object",
                        properties = mapOf(
                            "file" to MCPPropertySchema(
                                type = "string",
                                description = "Путь к файлу"
                            )
                        ),
                        required = listOf("file")
                    )
                )
            )
        )
    }
    
    override suspend fun callTool(name: String, args: Map<String, Any>): MCPToolResult {
        logger.info { "🔧 GitMCP вызов инструмента: $name" }
        
        return when (name) {
            "get_git_status" -> {
                val info = getFullInfo()
                val statusText = buildString {
                    appendLine("Git Status:")
                    appendLine("  Branch: ${info.currentBranch}")
                    appendLine("  Last Commit: ${info.lastCommit}")
                    appendLine("  Modified Files: ${info.modifiedFiles.size}")
                    if (info.modifiedFiles.isNotEmpty()) {
                        appendLine("  Files:")
                        info.modifiedFiles.forEach { file ->
                            appendLine("    - $file")
                        }
                    } else {
                        appendLine("  (no changes)")
                    }
                }
                
                MCPToolResult(
                    content = listOf(
                        MCPContent(
                            type = "text",
                            text = statusText
                        )
                    )
                )
            }
            
            "get_git_branch" -> {
                val branch = getCurrentBranch()
                MCPToolResult(
                    content = listOf(
                        MCPContent(
                            type = "text",
                            text = "Текущая ветка: $branch"
                        )
                    )
                )
            }
            
            "get_git_commits" -> {
                val limit = (args["limit"] as? Number)?.toInt() ?: 5
                val commits = getRecentCommits(limit)
                val commitsText = buildString {
                    appendLine("Последние $limit коммитов:")
                    commits.forEach { commit ->
                        appendLine("  - $commit")
                    }
                }
                
                MCPToolResult(
                    content = listOf(
                        MCPContent(
                            type = "text",
                            text = commitsText
                        )
                    )
                )
            }
            
            "get_git_diff" -> {
                val file = args["file"] as? String
                    ?: throw IllegalArgumentException("Параметр 'file' обязателен")
                
                val diff = getDiff(file)
                MCPToolResult(
                    content = listOf(
                        MCPContent(
                            type = "text",
                            text = if (diff.isBlank()) {
                                "Нет изменений в файле $file"
                            } else {
                                "Diff для файла $file:\n$diff"
                            }
                        )
                    )
                )
            }
            
            else -> {
                throw IllegalArgumentException("Неизвестный инструмент: $name")
            }
        }
    }
    
    /**
     * Получить текущую ветку
     */
    fun getCurrentBranch(): String {
        return executeGit("branch", "--show-current").trim()
    }
    
    /**
     * Получить статус репозитория (короткий формат)
     */
    fun getStatus(): String {
        return executeGit("status", "--short")
    }

    /**
     * Получить последние коммиты
     */
    fun getRecentCommits(limit: Int = 5): List<String> {
        val output = executeGit("log", "--oneline", "-n", limit.toString())
        return output.lines().filter { it.isNotBlank() }
    }
    
    /**
     * Получить информацию о последнем коммите
     */
    fun getLastCommit(): String {
        return executeGit("log", "-1", "--pretty=format:%h - %s (%an, %ar)")
    }
    
    /**
     * Получить список измененных файлов
     */
    fun getModifiedFiles(): List<String> {
        val output = executeGit("status", "--short")
        return output.lines()
            .filter { it.isNotBlank() }
            .map { line ->
                // Формат: " M file.txt" или "?? file.txt"
                val parts = line.trim().split(Regex("\\s+"), 2)
                if (parts.size == 2) parts[1] else line
            }
    }

    /**
     * Получить информацию о remote
     */
    fun getRemoteInfo(): String {
        return try {
            executeGit("remote", "-v")
        } catch (e: Exception) {
            "No remote configured"
        }
    }
    
    /**
     * Получить diff для конкретного файла
     */
    fun getDiff(fileName: String): String {
        return executeGit("diff", fileName)
    }
    
    /**
     * Проверка, является ли директория git-репозиторием
     */
    fun isGitRepository(): Boolean {
        return try {
            executeGit("rev-parse", "--git-dir")
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Получить полную информацию о состоянии репозитория
     */
    fun getFullInfo(): GitInfo {
        return try {
            GitInfo(
                isGitRepo = isGitRepository(),
                currentBranch = getCurrentBranch(),
                lastCommit = getLastCommit(),
                modifiedFiles = getModifiedFiles(),
                status = getStatus(),
                remote = getRemoteInfo()
            )
        } catch (e: Exception) {
            logger.error(e) { "Ошибка получения информации о git" }
            GitInfo(
                isGitRepo = false,
                currentBranch = "unknown",
                lastCommit = "unknown",
                modifiedFiles = emptyList(),
                status = "Error: ${e.message}",
                remote = "unknown"
            )
        }
    }
    
    /**
     * Выполнить git команду
     */
    private fun executeGit(vararg args: String): String {
        val command = listOf("git", "-C", projectPath) + args
        
        logger.debug { "Выполнение команды: ${command.joinToString(" ")}" }
        
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        
        val output = BufferedReader(InputStreamReader(process.inputStream))
            .use { it.readText() }
        
        val exitCode = process.waitFor()
        
        if (exitCode != 0) {
            logger.warn { "Git команда завершилась с кодом $exitCode: ${command.joinToString(" ")}" }
            logger.warn { "Output: $output" }
            throw RuntimeException("Git command failed (exit code: $exitCode): ${command.joinToString(" ")}\n$output")
        }
        
        return output
    }
}

/**
 * Модель данных с информацией о git репозитории
 */
@Serializable
data class GitInfo(
    val isGitRepo: Boolean,
    val currentBranch: String,
    val lastCommit: String,
    val modifiedFiles: List<String>,
    val status: String,
    val remote: String
)

