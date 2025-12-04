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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mcp.GitMCP
import mcp.LocalMCP
import mcp.MCPContentType
import mcp.MCPOrchestrator
import mu.KotlinLogging
import server.request_response.CodeReviewRequest
import server.request_response.CodeReviewResponse
import server.request_response.DocInfo
import server.request_response.DocsResponse
import server.request_response.ErrorResponse
import server.request_response.GitBranchResponse
import server.request_response.GitInfoResponse
import server.request_response.HealthResponse
import server.request_response.HelpResponse
import server.request_response.ReindexResponse
import server.helper.SupportRequestsContainer

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
    
    // Текущая роль ассистента
    @Volatile
    private var currentRole: AssistantRole = AssistantRole.COMMON

    fun start() {
        logger.info { "🌐 Запуск HTTP сервера..." }
        
        embeddedServer(Netty, port = config.server.port, host = config.server.host) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            
            routing {
                // Главная страница
                get("/") {
                    call.respondText(
                        """
                        🤖 Universal Dev Assistant
                        
                        Проект: ${config.project.name}
                        Путь: ${config.project.path}
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
                
                // ============================================================================
                // Управление ролями ассистента
                // ============================================================================
                
                // Получить список всех доступных ролей
                get("/roles") {
                    call.respond(
                        RolesListResponse(
                            currentRole = currentRole.name,
                            availableRoles = AssistantRole.getAllRolesInfo()
                        )
                    )
                }
                
                // Получить текущую роль
                get("/role") {
                    call.respond(
                        CurrentRoleResponse(
                            currentRole = currentRole.name,
                            description = currentRole.description
                        )
                    )
                }
                
                // Сменить роль: POST /role с телом {"role": "HELPER"}
                // или GET /role/HELPER
                post("/role") {
                    try {
                        val request = call.receive<ChangeRoleRequest>()
                        val newRole = AssistantRole.fromName(request.role)
                        
                        if (newRole == null) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ChangeRoleResponse(
                                    success = false,
                                    previousRole = currentRole.name,
                                    newRole = request.role,
                                    message = "Неизвестная роль '${request.role}'. Доступные: ${AssistantRole.entries.joinToString { it.name }}"
                                )
                            )
                            return@post
                        }
                        
                        val previousRole = currentRole
                        currentRole = newRole
                        
                        logger.info { "🔄 Роль изменена: ${previousRole.name} → ${newRole.name}" }
                        
                        call.respond(
                            ChangeRoleResponse(
                                success = true,
                                previousRole = previousRole.name,
                                newRole = newRole.name,
                                message = "Роль успешно изменена на ${newRole.displayName}"
                            )
                        )
                    } catch (e: Exception) {
                        logger.error(e) { "Ошибка смены роли" }
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Ошибка: ${e.message}. Формат: {\"role\": \"HELPER\"}")
                        )
                    }
                }
                
                // Альтернативный способ смены роли через URL
                get("/role/{roleName}") {
                    val roleName = call.parameters["roleName"] ?: ""
                    val newRole = AssistantRole.fromName(roleName)
                    
                    if (newRole == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ChangeRoleResponse(
                                success = false,
                                previousRole = currentRole.name,
                                newRole = roleName,
                                message = "Неизвестная роль '$roleName'. Доступные: ${AssistantRole.entries.joinToString { it.name }}"
                            )
                        )
                        return@get
                    }
                    
                    val previousRole = currentRole
                    currentRole = newRole
                    
                    logger.info { "🔄 Роль изменена: ${previousRole.name} → ${newRole.name}" }
                    
                    call.respond(
                        ChangeRoleResponse(
                            success = true,
                            previousRole = previousRole.name,
                            newRole = newRole.name,
                            message = "Роль успешно изменена на ${newRole.displayName}"
                        )
                    )
                }
                
                // Переиндексация документации
                post("/reindex") {
                    try {
                        logger.info { "🔄 Запущена переиндексация документации..." }
                        
                        val startTime = System.currentTimeMillis()
                        val result = runBlocking {
                            try {
                                mcpOrchestrator.callTool(LocalMCP.REINDEX_DOCUMENTS_TOOL_NAME, emptyMap())
                                "success"
                            } catch (e: Exception) {
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
                        // Проверяем - это команда управления задачами?
                        val answer = if (isTaskManagementCommand(question)) {
                            logger.info { "🎫 Обнаружена команда управления задачами: $question" }
                            runBlocking {
                                callTaskManagementLLM(question)
                            }
                        } else {
                            runBlocking {
                                callLLMWithTools(question)
                            }
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
                        if (config.git.enabled.not()) {
                            call.respond(
                                ErrorResponse("Git интеграция отключена")
                            )
                            return@get
                        }
                        
                        val status = runBlocking {
                            mcpOrchestrator.callTool(GitMCP.GET_GIT_STATUS_TOOL_NAME, emptyMap())
                        }
                        val statusText = status.content.firstOrNull()?.text ?: ""
                        
                        call.respond(
                            GitInfoResponse(
                                currentBranch = status.content.first { it.type == MCPContentType.currentBranch }.text,
                                status = statusText,
                                lastCommit = status.content.first { it.type == MCPContentType.lastCommit }.text,
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
                            mcpOrchestrator.callTool(GitMCP.GET_GIT_BRANCH_TOOL_NAME, emptyMap())
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
                // AI САМ запрашивает diff через GitHubMCP и документацию через LocalMCP!
                post("/review") {
                    try {
                        val request = call.receive<CodeReviewRequest>()
                        
                        logger.info { "🔍 Code Review: PR #${request.pr_number}" }
                        
                        // System prompt для code review
                        val tools = mcpOrchestrator.getAllTools()
                        val systemPrompt = ai.SystemPrompts.createCodeReviewSystemMessage(config, tools)
                        
                        // AI получает только номер PR - сам запросит diff и доки!
                        val userQuery = """
                                Ты - профессиональный ревьюер кода, знаешь все code conventions языков программирования
                                Проведи code review для Pull Request #${request.pr_number}
                                
                                Тебе нужно:
                                1. Получить информацию о PR и diff через инструменты
                                2. Запросить документацию по проекту чтоб оценить соответствие
                                3. Проанализировать код на соответствие правилам
                                4. Выдать структурированный review
                                
                                Необязательно чтоб там были замечания, 
                                """.trim()
                        
                        val messages = mutableListOf(
                            ai.HFMessage(role = "system", content = systemPrompt),
                            ai.HFMessage(role = "user", content = userQuery)
                        )
                        
                        logger.info { "🤖 AI сам вызовет tools для получения данных" }
                        
                        var response = aiClient.ask(messages)
                        val usedTools = mutableListOf<String>()
                        var iteration = 0
                        val maxIterations = 10
                        
                        // Tool calling loop - AI сам решает какие tools вызвать
                        while (iteration < maxIterations) {
                            iteration++

                            logger.debug { "📦 Ответ AI: $response" }
                            
                            // Парсим {"tools": [...]} формат
                            val toolsResponse = try {
                                json.decodeFromString<ToolsResponse>(response)
                            } catch (e: Exception) {
                                // Fallback: пробуем старый формат {"tool": "...", "args": {...}}
                                try {
                                    val singleTool = json.decodeFromString<ToolCall>(response)
                                    ToolsResponse(tools = listOf(singleTool))
                                } catch (e2: Exception) {
                                    logger.debug { "Нет tool вызовов, финальный ответ" }
                                    break
                                }
                            }
                            
                            if (toolsResponse.tools.isEmpty()) {
                                logger.debug { "Пустой массив tools, финальный ответ" }
                                break
                            }
                            
                            logger.info { "🔧 Iteration #$iteration: ${toolsResponse.tools.size} tool(s)" }
                            
                            // Выполняем ВСЕ tools из массива
                            val results = mutableListOf<String>()
                            for (toolCall in toolsResponse.tools) {
                                val toolArgs = toolCall.argsToMap().toMutableMap()
                                
                                // Если tool требует pr_number но AI его не передал - подставляем из request
                                if (toolCall.tool.contains("pr_", ignoreCase = true) && !toolArgs.containsKey("pr_number")) {
                                    toolArgs["pr_number"] = request.pr_number
                                }
                                
                                try {
                                    val result = mcpOrchestrator.callTool(toolCall.tool, toolArgs)
                                    val resultText = result.content.firstOrNull()?.text ?: ""
                                    
                                    usedTools.add(toolCall.tool)
                                    results.add("📌 ${toolCall.tool}:\n$resultText")
                                    logger.info { "✅ ${toolCall.tool} выполнен (${resultText.length} chars)" }
                                } catch (e: Exception) {
                                    logger.error(e) { "❌ Ошибка вызова tool ${toolCall.tool}" }
                                    results.add("📌 ${toolCall.tool}: ERROR - ${e.message}")
                                }
                            }
                            
                            // Отправляем все результаты одним сообщением
                            messages.add(ai.HFMessage(role = "assistant", content = response))
                            messages.add(ai.HFMessage(role = "user", content = "Результаты tools:\n\n${results.joinToString("\n\n")}"))
                            
                            response = aiClient.ask(messages)
                        }
                        
                        logger.info { "✅ Review завершен. Tools: ${usedTools.joinToString(", ")}" }
                        
                        call.respond(
                            CodeReviewResponse(
                                pr_number = request.pr_number,
                                review = response,
                                tools_used = usedTools
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
                
                // ============================================================================
                // HELPER: Обработка запросов пользователей (поддержка)
                // ============================================================================
                
                // POST /support - обработка запросов пользователей через HELPER
                post("/support") {
                    try {
                        logger.info { "🎫 HELPER: Начало обработки запросов поддержки..." }
                        
                        // Читаем requests.json
                        val requestsFile = java.io.File("src/main/kotlin/server/helper/requests.json")
                        if (!requestsFile.exists()) {
                            call.respond(
                                HttpStatusCode.NotFound,
                                ErrorResponse("Файл requests.json не найден")
                            )
                            return@post
                        }
                        
                        val requestsJson = requestsFile.readText()
                        val requestsContainer = json.decodeFromString<SupportRequestsContainer>(requestsJson)
                        
                        logger.info { "📋 Загружено ${requestsContainer.requests.size} запросов для обработки" }
                        
                        // Вызываем LLM с HELPER ролью
                        val answer = runBlocking {
                            callHelperLLM(requestsJson)
                        }
                        
                        logger.info { "✅ HELPER завершил обработку" }
                        logger.info { "📝 Ответ LLM: ${answer.take(500)}..." }
                        
                        // Парсим ответ и сохраняем в answers.json
                        try {
                            // Очищаем ответ от возможных markdown блоков
                            val cleanedAnswer = answer
                                .replace("```json", "")
                                .replace("```", "")
                                .trim()
                            
                            // Валидируем что это JSON
                            val answersContainer = json.decodeFromString<SupportRequestsContainer>(cleanedAnswer)
                            
                            // Сохраняем в answers.json
                            val answersFile = java.io.File("src/main/kotlin/server/helper/answers.json")
                            answersFile.writeText(json.encodeToString(SupportRequestsContainer.serializer(), answersContainer))
                            
                            logger.info { "💾 Ответы сохранены в answers.json" }
                            println("✅ Обработка закончена")
                            
                            // Возвращаем тот же JSON с заполненными answer
                            call.respond(answersContainer)
                        } catch (e: Exception) {
                            logger.error(e) { "❌ Ошибка парсинга ответа LLM" }
                            
                            // Сохраняем сырой ответ для отладки
                            val rawFile = java.io.File("src/main/kotlin/server/helper/answers_raw.txt")
                            rawFile.writeText(answer)
                            
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ErrorResponse("Ошибка парсинга ответа LLM: ${e.message}. Сырой ответ сохранён в answers_raw.txt")
                            )
                        }
                        
                    } catch (e: Exception) {
                        logger.error(e) { "❌ Ошибка обработки запросов поддержки" }
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("Ошибка: ${e.message}")
                        )
                    }
                }
            }
        }.start(wait = true)
        
        logger.info { "🚀 Сервер запущен на http://${config.server.host}:${config.server.port}" }
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
        tools.forEach { logger.info { "  - ${it.name}" } }

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

                    val rerankResult = mcpOrchestrator.callTool(LocalMCP.RERANK_SEARCH_TOOL_NAME, mapOf("query" to originalQuery))
                    val rerankText = rerankResult.content.firstOrNull()?.text ?: "Не удалось улучшить результаты"

                    logger.info { "📦 Результат реранкинга получен (${rerankText.length} chars)" }

                    // Используем формат как в createToolResultMessage
                    val formattedResult = rerankPrompt(rerankText)

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

            // Парсим {"tools": [...]} формат
            val toolsResponse = try {
                json.decodeFromString<ToolsResponse>(currentResponse)
            } catch (e: Exception) {
                // Fallback: пробуем старый формат {"tool": "...", "args": {...}}
                try {
                    val singleTool = json.decodeFromString<ToolCall>(currentResponse)
                    ToolsResponse(tools = listOf(singleTool))
                } catch (e2: Exception) {
                    logger.debug { "Нет tool вызовов, финальный ответ" }
                    break
                }
            }
            
            if (toolsResponse.tools.isEmpty()) {
                logger.debug { "Пустой массив tools, финальный ответ" }
                break
            }

            logger.info { "🔧 Tool calls: ${toolsResponse.tools.map { it.tool }}" }

            // Выполняем ВСЕ tools из массива
            val results = mutableListOf<String>()
            for (toolCall in toolsResponse.tools) {
                // Проверяем что tool существует
                if (!allTools.contains(toolCall.tool)) {
                    results.add("📌 ${toolCall.tool}: ERROR - инструмент не существует")
                    continue
                }

                // Вызываем tool
                try {
                    logger.info { "⚙️ Вызов tool: ${toolCall.tool}" }
                    val argsAsAny: Map<String, Any> = toolCall.argsToMap()
                    val result = mcpOrchestrator.callTool(toolCall.tool, argsAsAny)
                    val resultText = result.content.firstOrNull()?.text ?: "No result"

                    usedTools.add(toolCall.tool)
                    results.add("📌 ${toolCall.tool}:\n$resultText")
                    logger.info { "✅ Tool ${toolCall.tool} выполнен (${resultText.length} chars)" }

                    // Если это был search_knowledge_base - включаем детекцию жалоб
                    if (toolCall.tool == LocalMCP.SEARCH_KNOWLEDGE_BASE_TOOL_NAME) {
                        expectingIncorrectRAG = true
                        logger.info { "🔔 Детекция INCORRECT_RAG_ANSWER ВКЛЮЧЕНА" }
                    }

                } catch (e: Exception) {
                    logger.error(e) { "❌ Ошибка вызова tool ${toolCall.tool}" }
                    results.add("📌 ${toolCall.tool}: ERROR - ${e.message}")
                }
            }

            // Отправляем все результаты одним сообщением
            val formattedResult = SystemPrompts.createToolResultMessage(
                toolsResponse.tools.joinToString(", ") { it.tool },
                results.joinToString("\n\n")
            )
            logger.info { "📨 Отправляем результаты ${toolsResponse.tools.size} tools в LLM" }

            messages.add(HFMessage(role = "assistant", content = currentResponse))
            messages.add(HFMessage(role = "user", content = formattedResult))

            logger.info { "🔄 Повторный запрос к LLM с результатами tools..." }
            currentResponse = aiClient.ask(messages)
            logger.info { "📥 Ответ LLM после tools: ${currentResponse.take(200)}..." }
        }

        if (usedTools.isNotEmpty()) {
            logger.info { "✅ Использовано tools: ${usedTools.joinToString(" → ")}" }
        }

        logger.debug { "🧹 Ответ после очистки (${currentResponse.length} chars)" }

        return currentResponse
    }
    
    /**
     * Проверка - является ли сообщение командой управления задачами
     */
    private fun isTaskManagementCommand(message: String): Boolean {
        val trimmed = message.trim().lowercase()
        return trimmed.startsWith("/create_tasks") ||
               trimmed.startsWith("/edit_task") ||
               trimmed.startsWith("/delete_task")
    }
    
    /**
     * Вызов LLM для команд управления задачами
     * 
     * Обрабатывает команды:
     * - /create_tasks - создание задач на основе answers.json
     * - /edit_task <id или описание> <text> <title>
     * - /delete_task <id или описание>
     */
    private suspend fun callTaskManagementLLM(command: String): String {
        logger.info { "🎫 Обработка команды управления задачами: $command" }
        
        // Парсим команду и аргументы
        val parts = command.trim().split(" ", limit = 2)
        val commandName = parts[0]
        val commandArgs = if (parts.size > 1) parts[1] else ""
        
        // Собираем ТОЛЬКО нужные tools для task management (не все 50!)
        val allTools = mcpOrchestrator.getAllTools()
        val tools = when {
            commandName == "/create_tasks" -> allTools.filter { 
                it.name == "read_answers_file" 
            }
            commandName == "/edit_task" || commandName == "/delete_task" -> allTools.filter { 
                it.name == "read_tickets_file" 
            }
            else -> allTools.filter {
                it.name == "read_tickets_file" || it.name == "read_answers_file"
            }
        }
        
        logger.info { "🔧 Task Management: отфильтровано ${tools.size} инструментов из ${allTools.size}" }
        
        // Формируем system prompt для управления задачами
        val systemPrompt = SystemPrompts.createTaskManagementSystemMessage(config, tools, commandName, commandArgs)
        
        val messages = mutableListOf(
            HFMessage(role = "system", content = systemPrompt),
            HFMessage(role = "user", content = command)
        )
        
        var currentResponse = aiClient.ask(messages)
        logger.info { "📥 Ответ LLM получен" }
        
        val usedTools = mutableListOf<String>()
        val allToolNames = tools.map { it.name }
        var iteration = 0
        val maxIterations = 10
        
        // Tool calling loop
        while (iteration < maxIterations) {
            iteration++
            
            // Парсим tool calls
            val toolsResponse = try {
                json.decodeFromString<ToolsResponse>(currentResponse)
            } catch (e: Exception) {
                try {
                    val singleTool = json.decodeFromString<ToolCall>(currentResponse)
                    ToolsResponse(tools = listOf(singleTool))
                } catch (e2: Exception) {
                    logger.debug { "Нет tool вызовов, проверяем на финальный JSON" }
                    break
                }
            }
            
            if (toolsResponse.tools.isEmpty()) {
                logger.debug { "Пустой массив tools, финальный ответ" }
                break
            }
            
            logger.info { "🔧 Task Management Iteration #$iteration: ${toolsResponse.tools.map { it.tool }}" }
            
            // Выполняем tools
            val results = mutableListOf<String>()
            for (toolCall in toolsResponse.tools) {
                if (!allToolNames.contains(toolCall.tool)) {
                    results.add("📌 ${toolCall.tool}: ERROR - инструмент не существует")
                    continue
                }
                
                try {
                    logger.info { "⚙️ Вызов tool: ${toolCall.tool}" }
                    val argsAsAny: Map<String, Any> = toolCall.argsToMap()
                    val result = mcpOrchestrator.callTool(toolCall.tool, argsAsAny)
                    val resultText = result.content.firstOrNull()?.text ?: "No result"
                    
                    usedTools.add(toolCall.tool)
                    results.add("📌 ${toolCall.tool}:\n$resultText")
                    logger.info { "✅ Tool ${toolCall.tool} выполнен (${resultText.length} chars)" }
                } catch (e: Exception) {
                    logger.error(e) { "❌ Ошибка вызова tool ${toolCall.tool}" }
                    results.add("📌 ${toolCall.tool}: ERROR - ${e.message}")
                }
            }
            
            // Формируем сообщение с результатами
            val formattedResult = """
Результаты инструментов:

${results.joinToString("\n\n")}

🎯 Продолжай выполнение задачи. Если нужны ещё инструменты - вызови их.
Когда задача завершена - верни финальный JSON ответ.
""".trimIndent()
            
            messages.add(HFMessage(role = "assistant", content = currentResponse))
            messages.add(HFMessage(role = "user", content = formattedResult))
            
            logger.info { "🔄 Повторный запрос к LLM..." }
            currentResponse = aiClient.ask(messages)
            logger.info { "📥 Ответ получен (${currentResponse.length} chars)" }
        }
        
        if (usedTools.isNotEmpty()) {
            logger.info { "✅ Task Management: Использовано tools: ${usedTools.joinToString(" → ")}" }
        }
        
        // Для /create_tasks - парсим JSON и сохраняем tickets.json
        if (commandName == "/create_tasks") {
            return processCreateTasksResponse(currentResponse)
        }
        
        // Для /edit_task и /delete_task - тоже сохраняем результат
        if (commandName == "/edit_task" || commandName == "/delete_task") {
            return processModifyTasksResponse(currentResponse)
        }
        
        return currentResponse
    }
    
    /**
     * Обработка ответа LLM для /create_tasks
     * Парсит JSON с тикетами и сохраняет в tickets.json
     */
    private fun processCreateTasksResponse(response: String): String {
        logger.info { "💾 Обработка ответа /create_tasks и сохранение tickets.json..." }
        
        try {
            // Очищаем ответ от markdown
            val cleanedResponse = response
                .replace("```json", "")
                .replace("```", "")
                .trim()
            
            // Десериализуем JSON в модель
            val jsonParser = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            val createResponse = jsonParser.decodeFromString<CreateTasksResponse>(cleanedResponse)
            
            val container = server.helper.TicketsContainer(tickets = createResponse.tickets)
            
            // Сохраняем в файл
            val ticketsFile = java.io.File("src/main/kotlin/server/helper/tickets.json")
            val prettyJson = Json { prettyPrint = true; encodeDefaults = true }
            ticketsFile.writeText(prettyJson.encodeToString(server.helper.TicketsContainer.serializer(), container))
            
            logger.info { "✅ tickets.json сохранён (${createResponse.tickets.size} тикетов)" }
            
            // Формируем текстовый отчёт
            val tickets = createResponse.tickets
            val analyzed = createResponse.summary?.analyzed ?: 0
            val created = tickets.size
            val skipped = createResponse.summary?.skipped ?: (analyzed - created)
            
            // Группируем по приоритету
            val highPriority = tickets.filter { it.priority == "HIGH" }
            val normalPriority = tickets.filter { it.priority == "NORMAL" }
            val lowPriority = tickets.filter { it.priority == "LOW" }
            
            val report = buildString {
                appendLine("✅ Задачи успешно созданы!")
                appendLine()
                appendLine("📊 Статистика:")
                appendLine("   • Проанализировано обращений: $analyzed")
                appendLine("   • Создано тикетов: $created")
                appendLine("   • Пропущено (не требует разработки): $skipped")
                appendLine()
                appendLine("📌 По приоритету:")
                appendLine("   🔴 HIGH: ${highPriority.size}")
                appendLine("   🟡 NORMAL: ${normalPriority.size}")
                appendLine("   🟢 LOW: ${lowPriority.size}")
                appendLine()
                if (tickets.isNotEmpty()) {
                    appendLine("🎫 Созданные задачи:")
                    tickets.forEachIndexed { index, ticket ->
                        val priorityIcon = when(ticket.priority) {
                            "HIGH" -> "🔴"
                            "LOW" -> "🟢"
                            else -> "🟡"
                        }
                        appendLine("   ${index + 1}. $priorityIcon ${ticket.title}")
                        appendLine("      ID: ${ticket.id}")
                        appendLine("      Приоритет: ${ticket.priority}")
                        appendLine("      Решение: ${ticket.suggestiveTechnicalDecision.take(100)}...")
                        appendLine()
                    }
                }
                appendLine("💾 Файл сохранён: src/main/kotlin/server/helper/tickets.json")
            }
            
            return report
            
        } catch (e: Exception) {
            logger.error(e) { "❌ Ошибка парсинга ответа /create_tasks" }
            
            // Сохраняем сырой ответ для отладки
            val rawFile = java.io.File("src/main/kotlin/server/helper/tickets_raw.txt")
            rawFile.writeText(response)
            
            return "❌ Ошибка обработки ответа: ${e.message}\n\nСырой ответ сохранён в tickets_raw.txt для отладки.\n\nОтвет LLM:\n$response"
        }
    }
    
    /**
     * Обработка ответа LLM для /edit_task и /delete_task
     * Парсит JSON с тикетами и сохраняет в tickets.json
     */
    private fun processModifyTasksResponse(response: String): String {
        logger.info { "💾 Обработка ответа edit/delete и сохранение tickets.json..." }
        
        try {
            // Очищаем ответ от markdown
            val cleanedResponse = response
                .replace("```json", "")
                .replace("```", "")
                .trim()
            
            // Десериализуем JSON в модель
            val jsonParser = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            val modifyResponse = jsonParser.decodeFromString<ModifyTasksResponse>(cleanedResponse)
            
            val container = server.helper.TicketsContainer(tickets = modifyResponse.tickets)
            
            // Сохраняем в файл
            val ticketsFile = java.io.File("src/main/kotlin/server/helper/tickets.json")
            val prettyJson = Json { prettyPrint = true; encodeDefaults = true }
            ticketsFile.writeText(prettyJson.encodeToString(server.helper.TicketsContainer.serializer(), container))
            
            logger.info { "✅ tickets.json обновлён (${modifyResponse.tickets.size} тикетов)" }
            
            return modifyResponse.message ?: "✅ Задачи обновлены. Всего тикетов: ${modifyResponse.tickets.size}"
            
        } catch (e: Exception) {
            // Если не удалось распарсить как JSON - возвращаем текстовый ответ
            logger.debug { "Ответ не является JSON, возвращаем как текст: ${e.message}" }
            return response
        }
    }
    
    /**
     * Вызов LLM для HELPER роли (обработка запросов поддержки)
     * 
     * Использует только RAG и LocalMCP (без GitHubMCP).
     * Возвращает JSON с заполненными ответами.
     */
    private suspend fun callHelperLLM(requestsJson: String): String {
        logger.info { "🎫 HELPER: Вызов LLM для обработки запросов поддержки..." }
        
        // 1. Собираем tools (фильтруем GitHub-связанные)
        val allTools = mcpOrchestrator.getAllTools()
        val helperTools = allTools.filter { tool ->
            !tool.name.contains("github", ignoreCase = true) &&
            !tool.name.contains("pr_", ignoreCase = true) &&
            !tool.name.contains("pull_request", ignoreCase = true)
        }
        
        logger.info { "📋 HELPER tools: ${helperTools.map { it.name }}" }
        
        // 2. Формируем system prompt для HELPER
        val systemPrompt = SystemPrompts.createHelperSystemMessage(config, helperTools, requestsJson)
        
        // 3. Формируем сообщения для LLM
        val messages = mutableListOf(
            HFMessage(role = "system", content = systemPrompt),
            HFMessage(role = "user", content = "Обработай запросы пользователей и верни JSON с заполненными ответами.")
        )
        
        var currentResponse = aiClient.ask(messages)
        logger.info { "📥 HELPER: Ответ LLM получен" }
        
        val usedTools = mutableListOf<String>()
        val helperToolNames = helperTools.map { it.name }
        var iteration = 0
        val maxIterations = 15 // Больше итераций для обработки нескольких запросов
        
        // Tool calling loop
        while (iteration < maxIterations) {
            iteration++
            
            // Проверяем - это финальный JSON ответ или tool call?
            val toolsResponse = try {
                json.decodeFromString<ToolsResponse>(currentResponse)
            } catch (e: Exception) {
                // Пробуем старый формат
                try {
                    val singleTool = json.decodeFromString<ToolCall>(currentResponse)
                    ToolsResponse(tools = listOf(singleTool))
                } catch (e2: Exception) {
                    // Это не tool call - проверяем, это финальный JSON?
                    if (currentResponse.contains("\"requests\"") && currentResponse.contains("\"answer\"")) {
                        logger.info { "🎯 HELPER: Получен финальный JSON ответ" }
                        break
                    }
                    logger.debug { "HELPER: Нет tool вызовов, проверяем ответ..." }
                    break
                }
            }
            
            if (toolsResponse.tools.isEmpty()) {
                logger.debug { "HELPER: Пустой массив tools" }
                break
            }
            
            logger.info { "🔧 HELPER Iteration #$iteration: ${toolsResponse.tools.map { it.tool }}" }
            
            // Выполняем tools
            val results = mutableListOf<String>()
            for (toolCall in toolsResponse.tools) {
                // Проверяем что tool доступен для HELPER
                if (!helperToolNames.contains(toolCall.tool)) {
                    logger.warn { "⚠️ HELPER: Tool ${toolCall.tool} не доступен" }
                    results.add("📌 ${toolCall.tool}: ERROR - инструмент не доступен для HELPER")
                    continue
                }
                
                try {
                    logger.info { "⚙️ HELPER: Вызов tool ${toolCall.tool}" }
                    val argsAsAny: Map<String, Any> = toolCall.argsToMap()
                    val result = mcpOrchestrator.callTool(toolCall.tool, argsAsAny)
                    val resultText = result.content.firstOrNull()?.text ?: "No result"
                    
                    usedTools.add(toolCall.tool)
                    results.add("📌 ${toolCall.tool}:\n$resultText")
                    logger.info { "✅ HELPER: Tool ${toolCall.tool} выполнен" }
                } catch (e: Exception) {
                    logger.error(e) { "❌ HELPER: Ошибка вызова tool ${toolCall.tool}" }
                    results.add("📌 ${toolCall.tool}: ERROR - ${e.message}")
                }
            }
            
            // Формируем сообщение с результатами
            val formattedResult = """
Результаты инструментов:

${results.joinToString("\n\n")}

🎯 ВАЖНО: 
- Используй эту информацию для формирования ответов на запросы пользователей
- Если нужна дополнительная информация - вызови ещё инструменты
- Когда все ответы готовы - верни ПОЛНЫЙ JSON с заполненными answer (без markdown!)
""".trimIndent()
            
            messages.add(HFMessage(role = "assistant", content = currentResponse))
            messages.add(HFMessage(role = "user", content = formattedResult))
            
            logger.info { "🔄 HELPER: Повторный запрос к LLM..." }
            currentResponse = aiClient.ask(messages)
            logger.info { "📥 HELPER: Ответ получен (${currentResponse.length} chars)" }
        }
        
        if (usedTools.isNotEmpty()) {
            logger.info { "✅ HELPER: Использовано tools: ${usedTools.joinToString(" → ")}" }
        }
        
        return currentResponse
    }

}

private fun rerankPrompt(rerankText: String): String {
    return """Результат инструмента rerank_search (улучшенный поиск с LLM оценкой):
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
}

// ============================================================================
// Internal Models
// ============================================================================

/**
 * Ответ LLM на /create_tasks
 */
@Serializable
data class CreateTasksResponse(
    val tickets: List<server.helper.Ticket>,
    val summary: CreateTasksSummary? = null
)

@Serializable
data class CreateTasksSummary(
    val analyzed: Int = 0,
    val created: Int = 0,
    val skipped: Int = 0
)

/**
 * Ответ LLM на /edit_task и /delete_task
 */
@Serializable
data class ModifyTasksResponse(
    val tickets: List<server.helper.Ticket>,
    val message: String? = null
)

/**
 * Ответ с массивом tool calls от LLM
 * Формат: {"tools": [{"tool": "name", "args": {...}}, ...]}
 */
@Serializable
data class ToolsResponse(
    val tools: List<ToolCall>
)

/**
 * Tool call от LLM (чистый JSON когда нужен инструмент)
 * args может содержать разные типы: String, Int, Array, Object
 */
@Serializable
data class ToolCall(
    val tool: String,
    val args: JsonObject = JsonObject(emptyMap())
) {
    /**
     * Конвертирует args в Map<String, Any> для вызова MCP tool
     */
    fun argsToMap(): Map<String, Any> {
        return args.mapValues { (_, value) ->
            when (value) {
                is kotlinx.serialization.json.JsonPrimitive -> {
                    if (value.isString) {
                        value.content
                    } else {
                        // Пробуем распарсить как число или boolean
                        value.content.toIntOrNull() 
                            ?: value.content.toLongOrNull() 
                            ?: value.content.toDoubleOrNull() 
                            ?: value.content.toBooleanStrictOrNull()
                            ?: value.content
                    }
                }
                is JsonArray -> value.map { elem ->
                    if (elem is kotlinx.serialization.json.JsonPrimitive) elem.content else elem.toString()
                }
                is JsonObject -> value.toString()
            }
        }
    }
}
