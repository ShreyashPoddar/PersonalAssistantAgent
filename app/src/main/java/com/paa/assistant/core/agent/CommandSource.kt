package com.paa.assistant.core.agent

/**
 * Who asked for an action. SYSTEM_EVENT = something PAA noticed (a chat message, an SMS); on its own it may only
 * lead to READ / PRIVATE_REVERSIBLE actions — anything above needs the owner to confirm.
 */
enum class CommandSource { OWNER_VOICE, OWNER_UI, SCHEDULE, SYSTEM_EVENT }
