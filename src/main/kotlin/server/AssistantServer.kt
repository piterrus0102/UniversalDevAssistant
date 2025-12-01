package server

import ai.HuggingFaceClient
import config.ProjectConfig
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import mcp.GitMCP
import mu.KotlinLogging
import rag.RAGService

private val logger = KotlinLogging.logger {}

/**
 * HTTP сервер на Ktor для Dev Assistant
 */
class AssistantServer(
    private val config: ProjectConfig,
    private val rag: RAGService,
    private val git: GitMCP,
    private val aiClient: HuggingFaceClient
) {
    
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
                        - GET  /health          - Проверка работоспособности
                        - GET  /help?q=вопрос   - Задать вопрос о проекте
                        - GET  /git/status      - Git статус
                        - GET  /git/branch      - Текущая ветка
                        - GET  /git/info        - Полная информация о git
                        - GET  /docs            - Список проиндексированных документов
                        - GET  /docs/:path      - Содержимое конкретного документа
                        """.trimIndent(),
                        ContentType.Text.Plain
                    )
                }
                
                // Health check
                get("/health") {
                    call.respond(
                        HealthResponse(
                            status = "ok",
                            project = config.project.name,
                            docsCount = rag.getAllDocuments().size,
                            gitEnabled = config.git.enabled
                        )
                    )
                }
                
                // /help?q=вопрос - главная фишка!
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
                        // 1. Поиск в документации через RAG
                        val docsContext = rag.buildContext(question, maxDocs = 3)
                        
                        // 2. Информация о Git
                        val gitInfo = if (config.git.enabled) {
                            try {
                                val info = git.getFullInfo()
                                """
                                |Git Status:
                                |  Branch: ${info.currentBranch}
                                |  Last Commit: ${info.lastCommit}
                                |  Modified Files: ${info.modifiedFiles.size}
                                |  ${if (info.modifiedFiles.isNotEmpty()) 
                                      "Files: " + info.modifiedFiles.joinToString(", ") 
                                      else "No changes"}
                                """.trimMargin()
                            } catch (e: Exception) {
                                "Git info unavailable: ${e.message}"
                            }
                        } else {
                            "Git integration disabled"
                        }
                        
                        // 3. Формируем промпт для Claude
                        val systemPrompt = """
                            Ты - ассистент разработчика для проекта "${config.project.name}".
                            
                            Твоя задача - помогать разработчикам понимать структуру проекта, 
                            отвечать на вопросы о коде, API, архитектуре.
                            
                            Отвечай кратко, по делу, с конкретными примерами из документации.
                            Если в документации нет информации - так и скажи.
                        """.trimIndent()
                        
                        val userPrompt = """
                            $gitInfo
                            
                            ================================================================================
                            ДОКУМЕНТАЦИЯ ПРОЕКТА:
                            ================================================================================
                            $docsContext
                            
                            ================================================================================
                            ВОПРОС РАЗРАБОТЧИКА:
                            ================================================================================
                            $question
                            
                            Ответь на вопрос, используя информацию из документации выше.
                        """.trimIndent()
                        
                        // 4. Спрашиваем AI (HuggingFace)
                        val answer = aiClient.ask(userPrompt, systemPrompt)
                        
                        logger.info { "✅ Ответ сформирован (${answer.length} chars)" }
                        
                        call.respond(
                            HelpResponse(
                                project = config.project.name,
                                question = question,
                                answer = answer,
                                sources = rag.search(question, limit = 3).map { it.path }
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
                
                // Git endpoints
                get("/git/status") {
                    try {
                        val status = git.getStatus()
                        val branch = git.getCurrentBranch()
                        call.respond(mapOf(
                            "branch" to branch,
                            "status" to status
                        ))
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse(e.message ?: "Git error")
                        )
                    }
                }
                
                get("/git/branch") {
                    try {
                        val branch = git.getCurrentBranch()
                        call.respond(mapOf("branch" to branch))
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse(e.message ?: "Git error")
                        )
                    }
                }
                
                get("/git/info") {
                    try {
                        val info = git.getFullInfo()
                        call.respond(info)
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse(e.message ?: "Git error")
                        )
                    }
                }
                
                // Docs endpoints
                get("/docs") {
                    val docs = rag.getAllDocuments()
                    call.respond(
                        DocsListResponse(
                            count = docs.size,
                            documents = docs.map { 
                                DocInfo(it.path, it.lines, it.size) 
                            }
                        )
                    )
                }
                
                get("/docs/{path...}") {
                    val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
                    val doc = rag.getDocument(path)
                    
                    if (doc == null) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse("Документ не найден: $path")
                        )
                    } else {
                        call.respond(doc)
                    }
                }
            }
        }.start(wait = true)
        
        logger.info { "🚀 Сервер запущен на http://${config.server.host}:${config.server.port}" }
    }
}

// ============================================================================
// Response Models
// ============================================================================

@Serializable
data class HealthResponse(
    val status: String,
    val project: String,
    val docsCount: Int,
    val gitEnabled: Boolean
)

@Serializable
data class HelpResponse(
    val project: String,
    val question: String,
    val answer: String,
    val sources: List<String>
)

@Serializable
data class ErrorResponse(
    val error: String
)

@Serializable
data class DocsListResponse(
    val count: Int,
    val documents: List<DocInfo>
)

@Serializable
data class DocInfo(
    val path: String,
    val lines: Int,
    val size: Long
)

