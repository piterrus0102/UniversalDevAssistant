package mcp

import config.ProjectConfig
import mu.KotlinLogging
import rag.RAGService

private val logger = KotlinLogging.logger {}

/**
 * Локальный MCP сервер с инструментами для работы с документацией проекта
 * 
 * Предоставляет инструмент:
 * - search_knowledge_base: поиск по документации через RAG
 */
class LocalMCP(
    private val config: ProjectConfig,
    private val ragService: RAGService
) : MCPServer {
    
    override suspend fun listTools(): MCPToolsResponse {
        return MCPToolsResponse(
            tools = listOf(
                MCPTool(
                    name = READ_PROJECT_FILE_TOOL_NAME,
                    description = "Читает ИСХОДНЫЙ КОД файла проекта. " +
                                  "ИСПОЛЬЗУЙ ЭТОТ ИНСТРУМЕНТ если пользователь упоминает КОНКРЕТНЫЙ ФАЙЛ: " +
                                  "router.php, App.jsx, main.py, controller.php, index.js и т.д. " +
                                  "Возвращает полное содержимое файла для анализа.",
                    inputSchema = MCPToolSchema(
                        type = "object",
                        properties = mapOf(
                            "filename" to MCPPropertySchema(
                                type = "string",
                                description = "Имя файла (router.php) или путь (backend/router.php)"
                            )
                        ),
                        required = listOf("filename")
                    )
                ),
                MCPTool(
                    name = SEARCH_KNOWLEDGE_BASE_TOOL_NAME,
                    description = "Этот инструмент предназначен для поиска концептуальной информации в документации проекта. \n" +
                            "Используйте его для вопросов об архитектуре, настройке, гайдах по использованию и общей информации о проекте.\n" +
                            "\n" +
                            "Что ищет: README.md, документацию (.md файлы), руководства, описание API, архитектурные решения\n" +
                            "Что НЕ ищет: исходный код, конфигурационные файлы, логи - для этого используйте read_project_file\n" +
                            "\n" +
                            "Идеально подходит для:\n" +
                            "• Понимания общей архитектуры проекта\n" +
                            "• Поиска инструкций по настройке и развертыванию\n" +
                            "• Ответов на вопросы \"как это работает\" на концептуальном уровне\n" +
                            "• Изучения гайдов и руководств",
                    inputSchema = MCPToolSchema(
                        type = "object",
                        properties = mapOf(
                            "query" to MCPPropertySchema(
                                type = "string",
                                description = "Поисковый запрос для поиска в документации"
                            )
                        ),
                        required = listOf("query")
                    )
                ),
                MCPTool(
                    name = REINDEX_DOCUMENTS_TOOL_NAME,
                    description = "Переиндексация документации проекта. Заново сканирует все документы согласно config.yaml и обновляет индекс.",
                    inputSchema = MCPToolSchema(
                        type = "object",
                        properties = emptyMap(),
                        required = emptyList()
                    )
                ),
                MCPTool(
                    name = RERANK_SEARCH_TOOL_NAME,
                    description = "Улучшенный поиск с реранкингом для повышения релевантности. Используется когда пользователь недоволен предыдущим ответом.",
                    inputSchema = MCPToolSchema(
                        type = "object",
                        properties = mapOf(
                            "query" to MCPPropertySchema(
                                type = "string",
                                description = "Поисковый запрос для реранкинга"
                            )
                        ),
                        required = listOf("query")
                    )
                ),
                // ============================================================================
                // Инструменты для работы с задачами (тикетами)
                // ============================================================================
                MCPTool(
                    name = READ_ANSWERS_FILE_TOOL_NAME,
                    description = "Читает файл с ОТВЕТАМИ службы поддержки. " +
                                  "Содержит обращения пользователей и ответы на них. " +
                                  "Используй когда нужно проанализировать обращения для создания задач на разработку.",
                    inputSchema = MCPToolSchema(
                        type = "object",
                        properties = emptyMap(),
                        required = emptyList()
                    )
                ),
                MCPTool(
                    name = READ_TICKETS_FILE_TOOL_NAME,
                    description = "Читает файл с ЗАДАЧАМИ (тикетами) на разработку. " +
                                  "Содержит тикеты с полями: id, title, text, suggestiveTechnicalDecision, priority. " +
                                  "Используй когда нужно получить, отредактировать или удалить существующие задачи. " +
                                  "Это единственный источник данных о задачах - они хранятся отдельно от проекта.",
                    inputSchema = MCPToolSchema(
                        type = "object",
                        properties = emptyMap(),
                        required = emptyList()
                    )
                )
            )
        )
    }
    
    override suspend fun callTool(name: String, args: Map<String, Any>): MCPToolResult {
        logger.info { "🔧 LocalMCP вызов инструмента: $name" }
        
        return when (name) {
            SEARCH_KNOWLEDGE_BASE_TOOL_NAME -> {
                val query = args["query"] as? String
                    ?: throw IllegalArgumentException("Параметр 'query' обязателен")
                
                logger.info { "🔍 Поиск по документации: \"$query\"" }
                
                // Выполняем RAG поиск (берём 2 лучших чанка, не 3)
                val result = ragService.buildContext(query, maxDocs = 2)
                
                // Логируем найденные документы
                logger.info { "=" .repeat(60) }
                logger.info { "📚 RAG РЕЗУЛЬТАТЫ для запроса: \"$query\"" }
                logger.info { "📄 Найдено источников: ${result.sources.size}" }
                result.sources.forEachIndexed { index, source ->
                    logger.info { "  ${index + 1}. $source" }
                }
                logger.info { "📝 Размер контекста: ${result.context.length} символов" }
                logger.info { "=" .repeat(60) }
                
                // Добавляем информацию об источниках
                val sources = result.sources.joinToString("\n") { "- $it" }
                val contextWithSources = """
                    ${result.context}
                    
                    📌 Эта информация взята из следующих файлов (укажи их в разделе "Источники:"):
                    $sources
                """.trimIndent()
                
                MCPToolResult(
                    content = listOf(
                        MCPContent(
                            type = MCPContentType.text,
                            text = contextWithSources
                        )
                    )
                )
            }

            REINDEX_DOCUMENTS_TOOL_NAME -> {
                logger.info { "🔄 Переиндексация документации..." }
                
                try {
                    ragService.indexDocuments()
                    
                    MCPToolResult(
                        content = listOf(
                            MCPContent(
                                type = MCPContentType.text,
                                text = "✅ Документация успешно переиндексирована"
                            )
                        )
                    )
                } catch (e: Exception) {
                    logger.error(e) { "❌ Ошибка переиндексации" }
                    MCPToolResult(
                        content = listOf(
                            MCPContent(
                                type = MCPContentType.text,
                                text = "❌ Ошибка переиндексации: ${e.message}"
                            )
                        )
                    )
                }
            }

            RERANK_SEARCH_TOOL_NAME -> {
                val query = args["query"] as? String
                    ?: throw IllegalArgumentException("Параметр 'query' обязателен")
                
                logger.info { "🔄 Реранкинг для запроса: \"$query\"" }
                
                // Выполняем реранкинг (берём ТОЛЬКО лучший чанк!)
                val result = kotlinx.coroutines.runBlocking {
                    ragService.rerankSearch(query, topK = 1)
                }
                
                // Логируем найденные документы
                logger.info { "=" .repeat(60) }
                logger.info { "🔄 RERANK РЕЗУЛЬТАТЫ для запроса: \"$query\"" }
                logger.info { "📄 Найдено источников: ${result.sources.size}" }
                result.sources.forEachIndexed { index, source ->
                    logger.info { "  ${index + 1}. $source" }
                }
                logger.info { "📝 Размер контекста: ${result.context.length} символов" }
                logger.info { "=" .repeat(60) }
                
                // Добавляем информацию об источниках
                val sources = result.sources.joinToString("\n") { "- $it" }
                val contextWithSources = """
                    ${result.context}
                    
                    📌 Эта информация взята из следующих файлов (укажи их в разделе "Источники:"):
                    $sources
                """.trimIndent()
                
                MCPToolResult(
                    content = listOf(
                        MCPContent(
                            type = MCPContentType.text,
                            text = contextWithSources
                        )
                    )
                )
            }

            READ_PROJECT_FILE_TOOL_NAME -> {
                val filename = args["filename"] as? String
                    ?: throw IllegalArgumentException("Параметр 'filename' обязателен")
                
                logger.info { "📄 Чтение файла проекта: \"$filename\"" }
                
                // Ищем файл в проекте
                val projectPath = java.nio.file.Paths.get(config.project.path)
                val foundFile = findFileInProject(projectPath, filename)
                
                if (foundFile == null) {
                    // Ищем похожий файл с другим расширением
                    // Извлекаем только имя файла без пути и расширения
                    val fileNameOnly = filename.substringAfterLast("/")
                    val baseNameWithoutExt = fileNameOnly.substringBeforeLast(".")
                    val similarFile = findSimilarFile(projectPath, baseNameWithoutExt)
                    
                    if (similarFile != null) {
                        // Нашли похожий файл с другим расширением
                        val content = similarFile.toFile().readText()
                        val lines = content.lines().size
                        val relativePath = similarFile.toString().removePrefix(config.project.path + "/")
                        
                        MCPToolResult(
                            content = listOf(
                                MCPContent(
                                    type = MCPContentType.text,
                                    text = "⚠️ Файл '$filename' не найден, но есть похожий:\n\n" +
                                           "📄 Файл: $relativePath\n" +
                                           "📏 Строк: $lines\n" +
                                           "\n" +
                                           "```\n" +
                                           content +
                                           "\n```\n" +
                                           "\n" +
                                           "📌 Укажи в разделе \"Источники:\" этот файл:\n" +
                                           "- $relativePath"
                                )
                            )
                        )
                    } else {
                        MCPToolResult(
                            content = listOf(
                                MCPContent(
                                    type = MCPContentType.text,
                                    text = "❌ Файл '$filename' не найден в проекте.\n" +
                                           "Попробуй:\n" +
                                           "- Проверить имя файла\n" +
                                           "- Указать полный путь (backend/router.php)\n" +
                                           "- Использовать get_git_status чтобы увидеть структуру проекта"
                                )
                            )
                        )
                    }
                } else {
                    val content = foundFile.toFile().readText()
                    val lines = content.lines().size
                    val relativePath = foundFile.toString().removePrefix(config.project.path + "/")
                    
                    MCPToolResult(
                        content = listOf(
                            MCPContent(
                                type = MCPContentType.text,
                                text = "📄 Файл: $relativePath\n" +
                                       "📏 Строк: $lines\n" +
                                       "\n" +
                                       "```\n" +
                                       content +
                                       "\n```\n" +
                                       "\n" +
                                       "📌 Укажи в разделе \"Источники:\" этот файл:\n" +
                                       "- $relativePath"
                            )
                        )
                    )
                }
            }
            
            // ============================================================================
            // Обработчики инструментов для работы с задачами (тикетами)
            // ============================================================================
            
            READ_ANSWERS_FILE_TOOL_NAME -> {
                logger.info { "📋 Чтение файла answers.json..." }
                
                val answersFile = java.io.File("src/main/kotlin/server/helper/answers.json")
                
                if (!answersFile.exists()) {
                    MCPToolResult(
                        content = listOf(
                            MCPContent(
                                type = MCPContentType.text,
                                text = "❌ Файл answers.json не найден.\n" +
                                       "Сначала нужно обработать запросы пользователей через /support endpoint."
                            )
                        )
                    )
                } else {
                    val content = answersFile.readText()
                    logger.info { "✅ answers.json прочитан (${content.length} chars)" }
                    
                    MCPToolResult(
                        content = listOf(
                            MCPContent(
                                type = MCPContentType.text,
                                text = "📋 Содержимое answers.json:\n\n$content"
                            )
                        )
                    )
                }
            }
            
            READ_TICKETS_FILE_TOOL_NAME -> {
                logger.info { "🎫 Чтение файла tickets.json..." }
                
                val ticketsFile = java.io.File("src/main/kotlin/server/helper/tickets.json")
                
                if (!ticketsFile.exists()) {
                    MCPToolResult(
                        content = listOf(
                            MCPContent(
                                type = MCPContentType.text,
                                text = "📋 Файл tickets.json пуст или не существует.\n" +
                                       "Задач пока нет. Верни пустой массив tickets: []"
                            )
                        )
                    )
                } else {
                    val content = ticketsFile.readText()
                    logger.info { "✅ tickets.json прочитан (${content.length} chars)" }
                    
                    MCPToolResult(
                        content = listOf(
                            MCPContent(
                                type = MCPContentType.text,
                                text = "🎫 Содержимое tickets.json:\n\n$content"
                            )
                        )
                    )
                }
            }
            
            else -> {
                throw IllegalArgumentException("Неизвестный инструмент: $name")
            }
        }
    }
    
    /**
     * Поиск файла в проекте по имени или пути
     */
    private fun findFileInProject(projectPath: java.nio.file.Path, filename: String): java.nio.file.Path? {
        logger.info { "🔍 Поиск файла '$filename' в проекте $projectPath" }
        
        // 1. Проверяем прямой путь (точное совпадение)
        val directPath = projectPath.resolve(filename)
        if (java.nio.file.Files.exists(directPath) && java.nio.file.Files.isRegularFile(directPath)) {
            logger.info { "✅ Найден по прямому пути: $directPath" }
            return directPath
        }
        
        // 2. Если это путь (содержит /) - ищем по относительному пути (case-insensitive)
        if (filename.contains("/")) {
            logger.info { "🔄 Поиск по относительному пути (case-insensitive)..." }
            
            // Сначала пробуем точное совпадение пути
            val exactMatch = try {
                java.nio.file.Files.walk(projectPath)
                    .filter { java.nio.file.Files.isRegularFile(it) }
                    .filter { !shouldIgnorePath(it) }
                    .filter { path ->
                        val relativePath = projectPath.relativize(path).toString()
                        relativePath.equals(filename, ignoreCase = true)
                    }
                    .findFirst()
                    .orElse(null)
            } catch (e: Exception) {
                null
            }
            
            if (exactMatch != null) {
                logger.info { "✅ Найден по точному пути: $exactMatch" }
                return exactMatch
            }
            
            // Если не нашли точный путь - извлекаем имя файла и ищем его в указанной директории
            val parts = filename.split("/")
            val fileNameOnly = parts.last()
            val dirPrefix = parts.dropLast(1).joinToString("/")
            
            logger.info { "🔍 Точный путь не найден, ищем '$fileNameOnly' в директориях начинающихся с '$dirPrefix'..." }
            
            return try {
                java.nio.file.Files.walk(projectPath)
                    .filter { java.nio.file.Files.isRegularFile(it) }
                    .filter { !shouldIgnorePath(it) }
                    .filter { path ->
                        val relativePath = projectPath.relativize(path).toString()
                        // Проверяем что путь начинается с указанного префикса и имя файла совпадает
                        val nameMatches = path.fileName.toString().equals(fileNameOnly, ignoreCase = true)
                        val pathStartsWith = relativePath.startsWith(dirPrefix, ignoreCase = true)
                        
                        if (nameMatches && pathStartsWith) {
                            logger.info { "✅ Найден: $path (путь: $relativePath)" }
                        }
                        
                        nameMatches && pathStartsWith
                    }
                    .findFirst()
                    .orElse(null)
            } catch (e: Exception) {
                logger.error(e) { "Ошибка поиска по пути $filename" }
                null
            }
        }
        
        // 3. Ищем по имени файла рекурсивно (case-insensitive)
        return try {
            logger.info { "🔄 Рекурсивный поиск файла по имени (case-insensitive)..." }
            logger.info { "🔍 Ищем файл похожий на: $filename" }
            
            // Для отладки: собираем все .kt файлы
            val allFiles = mutableListOf<String>()
            var totalFiles = 0
            var ignoredFiles = 0
            
            val found = java.nio.file.Files.walk(projectPath)
                .filter { java.nio.file.Files.isRegularFile(it) }
                .peek { totalFiles++ }
                .filter { path ->
                    val shouldIgnore = shouldIgnorePath(path)
                    if (shouldIgnore) {
                        ignoredFiles++
                    } else {
                        // Собираем похожие файлы для отчета
                        val fileName = path.fileName.toString()
                        val baseName = fileName.substringBeforeLast(".")
                        
                        // Извлекаем только имя файла из искомого (без пути)
                        val searchFileNameOnly = filename.substringAfterLast("/")
                        val searchBaseName = searchFileNameOnly.substringBeforeLast(".")
                        
                        if (baseName.equals(searchBaseName, ignoreCase = true)) {
                            allFiles.add(path.toString())
                        }
                    }
                    !shouldIgnore
                }
                .filter { path ->
                    val nameMatches = path.fileName.toString().equals(filename, ignoreCase = true)
                    if (nameMatches) {
                        logger.info { "📁 ТОЧНОЕ СОВПАДЕНИЕ: $path" }
                    }
                    nameMatches
                }
                .findFirst()
                .orElse(null)
            
            logger.info { "📊 Статистика: всего файлов=$totalFiles, игнорировано=$ignoredFiles" }
            
            if (found != null) {
                logger.info { "✅ Итоговый файл: $found" }
            } else {
                logger.warn { "❌ Файл '$filename' не найден в проекте" }
                if (allFiles.isNotEmpty()) {
                    logger.warn { "💡 Похожие файлы найдены (первые 10):" }
                    allFiles.take(10).forEach { logger.warn { "  - $it" } }
                }
            }
            
            found
        } catch (e: Exception) {
            logger.error(e) { "Ошибка поиска файла $filename" }
            null
        }
    }
    
    /**
     * Поиск похожего файла (с другим расширением)
     */
    private fun findSimilarFile(projectPath: java.nio.file.Path, baseName: String): java.nio.file.Path? {
        logger.info { "🔍 Поиск похожего файла с базовым именем: $baseName" }
        
        return try {
            java.nio.file.Files.walk(projectPath)
                .filter { java.nio.file.Files.isRegularFile(it) }
                .filter { !shouldIgnorePath(it) }
                .filter { path ->
                    val fileName = path.fileName.toString()
                    val fileBaseName = fileName.substringBeforeLast(".")
                    fileBaseName.equals(baseName, ignoreCase = true)
                }
                .findFirst()
                .orElse(null)
                .also {
                    if (it != null) {
                        logger.info { "✅ Найден похожий: $it" }
                    }
                }
        } catch (e: Exception) {
            logger.error(e) { "Ошибка поиска похожего файла" }
            null
        }
    }
    
    /**
     * Проверка что путь нужно игнорировать
     */
    private fun shouldIgnorePath(path: java.nio.file.Path): Boolean {
        val pathStr = path.toString()
        return config.project.ignore.any { ignore ->
            pathStr.contains("/$ignore/") || pathStr.endsWith("/$ignore")
        }
    }

    companion object {
        const val SEARCH_KNOWLEDGE_BASE_TOOL_NAME = "search_knowledge_base"
        const val REINDEX_DOCUMENTS_TOOL_NAME = "reindex_documents"
        const val RERANK_SEARCH_TOOL_NAME = "rerank_search"
        const val READ_PROJECT_FILE_TOOL_NAME = "read_project_file"
        
        // Инструменты для работы с задачами
        const val READ_ANSWERS_FILE_TOOL_NAME = "read_answers_file"
        const val READ_TICKETS_FILE_TOOL_NAME = "read_tickets_file"
    }
}

