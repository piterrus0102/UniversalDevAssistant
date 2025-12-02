package mcp

import kotlinx.serialization.Serializable

/**
 * MCP (Model Context Protocol) - протокол для взаимодействия с инструментами
 * 
 * Любой MCP сервер должен реализовать этот интерфейс:
 * - listTools() - вернуть список доступных инструментов
 * - callTool() - вызвать инструмент с параметрами
 */
interface MCPServer {
    /**
     * Получить список доступных инструментов
     */
    suspend fun listTools(): MCPToolsResponse
    
    /**
     * Вызвать инструмент с параметрами
     */
    suspend fun callTool(name: String, args: Map<String, Any>): MCPToolResult
}

/**
 * Ответ с списком инструментов
 */
@Serializable
data class MCPToolsResponse(
    val tools: List<MCPTool>
)

/**
 * Описание инструмента
 */
@Serializable
data class MCPTool(
    val name: String,
    val description: String,
    val inputSchema: MCPToolSchema
)

/**
 * JSON Schema для параметров инструмента
 */
@Serializable
data class MCPToolSchema(
    val type: String = "object",
    val properties: Map<String, MCPPropertySchema>,
    val required: List<String> = emptyList()
)

/**
 * Описание одного параметра
 */
@Serializable
data class MCPPropertySchema(
    val type: String,
    val description: String
)

/**
 * Результат выполнения инструмента
 */
@Serializable
data class MCPToolResult(
    val content: List<MCPContent>
)

/**
 * Контент результата
 */
@Serializable
data class MCPContent(
    val type: MCPContentType = MCPContentType.text,
    val text: String
)

enum class MCPContentType {
    text,
    currentBranch,
    lastCommit,
    modifiedFilesSize,
    modifiedFiles,
    recentCommits,
}

