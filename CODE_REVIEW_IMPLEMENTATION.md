# ✅ Code Review Implementation - UniversalDevAssistant

## 📦 Что добавлено

### 1. CodeReviewMCP (новый MCP сервер)

**Файл:** `src/main/kotlin/mcp/CodeReviewMCP.kt`

**Функционал:**
- Инструмент `analyze_code_changes` для анализа PR
- Получение релевантной документации через RAG
- Категоризация файлов по типу (PHP, React, SQL)
- Формирование детального контекста для AI

**Ключевые методы:**
- `analyzeCodeChanges()` — анализ diff и файлов
- `getRelevantDocumentation()` — RAG поиск по ключевым темам:
  - Code conventions
  - Архитектура
  - Безопасность (SQL injection, XSS)
  - Naming conventions
- `categorizeFiles()` — группировка файлов (Backend, Frontend, etc)
- `buildCodeReviewContext()` — формирование полного контекста для LLM

### 2. System Prompt для Code Review

**Файл:** `src/main/kotlin/ai/SystemPrompts.kt`

**Добавлена функция:**
```kotlin
fun createCodeReviewSystemMessage(config: ProjectConfig, tools: List<MCPTool>): String
```

**Что проверяет AI:**
- 🔴 **Критичные проблемы** (SQL injection, XSS, security)
- 🟠 **Предупреждения** (нарушения conventions, отсутствие error handling)
- 💡 **Советы** (DRY violations, улучшения архитектуры)

**Формат ответа:**
```markdown
### 🔍 Найденные проблемы
1. 🔴 **файл.php:45** - SQL injection
   ❌ Описание
   💡 Решение
   📚 Ссылка на docs

### ⚠️ Предупреждения
...

### ✨ Советы по улучшению
...
```

### 3. POST /review Endpoint

**Файл:** `src/main/kotlin/server/AssistantServer.kt`

**Endpoint:** `POST /review`

**Request:**
```json
{
  "pr_number": 42,
  "pr_title": "feat: добавлен поиск",
  "pr_author": "developer",
  "diff": "diff --git...",
  "changed_files": ["file1.php", "file2.jsx"],
  "metadata": {}
}
```

**Response:**
```json
{
  "pr_number": 42,
  "review": "### 🔍 Найденные проблемы\n...",
  "summary": "Проанализировано 2 файл(ов)...",
  "files_analyzed": 2
}
```

**Workflow:**
1. Получает PR данные от GitHub Actions
2. Формирует контекст с diff и метаданными
3. Использует специальный code review system prompt
4. Вызывает `analyze_code_changes` tool (CodeReviewMCP)
5. AI анализирует с учетом документации проекта
6. Возвращает структурированный review

### 4. Регистрация CodeReviewMCP

**Файл:** `src/main/kotlin/Main.kt`

Добавлена регистрация нового MCP сервера:
```kotlin
val codeReviewMCP = mcp.CodeReviewMCP(config, rag)
mcpOrchestrator.registerServer("code-review", codeReviewMCP)
logger.info { "  ✓ CodeReviewMCP зарегистрирован (AI Code Review)" }
```

---

## 🎯 Как это работает

```
GitHub Actions runner
       │
       │ POST /review
       │ {pr_number, diff, files}
       ▼
┌──────────────────────────────────────┐
│    AssistantServer                   │
│    POST /review endpoint             │
└──────────┬───────────────────────────┘
           │
           │ 1. Формирует контекст
           │
           ▼
┌──────────────────────────────────────┐
│    MCPOrchestrator                   │
│    Находит нужный tool               │
└──────────┬───────────────────────────┘
           │
           │ 2. Вызывает analyze_code_changes
           │
           ▼
┌──────────────────────────────────────┐
│    CodeReviewMCP                     │
│    analyze_code_changes()            │
└──────────┬───────────────────────────┘
           │
           │ 3. RAG search: conventions, security
           │
           ▼
┌──────────────────────────────────────┐
│    RAGService                        │
│    search("code conventions...")     │
└──────────┬───────────────────────────┘
           │
           │ 4. Возвращает документацию
           │
           ▼
┌──────────────────────────────────────┐
│    CodeReviewMCP                     │
│    buildCodeReviewContext()          │
└──────────┬───────────────────────────┘
           │
           │ 5. Полный контекст для AI
           │
           ▼
┌──────────────────────────────────────┐
│    HuggingFaceClient (Qwen 2.5)      │
│    с code review system prompt       │
└──────────┬───────────────────────────┘
           │
           │ 6. AI анализирует
           │
           ▼
┌──────────────────────────────────────┐
│    AssistantServer                   │
│    Форматирует и возвращает review   │
└──────────────────────────────────────┘
```

---

## 📊 Что проверяет AI

### Проверки по категориям:

**🔒 Безопасность:**
- SQL injection (отсутствие prepared statements)
- XSS (dangerouslySetInnerHTML без sanitization)
- Отсутствие валидации входных данных
- Утечки конфиденциальных данных
- Слабое хеширование паролей

**📚 Code Conventions:**
- PHP: PSR-12, типизация, error handling
- React: Airbnb style, Hooks rules, async/await
- Naming conventions
- Форматирование, отступы

**🏗️ Архитектура:**
- MVC pattern соблюдение
- Правильное размещение файлов
- Service layer, Repository pattern
- Separation of concerns

**✨ Качество кода:**
- DRY violations (дублирование)
- KISS violations (излишняя сложность)
- Dead code, magic numbers
- Отсутствие комментариев в сложных местах

**🐛 Потенциальные баги:**
- Race conditions в async коде
- Memory leaks
- Null pointer exceptions
- Unhandled promises/errors
- Off-by-one errors

---

## 🚀 Запуск и тестирование

### 1. Запуск UniversalDevAssistant

```bash
cd ~/Desktop/UniversalDevAssistant
./gradlew run
```

Вывод:
```
✓ LocalMCP зарегистрирован (RAG поиск)
✓ GitMCP зарегистрирован (Git инструменты)
✓ CodeReviewMCP зарегистрирован (AI Code Review)  ← НОВОЕ!
✅ MCP серверов зарегистрировано: 3
```

### 2. Проверка endpoint

```bash
# Health check
curl http://localhost:3002/health

# Должно вернуть:
{
  "status": "ok",
  "project": "zayobushek",
  "mcpServers": 3,  # Было 2, стало 3!
  "mcpServerNames": ["local", "git", "code-review"],
  "gitEnabled": true
}
```

### 3. Тестовый code review

```bash
curl -X POST http://localhost:3002/review \
  -H "Content-Type: application/json" \
  -d '{
    "pr_number": 1,
    "pr_title": "test: demo review",
    "pr_author": "test-user",
    "diff": "diff --git a/test.php b/test.php\n+\$sql = \"SELECT * FROM users WHERE id = \$id\";",
    "changed_files": ["test.php"]
  }'
```

**Ожидаемый ответ:**
```json
{
  "pr_number": 1,
  "review": "### 🔍 Найденные проблемы\n\n1. 🔴 **test.php** - SQL injection...",
  "summary": "Проанализировано 1 файл(ов)...",
  "files_analyzed": 1
}
```

---

## 🔗 Интеграция с GitHub Actions

**В проекте zayobushek уже настроен:**
- GitHub Actions workflow (`.github/workflows/code-review.yml`)
- Python скрипты для подготовки данных
- Self-hosted runner для доступа к localhost

**Workflow шаги:**
1. Runner собирает PR diff и файлы
2. Python формирует JSON request
3. **Вызов:** `POST http://localhost:3002/review`
4. UniversalDevAssistant анализирует через CodeReviewMCP
5. Возвращает review
6. GitHub Action постит как комментарий в PR

---

## 📚 Используемая документация

CodeReviewMCP автоматически ищет в RAG:
- `docs/07_Code_conventions.md` — стандарты кода
- `docs/06_Архитектура_проекта.md` — паттерны
- `docs/05_Нейминг_сущностей.md` — naming
- Любые другие релевантные docs/

RAG queries:
1. "code conventions стандарты кода"
2. "архитектура проекта паттерны"
3. "безопасность security SQL injection XSS"
4. "правила именования naming conventions"

---

## 📈 Статистика

**Добавлено строк кода:**
- `CodeReviewMCP.kt`: ~270 строк
- `SystemPrompts.kt`: +~80 строк (новая функция)
- `AssistantServer.kt`: +~150 строк (новый endpoint)
- `Main.kt`: +4 строки (регистрация MCP)

**Всего:** ~500 строк нового кода

**MCP серверов:** 2 → 3
- LocalMCP (RAG)
- GitMCP (Git)
- **CodeReviewMCP** ← новый!

---

## ✅ Готово к использованию!

**Проверка:**
```bash
# 1. Запусти UniversalDevAssistant
cd ~/Desktop/UniversalDevAssistant
./gradlew run

# 2. В другом терминале - health check
curl http://localhost:3002/health | jq .mcpServerNames
# Должно быть: ["local", "git", "code-review"]

# 3. В проекте zayobushek создай тестовый PR
cd ~/Desktop/zayobushek
# (уже создан demo/ai-code-review-test с намеренными ошибками)

# 4. Workflow автоматически сделает review!
```

---

## 🎯 Day 21 - Выполнено

✅ **GitHub Action** запускается на PR
✅ **Ассистент получает** diff и файлы через POST /review
✅ **RAG использует** документацию для контекста
✅ **MCP** предоставляет инструменты (analyze_code_changes)
✅ **AI отдаёт** структурированный review с проблемами, багами, советами

**Бонус:**
✅ Специализированный CodeReviewMCP
✅ Отдельный system prompt для code review
✅ Категоризация файлов по типам
✅ Автоматический поиск релевантной документации
✅ Полная интеграция с существующей MCP архитектурой

---

**Создано:** 2025-12-02
**Проект:** UniversalDevAssistant
**Интеграция с:** zayobushek (GitHub Actions)

