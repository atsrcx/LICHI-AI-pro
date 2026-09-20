# LICHI-AI

A high-performance, open-source Android chat client for any OpenAI-compatible LLM API. Native Kotlin + Jetpack Compose, no backend, bring your own keys.

## Features

- **Multi-provider** — point at any OpenAI-compatible endpoint (OpenAI, OpenRouter, DeepSeek, Groq, Mistral, Together, Gemini, Ollama, LM Studio, custom).
- **Auto-fetch models** — `GET /models` pulls the model list per provider; manual add and remove also supported.
- **Bottom-sheet model picker** — tap the chip in the top bar, search across all providers.
- **Custom assistants** — name, emoji avatar, system prompt, optional temperature override. Built-in presets (General / Coder / Translator / Writer / Summarizer).
- **Prompt variables** — `{model} {provider} {assistant} {date} {time} {datetime} {weekday} {locale}` rendered into the system prompt.
- **Media attachments** — pick image or file from `+` menu. Images are sent as base64 data URLs.
- **Streaming** — Server-Sent-Events with a stop button.
- **Markdown** — headings, fenced code blocks, lists, quotes, bold / italic / inline code.
- **Material You + theme mode** — dynamic color on Android 12+, plus follow-system / light / dark.
- **Pure local storage** — DataStore preferences, no analytics, no backend.

## Configuration

1. Open the drawer (top-left) → **Settings → Providers**
2. **Add provider**, pick a preset (or "Custom"), paste your API key
3. Tap **Fetch models**, or add a model id manually
4. Back to chat, tap the model chip in the top bar to switch model
5. (Optional) **Settings → Assistants** to set up role-specific system prompts

## Project layout

```
app/src/main/kotlin/com/lichiai
├── MainActivity.kt                  # Compose entry, locale + theme
├── ChatViewModel.kt                 # State + send/stream/persist
├── api/LlmClient.kt                 # ktor + SSE, multipart content
├── data/                            # DataStore (settings + conversations + providers + assistants)
├── ui/AppRoot.kt                    # Routing, drawer, back-stack
├── ui/ChatScreen.kt                 # Top bar, message stream, bubbles
├── ui/InputBar.kt                   # Text input + attachment picker
├── ui/ModelPicker.kt                # Bottom-sheet model selector
├── ui/Drawer.kt                     # Date-grouped chat list, search
├── ui/SettingsScreen.kt             # Settings hub
├── ui/ProvidersScreen.kt            # Provider editor + fetch models
├── ui/AssistantsScreen.kt           # Assistant editor
├── ui/MarkdownText.kt               # Inline markdown renderer
└── ui/theme/Theme.kt                # Material 3 + dynamic color
```

## License

[MIT](LICENSE)
