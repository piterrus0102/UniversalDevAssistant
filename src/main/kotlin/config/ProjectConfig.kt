package config

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class ProjectConfig(
    val project: ProjectInfo,
    val ai: AIConfig,
    val server: ServerConfig,
    val git: GitConfig,
    val vectorization: VectorizationConfig? = null
) {
    companion object {
        fun load(configPath: String = "config.yaml"): ProjectConfig {
            val configFile = File(configPath)
            if (!configFile.exists()) {
                throw IllegalStateException(
                    "Config file not found: $configPath\n" +
                    "Please copy config.yaml.example to config.yaml and configure it."
                )
            }
            
            val yaml = Yaml.default
            return yaml.decodeFromString(serializer(), configFile.readText())
        }
    }
}

@Serializable
data class ProjectInfo(
    val name: String,
    val path: String,
    val docs: List<String>,
    val ignore: List<String> = emptyList()
)

@Serializable
data class AIConfig(
    val provider: String,
    val model: String,
    val api_key: String,
    val max_tokens: Int = 2048,
    val api_url: String? = null
)

@Serializable
data class ServerConfig(
    val port: Int,
    val host: String
)

@Serializable
data class GitConfig(
    val enabled: Boolean
)

@Serializable
data class VectorizationConfig(
    val enabled: Boolean,
    val ollama_url: String,
    val model: String
)

