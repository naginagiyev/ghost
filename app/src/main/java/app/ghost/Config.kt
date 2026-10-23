package app.ghost

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object Config {
    const val CONTEXT_TOKENS = 4096
    const val MAX_NEW_TOKENS = 1024
    const val THROTTLED_MAX_NEW_TOKENS = 256
    const val UNLOAD_GRACE_MS = 30_000L
    const val STREAM_UI_INTERVAL_MS = 33L
    const val IMAGE_MAX_SIDE = 512
    const val IMAGE_TOKEN_COST = 280
    const val VISION_BACKEND = "gpu"
    const val TEMPERATURE = 1.0
    const val TOP_K = 64
    const val TOP_P = 0.95
    const val CHARS_PER_TOKEN = 3.5
    const val TURN_OVERHEAD_TOKENS = 4
    const val PREPARE_PROMPT_TOKENS = 256
    const val TITLE_TOKENS = 20
    const val TITLE_MAX_CHARS = 50
    const val MODEL_LABEL = "gemma-4-e2b"

    private const val SYSTEM_PROMPT = """You are a private assistant running entirely on the user's phone. Nothing in this conversation leaves the device.
Be direct and honest. Give your real assessment even when it is uncomfortable. Do not flatter, pad, or hedge.
If the user is wrong, say so and explain why. Do not moralize or add disclaimers unless there is a real safety concern.
Keep answers short by default. Go longer only when asked or when the topic needs it.
If you do not know something or are unsure, say so plainly instead of guessing.
You have no internet access and your knowledge has a cutoff, so for recent events say you may be out of date.
If the user seems to be in danger or crisis, respond with care and encourage contacting real people or emergency services.
Today is {date}, local time {time}."""

    fun systemPrompt(now: LocalDateTime = LocalDateTime.now()): String = SYSTEM_PROMPT
        .replace("{date}", now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")))
        .replace("{time}", now.format(DateTimeFormatter.ofPattern("HH:mm")))
}
