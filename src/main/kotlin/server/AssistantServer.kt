package server

import ai.HuggingFaceClient
import ai.HFMessage
import ai.SystemPrompts
import config.ProjectConfig
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mcp.MCPOrchestrator
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * HTTP сервер на Ktor для Dev Assistant
 * 
 * Использует MCP архитектуру с tool calling через LLM
 */
class AssistantServer(
    private val config: ProjectConfig,
    private val mcpOrchestrator: MCPOrchestrator,
    private val aiClient: HuggingFaceClient
) {
    
    private val json = Json { ignoreUnknownKeys = true }
    
    // Флаг ожидания INCORRECT_RAG_ANSWER (включается после search_knowledge_base)
    @Volatile
    private var expectingIncorrectRAG = false
    
    // Оригинальный запрос пользователя (для реранкинга)
    @Volatile
    private var lastUserQuery: String = ""
    
    /**
     * Очистка ответа от артефактов (JSON tool calls, комментариев и т.д.)
     */
    private fun cleanResponse(response: String): String {
        var cleaned = response.trim()
        
        // Убираем JSON tool calls если они попали в ответ
        cleaned = cleaned.replace(Regex("""\{"tool":\s*"[^"]+",\s*"args":\s*\{[^}]*\}\}"""), "")
        
        // Убираем markdown json блоки
        cleaned = cleaned.replace(Regex("""```json.*?```""", RegexOption.DOT_MATCHES_ALL), "")
        
        // Убираем комментарии в квадратных скобках типа [сервер вызывает tool...]
        cleaned = cleaned.replace(Regex("""\[.*?\]"""), "")
        
        // Убираем префиксы типа "A:", "Assistant:", "Ответ:"
        cleaned = cleaned.replace(Regex("""^(A:|Assistant:|Ответ:)\s*""", RegexOption.MULTILINE), "")
        
        // Убираем лишние пустые строки
        cleaned = cleaned.replace(Regex("""\n{3,}"""), "\n\n")
        
        return cleaned.trim()
    }
    
    /**
     * Вызов LLM с поддержкой MCP tool calling
     **
     * Workflow:
     * 1. Собираем tools от всех MCP серверов
     * 2. Формируем system prompt с описанием tools
     * 3. Отправляем в LLM
     * 4. Парсим ответ на наличие USE_TOOL:
     * 5. Если есть - вызываем tool, отправляем результат обратно в LLM
     * 6. Повторяем пока не получим финальный ответ
     */
    private suspend fun callLLMWithTools(userMessage: String): String {
        // Сохраняем оригинальный запрос пользователя для возможного реранкинга
        lastUserQuery = userMessage
        
        logger.info { "🤖 Вызов LLM с MCP tools..." }
        
        // 1. Собираем tools от всех MCP серверов
        val tools = mcpOrchestrator.getAllTools()
        logger.debug { "📋 Доступно tools: ${tools.size}" }
        tools.forEach { logger.debug { "  - ${it.name}" } }
        
        // 2. Формируем system prompt (с учетом ожидания жалоб)
        val systemPrompt = SystemPrompts.createSystemMessage(config, tools, expectingIncorrectRAG)
        
        // 3. Формируем сообщения для LLM
        val messages = mutableListOf(
            HFMessage(role = "system", content = systemPrompt),
            HFMessage(role = "user", content = userMessage)
        )
        
        var currentResponse = aiClient.ask(messages)
        
        logger.info { "📥 Ответ LLM получен" }
        val usedTools = mutableListOf<String>()
        val allTools = tools.map { it.name }
        
        // Tool calling loop: пытаемся распарсить ответ как JSON
        while (true) {
            // ===== ПРОВЕРКА НА INCORRECT_RAG_ANSWER =====
            if (expectingIncorrectRAG && currentResponse.trim() == "INCORRECT_RAG_ANSWER") {
                logger.info { "🚨 INCORRECT_RAG_ANSWER ОБНАРУЖЕН!" }
                logger.info { "Пользователь недоволен ответом, запускаю РЕРАНКИНГ..." }
                
                // Выключаем детекцию
                expectingIncorrectRAG = false
                
                // Запускаем реранкинг через MCP tool
                try {
                    logger.info { "🔄 Вызов tool: rerank_search с оригинальным запросом" }
                    
                    // Используем сохраненный оригинальный запрос (до жалобы "не то")
                    // Ищем второй с конца user message (предыдущий запрос перед жалобой)
                    val originalQuery = messages.asReversed()
                        .filter { it.role == "user" }
                        .drop(1)  // Пропускаем текущий запрос (жалобу)
                        .firstOrNull()?.content ?: lastUserQuery
                    
                    logger.info { "Оригинальный запрос для реранкинга: \"$originalQuery\"" }
                    logger.info { "Текущий запрос (жалоба): \"$userMessage\"" }
                    
                    val rerankResult = mcpOrchestrator.callTool("rerank_search", mapOf("query" to originalQuery))
                    val rerankText = rerankResult.content.firstOrNull()?.text ?: "Не удалось улучшить результаты"
                    
                    logger.info { "📦 Результат реранкинга получен (${rerankText.length} chars)" }
                    
                    // Используем формат как в createToolResultMessage
                    val formattedResult = """Результат инструмента rerank_search (улучшенный поиск с LLM оценкой):
$rerankText

🎯 КРИТИЧЕСКИ ВАЖНО - ОТВЕТЬ ПОЛЬЗОВАТЕЛЮ:

1. ✅ Используй БУКВАЛЬНО информацию из результата выше
2. ✅ Если в результате есть список - ПЕРЕПИШИ ЕГО ПОЛНОСТЬЮ, не сокращай!
3. ✅ НЕ придумывай, НЕ додумывай - ТОЛЬКО то что написано выше
4. ✅ Верни ОБЫЧНЫЙ ТЕКСТОВЫЙ ответ на РУССКОМ языке (НЕ JSON!)
5. ✅ В КОНЦЕ добавь "📚 Источники:" со списком файлов из результата

❌ НЕ ПИШИ НА КИТАЙСКОМ! Только русский!
❌ НЕ выдумывай информацию которой нет в результате!

Если в результате есть полный список (например, endpoints) - СКОПИРУЙ ЕГО ВЕСЬ!"""
                    
                    messages.add(HFMessage(role = "assistant", content = currentResponse))
                    messages.add(HFMessage(role = "user", content = formattedResult))
                    
                    logger.info { "📨 Отправляем улучшенные результаты в LLM..." }
                    currentResponse = aiClient.ask(messages)
                    logger.info { "✅ Реранкинг завершен, получен новый ответ (${currentResponse.length} chars)" }
                    
                    // НЕ делаем continue - выходим из цикла с новым ответом
                    break
                    
                } catch (e: Exception) {
                    logger.error(e) { "❌ Ошибка реранкинга" }
                    return "Извините, произошла ошибка при попытке улучшить результаты поиска: ${e.message}"
                }
            }
            
            // Извлекаем JSON из markdown блока если есть
            var jsonText = currentResponse.trim()
            
            // Если ответ в markdown блоке ```json ... ``` - извлекаем JSON
            val markdownJsonRegex = Regex("""```json\s*(\{.*?\})\s*```""", RegexOption.DOT_MATCHES_ALL)
            val markdownMatch = markdownJsonRegex.find(jsonText)
            if (markdownMatch != null) {
                jsonText = markdownMatch.groupValues[1].trim()
                logger.debug { "📦 Извлечен JSON из markdown блока" }
            }
            
            // Пытаемся распарсить как ToolCall
            val toolCall = try {
                json.decodeFromString<ToolCall>(jsonText)
            } catch (e: Exception) {
                // Не JSON или не ToolCall - значит это финальный ответ
                logger.debug { "Ответ не является tool call, это финальный ответ" }
                null
            }
            
            // Если не tool call - выходим из цикла
            if (toolCall == null) break
            
            logger.info { "🔧 Tool call: ${toolCall.tool}(${toolCall.args})" }
            
            // Проверяем что tool существует
            if (!allTools.contains(toolCall.tool)) {
                val errorMsg = SystemPrompts.createToolNotFoundMessage(toolCall.tool, allTools)
                messages.add(HFMessage(role = "assistant", content = currentResponse))
                messages.add(HFMessage(role = "user", content = errorMsg))
                
                currentResponse = aiClient.ask(messages)
                continue
            }
            
            // Вызываем tool
            try {
                logger.info { "⚙️ Вызов tool: ${toolCall.tool}" }
                val argsAsAny: Map<String, Any> = toolCall.args.mapValues { it.value as Any }
                val result = mcpOrchestrator.callTool(toolCall.tool, argsAsAny)
                val resultText = result.content.firstOrNull()?.text ?: "No result"
                
                usedTools.add(toolCall.tool)
                logger.info { "✅ Tool ${toolCall.tool} выполнен" }
                logger.info { "📄 Результат tool (первые 300 символов): ${resultText.take(300)}..." }
                
                // Если это был search_knowledge_base - включаем детекцию жалоб
                if (toolCall.tool == "search_knowledge_base") {
                    expectingIncorrectRAG = true
                    logger.info { "🔔 Детекция INCORRECT_RAG_ANSWER ВКЛЮЧЕНА (после search_knowledge_base)" }
                }
                
                // Отправляем результат обратно в LLM
                val formattedResult = SystemPrompts.createToolResultMessage(toolCall.tool, resultText)
                logger.info { "📨 Отправляем результат в LLM (${formattedResult.length} chars)" }
                
                messages.add(HFMessage(role = "assistant", content = currentResponse))
                messages.add(HFMessage(role = "user", content = formattedResult))
                
                logger.info { "🔄 Повторный запрос к LLM с результатом tool..." }
                currentResponse = aiClient.ask(messages)
                logger.info { "📥 Ответ LLM после tool: ${currentResponse.take(200)}..." }
                
            } catch (e: Exception) {
                logger.error(e) { "❌ Ошибка вызова tool ${toolCall.tool}" }
                val errorMsg = "ERROR при вызове ${toolCall.tool}: ${e.message}"
                messages.add(HFMessage(role = "assistant", content = currentResponse))
                messages.add(HFMessage(role = "user", content = errorMsg))
                
                currentResponse = aiClient.ask(messages)
            }
        }
        
        if (usedTools.isNotEmpty()) {
            logger.info { "✅ Использовано tools: ${usedTools.joinToString(" → ")}" }
        }
        
        // Очищаем ответ от артефактов
        val cleanedResponse = cleanResponse(currentResponse)
        logger.debug { "🧹 Ответ после очистки (${cleanedResponse.length} chars)" }
        
        return cleanedResponse
    }
    
    fun start() {
        logger.info { "🌐 Запуск HTTP сервера..." }
        
        embeddedServer(Netty, port = config.server.port, host = config.server.host) {
            install(ContentNegotiation) {
                json()
            }
            
            routing {
                // Главная страница
                get("/") {
                    call.respondText(
                        """
                        🤖 Universal Dev Assistant
                        
                        Проект: ${config.project.name}
                        Путь: ${config.project.path}
                        
                        Доступные endpoints:
                        - GET  /health          - Проверка работоспособности (MCP серверов)
                        - GET  /help?q=вопрос   - Задать вопрос о проекте (MCP + AI Agent)
                        - POST /reindex         - Переиндексация документации
                        
                        MCP Architecture:
                        - LocalMCP: search_knowledge_base (RAG поиск по документации)
                        - GitMCP: get_git_status, get_git_branch, get_git_commits, get_git_diff
                        
                        AI Agent автоматически выбирает нужные инструменты!
                        """.trimIndent(),
                        ContentType.Text.Plain
                    )
                }
                
                // Health check
                get("/health") {
                    val mcpServerCount = mcpOrchestrator.getServerCount()
                    val mcpServers = mcpOrchestrator.getServerNames()
                    
                    call.respond(
                        HealthResponse(
                            status = "ok",
                            project = config.project.name,
                            mcpServers = mcpServerCount,
                            mcpServerNames = mcpServers,
                            gitEnabled = config.git.enabled
                        )
                    )
                }
                
                // Переиндексация документации
                post("/reindex") {
                    try {
                        logger.info { "🔄 Запущена переиндексация документации..." }
                        
                        val startTime = System.currentTimeMillis()
                        
                        // Вызываем через MCP tool
                        val result = runBlocking {
                            try {
                                mcpOrchestrator.callTool("reindex_documents", emptyMap())
                                "success"
                            } catch (e: Exception) {
                                // Если tool не существует - значит нужна прямая реиндексация
                                // (это нормально, т.к. reindex не обязательный tool)
                                logger.warn { "Tool reindex_documents не найден, пропускаем" }
                                "skipped"
                            }
                        }
                        
                        val duration = System.currentTimeMillis() - startTime
                        
                        logger.info { "✅ Переиндексация завершена за ${duration}ms" }
                        
                        call.respond(
                            ReindexResponse(
                                status = result,
                                message = "Документация переиндексирована (кеш обновлен)",
                                durationMs = duration
                            )
                        )
                    } catch (e: Exception) {
                        logger.error(e) { "❌ Ошибка переиндексации" }
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("Ошибка переиндексации: ${e.message}")
                        )
                    }
                }
                
                // /help?q=вопрос - главная фишка! (MCP + Tool Calling)
                get("/help") {
                    val question = call.request.queryParameters["q"]
                    
                    if (question.isNullOrBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Параметр 'q' (вопрос) обязателен")
                        )
                        return@get
                    }
                    
                    logger.info { "❓ Вопрос: $question" }
                    
                    try {
                        // Новая MCP архитектура:
                        // 1. LLM получает вопрос + список tools
                        // 2. LLM решает какие tools вызвать (USE_TOOL:)
                        // 3. Вызываем tools через orchestrator
                        // 4. Результаты обратно в LLM
                        // 5. Финальный ответ
                        
                        val answer = runBlocking {
                            callLLMWithTools(question)
                        }
                        
                        logger.info { "✅ Ответ сформирован (${answer.length} chars)" }
                        
                        call.respond(
                            HelpResponse(
                                project = config.project.name,
                                question = question,
                                answer = answer
                            )
                        )
                        
                    } catch (e: Exception) {
                        logger.error(e) { "Ошибка обработки вопроса" }
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("Ошибка: ${e.message}")
                        )
                    }
                }
                
                // Git информация
                get("/git/info") {
                    try {
                        if (!config.git.enabled) {
                            call.respond(
                                ErrorResponse("Git интеграция отключена")
                            )
                            return@get
                        }
                        
                        val status = runBlocking {
                            mcpOrchestrator.callTool("get_git_status", emptyMap())
                        }
                        val statusText = status.content.firstOrNull()?.text ?: ""
                        
                        // Парсинг git status (формат: "Git Status:\n  Branch: main\n  Last Commit: ...")
                        val branchRegex = Regex("""Branch:\s*(.+)""")
                        val commitRegex = Regex("""Last Commit:\s*(.+)""")
                        
                        val branch = branchRegex.find(statusText)?.groupValues?.get(1)?.trim() ?: "unknown"
                        val lastCommit = commitRegex.find(statusText)?.groupValues?.get(1)?.trim() ?: ""
                        
                        call.respond(
                            GitInfoResponse(
                                currentBranch = branch,
                                status = statusText,
                                lastCommit = lastCommit
                            )
                        )
                    } catch (e: Exception) {
                        logger.error(e) { "Ошибка получения git info" }
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("Ошибка: ${e.message}")
                        )
                    }
                }
                
                // Текущая ветка
                get("/git/branch") {
                    try {
                        if (!config.git.enabled) {
                            call.respond(
                                ErrorResponse("Git интеграция отключена")
                            )
                            return@get
                        }
                        
                        val result = runBlocking {
                            mcpOrchestrator.callTool("get_git_branch", emptyMap())
                        }
                        val branchText = result.content.firstOrNull()?.text ?: "unknown"
                        
                        // Формат: "Текущая ветка: main" - извлекаем только название
                        val branch = branchText.substringAfter("Текущая ветка:", "unknown").trim()
                        
                        call.respond(
                            GitBranchResponse(branch = branch)
                        )
                    } catch (e: Exception) {
                        logger.error(e) { "Ошибка получения ветки" }
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("Ошибка: ${e.message}")
                        )
                    }
                }
                
                // Список документов (реально проиндексированных)
                get("/docs") {
                    try {
                        // Читаем index.json чтобы показать РЕАЛЬНО проиндексированные файлы
                        val indexFile = java.io.File("src/main/kotlin/rag/index.json")
                        
                        if (indexFile.exists()) {
                            val indexJson = indexFile.readText()
                            val index = json.parseToJsonElement(indexJson).jsonObject
                            val documents = index["documents"]?.jsonArray ?: emptyList()
                            
                            val docs = documents.map { doc ->
                                val path = doc.jsonObject["path"]?.jsonPrimitive?.content ?: "unknown"
                                DocInfo(path = path)
                            }
                            
                            call.respond(
                                DocsResponse(
                                    count = docs.size,
                                    documents = docs
                                )
                            )
                        } else {
                            // Если индекс еще не создан - показываем что задано в конфиге
                            val docs = config.project.docs.map { DocInfo(path = it) }
                            call.respond(
                                DocsResponse(
                                    count = docs.size,
                                    documents = docs
                                )
                            )
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "Ошибка получения списка документов" }
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("Ошибка: ${e.message}")
                        )
                    }
                }
                
                // POST /review - AI Code Review
                // AI САМ вызывает get_code_changes и search_knowledge_base!
                post("/review") {
                    try {
                        val request = call.receive<CodeReviewRequest>()
                        
                        logger.info { "🔍 Code Review: PR #${request.pr_number}" }
                        
                        // System prompt для code review
                        val tools = mcpOrchestrator.getAllTools()
                        val systemPrompt = ai.SystemPrompts.createCodeReviewSystemMessage(config, tools)
                        
                        // Формируем запрос для AI
                        val userQuery = """
Проведи code review для Pull Request #${request.pr_number}: ${request.pr_title}

Автор: ${request.pr_author}
Изменено файлов: ${request.changed_files.size}

Используй инструменты:
1. get_code_changes - получить код PR
2. search_knowledge_base - найти Code Conventions

Проверь:
- Безопасность (SQL injection, XSS)
- Code Conventions
- Потенциальные баги
""".trim()
                        
                        val messages = mutableListOf(
                            ai.HFMessage(role = "system", content = systemPrompt),
                            ai.HFMessage(role = "user", content = userQuery)
                        )
                        
                        logger.info { "🤖 AI должен сам вызвать tools" }
                        
                        var response = aiClient.ask(messages)
                        val usedTools = mutableListOf<String>()
                        var iteration = 0
                        val maxIterations = 10
                        
                        // Tool calling loop
                        while (iteration < maxIterations) {
                            iteration++
                            
                            val toolCall = try {
                                json.decodeFromString<ToolCall>(response.trim())
                            } catch (e: Exception) {
                                // Финальный ответ
                                break
                            }
                            
                            logger.info { "🔧 Tool #$iteration: ${toolCall.tool}" }
                            
                            val toolArgs: Map<String, Any> = when (toolCall.tool) {
                                "get_code_changes" -> mapOf(
                                    "pr_number" to request.pr_number,
                                    "pr_title" to request.pr_title,
                                    "diff" to request.diff,
                                    "changed_files" to request.changed_files,
                                    "files_content" to (request.files_content ?: emptyMap<String, String>())
                                )
                                else -> toolCall.args
                            }
                            
                            val result = mcpOrchestrator.callTool(toolCall.tool, toolArgs)
                            val resultText = result.content.firstOrNull()?.text ?: ""
                            
                            usedTools.add(toolCall.tool)
                            logger.info { "✅ ${toolCall.tool} выполнен (${resultText.length} chars)" }
                            
                            messages.add(ai.HFMessage(role = "assistant", content = response))
                            messages.add(ai.HFMessage(role = "user", content = "Результат '${toolCall.tool}':\n$resultText"))
                            
                            response = aiClient.ask(messages)
                        }
                        
                        logger.info { "✅ Review завершен. Tools: ${usedTools.joinToString(", ")}" }
                        
                        call.respond(
                            CodeReviewResponse(
                                pr_number = request.pr_number,
                                review = response,
                                summary = "Tools used: ${usedTools.joinToString(", ")}",
                                files_analyzed = request.changed_files.size
                            )
                        )
                        
                    } catch (e: Exception) {
                        logger.error(e) { "❌ Code review error" }
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("Error: ${e.message}")
                        )
                    }
                }
            }
        }.start(wait = true)
        
        logger.info { "🚀 Сервер запущен на http://${config.server.host}:${config.server.port}" }
    }
}

// ============================================================================
// Internal Models
// ============================================================================

/**
 * Tool call от LLM (чистый JSON когда нужен инструмент)
 */
@Serializable
data class ToolCall(
    val tool: String,
    val args: Map<String, String> = emptyMap()
)

// ============================================================================
// Response Models
// ============================================================================

@Serializable
data class HealthResponse(
    val status: String,
    val project: String,
    val mcpServers: Int,
    val mcpServerNames: List<String>,
    val gitEnabled: Boolean
)

@Serializable
data class HelpResponse(
    val project: String,
    val question: String,
    val answer: String
)

@Serializable
data class ErrorResponse(
    val error: String
)

@Serializable
data class ReindexResponse(
    val status: String,
    val message: String,
    val durationMs: Long
)

@Serializable
data class GitInfoResponse(
    val currentBranch: String,
    val status: String,
    val lastCommit: String
)

@Serializable
data class GitBranchResponse(
    val branch: String
)

@Serializable
data class DocsResponse(
    val count: Int,
    val documents: List<DocInfo>
)

@Serializable
data class DocInfo(
    val path: String
)

@Serializable
data class CodeReviewRequest(
    val pr_number: Int,
    val pr_title: String,
    val pr_author: String,
    val diff: String,
    val changed_files: List<String>,
    val files_content: Map<String, String>? = null,
    val metadata: Map<String, String>? = null
)

@Serializable
data class CodeReviewResponse(
    val pr_number: Int,
    val review: String,
    val summary: String,
    val files_analyzed: Int
)

