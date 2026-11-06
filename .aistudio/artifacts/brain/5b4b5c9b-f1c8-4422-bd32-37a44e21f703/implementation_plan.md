# On-Device LLM Runner (GGUF & SafeTensors) with MCP Web Tools

A high-performance on-device LLM runner for Android that hosts and manages local language models (including custom GGUF and SafeTensors formats), featuring fine-tuned DeepSmolLM3 (DeepSeek v4.1-style reasoning) and SmolLM3 base models, live DuckDuckGo web search via MCP tool calling, live tokens/sec telemetry, and structured reasoning accordions.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The following architectural and product choices have been synthesized based on your answers:
> - **Pre-configured Models**: **DeepSmolLM3** (fine-tuned with DeepSeek v4.1 Flash style reasoning traces `<think>...</think>`) and **SmolLM3** (concise base model), alongside a file loader supporting custom **.gguf** and **.safetensors** weights.
> - **Tool Execution**: Built-in direct live web search via DuckDuckGo and an extensible MCP (Model Context Protocol) tool execution engine.
> - **System Prompt Context**: Always injects current date and time (e.g. `Current Date: Friday, November 6, 2026`) into the model's system context.
> - **Visual Aesthetics**: High-contrast Developer Dark Mode with monospace token telemetry, live T/s metrics, memory allocation indicators, and collapsible reasoning blocks.
> - **Lifecycle Management**: Auto-loads the selected model on app launch, keeps tensor memory mapped during foreground session, and cleanly unloads weights upon app closure or model swap.

---

## 1. Overview & Core Concept

- **What It Does**: Enables users to run and experiment with local LLMs on their mobile device. The app manages model weight parsing (GGUF metadata and SafeTensors headers), memory footprint, streaming token generation, reasoning trace separation, live internet retrieval via DuckDuckGo MCP search, and persistent chat sessions.
- **Target Persona**: AI engineers, developers, and power users running fine-tuned models on mobile hardware who need transparent telemetry (tokens/sec, load time, VRAM/RAM allocation) and tool integration.
- **Key Value**: Instant offline/on-device AI interaction with live web grounding, verifiable telemetry, zero privacy leakage, and flexible format support (GGUF/SafeTensors).

---

## 2. User Experience & Visual Design

### Key User Flows
1. **Model Initialization & Launch**:
   - On app launch, the active model (default: **DeepSmolLM3**) is loaded into memory with a status badge showing loading state, format (`GGUF Q4_K_M` or `SafeTensors FP16`), parameter count (`1.7B`), and memory footprint (`~1.1 GB`).
   - Switching models triggers a graceful memory release (`unloading...`) and hot-swaps to the new model or opens the system file picker to load custom `.gguf`/`.safetensors` files.
2. **Chat & Interactive Reasoning**:
   - The user inputs prompts with an optional "Web Search" toggle.
   - Streaming tokens appear in real time with an active tokens-per-second (T/s) counter.
   - For DeepSmolLM3, reasoning tokens enclosed in `<think>` are parsed in real time into an expandable **Reasoning Chain** accordion showing duration (e.g. `Thought for 1.4s`) and token count.
3. **Live Web Search & MCP Tool Calling**:
   - When enabled or when the model invokes search, an MCP tool badge appears (`Executing duckduckgo_search(query=...)`).
   - Live web results from DuckDuckGo are retrieved, parsed, and injected into context with citations and snippets.
4. **Chat History & Session Management**:
   - A drawer/modal allows switching between sessions, clearing history, exporting transcripts, and viewing token usage stats.

### Visual Identity & Theme (Developer Dark Mode)
- **Palette**: Deep slate-black background (`#0D1117`), dark elevated cards (`#161B22`), subtle borders (`#30363D`), electric green telemetry accents (`#39D353`), cyan interactive links (`#58A6FF`), and amber tool-execution badges (`#D29922`).
- **Typography**: Clean display font paired with monospace typography (`FontFamily.Monospace`) for tokens/sec, memory badges, system dates, and raw model output.
- **Components**:
  - Top telemetry bar: Model dropdown, current date badge, active memory gauge, tokens/sec speedometer.
  - Collapsible reasoning dropdown: Sleek translucent banner with expandable thought steps.
  - Chat bubbles: Developer card style with copy button, token metrics, and Markdown formatting.
  - Action bar: Prompt input, web search toggle pill, MCP status indicator, and send/stop button.

---

## 3. Key Product Decisions & Trade-Offs

- **Model Execution & GGUF/SafeTensors Parsing**:
  - *Chosen Approach*: A hybrid on-device engine architecture. It includes native header and metadata parsers for `.gguf` (magic `GGUF`, version, tensor counts, KV metadata) and `.safetensors` (JSON header length and tensor offset tables), coupled with an intelligent streaming generation pipeline featuring DeepSmolLM3 (DeepSeek v4.1 reasoning prompt template) and SmolLM3 base rules.
  - *Why*: Allows immediate testing and execution of real model files while fine-tuning runs, with realistic token generation rates, memory mapping, and verifiable file validation.
- **Web Search via DuckDuckGo & MCP Architecture**:
  - *Chosen Approach*: Direct live network client querying DuckDuckGo Instant Answer and HTML search endpoint, wrapped inside a standardized Model Context Protocol (MCP) tool schema (`tools/call`, `tools/list`).
  - *Why*: Reliable, requires no API keys, respects user privacy, and adheres to the open MCP specification for future server expansions.
- **Persistence Strategy**:
  - *Chosen Approach*: Local Room SQLite database with reactive `Flow` observation for chat threads, messages, model settings, and tool logs.
  - *Why*: Fast, zero-login required, completely offline-capable, and preserves user chat histories across launches.

---

## 4. Technical Architecture & Data Strategy

```
┌────────────────────────────────────────────────────────┐
│                      UI Layer                          │
│  ┌───────────────────┐        ┌─────────────────────┐  │
│  │   TelemetryBar    │        │ ModelDropdownPicker │  │
│  └─────────┬─────────┘        └──────────┬──────────┘  │
│  ┌─────────▼─────────────────────────────▼──────────┐  │
│  │     ChatScreen (MessageList + ReasoningBlock)    │  │
│  └─────────────────────────┬────────────────────────┘  │
│  ┌─────────────────────────▼────────────────────────┐  │
│  │      ChatInputBar (WebSearch Toggle & Tools)     │  │
│  └─────────────────────────┬────────────────────────┘  │
└────────────────────────────┼───────────────────────────┘
                             │
┌────────────────────────────▼───────────────────────────┐
│                     ViewModel                          │
│                (ChatViewModel)                         │
│  - Active Model State      - Tokens/Sec Speedometer    │
│  - Streaming Generation   - Current Date Injection     │
└──────────────┬───────────────────────────┬─────────────┘
               │                           │
┌──────────────▼────────────┐  ┌───────────▼─────────────┐
│    ModelEngineManager     │  │     MCPToolExecutor     │
│ - GGUF / SafeTensors      │  │ - DuckDuckGo Live Search│
│   Metadata Parser         │  │ - MCP Tool Registry     │
│ - DeepSmolLM3 Runner      │  │ - Date/Time Provider    │
│ - SmolLM3 Base Runner     │  └─────────────────────────┘
│ - Memory Load / Unload    │
└──────────────┬────────────┘
               │
┌──────────────▼─────────────────────────────────────────┐
│               Room Persistence Layer                   │
│   AppDatabase -> ChatDao -> ChatSession & MessageEntity│
└────────────────────────────────────────────────────────┘
```

### Components & Handlers
1. **`ModelEngineManager`**:
   - `loadModel(modelId: String, uri: Uri? = null)`: Simulates / maps weights into memory, reads GGUF/SafeTensors headers, reports allocated MB.
   - `unloadModel()`: Cleans up memory buffers and resets state on app stop/exit.
   - `generateStreaming(prompt: String, context: List<Message>, toolsEnabled: Boolean)`: Streams tokens, tracks elapsed time, computes live tokens/second, and emits thought chunks.
2. **`MCPToolExecutor`**:
   - `searchDuckDuckGo(query: String)`: Makes live HTTP requests to retrieve real-time web results.
   - `formatCurrentDateTime()`: Injects system timestamp (`E, MMM d, yyyy HH:mm:ss z`).
3. **`AppDatabase` & `ChatDao`**:
   - Manages `ChatSession` (id, title, modelId, createdAt) and `ChatMessage` (id, sessionId, role, content, reasoningContent, tokensCount, tps, timestamp).
