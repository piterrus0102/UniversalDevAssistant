import ai.HuggingFaceClient
import config.ProjectConfig
import mcp.GitMCP
import mcp.LocalMCP
import mcp.MCPOrchestrator
import mu.KotlinLogging
import rag.OllamaClient
import rag.RAGService
import rag.Reranker
import server.AssistantServer
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

/**
 * Universal Dev Assistant
 * 
 * Универсальный AI-ассистент для любого проекта с поддержкой:
 * - RAG (Retrieval-Augmented Generation) для документации
 * - MCP (Model Context Protocol) для Git
 * - HuggingFace AI (Qwen 2.5) для интеллектуальных ответов
 * - Ollama для векторизации документов
 */
fun main() {
    printBanner()
    
    try {
        // 1. Загружаем конфигурацию
        logger.info { "📋 Загрузка конфигурации..." }
        val config = ProjectConfig.load("config.yaml")
        
        logger.info { "✅ Конфигурация загружена" }
        logger.info { "📂 Проект: ${config.project.name}" }
        logger.info { "📍 Путь: ${config.project.path}" }
        logger.info { "🤖 AI модель: ${config.ai.model} (${config.ai.provider})" }
        
        // 2. Инициализируем компоненты
        logger.info { "🔧 Инициализация компонентов..." }
        
        // Ollama клиент для векторизации (опционально)
        val ollamaClient = if (config.vectorization?.enabled == true) {
            logger.info { "🔢 Инициализация Ollama клиента..." }
            val client = OllamaClient(config.vectorization)
            if (client.checkHealth()) {
                logger.info { "✅ Ollama доступна (модель: ${config.vectorization.model})" }
                client
            } else {
                logger.warn { "⚠️ Ollama недоступна, векторизация будет отключена" }
                null
            }
        } else {
            logger.info { "⏭️ Векторизация отключена в конфиге" }
            null
        }
        
        val hfClient = HuggingFaceClient(config.ai)
        
        // Reranker для улучшения релевантности (опционально, использует HF client)
        val reranker = if (config.vectorization?.enabled == true) {
            Reranker(hfClient)
        } else {
            null
        }
        
        val rag = RAGService(config, ollamaClient, reranker)
        
        // MCP Orchestrator - координатор всех MCP серверов
        logger.info { "🔧 Инициализация MCP архитектуры..." }
        val mcpOrchestrator = MCPOrchestrator()
        
        // Регистрируем LocalMCP (search_knowledge_base)
        val localMCP = LocalMCP(config, rag)
        mcpOrchestrator.registerServer("local", localMCP)
        logger.info { "  ✓ LocalMCP зарегистрирован (RAG поиск)" }
        
        // Регистрируем GitMCP (git tools)
        val git = GitMCP(config.project.path)
        if (config.git.enabled) {
            mcpOrchestrator.registerServer("git", git)
            logger.info { "  ✓ GitMCP зарегистрирован (Git инструменты)" }
        } else {
            logger.info { "  ⏭️ GitMCP отключен в конфиге" }
        }
        
        logger.info { "✅ MCP серверов зарегистрировано: ${mcpOrchestrator.getServerCount()}" }
        logger.info { "✅ Компоненты инициализированы" }
        
        // 3. Проверяем Git
        if (config.git.enabled) {
            logger.info { "🔍 Проверка Git репозитория..." }
            if (git.isGitRepository()) {
                val branch = git.getCurrentBranch()
                logger.info { "✅ Git репозиторий найден (ветка: $branch)" }
            } else {
                logger.warn { "⚠️  Директория не является Git репозиторием" }
            }
        } else {
            logger.info { "⏭️  Git интеграция отключена в конфиге" }
        }
        
        // 4. Загружаем или индексируем документацию
        logger.info { "📚 Загрузка индекса документации..." }
        if (rag.loadIndexIfExists().not()) {
            logger.info { "🔄 Индекс не найден или устарел, выполняю индексацию..." }
            rag.indexDocuments()
        }
        
        // 5. Проверяем HuggingFace API
        logger.info { "🧪 Проверка HuggingFace API..." }
        if (hfClient.healthCheck()) {
            logger.info { "✅ HuggingFace API работает" }
        } else {
            logger.error { "❌ HuggingFace API недоступен" }
            logger.error { "Проверьте API ключ в config.yaml" }
            exitProcess(1)
        }
        
        // 6. Запускаем HTTP сервер
        logger.info { "🚀 Запуск сервера..." }
        println()
        println("=" .repeat(80))
        println("🤖 Universal Dev Assistant готов к работе!")
        println("📂 Проект: ${config.project.name}")
        println("🌐 Сервер: http://${config.server.host}:${config.server.port}")
        println()
        println("Примеры использования:")
        println("  curl 'http://localhost:${config.server.port}/help?q=структура проекта'")
        println("  curl http://localhost:${config.server.port}/git/status")
        println("  curl http://localhost:${config.server.port}/docs")
        println("=" .repeat(80))
        println()
        
        val server = AssistantServer(
            config = config,
            mcpOrchestrator = mcpOrchestrator,
            aiClient = hfClient
        )
        
        server.start()
        
    } catch (e: IllegalStateException) {
        logger.error { "❌ Ошибка конфигурации: ${e.message}" }
        println()
        println("Подсказка:")
        println("  1. Скопируйте config.yaml.example в config.yaml")
        println("  2. Отредактируйте config.yaml (укажите путь к проекту)")
        println("  3. Проверьте HuggingFace API ключ в config.yaml")
        println()
        exitProcess(1)
        
    } catch (e: Exception) {
        logger.error(e) { "❌ Критическая ошибка" }
        exitProcess(1)
    }
}

private fun printBanner() {
    println(
        """
        
        ╔══════════════════════════════════════════════════════════════════╗
        ║                                                                  ║
        ║        Universal Dev Assistant                                   ║
        ║                                                                  ║
        ║        AI-powered assistant for your development project         ║
        ║        with RAG, MCP, and HuggingFace AI (Qwen 2.5)              ║
        ║                                                                  ║
        ╚══════════════════════════════════════════════════════════════════╝
        
        """.trimIndent()
    )
}

