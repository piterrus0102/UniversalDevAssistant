package mcp

import mu.KotlinLogging
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Git MCP - Model Context Protocol для работы с Git
 * Выполняет git команды в контексте проекта
 */
class GitMCP(private val projectPath: String) {
    
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
     * Получить полный статус
     */
    fun getFullStatus(): String {
        return executeGit("status")
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
     * Получить список всех веток
     */
    fun getBranches(): List<String> {
        val output = executeGit("branch", "-a")
        return output.lines()
            .filter { it.isNotBlank() }
            .map { it.trim().removePrefix("* ").trim() }
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
data class GitInfo(
    val isGitRepo: Boolean,
    val currentBranch: String,
    val lastCommit: String,
    val modifiedFiles: List<String>,
    val status: String,
    val remote: String
)

