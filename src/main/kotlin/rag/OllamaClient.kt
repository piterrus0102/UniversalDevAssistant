package rag

import config.VectorizationConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Клиент для работы с Ollama API (векторизация текста)
 * Используется для генерации embeddings через модель mxbai-embed-large
 */
class OllamaClient(private val config: VectorizationConfig) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()
    
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }
    
    private val embedUrl = "${config.ollama_url}/api/embeddings"
    private val healthUrl = config.ollama_url
    
    /**
     * Получить эмбеддинг (вектор) для текста
     */
    fun embed(text: String): List<Double> {
        logger.debug { "🔢 Генерация эмбеддинга для текста (${text.length} символов)..." }
        
        try {
            val requestBody = OllamaEmbedRequest(
                model = config.model,
                prompt = text
            )
            
            val requestJson = json.encodeToString(requestBody)
            
            val request = HttpRequest.newBuilder()
                .uri(URI.create(embedUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .timeout(Duration.ofSeconds(180))
                .build()
            
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() != 200) {
                logger.error { "Ollama API error: ${response.statusCode()}" }
                logger.error { "Response body: ${response.body()}" }
                throw RuntimeException("Ollama API returned status ${response.statusCode()}: ${response.body()}")
            }
            
            val ollamaResponse = json.decodeFromString<OllamaEmbedResponse>(response.body())
            
            logger.debug { "✓ Эмбеддинг сгенерирован (размерность: ${ollamaResponse.embedding.size})" }
            
            return ollamaResponse.embedding
            
        } catch (e: Exception) {
            logger.error(e) { "❌ Ошибка при генерации эмбеддинга" }
            throw RuntimeException("Failed to generate embedding: ${e.message}", e)
        }
    }
    
    /**
     * Получить эмбеддинги для массива текстов (batch processing)
     */
    fun embedBatch(texts: List<String>): List<List<Double>> {
        logger.info { "🔢 Генерация эмбеддингов для ${texts.size} текстов..." }
        
        val embeddings = texts.mapIndexed { index, text ->
            logger.debug { "Обработка ${index + 1}/${texts.size}..." }
            
            val embedding = embed(text)
            
            // Небольшая задержка чтобы не перегружать Ollama
            if (index < texts.size - 1) {
                Thread.sleep(100)
            }
            
            embedding
        }
        
        logger.info { "✓ Все эмбеддинги сгенерированы" }
        return embeddings
    }
    
    /**
     * Вычислить косинусное сходство между двумя векторами
     */
    fun cosineSimilarity(vec1: List<Double>, vec2: List<Double>): Double {
        require(vec1.size == vec2.size) { "Векторы должны иметь одинаковую размерность" }
        
        var dotProduct = 0.0
        var norm1 = 0.0
        var norm2 = 0.0
        
        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            norm1 += vec1[i] * vec1[i]
            norm2 += vec2[i] * vec2[i]
        }
        
        return dotProduct / (kotlin.math.sqrt(norm1) * kotlin.math.sqrt(norm2))
    }
    
    /**
     * Проверить доступность Ollama API
     */
    fun checkHealth(): Boolean {
        return try {
            logger.debug { "Проверка доступности Ollama..." }
            
            val request = HttpRequest.newBuilder()
                .uri(URI.create(healthUrl))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build()
            
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val isHealthy = response.statusCode() == 200
            
            if (isHealthy) {
                logger.info { "✓ Ollama доступна" }
            } else {
                logger.warn { "⚠️ Ollama вернула код ${response.statusCode()}" }
            }
            
            isHealthy
            
        } catch (e: Exception) {
            logger.error { "❌ Ollama недоступна: ${e.message}" }
            false
        }
    }
}

// ============================================================================
// Модели данных для Ollama API
// ============================================================================

@Serializable
data class OllamaEmbedRequest(
    val model: String,
    val prompt: String
)

@Serializable
data class OllamaEmbedResponse(
    val embedding: List<Double>
)

