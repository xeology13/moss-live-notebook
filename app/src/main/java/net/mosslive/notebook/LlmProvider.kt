package net.mosslive.notebook

/**
 * 整理笔记用的文本模型提供商。
 *
 * 全部走 OpenAI 兼容的 `POST {baseUrl}/chat/completions`，所以新增一家基本只要在
 * [LlmProviders.all] 里加一行；只有厂商特有的请求字段（如 MiniMax 的 `thinking`）
 * 才按 id 单独处理。
 *
 * 预设里的 baseUrl / model 只是**默认值**，设置页可以改，改过的值存本地 prefs。
 * 本项目不内置任何 key：key 只能由用户在设置页自己填。
 */
data class LlmProvider(
    val id: String,
    val label: String,
    /** 默认 base URL（含 /v1 之类前缀）；拼 /chat/completions 时不会重复补。 */
    val baseUrl: String,
    /** 默认模型名。各家模型迭代很快，界面上可改，别把它当契约。 */
    val model: String,
    /** 本机推理（Ollama）不需要 key。 */
    val needsKey: Boolean = true,
    /** 设置页里的一行说明。 */
    val hint: String = "",
)

object LlmProviders {

    /** 自定义：base URL / 模型名全由用户填。 */
    const val CUSTOM = "custom"

    /** MiniMax：唯一带 `thinking` 字段、也是唯一有自家备用模型的提供商。 */
    const val MINIMAX = "minimax"

    val all: List<LlmProvider> = listOf(
        LlmProvider(
            "minimax", "MiniMax", "https://api.minimaxi.com/v1", "MiniMax-M3",
            hint = "国内直连，中文笔记质量好"
        ),
        LlmProvider(
            "deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat",
            hint = "便宜，中文稳"
        ),
        LlmProvider(
            "zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash",
            hint = "有免费档"
        ),
        LlmProvider(
            "dashscope", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus",
            hint = "阿里云百炼 · 兼容模式"
        ),
        LlmProvider(
            "moonshot", "Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k",
            hint = "长上下文"
        ),
        LlmProvider(
            "siliconflow", "硅基流动", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct",
            hint = "有免费模型"
        ),
        LlmProvider(
            "openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini",
            hint = "需要能访问外网"
        ),
        LlmProvider(
            "openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini",
            hint = "一个 key 调多家模型"
        ),
        LlmProvider(
            "ollama", "Ollama（本机）", "http://127.0.0.1:11434/v1", "qwen2.5:7b",
            needsKey = false, hint = "手机同网段跑 Ollama，不需要 key"
        ),
        LlmProvider(
            CUSTOM, "自定义（OpenAI 兼容）", "", "",
            hint = "填任意 OpenAI 兼容服务的 base URL 与模型名"
        ),
    )

    val default: LlmProvider get() = all.first()

    fun byId(id: String?): LlmProvider? = all.firstOrNull { it.id == id }

    fun needsKey(id: String): Boolean = byId(id)?.needsKey ?: true

    /** 只显示指纹，不回显明文。 */
    fun mask(key: String): String {
        val k = key.trim()
        return when {
            k.isEmpty() -> "未填写"
            k.length <= 12 -> "…（${k.length} 字符）"
            else -> "${k.take(6)}…${k.takeLast(4)}（${k.length} 字符）"
        }
    }
}

/** 一次整理实际生效的配置（提供商 + base/model + key）。 */
data class LlmConfig(
    val providerId: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
) {
    /** 配置齐了才调模型；否则退回本地规则整理（不报错、不联网）。 */
    val usable: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() &&
            (apiKey.isNotBlank() || !LlmProviders.needsKey(providerId))

    val provider: LlmProvider? get() = LlmProviders.byId(providerId)
}
