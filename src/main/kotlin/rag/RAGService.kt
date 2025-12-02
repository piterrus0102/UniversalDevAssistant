package rag

import config.ProjectConfig
import mu.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.streams.toList

private val logger = KotlinLogging.logger {}

/**
 * Модель для сохранения индекса на диск
 */
@Serializable
data class RAGIndex(
    val projectName: String,
    val projectPath: String,
    val documents: List<Document>,
    val chunks: List<DocumentChunk>,  // Добавляем чанки
    val embeddings: List<List<Double>>,
    val timestamp: Long,
    val vectorizationEnabled: Boolean
)

class RAGService(
    private val config: ProjectConfig,
    private val ollamaClient: OllamaClient? = null,
    private val reranker: Reranker? = null  // Опциональный reranker для улучшения релевантности
) {
    private val documents = mutableListOf<Document>()
    private val chunks = mutableListOf<DocumentChunk>()  // Чанки для векторизации
    private val documentEmbeddings = mutableListOf<List<Double>>()
    private val projectPath = Paths.get(config.project.path)
    private val vectorizationEnabled = config.vectorization?.enabled == true && ollamaClient != null
    
    // Путь к файлу кеша индекса
    private val indexCacheFile = File("src/main/kotlin/rag/index.json")
    
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    
    /**
     * Загружает индекс из кеша если он существует
     */
    fun loadIndexIfExists(): Boolean {
        if (!indexCacheFile.exists()) {
            logger.info { "📂 Файл индекса не найден: ${indexCacheFile.path}" }
            return false
        }
        
        return try {
            logger.info { "📦 Загрузка индекса из кеша: ${indexCacheFile.path}" }
            val indexJson = indexCacheFile.readText()
            val index = json.decodeFromString<RAGIndex>(indexJson)
            
            // Проверяем что индекс для нужного проекта
            if (index.projectPath != config.project.path) {
                logger.warn { "⚠️ Индекс для другого проекта (${index.projectPath})" }
                return false
            }
            
            documents.clear()
            documents.addAll(index.documents)
            
            chunks.clear()
            chunks.addAll(index.chunks)
            
            documentEmbeddings.clear()
            documentEmbeddings.addAll(index.embeddings)
            
            logger.info { "✅ Индекс загружен: ${documents.size} документов, ${chunks.size} чанков, ${documentEmbeddings.size} векторов" }
            logger.info { "📅 Время индексации: ${java.time.Instant.ofEpochMilli(index.timestamp)}" }
            logger.info { "📊 Общий размер: ${documents.sumOf { it.size } / 1024} KB" }
            
            true
        } catch (e: Exception) {
            logger.error(e) { "❌ Ошибка загрузки индекса, требуется переиндексация" }
            false
        }
    }
    
    /**
     * Сохраняет индекс в кеш
     */
    private fun saveIndex() {
        try {
            logger.info { "💾 Сохранение индекса в кеш: ${indexCacheFile.path}" }
            
            // Создаем директорию если не существует
            indexCacheFile.parentFile?.mkdirs()
            
            val index = RAGIndex(
                projectName = config.project.name,
                projectPath = config.project.path,
                documents = documents.toList(),
                chunks = chunks.toList(),
                embeddings = documentEmbeddings.toList(),
                timestamp = System.currentTimeMillis(),
                vectorizationEnabled = vectorizationEnabled
            )
            
            val indexJson = json.encodeToString(index)
            indexCacheFile.writeText(indexJson)
            
            logger.info { "✅ Индекс сохранен (${indexCacheFile.length() / 1024} KB)" }
        } catch (e: Exception) {
            logger.error(e) { "❌ Ошибка сохранения индекса" }
        }
    }
    
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
        
        // Разбиваем документы на чанки
        // Уменьшили размер чанка с 2000 до 1000 для лучшей гранулярности
        chunks.clear()
        documents.forEach { doc ->
            val docChunks = doc.toChunks(maxChunkSize = 1500)
            chunks.addAll(docChunks)
            if (docChunks.size > 1) {
                logger.debug { "  📄 ${doc.path}: разбит на ${docChunks.size} чанков" }
            }
        }
        logger.info { "📦 Всего чанков: ${chunks.size}" }
        
        // Векторизация чанков если включена
        if (vectorizationEnabled && ollamaClient != null) {
            logger.info { "🔢 Начинаю векторизацию ${chunks.size} чанков через Ollama..." }
            try {
                documentEmbeddings.addAll(
                    ollamaClient.embedBatch(chunks.map { it.content })
                )
                logger.info { "✅ Векторизация завершена (${documentEmbeddings.size} векторов)" }
            } catch (e: Exception) {
                logger.error(e) { "❌ Ошибка векторизации" }
                logger.warn { "Продолжаю без векторизации (будет использоваться keyword search)" }
            }
        } else {
            logger.info { "⏭️ Векторизация отключена (keyword search)" }
        }
        
        // Сохраняем индекс в кеш
        saveIndex()
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
     * Векторный поиск через Ollama (по чанкам)
     */
    private fun searchVector(query: String, limit: Int): List<Document> {
        logger.debug { "🔢 Векторный поиск по ${chunks.size} чанкам..." }
        
        try {
            // Векторизуем запрос
            val queryEmbedding = ollamaClient!!.embed(query)
            
            // Вычисляем similarity для каждого чанка
            val chunkSimilarities = chunks.mapIndexed { index, chunk ->
                val similarity = if (index < documentEmbeddings.size) {
                    ollamaClient.cosineSimilarity(queryEmbedding, documentEmbeddings[index])
                } else {
                    0.0
                }
                
                Triple(chunk, similarity, index)
            }
            
            // Берем топ чанков
            val topChunks = chunkSimilarities
                .sortedByDescending { it.second }
                .take(limit)  // Берем больше чанков, т.к. может быть несколько из одного документа
            
            logger.debug { "  Топ чанков:" }
            topChunks.take(5).forEach { (chunk, sim, idx) ->
                logger.debug { "    ${chunk.id} (similarity: ${(sim * 100).toInt()}%)" }
            }
            
            // Группируем чанки по документам и собираем уникальные документы
            val docPaths = topChunks
                .map { it.first.path }
                .distinct()
                .take(limit)
            
            // Возвращаем полные документы
            val results = documents.filter { doc -> 
                docPaths.contains(doc.path) 
            }
            
            logger.debug { "  Найдено: ${results.size} документов из ${topChunks.size} чанков (vector search)" }
            
            return results
            
        } catch (e: Exception) {
            logger.error(e) { "Ошибка векторного поиска, fallback на keyword search" }
            return search(query, limit)
        }
    }
    
    /**
     * Результат построения контекста
     */
    data class ContextResult(
        val context: String,
        val sources: List<String>
    )
    
    /**
     * Реранкинг для улучшения релевантности результатов
     * Использует гибридный подход: threshold filtering + LLM scoring
     */
    suspend fun rerankSearch(query: String, topK: Int = 3): ContextResult {
        if (reranker == null) {
            logger.warn { "Reranker не доступен, используем обычный поиск" }
            return buildContext(query, topK)
        }
        
        if (!vectorizationEnabled || documentEmbeddings.isEmpty() || ollamaClient == null) {
            logger.warn { "Векторизация не включена, реранкинг недоступен" }
            return buildContext(query, topK)
        }
        
        logger.info { "🔄 Запуск реранкинга для запроса: \"$query\"" }
        
        try {
            // Векторизуем запрос
            val queryEmbedding = ollamaClient.embed(query)
            
            // Находим ВСЕ чанки с similarity
            val allChunksWithSim = chunks.mapIndexed { index, chunk ->
                val similarity = if (index < documentEmbeddings.size) {
                    ollamaClient.cosineSimilarity(queryEmbedding, documentEmbeddings[index])
                } else {
                    0.0
                }
                Pair(chunk, similarity)
            }.sortedByDescending { it.second }
            
            logger.info { "Найдено чанков для реранкинга: ${allChunksWithSim.size}" }
            
            // Применяем гибридный реранкинг
            val rerankResult = reranker.hybridRerank(
                query = query,
                chunks = allChunksWithSim,
                options = Reranker.RerankOptions(
                    minSimilarity = 0.25,
                    topK = topK,
                    maxChunksForLLM = 20  // Ограничиваем LLM-оценку топ-20 (ускорение)
                )
            )
            
            if (rerankResult.chunks.isEmpty()) {
                return ContextResult(
                    context = "После реранкинга не найдено релевантных результатов.",
                    sources = emptyList()
                )
            }
            
            // Показываем лучший результат даже если оценка низкая (> 0)
            val bestChunk = rerankResult.chunks.first()
            logger.info { "🎯 Лучший чанк: ${bestChunk.llmScore}/10" }
            
            val context = rerankResult.chunks.joinToString("\n\n" + "=".repeat(80) + "\n\n") { rankedChunk ->
                """
                |📄 Файл: ${rankedChunk.chunk.path} (чанк ${rankedChunk.chunk.chunkIndex})
                |   🎯 LLM оценка: ${rankedChunk.llmScore.toInt()}/10, Similarity: ${(rankedChunk.similarity * 100).toInt()}%
                |
                |${rankedChunk.chunk.content}
                """.trimMargin()
            }
            
            val sources = rerankResult.chunks.map { it.chunk.path }.distinct()
            
            logger.info { "✅ Реранкинг завершен: ${rerankResult.chunks.size} финальных чанков" }
            
            return ContextResult(
                context = context,
                sources = sources
            )
            
        } catch (e: Exception) {
            logger.error(e) { "Ошибка реранкинга, fallback на обычный поиск" }
            return buildContext(query, topK)
        }
    }
    
    /**
     * Формирует контекст для AI из найденных документов/чанков
     */
    fun buildContext(query: String, maxDocs: Int = 3): ContextResult {
        // Если векторизация включена - используем чанки с vector search
        if (vectorizationEnabled && documentEmbeddings.isNotEmpty() && ollamaClient != null) {
            return buildContextFromChunks(query, maxChunks = maxDocs * 2)
        }
        
        // Fallback: keyword search по чанкам (не по целым документам)
        return buildContextFromChunksKeyword(query, maxChunks = maxDocs * 2)
    }
    
    /**
     * Keyword search по чанкам (fallback когда нет векторизации)
     */
    private fun buildContextFromChunksKeyword(query: String, maxChunks: Int = 4): ContextResult {
        val queryTerms = query.lowercase()
            .split(Regex("[\\s,.?!]+"))
            .filter { it.length > 2 }
        
        if (queryTerms.isEmpty() || chunks.isEmpty()) {
            return ContextResult(
                context = "Документация не найдена по запросу.",
                sources = emptyList()
            )
        }
        
        // Скоринг чанков по количеству совпадающих термов
        val scoredChunks = chunks.map { chunk ->
            val score = queryTerms.sumOf { term ->
                chunk.content.lowercase().split(Regex("\\W+"))
                    .count { it == term }
            }
            Pair(chunk, score)
        }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(maxChunks)
        
        if (scoredChunks.isEmpty()) {
            return ContextResult(
                context = "Документация не найдена по запросу.",
                sources = emptyList()
            )
        }
        
        logger.debug { "  Найдено ${scoredChunks.size} релевантных чанков (keyword search)" }
        
        val context = scoredChunks.joinToString("\n\n" + "=".repeat(80) + "\n\n") { (chunk, score) ->
            """
            |📄 Файл: ${chunk.path} (чанк ${chunk.chunkIndex}, совпадений: $score)
            |
            |${chunk.content}
            """.trimMargin()
        }
        
        return ContextResult(
            context = context,
            sources = scoredChunks.map { it.first.path }.distinct()
        )
    }
    
    /**
     * Формирует контекст из релевантных чанков
     */
    private fun buildContextFromChunks(query: String, maxChunks: Int = 3): ContextResult {  // По умолчанию 2
        try {
            val queryEmbedding = ollamaClient!!.embed(query)
            
            // Находим топ чанков (берём МЕНЬШЕ для лучшей релевантности)
            val topChunks = chunks.mapIndexed { index, chunk ->
                val similarity = if (index < documentEmbeddings.size) {
                    ollamaClient.cosineSimilarity(queryEmbedding, documentEmbeddings[index])
                } else {
                    0.0
                }
                Pair(chunk, similarity)
            }
                .sortedByDescending { it.second }
                .take(maxChunks)
            
            if (topChunks.isEmpty()) {
                return ContextResult(
                    context = "Документация не найдена по запросу.",
                    sources = emptyList()
                )
            }
            
            logger.debug { "  Используем ${topChunks.size} релевантных чанков для контекста" }
            topChunks.forEachIndexed { i, (chunk, sim) ->
                logger.debug { "    ${i+1}. ${chunk.path} (чанк ${chunk.chunkIndex}, similarity: ${(sim * 100).toInt()}%)" }
            }
            
            val context = topChunks.joinToString("\n\n" + "=".repeat(80) + "\n\n") { (chunk, similarity) ->
                """
                |📄 Файл: ${chunk.path} (чанк ${chunk.chunkIndex}, релевантность: ${(similarity * 100).toInt()}%)
                |
                |${chunk.content}
                """.trimMargin()
            }
            
            // Собираем уникальные источники (файлы)
            val sources = topChunks.map { it.first.path }.distinct()
            
            return ContextResult(
                context = context,
                sources = sources
            )
            
        } catch (e: Exception) {
            logger.error(e) { "Ошибка построения контекста из чанков" }
            return ContextResult(
                context = "Документация не найдена по запросу.",
                sources = emptyList()
            )
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

