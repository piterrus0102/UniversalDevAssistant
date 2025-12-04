#!/bin/bash

# Universal Dev Assistant CLI
# Удобный интерфейс командной строки для AI-ассистента

# Устанавливаем UTF-8 локаль для корректной работы с русским языком
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8

# Переходим в корень проекта (на уровень выше scripts/)
cd "$(dirname "$0")/.." || exit 1

SERVER_URL="http://localhost:3002"

# Цвета
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Проверка работы сервера
check_server() {
    if ! curl -s "$SERVER_URL/health" > /dev/null 2>&1; then
        echo -e "${RED}❌ Ошибка: сервер не запущен${NC}"
        echo ""
        echo "Запустите сервер в отдельном терминале:"
        echo -e "${CYAN}  ./scripts/START.sh${NC}"
        echo "  или"
        echo -e "${CYAN}  ./gradlew run${NC}"
        exit 1
    fi
}

# Показать помощь
show_help() {
    echo -e "${CYAN}╔════════════════════════════════════════════════════════╗${NC}"
    echo -e "${CYAN}║${NC}  ${MAGENTA}🤖 Universal Dev Assistant CLI${NC}                    ${CYAN}║${NC}"
    echo -e "${CYAN}╚════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "${YELLOW}📋 Использование из командной строки:${NC}"
    echo -e "  ${GREEN}./scripts/assistant.sh${NC} ${BLUE}<команда>${NC} [аргументы]"
    echo ""
    echo -e "${YELLOW}Команды:${NC}"
    echo -e "  ${BLUE}ask${NC} \"вопрос\"        Задать вопрос AI о проекте"
    echo -e "  ${BLUE}git${NC}                 Показать Git статус"
    echo -e "  ${BLUE}branch${NC}              Показать текущую ветку"
    echo -e "  ${BLUE}docs${NC}                Список документов"
    echo -e "  ${BLUE}roles${NC}               Показать доступные роли"
    echo -e "  ${BLUE}role${NC} <NAME>         Сменить роль (COMMON, HELPER)"
    echo -e "  ${BLUE}health${NC}              Проверка работоспособности"
    echo -e "  ${BLUE}reindex${NC}             Переиндексация документации"
    echo -e "  ${BLUE}support${NC}             Обработать запросы поддержки (только HELPER)"
    echo -e "  ${BLUE}help${NC}                Показать эту справку"
    echo ""
    echo -e "${YELLOW}Управление задачами (режим COMMON):${NC}"
    echo -e "  ${BLUE}create_tasks${NC}                        Создать задачи из answers.json"
    echo -e "  ${BLUE}edit_task${NC} <id> [text=\"...\"] [title=\"...\"]  Редактировать задачу"
    echo -e "  ${BLUE}delete_task${NC} <id или описание>       Удалить задачу"
    echo ""
    echo -e "${YELLOW}💬 Интерактивный режим:${NC}"
    echo -e "  ${GREEN}./scripts/assistant.sh${NC}         Запустить интерактивную консоль"
    echo ""
    echo -e "  ${YELLOW}В интерактивном режиме:${NC}"
    echo -e "    ${BLUE}/help${NC}             Показать справку"
    echo -e "    ${BLUE}/git${NC}              Git статус"
    echo -e "    ${BLUE}/branch${NC}           Текущая ветка"
    echo -e "    ${BLUE}/docs${NC}             Список документов"
    echo -e "    ${BLUE}/roles${NC}            Показать роли"
    echo -e "    ${BLUE}/role${NC} <NAME>      Сменить роль"
    echo -e "    ${BLUE}/health${NC}           Проверка сервера"
    echo -e "    ${BLUE}/reindex${NC}          Переиндексация документации"
    echo -e "    ${BLUE}/support${NC}          Обработать запросы поддержки (HELPER)"
    echo -e "    ${BLUE}/create_tasks${NC}     Создать задачи из answers.json"
    echo -e "    ${BLUE}/edit_task${NC}        Редактировать задачу"
    echo -e "    ${BLUE}/delete_task${NC}      Удалить задачу"
    echo -e "    ${BLUE}/exit${NC}             Выход"
    echo ""
    echo -e "    ${GREEN}Без /${NC} - просто задать вопрос AI"
    echo ""
    echo -e "${YELLOW}Примеры:${NC}"
    echo ""
    echo -e "  ${CYAN}# Из командной строки${NC}"
    echo -e "  ${GREEN}./scripts/assistant.sh${NC} ask \"что это за проект\""
    echo -e "  ${GREEN}./scripts/assistant.sh${NC} git"
    echo ""
    echo -e "  ${CYAN}# В интерактивном режиме${NC}"
    echo -e "  ${GREEN}>${NC} где API документация       ${MAGENTA}# вопрос к AI${NC}"
    echo -e "  ${GREEN}>${NC} /git                       ${MAGENTA}# команда${NC}"
    echo -e "  ${GREEN}>${NC} /docs                      ${MAGENTA}# команда${NC}"
    echo ""
}

# Задать вопрос AI
ask_question() {
    local question="$1"
    
    if [ -z "$question" ]; then
        echo -e "${RED}❌ Ошибка: укажите вопрос${NC}"
        echo "Использование: ./scripts/assistant.sh ask \"ваш вопрос\""
        exit 1
    fi
    
    echo -e "${CYAN}🤔 Вопрос:${NC} $question"
    echo ""
    echo -e "${YELLOW}💭 Думаю...${NC}"
    echo ""
    
    RESPONSE=$(curl -s --get --data-urlencode "q=$question" "$SERVER_URL/help")
    
    if [ -z "$RESPONSE" ]; then
        echo -e "${RED}❌ Ошибка: нет ответа от сервера${NC}"
        exit 1
    fi
    
    # Извлекаем ответ (используем python для надежного парсинга JSON)
    ANSWER=$(echo "$RESPONSE" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print(data.get('answer', ''))
except:
    print('')
" 2>/dev/null)
    
    if [ -z "$ANSWER" ]; then
        echo -e "${RED}❌ Ошибка в ответе сервера${NC}"
        echo -e "${YELLOW}Ответ сервера:${NC}"
        echo "$RESPONSE"
        exit 1
    fi
    
    echo -e "${GREEN}🤖 Ответ:${NC}"
    echo ""
    echo "$ANSWER"
    echo ""
    
    # Источники
    SOURCES=$(echo "$RESPONSE" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    sources = data.get('sources', [])
    for s in sources:
        print('  - ' + s)
except:
    pass
" 2>/dev/null)
    
    if [ ! -z "$SOURCES" ]; then
        echo -e "${BLUE}📚 Источники:${NC}"
        echo "$SOURCES"
    fi
}

# Показать Git статус
show_git() {
    echo -e "${CYAN}📂 Git информация${NC}"
    echo ""
    
    RESPONSE=$(curl -s "$SERVER_URL/git/info")
    
    if [ -z "$RESPONSE" ]; then
        echo -e "${RED}❌ Ошибка: нет ответа от сервера${NC}"
        exit 1
    fi
    
    # Извлекаем данные через Python для надежного парсинга
    BRANCH=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('currentBranch',''))" 2>/dev/null)
    LAST_COMMIT=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('lastCommit',''))" 2>/dev/null)
    STATUS=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
    
    echo -e "${YELLOW}Текущая ветка:${NC} ${GREEN}$BRANCH${NC}"
    echo ""
    
    if [ ! -z "$LAST_COMMIT" ]; then
        echo -e "${YELLOW}Последний коммит:${NC}"
        echo "  $LAST_COMMIT"
        echo ""
    fi
    
    if [ ! -z "$STATUS" ] && [ "$STATUS" != "" ]; then
        echo -e "${YELLOW}Статус:${NC}"
        echo "$STATUS" | sed 's/^/  /'
    else
        echo -e "${GREEN}✅ Нет изменений${NC}"
    fi
}

# Показать текущую ветку
show_branch() {
    RESPONSE=$(curl -s "$SERVER_URL/git/branch")
    BRANCH=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('branch',''))" 2>/dev/null)
    echo -e "${YELLOW}Текущая ветка:${NC} ${GREEN}$BRANCH${NC}"
}

# Показать список документов
show_docs() {
    echo -e "${CYAN}📚 Проиндексированные документы${NC}"
    echo ""
    
    RESPONSE=$(curl -s "$SERVER_URL/docs")
    
    if [ -z "$RESPONSE" ]; then
        echo -e "${RED}❌ Ошибка: нет ответа от сервера${NC}"
        exit 1
    fi
    
    COUNT=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('count',''))" 2>/dev/null)
    
    echo -e "${YELLOW}Всего документов:${NC} ${GREEN}$COUNT${NC}"
    echo ""
    
    # Извлекаем пути документов через Python
    echo "$RESPONSE" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    docs = data.get('documents', [])
    for i, doc in enumerate(docs, 1):
        print(f\"  {i}. {doc.get('path', '')}\")
except:
    pass
" 2>/dev/null
}

# Проверка здоровья
show_health() {
    echo -e "${CYAN}🏥 Состояние сервера${NC}"
    echo ""
    
    RESPONSE=$(curl -s "$SERVER_URL/health")
    
    if [ -z "$RESPONSE" ]; then
        echo -e "${RED}❌ Сервер не отвечает${NC}"
        exit 1
    fi
    
    STATUS=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
    PROJECT=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('project',''))" 2>/dev/null)
    DOCS_COUNT=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('docsCount',''))" 2>/dev/null)
    GIT_ENABLED=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('gitEnabled',''))" 2>/dev/null)
    
    echo -e "${YELLOW}Статус:${NC} ${GREEN}$STATUS${NC}"
    echo -e "${YELLOW}Проект:${NC} ${GREEN}$PROJECT${NC}"
    echo -e "${YELLOW}Документов:${NC} ${GREEN}$DOCS_COUNT${NC}"
    echo -e "${YELLOW}Git:${NC} ${GREEN}$GIT_ENABLED${NC}"
    echo ""
    echo -e "${GREEN}✅ Сервер работает нормально${NC}"
}

# Показать роли ассистента
show_roles() {
    echo -e "${CYAN}📋 Роли ассистента${NC}"
    echo ""

    RESPONSE=$(curl -s "$SERVER_URL/roles")

    if [ -z "$RESPONSE" ]; then
        echo -e "${RED}❌ Сервер не отвечает${NC}"
        exit 1
    fi

    CURRENT_ROLE=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('currentRole',''))" 2>/dev/null)
    
    echo -e "${YELLOW}Текущая роль:${NC} ${GREEN}$CURRENT_ROLE${NC}"
    echo ""
    echo -e "${YELLOW}Доступные роли:${NC}"
    
    # Извлекаем список ролей через Python
    echo "$RESPONSE" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    roles = data.get('availableRoles', [])
    current = data.get('currentRole', '')
    for role in roles:
        name = role.get('name', '')
        desc = role.get('description', '')
        marker = ' ✓' if name == current else ''
        print(f\"  • {name}{marker} - {desc}\")
except:
    pass
" 2>/dev/null
    echo ""
}

# Сменить роль ассистента
change_role() {
    local role_name="$1"
    
    if [ -z "$role_name" ]; then
        echo -e "${RED}❌ Укажите имя роли${NC}"
        echo "Использование: ./scripts/assistant.sh role <ROLE_NAME>"
        echo "Пример: ./scripts/assistant.sh role HELPER"
        exit 1
    fi
    
    echo -e "${CYAN}🔄 Смена роли на ${role_name}...${NC}"
    echo ""
    
    RESPONSE=$(curl -s "$SERVER_URL/role/$role_name")
    
    if [ -z "$RESPONSE" ]; then
        echo -e "${RED}❌ Сервер не отвечает${NC}"
        exit 1
    fi
    
    SUCCESS=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('success',''))" 2>/dev/null)
    MESSAGE=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('message',''))" 2>/dev/null)
    PREV_ROLE=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('previousRole',''))" 2>/dev/null)
    NEW_ROLE=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('newRole',''))" 2>/dev/null)
    
    if [ "$SUCCESS" == "True" ] || [ "$SUCCESS" == "true" ]; then
        echo -e "${GREEN}✅ $MESSAGE${NC}"
        echo -e "${YELLOW}Предыдущая роль:${NC} $PREV_ROLE"
        echo -e "${YELLOW}Новая роль:${NC} ${GREEN}$NEW_ROLE${NC}"
    else
        echo -e "${RED}❌ $MESSAGE${NC}"
    fi
}

# Получить текущую роль
get_current_role() {
    RESPONSE=$(curl -s "$SERVER_URL/role")
    CURRENT_ROLE=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('currentRole',''))" 2>/dev/null)
    echo "$CURRENT_ROLE"
}

# Обработка запросов поддержки (только для HELPER)
process_support() {
    # Проверяем текущую роль
    CURRENT_ROLE=$(get_current_role)
    
    if [ "$CURRENT_ROLE" != "HELPER" ]; then
        echo -e "${YELLOW}⚠️  Команда /support доступна только в режиме HELPER${NC}"
        echo ""
        echo -e "Для переключения выполните:"
        echo -e "  ${CYAN}/role HELPER${NC}"
        echo ""
        echo -e "Или из командной строки:"
        echo -e "  ${CYAN}./scripts/assistant.sh role HELPER${NC}"
        return
    fi
    
    echo -e "${CYAN}🎫 Обработка запросов поддержки...${NC}"
    echo ""
    echo -e "${YELLOW}💭 Обрабатываю тикеты пользователей...${NC}"
    echo ""
    
    RESPONSE=$(curl -s -X POST "$SERVER_URL/support")
    
    if [ -z "$RESPONSE" ]; then
        echo -e "${RED}❌ Ошибка: нет ответа от сервера${NC}"
        return
    fi
    
    # Проверяем на ошибку
    ERROR=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('error',''))" 2>/dev/null)
    
    if [ ! -z "$ERROR" ] && [ "$ERROR" != "" ]; then
        echo -e "${RED}❌ Ошибка: $ERROR${NC}"
        return
    fi
    
    echo -e "${GREEN}✅ Обработка закончена${NC}"
    echo ""
    echo -e "${YELLOW}📋 Результаты:${NC}"
    echo ""
    
    # Выводим результаты через Python
    echo "$RESPONSE" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    requests = data.get('requests', [])
    for i, req in enumerate(requests, 1):
        print(f\"━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\")
        print(f\"📌 Запрос #{i}\")
        print(f\"   Пользователь: {req.get('userName', '')}\")
        print(f\"   Дата: {req.get('date', '')}\")
        print(f\"   Тема: {req.get('title', '')}\")
        print(f\"   Вопрос: {req.get('message', '')}\")
        print(f\"   \")
        print(f\"   💬 Ответ: {req.get('answer', 'Нет ответа')}\")
        print()
except Exception as e:
    print(f'Ошибка парсинга: {e}')
" 2>/dev/null
    
    echo -e "${BLUE}💾 Ответы сохранены в: src/main/kotlin/server/helper/answers.json${NC}"
}

# ============================================================================
# Команды управления задачами (тикетами)
# ============================================================================

# Создать задачи на основе answers.json
create_tasks() {
    echo -e "${CYAN}🎫 Создание задач на основе обращений пользователей...${NC}"
    echo ""
    echo -e "${YELLOW}💭 Анализирую answers.json и создаю тикеты...${NC}"
    echo ""
    
    RESPONSE=$(curl -s --get --data-urlencode "q=/create_tasks" "$SERVER_URL/help")
    
    if [ -z "$RESPONSE" ]; then
        echo -e "${RED}❌ Ошибка: нет ответа от сервера${NC}"
        return
    fi
    
    ANSWER=$(echo "$RESPONSE" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print(data.get('answer', ''))
except:
    print('')
" 2>/dev/null)
    
    if [ -z "$ANSWER" ]; then
        echo -e "${RED}❌ Ошибка в ответе сервера${NC}"
        echo "$RESPONSE"
        return
    fi
    
    echo -e "${GREEN}✅ Результат:${NC}"
    echo ""
    echo "$ANSWER"
    echo ""
    echo -e "${BLUE}💾 Задачи сохранены в: src/main/kotlin/server/helper/tickets.json${NC}"
}

# Редактировать задачу
edit_task() {
    local args="$*"
    
    if [ -z "$args" ]; then
        echo -e "${RED}❌ Укажите ID задачи и параметры для изменения${NC}"
        echo ""
        echo -e "${YELLOW}Использование:${NC}"
        echo -e "  ${GREEN}./scripts/assistant.sh edit_task${NC} <id> [text=\"новый текст\"] [title=\"новый заголовок\"]"
        echo ""
        echo -e "${YELLOW}Примеры:${NC}"
        echo -e "  ${CYAN}edit_task abc-123 text=\"Обновленное описание\"${NC}"
        echo -e "  ${CYAN}edit_task abc-123 title=\"Новый заголовок\" text=\"Новое описание\"${NC}"
        echo -e "  ${CYAN}edit_task \"мобильное приложение\" text=\"Срочно нужно\"${NC}"
        return
    fi
    
    echo -e "${CYAN}✏️  Редактирование задачи...${NC}"
    echo ""
    echo -e "${YELLOW}💭 Обрабатываю запрос: /edit_task $args${NC}"
    echo ""
    
    RESPONSE=$(curl -s --get --data-urlencode "q=/edit_task $args" "$SERVER_URL/help")
    
    if [ -z "$RESPONSE" ]; then
        echo -e "${RED}❌ Ошибка: нет ответа от сервера${NC}"
        return
    fi
    
    ANSWER=$(echo "$RESPONSE" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print(data.get('answer', ''))
except:
    print('')
" 2>/dev/null)
    
    if [ -z "$ANSWER" ]; then
        echo -e "${RED}❌ Ошибка в ответе сервера${NC}"
        echo "$RESPONSE"
        return
    fi
    
    echo -e "${GREEN}✅ Результат:${NC}"
    echo ""
    echo "$ANSWER"
}

# Удалить задачу
delete_task() {
    local args="$*"
    
    if [ -z "$args" ]; then
        echo -e "${RED}❌ Укажите ID задачи или её описание${NC}"
        echo ""
        echo -e "${YELLOW}Использование:${NC}"
        echo -e "  ${GREEN}./scripts/assistant.sh delete_task${NC} <id или описание>"
        echo ""
        echo -e "${YELLOW}Примеры:${NC}"
        echo -e "  ${CYAN}delete_task abc-123-def-456${NC}"
        echo -e "  ${CYAN}delete_task \"мобильное приложение\"${NC}"
        return
    fi
    
    echo -e "${CYAN}🗑️  Удаление задачи...${NC}"
    echo ""
    echo -e "${YELLOW}💭 Обрабатываю запрос: /delete_task $args${NC}"
    echo ""
    
    RESPONSE=$(curl -s --get --data-urlencode "q=/delete_task $args" "$SERVER_URL/help")
    
    if [ -z "$RESPONSE" ]; then
        echo -e "${RED}❌ Ошибка: нет ответа от сервера${NC}"
        return
    fi
    
    ANSWER=$(echo "$RESPONSE" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print(data.get('answer', ''))
except:
    print('')
" 2>/dev/null)
    
    if [ -z "$ANSWER" ]; then
        echo -e "${RED}❌ Ошибка в ответе сервера${NC}"
        echo "$RESPONSE"
        return
    fi
    
    echo -e "${GREEN}✅ Результат:${NC}"
    echo ""
    echo "$ANSWER"
}

# Переиндексация документации
reindex_docs() {
    echo -e "${CYAN}🔄 Переиндексация документации${NC}"
    echo ""
    
    RESPONSE=$(curl -s -X POST "$SERVER_URL/reindex")
    
    if [ -z "$RESPONSE" ]; then
        echo -e "${RED}❌ Ошибка: нет ответа от сервера${NC}"
        exit 1
    fi
    
    STATUS=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
    MESSAGE=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('message',''))" 2>/dev/null)
    DURATION=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('durationMs',''))" 2>/dev/null)
    
    if [ "$STATUS" == "success" ] || [ "$STATUS" == "skipped" ]; then
        echo -e "${GREEN}✅ $MESSAGE${NC}"
        if [ ! -z "$DURATION" ]; then
            echo -e "${YELLOW}Время выполнения:${NC} ${DURATION}ms"
        fi
    else
        echo -e "${RED}❌ Ошибка переиндексации${NC}"
        echo "$RESPONSE"
    fi
}

# Интерактивный режим
interactive_mode() {
    echo -e "${CYAN}╔════════════════════════════════════════════════════════╗${NC}"
    echo -e "${CYAN}║${NC}  ${MAGENTA}🤖 Universal Dev Assistant${NC}                         ${CYAN}║${NC}"
    echo -e "${CYAN}║${NC}  ${YELLOW}Интерактивный режим${NC}                                 ${CYAN}║${NC}"
    echo -e "${CYAN}╚════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "💡 Команды: ${BLUE}/help${NC} ${BLUE}/git${NC} ${BLUE}/docs${NC} ${BLUE}/roles${NC} ${BLUE}/role${NC} ${BLUE}/support${NC} ${BLUE}/create_tasks${NC} ${BLUE}/edit_task${NC} ${BLUE}/delete_task${NC} ${BLUE}/exit${NC}"
    echo -e "💬 Просто напишите вопрос чтобы спросить AI"
    echo ""
    
    while true; do
        echo -n -e "${GREEN}>${NC} "
        read -r input
        
        if [ -z "$input" ]; then
            continue
        fi
        
        # Удаляем пробелы в начале и конце
        input="${input#"${input%%[![:space:]]*}"}"
        input="${input%"${input##*[![:space:]]}"}"
        
        # Проверяем команды (с / и без)
        case "$input" in
            /exit|/quit|/q|exit|quit|q)
                echo "Пока! 👋"
                exit 0
                ;;
            /help|/h)
                show_help
                ;;
            /git|/g)
                show_git
                ;;
            /branch|/b)
                show_branch
                ;;
            /docs|/d)
                show_docs
                ;;
            /health)
                show_health
                ;;
            /roles)
                show_roles
                ;;
            /role\ *)
                # /role HELPER -> извлекаем имя роли
                role_name="${input#/role }"
                change_role "$role_name"
                ;;
            /role)
                # Просто /role без аргумента - показываем текущую роль
                show_roles
                ;;
            /reindex|/r)
                reindex_docs
                ;;
            /support|/s)
                process_support
                ;;
            /create_tasks|/ct)
                create_tasks
                ;;
            /edit_task*|/et\ *)
                # /edit_task abc-123 text="..." -> извлекаем аргументы
                if [[ "$input" == /edit_task* ]]; then
                    task_args="${input#/edit_task}"
                    task_args="${task_args# }"
                else
                    task_args="${input#/et }"
                fi
                if [ -z "$task_args" ]; then
                    edit_task
                else
                    edit_task "$task_args"
                fi
                ;;
            /delete_task*|/dt\ *)
                # /delete_task abc-123 или /delete_task "описание" -> извлекаем аргументы
                if [[ "$input" == /delete_task* ]]; then
                    # Убираем /delete_task и пробелы в начале
                    task_args="${input#/delete_task}"
                    task_args="${task_args# }"
                else
                    task_args="${input#/dt }"
                fi
                # Убираем кавычки если есть
                task_args="${task_args%\"}"
                task_args="${task_args#\"}"
                if [ -z "$task_args" ]; then
                    delete_task
                else
                    delete_task "$task_args"
                fi
                ;;
            /*)
                # Неизвестная команда с /
                command="${input#/}"
                echo -e "${RED}❌ Неизвестная команда: /$command${NC}"
                echo -e "Доступные команды: ${BLUE}/help /git /branch /docs /roles /role /support /create_tasks /edit_task /delete_task /health /reindex /exit${NC}"
                ;;
            *)
                # Это вопрос к AI
                ask_question "$input"
                ;;
        esac
        echo ""
    done
}

# Основная логика
main() {
    # Проверяем сервер
    check_server
    
    # Если нет аргументов - интерактивный режим
    if [ $# -eq 0 ]; then
        interactive_mode
        exit 0
    fi
    
    COMMAND="$1"
    shift
    
    case "$COMMAND" in
        ask)
            ask_question "$*"
            ;;
        git)
            show_git
            ;;
        branch)
            show_branch
            ;;
        docs)
            show_docs
            ;;
        roles)
            show_roles
            ;;
        role)
            change_role "$1"
            ;;
        health)
            show_health
            ;;
        reindex)
            reindex_docs
            ;;
        support)
            process_support
            ;;
        create_tasks|ct)
            create_tasks
            ;;
        edit_task|et)
            edit_task "$@"
            ;;
        delete_task|dt)
            delete_task "$@"
            ;;
        help|--help|-h)
            show_help
            ;;
        *)
            echo -e "${RED}❌ Неизвестная команда: $COMMAND${NC}"
            echo ""
            show_help
            exit 1
            ;;
    esac
}

main "$@"

