# MOSS 笔记（Android）

边聊边记的语音笔记本：接 MOSS 的实时双工语音，聊完把这一通整理成 Markdown 笔记，全部存在手机本地。
无账号、无云端同步。

> ⚠️ 本项目是第三方客户端，不是 MOSS 官方项目。实时语音与批量转写调用 MOSS 的 API，
> 需要自备 MOSS API Key。

## 功能

- **实时双工通话**：16 kHz 上行 / 48 kHz 下行，逐 chunk ACK，可随时插话打断，AEC 可用
- **双轨录音**：上行（我说的）+ 下行（它说的）都落盘，用来还原整段对话
- **语音生成笔记**：`上行转写 + 下行转写 + 助手原话` → 你选的模型整理 → 存 `.md`
- **没有新录音不重复生成**：整理位置持久化，没新内容直接提示「无新的语音记录」；长按「＋ 生成笔记」强制重来
- **笔记管理**：列表（左滑删除，二次确认）、搜索（标题 + 正文）、轻量 Markdown 渲染、
  手动编辑、分享、导出到 `下载/MOSS笔记/`
- **继续聊**：把一篇已有笔记带进下一通，保存时整篇改写而不是另起一篇
- **设置**：MOSS Key、整理笔记的模型（10 个预设 + 自定义）、系统降噪、上行增益、调试日志

## 需要什么

- Android 8.0+（minSdk 26），需要有麦克风的真机或模拟器
- **MOSS API Key**：实时语音与批量转写必需，在 MOSI Studio 控制台申请：<https://studio.mosi.cn>
  （注册/登录 → 控制台 → API 密钥 → 创建。注意：产品叫 MOSS，但域名是 `mosi.cn`，
  API 端点固定为 `https://api.mosi.cn`，不要想当然改成 moss.cn）
- 可选：任意 OpenAI 兼容的文本模型服务（只用于「整理笔记」这一步）；不配也能用本地规则整理

## 构建

需要 JDK 17 与 Android SDK（compileSdk 34、build-tools 34）。

```bash
export JAVA_HOME=/path/to/jdk17
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # R8 压缩；用 debug keystore 签名，便于覆盖升级
```

**默认不内嵌任何 key。** 想给自用调试包预注入 key 时显式传 Gradle 属性（release 恒为空串）：

```bash
./gradlew assembleDebug -PmossInjectKey=sk-...
```

正式包不内嵌 key，key 只能由用户在 App 设置页填写 —— APK 流传出去也拿不到账号额度。

## 配置

### 1. MOSS Key

设置 → API Key → 粘贴 → 保存并使用。key 只存在 App 私有目录（`SharedPreferences`），不上传、不入库。
同一账号只允许 1 路并发会话，另一路会连不上。

### 2. 整理笔记的模型（可选）

设置 → 整理笔记的模型：选提供商，必要时改模型名 / Base URL，填该家的 key。
**不填 key 就用本地规则整理**（不调模型，只按句分段）。

| 提供商 | Base URL | 默认模型 |
|---|---|---|
| MiniMax | `https://api.minimaxi.com/v1` | `MiniMax-M3` |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` |
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |
| Kimi | `https://api.moonshot.cn/v1` | `moonshot-v1-8k` |
| 硅基流动 | `https://api.siliconflow.cn/v1` | `Qwen/Qwen2.5-7B-Instruct` |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| OpenRouter | `https://openrouter.ai/api/v1` | `openai/gpt-4o-mini` |
| Ollama（本机） | `http://127.0.0.1:11434/v1` | `qwen2.5:7b`（不需要 key） |
| 自定义 | 自填 | 自填 |

- 所有提供商都按 **OpenAI 兼容**的 `POST {baseUrl}/chat/completions` 调用；
  只有 MiniMax 会额外带 `thinking` 字段（别的厂商收到未知字段可能直接报错）。
- 模型名迭代很快，写死会很快过时，所以模型名与 Base URL 在界面上都可改；
  每个提供商各存一份配置，来回切换不会互相覆盖。
- 想接自建服务（vLLM / LM Studio / one-api 等），选「自定义」填 base URL 与模型名即可。

## 隐私与安全

- `android:allowBackup="false"`，避免 key 与笔记被云备份 / `adb backup` 带走
- FileProvider 只暴露 `files/notes/`，原始录音不外泄
- 录音（`uplink-*.pcm` / `downlink-*.pcm`）只在 App 私有目录，启动时清理 3 天前的
- 日志不打印 key / token，只打印转写文本与错误码
- 网络：MOSS 侧固定 `https://api.mosi.cn`；整理模型只发往你在设置里选定的 base URL

已知残留风险：key 在 `SharedPreferences` 里是明文（App 私有目录，未 root 读不到）。
想更强可换 `EncryptedSharedPreferences`，代价是多一个依赖。

## 目录

```
app/src/main/java/net/mosslive/notebook/
├── MainActivity.kt      主界面 / 设置 / 详情 / 编辑 / 搜索 / 左滑删除
├── MossEngine.kt        会话状态、笔记生成管线、录音标记、key 与整理配置存取
├── MossApi.kt           批量转写、48k→16k 降采样、模型整理、本地规则兜底
├── LlmProvider.kt       整理模型提供商预设（base/model/key 全在设置页）
├── NoteStore.kt         Markdown 文件存储（一篇一个 .md）
├── DefaultNotes.kt      首次运行带入的《使用说明》
├── SessionService.kt    麦克风前台服务
└── moss/
    ├── MossClient.kt    WSS 握手、事件分发、工具调用、挂断结算
    ├── MicSource.kt     16k 采集、AEC/NS、增益、电平、落盘
    └── SpeakerSink.kt   48k 播放、chunk ACK、电平、落盘
```

## 实现要点（踩过的坑）

- `wss://api.mosi.cn/v1/realtime?model=moss-live-1.0&user=<deviceID>`，key 走 `Authorization: Bearer …` 头（不拼 URL）
- 挂断必须先发 `playback.completed` 再关连接，否则账号唯一并发槽被占，下次连 429
- 打断只清本地播放队列；发 `playback.stopped` 会回 `invalid_public_request`
- **被打断的那轮，服务端不给任何 `output.transcript`**（done/updated 均 0 条）→ 想保住它查到的资料，
  必须自己录下行音频再批量转写
- 实时 ASR 质量差（碎片、错字、误判语种），笔记正文一律走 `moss-transcribe-diarize-pro` 批量转写
- 生成笔记前要等它把当前这句说完（最多 6 秒），否则截到半句
- 笔记有两个生产者：客户端 `generateNote` 与模型调用的 `save_note` 工具，两者都要打「已存」标记，
  否则「明明保存了还弹窗」

## 已知限制

- 只支持 Android
- 实时转写质量取决于 MOSS 的 ASR，客户端改不了，只能在整理层规整
- App 被系统杀掉会中断后台整理（普通 `Thread`，不是 WorkManager）

## 许可

MIT，见 [LICENSE](LICENSE)。
