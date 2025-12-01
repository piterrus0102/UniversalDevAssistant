package rag

import config.ProjectConfig
import model.Document
import mu.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.streams.toList

private val logger = KotlinLogging.logger {}

class RAGService(
    private val config: ProjectConfig,
    private val ollamaClient: OllamaClient? = null
) {
    private val documents = mutableListOf<Document>()
    private val documentEmbeddings = mutableListOf<List<Double>>()
    private val projectPath = Paths.get(config.project.path)
    private val vectorizationEnabled = config.vectorization?.enabled == true && ollamaClient != null
    
    /**
     * Индексирует документы проекта согласно config.yaml
     */
    fun indexDocuments() {
        logger.info { "📖 Начинаю индексацию документов для проекта: ${config.project.name}" }
        logger.info { "📍 Путь к проекту: ${config.project.path}" }
        
        documents.clear()
        documentEmbeddings.clear()
        
        config.project.docs.forEach { pattern ->
            logger.debug { "Обработка паттерна: $pattern" }
            val files = findFilesByPattern(pattern)
            
            files.forEach { file ->
                try {
                    val content = file.readText()
                    val doc = Document(
                        path = projectPath.relativize(file).toString(),
                        content = content,
                        lines = content.lines().size,
                        size = Files.size(file)
                    )
                    documents.add(doc)
                    logger.debug { "  ✓ ${doc.path} (${doc.lines} lines, ${doc.size} bytes)" }
                } catch (e: Exception) {
                    logger.warn { "  ✗ Ошибка чтения файла $file: ${e.message}" }
                }
            }
        }
        
        logger.info { "✅ Проиндексировано ${documents.size} документов" }
        logger.info { "📊 Общий размер: ${documents.sumOf { it.size } / 1024} KB" }
        
        // Векторизация документов если включена
        if (vectorizationEnabled && ollamaClient != null) {
            logger.info { "🔢 Начинаю векторизацию документов через Ollama..." }
            try {
                documentEmbeddings.addAll(
                    ollamaClient.embedBatch(documents.map { it.content.take(8000) }) // Ограничиваем размер
                )
                logger.info { "✅ Векторизация завершена (${documentEmbeddings.size} векторов)" }
            } catch (e: Exception) {
                logger.error(e) { "❌ Ошибка векторизации" }
                logger.warn { "Продолжаю без векторизации (будет использоваться keyword search)" }
            }
        } else {
            logger.info { "⏭️ Векторизация отключена (keyword search)" }
        }
    }
    
    /**
     * Поиск документов по запросу
     */
    fun search(query: String, limit: Int = 5): List<Document> {
        logger.debug { "🔍 Поиск по запросу: '$query'" }
        
        // Если векторизация включена и доступна
        if (vectorizationEnabled && documentEmbeddings.isNotEmpty() && ollamaClient != null) {
            return searchVector(query, limit)
        }
        
        // Fallback: keyword search
        val results = documents
            .filter { it.matches(query) }
            .sortedByDescending { doc ->
                // Простой скоринг: количество упоминаний запроса
                query.split(" ")
                    .filter { it.length > 2 }
                    .sumOf { term -> 
                        doc.content.split(Regex("\\W+"))
                            .count { it.equals(term, ignoreCase = true) }
                    }
            }
            .take(limit)
        
        logger.debug { "  Найдено: ${results.size} документов (keyword search)" }
        return results
    }
    
    /**
     * Векторный поиск через Ollama
     */
    private fun searchVector(query: String, limit: Int): List<Document> {
        logger.debug { "🔢 Векторный поиск..." }
        
        try {
            // Векторизуем запрос
            val queryEmbedding = ollamaClient!!.embed(query)
            
            // Вычисляем similarity для каждого документа
            val similarities = documents.mapIndexed { index, doc ->
                val similarity = if (index < documentEmbeddings.size) {
                    ollamaClient.cosineSimilarity(queryEmbedding, documentEmbeddings[index])
                } else {
                    0.0
                }
                
                Pair(doc, similarity)
            }
            
            // Сортируем по similarity и берем топ
            val results = similarities
                .sortedByDescending { it.second }
                .take(limit)
                .map { it.first }
            
            logger.debug { "  Найдено: ${results.size} документов (vector search)" }
            similarities.take(limit).forEachIndexed { i, (doc, sim) ->
                logger.debug { "    ${i+1}. ${doc.path} (similarity: ${(sim * 100).toInt()}%)" }
            }
            
            return results
            
        } catch (e: Exception) {
            logger.error(e) { "Ошибка векторного поиска, fallback на keyword search" }
            return search(query, limit)
        }
    }
    
    /**
     * Получить все документы
     */
    fun getAllDocuments(): List<Document> = documents.toList()
    
    /**
     * Получить документ по пути
     */
    fun getDocument(path: String): Document? {
        return documents.find { it.path == path }
    }
    
    /**
     * Формирует контекст для AI из найденных документов
     */
    fun buildContext(query: String, maxDocs: Int = 3): String {
        val relevantDocs = search(query, maxDocs)
        
        if (relevantDocs.isEmpty()) {
            return "Документация не найдена по запросу."
        }
        
        return relevantDocs.joinToString("\n\n" + "=".repeat(80) + "\n\n") { doc ->
            val snippet = doc.getRelevantSnippet(query, contextLines = 5)
            """
            |📄 Файл: ${doc.path}
            |
            |${if (snippet.isNotEmpty()) snippet else doc.content.take(1000)}
            """.trimMargin()
        }
    }
    
    /**
     * Находит файлы по glob-паттерну
     */
    private fun findFilesByPattern(pattern: String): List<Path> {
        // Если это прямой путь к файлу
        val directPath = projectPath.resolve(pattern)
        if (directPath.isRegularFile()) {
            return listOf(directPath)
        }
        
        // Если это паттерн типа "docs/*.md"
        if (pattern.contains("*")) {
            val parts = pattern.split("/")
            val dir = parts.dropLast(1).joinToString("/")
            val filePattern = parts.last()
            
            val searchDir = if (dir.isEmpty()) projectPath else projectPath.resolve(dir)
            
            if (!Files.exists(searchDir)) {
                logger.warn { "Директория не найдена: $searchDir" }
                return emptyList()
            }
            
            return Files.walk(searchDir, if (pattern.contains("**")) Int.MAX_VALUE else 1)
                .filter { it.isRegularFile() }
                .filter { matchesPattern(it.fileName.toString(), filePattern) }
                .filter { !shouldIgnore(it) }
                .toList()
        }
        
        // Простой путь к файлу
        val simplePath = projectPath.resolve(pattern)
        return if (Files.exists(simplePath) && simplePath.isRegularFile()) {
            listOf(simplePath)
        } else {
            emptyList()
        }
    }
    
    /**
     * Проверка, соответствует ли имя файла паттерну
     */
    private fun matchesPattern(fileName: String, pattern: String): Boolean {
        if (pattern == "*") return true
        if (pattern == "*.md") return fileName.endsWith(".md")
        if (pattern.startsWith("*.")) {
            val ext = pattern.substring(1)
            return fileName.endsWith(ext)
        }
        return fileName == pattern
    }
    
    /**
     * Проверка, нужно ли игнорировать файл
     */
    private fun shouldIgnore(path: Path): Boolean {
        val pathStr = path.toString()
        return config.project.ignore.any { ignore ->
            pathStr.contains(ignore)
        }
    }
}

