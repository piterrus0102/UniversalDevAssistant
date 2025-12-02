package mcp

import config.ProjectConfig
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import rag.RAGService

private val logger = KotlinLogging.logger {}

/**
 * CodeReviewMCP - MCP сервер для Code Review
 * 
 * Предоставляет инструмент get_code_changes для получения информации о PR.
 * AI должен САМ запросить документацию через search_knowledge_base из LocalMCP!
 */
class CodeReviewMCP(
    private val config: ProjectConfig,
    private val ragService: RAGService
) : MCPServer {

    override suspend fun listTools(): MCPToolsResponse {
        return MCPToolsResponse(
            tools = listOf(
                MCPTool(
                    name = "get_code_changes",
                    description = """
                        Получить информацию об изменениях кода в Pull Request.
                        Возвращает: diff, список файлов, содержимое файлов.
                        
                        ВАЖНО: Этот tool НЕ включает документацию проекта!
                        Для получения Code Conventions используй search_knowledge_base!
                    """.trimIndent(),
                    inputSchema = MCPToolSchema(
                        type = "object",
                        properties = mapOf(
                            "pr_number" to MCPPropertySchema(
                                type = "integer",
                                description = "Номер Pull Request"
                            ),
                            "pr_title" to MCPPropertySchema(
                                type = "string",
                                description = "Название Pull Request"
                            ),
                            "diff" to MCPPropertySchema(
                                type = "string",
                                description = "Git diff изменений"
                            ),
                            "changed_files" to MCPPropertySchema(
                                type = "array",
                                description = "Список измененных файлов"
                            ),
                            "files_content" to MCPPropertySchema(
                                type = "object",
                                description = "Содержимое файлов после изменений"
                            )
                        ),
                        required = listOf("pr_number", "diff", "changed_files")
                    )
                )
            )
        )
    }

    override suspend fun callTool(name: String, args: Map<String, Any>): MCPToolResult {
        logger.info { "🔧 CodeReviewMCP вызов инструмента: $name" }
        
        return when (name) {
            "get_code_changes" -> getCodeChanges(args)
            else -> throw IllegalArgumentException("Неизвестный инструмент: $name")
        }
    }

    /**
     * Получение изменений кода (БЕЗ документации!)
     * AI должен САМ запросить документацию через search_knowledge_base
     */
    private suspend fun getCodeChanges(args: Map<String, Any>): MCPToolResult {
        val prNumber = (args["pr_number"] as? Number)?.toInt() ?: 0
        val prTitle = args["pr_title"] as? String ?: "Unknown PR"
        val diff = args["diff"] as? String ?: ""
        val changedFiles = (args["changed_files"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        
        // Получаем полное содержимое файлов ПОСЛЕ изменений
        val filesContent = (args["files_content"] as? Map<*, *>)
            ?.mapKeys { it.key as String }
            ?.mapValues { it.value as String }
            ?: emptyMap()
        
        logger.info { "📊 Получение изменений PR #$prNumber: $prTitle" }
        logger.info { "📝 Изменено файлов: ${changedFiles.size}" }
        logger.info { "📏 Размер diff: ${diff.length} символов" }
        logger.info { "📄 Полное содержимое: ${filesContent.size} файлов" }
        
        // Категоризация файлов
        val filesByType = categorizeFiles(changedFiles)
        
        // Формируем контекст БЕЗ документации!
        val context = buildString {
            appendLine("# Pull Request #$prNumber: $prTitle")
            appendLine()
            appendLine("## Измененные файлы (${changedFiles.size})")
            filesByType.forEach { (category, files) ->
                appendLine()
                appendLine("### $category")
                files.forEach { file ->
                    appendLine("- `$file`")
                }
            }
            appendLine()
            
            // АКТУАЛЬНЫЙ КОД - ПЕРВЫМ!
            if (filesContent.isNotEmpty()) {
                appendLine("## 📄 АКТУАЛЬНЫЙ КОД (анализируй ЭТОТ)")
                appendLine()
                filesContent.forEach { (file, content) ->
                    appendLine("### Файл: `$file`")
                    appendLine("```${getFileExtension(file)}")
                    val truncated = if (content.length > 15000) {
                        content.take(15000) + "\n... (обрезано)"
                    } else {
                        content
                    }
                    appendLine(truncated)
                    appendLine("```")
                    appendLine()
                }
            }
            
            // Diff в конце (только для справки)
            appendLine("## Git Diff (для справки что изменилось)")
            appendLine("```diff")
            val truncatedDiff = if (diff.length > 8000) {
                diff.take(8000) + "\n... (обрезано)"
            } else {
                diff
            }
            appendLine(truncatedDiff)
            appendLine("```")
        }
        
        logger.info { "✅ Контекст подготовлен (${context.length} символов)" }
        logger.info { "💡 AI должен САМ вызвать search_knowledge_base для получения правил!" }
        
        return MCPToolResult(
            content = listOf(
                MCPContent(
                    type = "text",
                    text = context
                )
            )
        )
    }
    
    private fun categorizeFiles(files: List<String>): Map<String, List<String>> {
        val categories = mutableMapOf<String, MutableList<String>>()
        
        files.forEach { file ->
            val category = when {
                file.endsWith(".php") -> "Backend (PHP)"
                file.endsWith(".js") || file.endsWith(".jsx") -> "Frontend (JavaScript/React)"
                file.endsWith(".ts") || file.endsWith(".tsx") -> "Frontend (TypeScript/React)"
                file.endsWith(".sql") -> "Database (SQL)"
                file.endsWith(".md") -> "Documentation"
                file.endsWith(".py") -> "Scripts (Python)"
                file.endsWith(".yml") || file.endsWith(".yaml") -> "Configuration"
                else -> "Other"
            }
            
            categories.getOrPut(category) { mutableListOf() }.add(file)
        }
        
        return categories
    }
    
    private fun getFileExtension(filePath: String): String {
        return when {
            filePath.endsWith(".php") -> "php"
            filePath.endsWith(".js") || filePath.endsWith(".jsx") -> "javascript"
            filePath.endsWith(".ts") || filePath.endsWith(".tsx") -> "typescript"
            filePath.endsWith(".sql") -> "sql"
            filePath.endsWith(".md") -> "markdown"
            filePath.endsWith(".py") -> "python"
            filePath.endsWith(".yml") || filePath.endsWith(".yaml") -> "yaml"
            else -> ""
        }
    }
}
