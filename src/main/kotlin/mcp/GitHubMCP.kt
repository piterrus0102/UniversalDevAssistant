package mcp

import config.GitHubConfig
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.io.*
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * GitHub MCP Client - правильная реализация через MCP протокол
 * 
 * Архитектура:
 *   Kotlin App → GitHubMCP → StdioTransport → github-mcp-server (Go) → GitHub API
 * 
 * Общение происходит через stdin/stdout с использованием JSON-RPC 2.0
 */
class GitHubMCP(private val config: GitHubConfig) : MCPServer {
    
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var isConnected = false
    private val requestId = AtomicInteger(0)
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }
    
    // Кеш инструментов
    private var cachedTools: List<MCPTool>? = null
    
    /**
     * Подключиться к GitHub MCP Server (Go binary)
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (isConnected) {
            logger.info { "🔌 GitHub MCP уже подключен" }
            return@withContext true
        }
        
        val serverPath = config.mcp_server_path
        if (serverPath.isNullOrBlank()) {
            logger.warn { "⚠️ GitHub MCP Server path не указан в config.yaml (github.mcp_server_path)" }
            return@withContext false
        }
        
        val serverFile = File(serverPath)
        if (!serverFile.exists()) {
            logger.error { "❌ GitHub MCP Server не найден: $serverPath" }
            logger.info { "💡 Скачайте github-mcp-server с https://github.com/github/github-mcp-server" }
            return@withContext false
        }
        
        if (!serverFile.canExecute()) {
            logger.error { "❌ GitHub MCP Server не исполняемый: $serverPath" }
            logger.info { "💡 Сделайте файл исполняемым: chmod +x $serverPath" }
            return@withContext false
        }
        
        try {
            logger.info { "🚀 Запуск GitHub MCP Server: $serverPath" }
            
            // Запускаем процесс с токеном в environment
            val processBuilder = ProcessBuilder(serverPath, "stdio")
                .apply {
                    environment()["GITHUB_PERSONAL_ACCESS_TOKEN"] = config.token
                }
                .redirectErrorStream(false)
            
            process = processBuilder.start()
            writer = process!!.outputStream.bufferedWriter()
            reader = process!!.inputStream.bufferedReader()
            
            // Инициализируем MCP соединение
            val initResult = sendRequest("initialize", buildJsonObject {
                put("protocolVersion", "2024-11-05")
                put("capabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject {
                    put("name", "UniversalDevAssistant")
                    put("version", "1.0.0")
                })
            })
            
            if (initResult != null) {
                // Отправляем initialized notification
                sendNotification("notifications/initialized", buildJsonObject {})
                
                isConnected = true
                logger.info { "✅ GitHub MCP Server подключен" }
                
                // Загружаем список инструментов
                loadTools()
                
                return@withContext true
            } else {
                logger.error { "❌ Не удалось инициализировать MCP соединение" }
                disconnect()
                return@withContext false
            }
            
        } catch (e: Exception) {
            logger.error(e) { "❌ Ошибка подключения к GitHub MCP Server" }
            disconnect()
            return@withContext false
        }
    }
    
    /**
     * Отключиться от GitHub MCP Server
     */
    fun disconnect() {
        try {
            writer?.close()
            reader?.close()
            process?.destroy()
        } catch (e: Exception) {
            logger.warn { "Ошибка при отключении: ${e.message}" }
        } finally {
            writer = null
            reader = null
            process = null
            isConnected = false
            cachedTools = null
            logger.info { "🔌 GitHub MCP Server отключен" }
        }
    }
    
    /**
     * Загрузить список доступных инструментов
     */
    private suspend fun loadTools() {
        try {
            val result = sendRequest("tools/list", buildJsonObject {})
            if (result != null) {
                val toolsArray = result["tools"]?.jsonArray ?: return
                cachedTools = toolsArray.map { toolJson ->
                    val obj = toolJson.jsonObject
                    MCPTool(
                        name = obj["name"]?.jsonPrimitive?.content ?: "",
                        description = obj["description"]?.jsonPrimitive?.content ?: "",
                        inputSchema = parseInputSchema(obj["inputSchema"]?.jsonObject)
                    )
                }
                logger.info { "📋 Загружено ${cachedTools?.size ?: 0} инструментов GitHub" }
                cachedTools?.take(5)?.forEach { tool ->
                    logger.debug { "  🔧 ${tool.name}: ${tool.description.take(50)}..." }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Ошибка загрузки инструментов" }
        }
    }
    
    private fun parseInputSchema(schemaJson: JsonObject?): MCPToolSchema {
        if (schemaJson == null) return MCPToolSchema(properties = emptyMap())
        
        val properties = schemaJson["properties"]?.jsonObject?.mapValues { (_, value) ->
            val propObj = value.jsonObject
            MCPPropertySchema(
                type = propObj["type"]?.jsonPrimitive?.content ?: "string",
                description = propObj["description"]?.jsonPrimitive?.content ?: ""
            )
        } ?: emptyMap()
        
        val required = schemaJson["required"]?.jsonArray?.map { 
            it.jsonPrimitive.content 
        } ?: emptyList()
        
        return MCPToolSchema(
            type = schemaJson["type"]?.jsonPrimitive?.content ?: "object",
            properties = properties,
            required = required
        )
    }
    
    override suspend fun listTools(): MCPToolsResponse {
        if (!isConnected) {
            connect()
        }
        
        return MCPToolsResponse(tools = cachedTools ?: emptyList())
    }
    
    override suspend fun callTool(name: String, args: Map<String, Any>): MCPToolResult {
        if (!isConnected) {
            if (!connect()) {
                return MCPToolResult(
                    content = listOf(MCPContent(text = "❌ GitHub MCP Server не подключен"))
                )
            }
        }
        
        logger.info { "🔧 Вызов инструмента: $name" }
        logger.debug { "📦 Args: $args" }
        
        // ВСЕГДА используем owner/repo из конфига (AI может передать неправильные)
        val enrichedArgs = args.toMutableMap()
        enrichedArgs["owner"] = config.owner
        enrichedArgs["repo"] = config.repo
        
        // Для pull_request_read добавляем include: ["files"] чтобы получить diff
        if (name == "pull_request_read") {
            if (!enrichedArgs.containsKey("include")) {
                enrichedArgs["include"] = listOf("files")
            }
        }
        
        logger.info { "✅ ФАКТИЧЕСКИЕ ПАРАМЕТРЫ (из конфига): owner=${config.owner}, repo=${config.repo}" }
        logger.info { "📦 Enriched Args: $enrichedArgs" }
        
        try {
            val argsJson = buildJsonObject {
                enrichedArgs.forEach { (key, value) ->
                    when (value) {
                        is String -> put(key, value)
                        is Number -> put(key, value)
                        is Boolean -> put(key, value)
                        is List<*> -> put(key, buildJsonArray {
                            value.forEach { item -> add(item.toString()) }
                        })
                        else -> put(key, value.toString())
                    }
                }
            }
            
            val result = sendRequest("tools/call", buildJsonObject {
                put("name", name)
                put("arguments", argsJson)
            })
            
            if (result != null) {
                val content = result["content"]?.jsonArray?.map { contentJson ->
                    val obj = contentJson.jsonObject
                    MCPContent(
                        type = MCPContentType.text,
                        text = obj["text"]?.jsonPrimitive?.content ?: ""
                    )
                } ?: listOf(MCPContent(text = "Пустой результат"))
                
                logger.info { "✅ Инструмент $name выполнен" }
                
                // Детальный лог для pull_request_read
                if (name == "pull_request_read") {
                    logger.info { "=" .repeat(60) }
                    logger.info { "📥 ОТВЕТ ОТ pull_request_read:" }
                    content.forEach { c ->
                        val text = c.text
                        logger.info { "📝 Размер ответа: ${text.length} символов" }
                        // Логируем первые 2000 символов
                        logger.info { "📄 Содержимое (первые 2000 символов):" }
                        logger.info { text.take(2000) }
                        if (text.length > 2000) {
                            logger.info { "... (обрезано, всего ${text.length} символов)" }
                        }
                    }
                    logger.info { "=" .repeat(60) }
                }
                
                return MCPToolResult(content = content)
            } else {
                return MCPToolResult(
                    content = listOf(MCPContent(text = "❌ Ошибка вызова инструмента $name"))
                )
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Ошибка вызова инструмента $name" }
            return MCPToolResult(
                content = listOf(MCPContent(text = "❌ Ошибка: ${e.message}"))
            )
        }
    }
    
    /**
     * Отправить JSON-RPC запрос
     */
    private suspend fun sendRequest(method: String, params: JsonObject): JsonObject? = withContext(Dispatchers.IO) {
        val id = requestId.incrementAndGet()
        
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        
        try {
            val requestStr = json.encodeToString(request)
            logger.debug { "📤 Request: $requestStr" }
            
            writer?.write(requestStr)
            writer?.newLine()
            writer?.flush()
            
            // Читаем ответ
            val responseLine = reader?.readLine()
            if (responseLine != null) {
                logger.debug { "📥 Response: ${responseLine.take(200)}..." }
                val responseJson = json.parseToJsonElement(responseLine).jsonObject
                
                // Проверяем на ошибку
                val error = responseJson["error"]
                if (error != null && error !is JsonNull) {
                    val errorObj = error.jsonObject
                    val errorMsg = errorObj["message"]?.jsonPrimitive?.content ?: "Unknown error"
                    logger.error { "MCP Error: $errorMsg" }
                    return@withContext null
                }
                
                return@withContext responseJson["result"]?.jsonObject
            }
            
            return@withContext null
            
        } catch (e: Exception) {
            logger.error(e) { "Ошибка отправки запроса" }
            return@withContext null
        }
    }
    
    /**
     * Отправить JSON-RPC notification (без ответа)
     */
    private suspend fun sendNotification(method: String, params: JsonObject) = withContext(Dispatchers.IO) {
        val notification = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }
        
        try {
            val notificationStr = json.encodeToString(notification)
            writer?.write(notificationStr)
            writer?.newLine()
            writer?.flush()
        } catch (e: Exception) {
            logger.warn { "Ошибка отправки notification: ${e.message}" }
        }
    }
}
