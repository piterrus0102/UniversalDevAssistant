package mcp

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * MCP Orchestrator - координатор всех MCP серверов
 * 
 * Задачи:
 * 1. Собирает список tools от всех подключенных MCP серверов
 * 2. Находит нужный MCP сервер для вызова tool
 * 3. Вызывает tool через соответствующий MCP сервер
 */
class MCPOrchestrator {
    private val mcpServers = mutableMapOf<String, MCPServer>()
    
    /**
     * Зарегистрировать MCP сервер
     */
    fun registerServer(name: String, server: MCPServer) {
        logger.info { "📌 Регистрация MCP сервера: $name" }
        mcpServers[name] = server
    }
    
    /**
     * Получить все tools от всех MCP серверов
     */
    suspend fun getAllTools(): List<MCPTool> {
        logger.debug { "🔧 Сбор tools от ${mcpServers.size} MCP серверов..." }
        
        val allTools = mutableListOf<MCPTool>()
        
        for ((name, server) in mcpServers) {
            try {
                val response = server.listTools()
                logger.debug { "  ✓ $name: ${response.tools.size} tools" }
                allTools.addAll(response.tools)
            } catch (e: Exception) {
                logger.error(e) { "  ✗ Ошибка получения tools от $name" }
            }
        }
        
        logger.debug { "✅ Всего tools: ${allTools.size}" }
        return allTools
    }
    
    /**
     * Найти MCP сервер который предоставляет указанный tool
     */
    suspend fun findServerForTool(toolName: String): MCPServer? {
        logger.debug { "🔍 Поиск MCP сервера для tool: $toolName" }
        
        for ((name, server) in mcpServers) {
            try {
                val response = server.listTools()
                val hasTool = response.tools.any { it.name == toolName }
                
                if (hasTool) {
                    logger.debug { "  ✓ Найдено в: $name" }
                    return server
                }
            } catch (e: Exception) {
                logger.error(e) { "  ✗ Ошибка проверки $name" }
            }
        }
        
        logger.warn { "  ✗ MCP сервер для tool '$toolName' не найден" }
        return null
    }
    
    /**
     * Вызвать tool (автоматически находит нужный MCP сервер)
     */
    suspend fun callTool(toolName: String, args: Map<String, Any>): MCPToolResult {
        val server = findServerForTool(toolName)
            ?: throw IllegalArgumentException("Tool '$toolName' не найден ни в одном MCP сервере")
        
        logger.info { "⚙️ Вызов tool: $toolName" }
        return server.callTool(toolName, args)
    }
    
    /**
     * Получить количество зарегистрированных серверов
     */
    fun getServerCount(): Int = mcpServers.size
    
    /**
     * Получить список имен зарегистрированных серверов
     */
    fun getServerNames(): List<String> = mcpServers.keys.toList()
}

