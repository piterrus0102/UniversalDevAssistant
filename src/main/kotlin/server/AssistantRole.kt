package server

/**
 * Роли ассистента
 * 
 * Определяют поведение и специализацию ассистента:
 * - COMMON: Общий режим работы (помощь с кодом, документацией)
 * - HELPER: Режим поддержки пользователей (работа с тикетами, FAQ, CRM)
 */
enum class AssistantRole(val displayName: String, val description: String) {
    /**
     * Общая роль - помощь разработчикам с кодом и документацией
     */
    COMMON(
        displayName = "COMMON",
        description = "Общий режим: помощь с кодом, документацией, Git"
    ),
    
    /**
     * Роль поддержки - работа с пользователями, тикетами, FAQ
     */
    HELPER(
        displayName = "HELPER", 
        description = "Поддержка пользователей: тикеты, FAQ, CRM интеграция"
    );
    
    companion object {
        /**
         * Получить роль по имени (case-insensitive)
         */
        fun fromName(name: String): AssistantRole? {
            return entries.find { it.name.equals(name, ignoreCase = true) }
        }
        
        /**
         * Вывести список всех доступных ролей
         */
        fun printAvailableRoles() {
            println("📋 Доступные роли ассистента:")
            entries.forEach { role ->
                println("   • ${role.displayName} - ${role.description}")
            }
            println()
        }
        
        /**
         * Получить информацию о всех ролях
         */
        fun getAllRolesInfo(): List<RoleInfo> {
            return entries.map { role ->
                RoleInfo(
                    name = role.name,
                    displayName = role.displayName,
                    description = role.description
                )
            }
        }
    }
}

/**
 * Информация о роли для API ответа
 */
@kotlinx.serialization.Serializable
data class RoleInfo(
    val name: String,
    val displayName: String,
    val description: String
)

/**
 * Ответ API с текущей ролью
 */
@kotlinx.serialization.Serializable
data class CurrentRoleResponse(
    val currentRole: String,
    val description: String
)

/**
 * Ответ API со списком всех ролей
 */
@kotlinx.serialization.Serializable
data class RolesListResponse(
    val currentRole: String,
    val availableRoles: List<RoleInfo>
)

/**
 * Запрос на смену роли
 */
@kotlinx.serialization.Serializable
data class ChangeRoleRequest(
    val role: String
)

/**
 * Ответ на смену роли
 */
@kotlinx.serialization.Serializable
data class ChangeRoleResponse(
    val success: Boolean,
    val previousRole: String,
    val newRole: String,
    val message: String
)


