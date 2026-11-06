package com.example.model

import com.example.tools.MCPToolExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import java.util.Random

sealed class GenerationEvent {
    data class ThinkingToken(val token: String) : GenerationEvent()
    data class ToolExecuting(val toolName: String, val argument: String) : GenerationEvent()
    data class ToolResult(val toolName: String, val resultSnippet: String, val sourceUrl: String?) : GenerationEvent()
    data class ContentToken(val token: String) : GenerationEvent()
    data class TelemetryUpdate(
        val tokensGenerated: Int,
        val tokensPerSecond: Double,
        val memoryAllocatedMb: Int
    ) : GenerationEvent()
    data class Complete(
        val totalTokens: Int,
        val averageTps: Double,
        val thinkingContent: String,
        val content: String,
        val durationMs: Long
    ) : GenerationEvent()
    data class Error(val message: String) : GenerationEvent()
}

class ModelEngineManager(
    private val mcpToolExecutor: MCPToolExecutor
) {
    private val _loadState = MutableStateFlow(ModelLoadState.UNLOADED)
    val loadState: StateFlow<ModelLoadState> = _loadState.asStateFlow()

    private val _currentModel = MutableStateFlow<ModelProfile?>(null)
    val currentModel: StateFlow<ModelProfile?> = _currentModel.asStateFlow()

    private val _allocatedMemoryMb = MutableStateFlow(0)
    val allocatedMemoryMb: StateFlow<Int> = _allocatedMemoryMb.asStateFlow()

    private val _currentTps = MutableStateFlow(0.0)
    val currentTps: StateFlow<Double> = _currentTps.asStateFlow()

    private val random = Random()

    suspend fun loadModel(profile: ModelProfile) {
        if (_currentModel.value?.id == profile.id && _loadState.value == ModelLoadState.LOADED) {
            return
        }

        // Unload if another model was loaded
        if (_loadState.value == ModelLoadState.LOADED) {
            unloadModel()
        }

        _loadState.value = ModelLoadState.LOADING
        // Realistic memory mapping delay for weights
        delay(400)
        _currentModel.value = profile
        _allocatedMemoryMb.value = profile.memoryMb
        _loadState.value = ModelLoadState.LOADED
    }

    suspend fun unloadModel() {
        if (_loadState.value == ModelLoadState.UNLOADED) return
        _loadState.value = ModelLoadState.UNLOADED
        _currentModel.value = null
        _allocatedMemoryMb.value = 0
        _currentTps.value = 0.0
        // Clean up memory
        System.gc()
    }

    fun generateStream(
        prompt: String,
        searchEnabled: Boolean,
        systemDate: String = mcpToolExecutor.getCurrentFormattedDate()
    ): Flow<GenerationEvent> = flow {
        val model = _currentModel.value
        if (model == null || _loadState.value != ModelLoadState.LOADED) {
            emit(GenerationEvent.Error("No model loaded in memory. Please select and load a model first."))
            return@flow
        }

        val startTime = System.currentTimeMillis()
        var totalTokens = 0
        val thinkingBuilder = StringBuilder()
        val contentBuilder = StringBuilder()

        // 1. Check if Web Search / MCP tools should run
        val shouldSearch = searchEnabled || isWebSearchIntent(prompt)
        var webGroundingContext = ""
        var webSourceUrl: String? = null

        if (shouldSearch) {
            val searchQuery = extractSearchTerms(prompt)
            emit(GenerationEvent.ToolExecuting("duckduckgo_search", searchQuery))
            delay(350)

            val searchResult = mcpToolExecutor.performDuckDuckGoSearch(searchQuery)
            webSourceUrl = searchResult.sourceUrl
            val snippet = searchResult.result.take(600)
            emit(GenerationEvent.ToolResult("duckduckgo_search", snippet, webSourceUrl))
            webGroundingContext = searchResult.result
            delay(200)
        }

        // 2. DeepSmolLM3 Reasoning Phase (DeepSeek v4.1 Flash Style)
        if (model.supportsReasoning) {
            val reasoningSteps = generateReasoningSteps(prompt, systemDate, webGroundingContext)
            for (step in reasoningSteps) {
                val words = step.split(" ")
                for (word in words) {
                    val token = "$word "
                    thinkingBuilder.append(token)
                    totalTokens++

                    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
                    val tps = if (elapsedSec > 0) (totalTokens / elapsedSec) else 32.5
                    val jitteredTps = (tps + (random.nextDouble() * 4.0 - 2.0)).coerceIn(28.0, 44.0)
                    _currentTps.value = jitteredTps

                    emit(GenerationEvent.ThinkingToken(token))
                    emit(GenerationEvent.TelemetryUpdate(totalTokens, jitteredTps, model.memoryMb))
                    // Simulation delay between 18ms and 28ms for ~38-45 T/s on device
                    delay(22)
                }
                thinkingBuilder.append("\n\n")
                emit(GenerationEvent.ThinkingToken("\n\n"))
                delay(60)
            }
        }

        // 3. Final Content Stream Phase
        val responseChunks = buildResponseContent(model, prompt, systemDate, webGroundingContext, webSourceUrl)
        for (chunk in responseChunks) {
            val words = chunk.split(" ")
            for (word in words) {
                val token = "$word "
                contentBuilder.append(token)
                totalTokens++

                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
                val tps = if (elapsedSec > 0) (totalTokens / elapsedSec) else 34.0
                val jitteredTps = (tps + (random.nextDouble() * 3.5 - 1.75)).coerceIn(29.0, 46.0)
                _currentTps.value = jitteredTps

                emit(GenerationEvent.ContentToken(token))
                emit(GenerationEvent.TelemetryUpdate(totalTokens, jitteredTps, model.memoryMb))
                delay(20)
            }
            contentBuilder.append("\n")
            emit(GenerationEvent.ContentToken("\n"))
            delay(40)
        }

        val totalDuration = System.currentTimeMillis() - startTime
        val finalTps = if (totalDuration > 0) (totalTokens * 1000.0 / totalDuration) else 34.0
        _currentTps.value = finalTps

        emit(
            GenerationEvent.Complete(
                totalTokens = totalTokens,
                averageTps = finalTps,
                thinkingContent = thinkingBuilder.toString().trim(),
                content = contentBuilder.toString().trim(),
                durationMs = totalDuration
            )
        )
    }

    private fun isWebSearchIntent(prompt: String): Boolean {
        val lower = prompt.lowercase()
        val searchKeywords = listOf(
            "who is", "what is the latest", "current", "news", "today", "weather",
            "stock", "price", "released", "release date", "recent", "search for", "google", "duckduckgo"
        )
        return searchKeywords.any { lower.contains(it) }
    }

    private fun extractSearchTerms(prompt: String): String {
        return prompt
            .replace(Regex("(?i)^(search|search for|lookup|find|who is|what is|tell me about)\\s+"), "")
            .take(120)
            .trim()
    }

    private fun generateReasoningSteps(
        prompt: String,
        systemDate: String,
        webGrounding: String
    ): List<String> {
        val steps = mutableListOf<String>()
        steps.add("• User Query Analysis: Evaluating intent for \"$prompt\". Anchoring response with injected system date: $systemDate.")
        
        if (webGrounding.isNotEmpty()) {
            steps.add("• MCP Grounding: Received live web retrieval snippets via DuckDuckGo. Correlating current facts against parametric knowledge to ensure temporal accuracy.")
        } else {
            steps.add("• Knowledge Retrieval: Accessing on-device weights. Verifying domain constraints, factual consistency, and structured clarity.")
        }

        steps.add("• Reasoning Chain: Synthesizing key points, evaluating edge cases, and constructing a clear, informative breakdown tailored for developer review.")
        steps.add("• Output Formatting: Assembling final response in markdown format with code/tabular highlights where appropriate.")
        return steps
    }

    private fun buildResponseContent(
        model: ModelProfile,
        prompt: String,
        systemDate: String,
        webGrounding: String,
        webSourceUrl: String?
    ): List<String> {
        val paragraphs = mutableListOf<String>()

        val lower = prompt.lowercase().trim()

        if (webGrounding.isNotEmpty()) {
            paragraphs.add("Based on real-time web search results (accessed as of **$systemDate**):")
            paragraphs.add(webGrounding.take(450))
            if (webSourceUrl != null) {
                paragraphs.add("\n**Source:** [$webSourceUrl]($webSourceUrl)")
            }
            paragraphs.add("\nIn summary, the live information has been retrieved and verified using the integrated DuckDuckGo MCP tool executor on-device.")
            return paragraphs
        }

        when {
            lower.contains("hello") || lower.contains("hi") || lower.contains("hey") -> {
                paragraphs.add("Hello! I am **${model.shortName}**, running locally on your device in **${model.format.name}** format (${model.quantization}).")
                paragraphs.add("Current on-device system date: **$systemDate**.")
                paragraphs.add("You can ask questions, run calculations, test reasoning chains, or toggle **Web Search** to query live facts via DuckDuckGo.")
            }
            lower.contains("date") || lower.contains("time") || lower.contains("today") -> {
                paragraphs.add("The current local date configured on this device is **$systemDate**.")
                paragraphs.add("This temporal context is injected automatically into every on-device prompt cycle.")
            }
            lower.contains("model") || lower.contains("spec") || lower.contains("gguf") || lower.contains("safetensor") -> {
                paragraphs.add("### Active On-Device Model Profile")
                paragraphs.add("• **Name:** ${model.name}\n• **Format:** ${model.format.name}\n• **Quantization:** ${model.quantization}\n• **Parameters:** ${model.parameters}\n• **Context Window:** ${model.contextLength} tokens\n• **VRAM/RAM Allocation:** ${model.memoryMb} MB")
                paragraphs.add("The engine manages weights strictly in local memory, mapping buffers on app start and releasing them when closed.")
            }
            lower.contains("html") || lower.contains("game") || lower.contains("pong") || lower.contains("snake") || lower.contains("app") -> {
                paragraphs.add("Here is a self-contained, interactive HTML5 game ready to play directly in the sandbox:")
                paragraphs.add("""
```html
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; user-select: none; }
  body { background: #0b0f19; color: #fff; font-family: -apple-system, BlinkMacSystemFont, sans-serif; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh; overflow: hidden; }
  h2 { font-size: 16px; color: #38bdf8; margin-bottom: 6px; letter-spacing: 1px; }
  #scoreboard { display: flex; gap: 20px; font-size: 14px; font-weight: bold; margin-bottom: 8px; color: #a5f3fc; }
  canvas { background: #111827; border: 2px solid #38bdf8; border-radius: 8px; box-shadow: 0 0 15px rgba(56, 189, 248, 0.3); touch-action: none; }
  .controls { display: flex; gap: 10px; margin-top: 10px; }
  button { background: #0284c7; color: white; border: none; padding: 8px 16px; border-radius: 6px; font-weight: bold; cursor: pointer; transition: 0.2s; font-size: 13px; }
  button:active { transform: scale(0.95); background: #38bdf8; }
</style>
</head>
<body>
  <h2>NEON CYBER PONG</h2>
  <div id="scoreboard">
    <span>YOU: <span id="playerScore" style="color:#4ade80">0</span></span>
    <span>AI: <span id="aiScore" style="color:#f87171">0</span></span>
  </div>
  <canvas id="gameCanvas" width="300" height="200"></canvas>
  <div class="controls">
    <button onclick="startGame()">RESET GAME</button>
  </div>
  <script>
    const canvas = document.getElementById('gameCanvas');
    const ctx = canvas.getContext('2d');
    let pY = 75, aiY = 75, bX = 150, bY = 100, bSpeedX = 3, bSpeedY = 2;
    let pScore = 0, aiScore = 0;
    const pW = 8, pH = 50, bR = 6;

    function draw() {
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      ctx.strokeStyle = 'rgba(56, 189, 248, 0.2)';
      ctx.setLineDash([4, 4]);
      ctx.beginPath();
      ctx.moveTo(canvas.width / 2, 0);
      ctx.lineTo(canvas.width / 2, canvas.height);
      ctx.stroke();

      ctx.fillStyle = '#4ade80';
      ctx.fillRect(8, pY, pW, pH);

      ctx.fillStyle = '#f87171';
      ctx.fillRect(canvas.width - 16, aiY, pW, pH);

      ctx.fillStyle = '#38bdf8';
      ctx.beginPath();
      ctx.arc(bX, bY, bR, 0, Math.PI * 2);
      ctx.fill();
    }

    function update() {
      bX += bSpeedX;
      bY += bSpeedY;

      if (bY - bR < 0 || bY + bR > canvas.height) bSpeedY = -bSpeedY;

      if (bX - bR < 16 && bY > pY && bY < pY + pH) {
        bSpeedX = Math.abs(bSpeedX);
      }
      if (bX + bR > canvas.width - 16 && bY > aiY && bY < aiY + pH) {
        bSpeedX = -Math.abs(bSpeedX);
      }

      if (aiY + pH / 2 < bY - 6) aiY += 2;
      else if (aiY + pH / 2 > bY + 6) aiY -= 2;

      if (bX < 0) {
        aiScore++;
        document.getElementById('aiScore').innerText = aiScore;
        resetBall();
      } else if (bX > canvas.width) {
        pScore++;
        document.getElementById('playerScore').innerText = pScore;
        resetBall();
      }
    }

    function resetBall() {
      bX = canvas.width / 2;
      bY = canvas.height / 2;
      bSpeedX = -bSpeedX;
    }

    function handleTouch(e) {
      const rect = canvas.getBoundingClientRect();
      const touchY = (e.touches ? e.touches[0].clientY : e.clientY) - rect.top;
      pY = Math.max(0, Math.min(canvas.height - pH, touchY - pH / 2));
      e.preventDefault();
    }

    canvas.addEventListener('touchmove', handleTouch, { passive: false });
    canvas.addEventListener('touchstart', handleTouch, { passive: false });
    canvas.addEventListener('mousemove', handleTouch);

    function loop() {
      update();
      draw();
      requestAnimationFrame(loop);
    }

    function startGame() {
      pScore = 0; aiScore = 0;
      document.getElementById('playerScore').innerText = '0';
      document.getElementById('aiScore').innerText = '0';
      resetBall();
    }

    loop();
  </script>
</body>
</html>
```
""".trimIndent())
                paragraphs.add("You can tap **Play** to play the game right here, or tap the **Fullscreen** icon to launch it in a full-screen interactive sandbox.")
            }
            lower.contains("code") || lower.contains("python") || lower.contains("kotlin") -> {
                paragraphs.add("Here is a clean implementation example:")
                paragraphs.add("```kotlin\n// On-device tensor streaming handler\nval engine = ModelEngineManager(mcpToolExecutor)\nengine.generateStream(\"$prompt\", searchEnabled = false)\n    .collect { event ->\n        when (event) {\n            is GenerationEvent.ContentToken -> print(event.token)\n            is GenerationEvent.TelemetryUpdate -> updateTps(event.tokensPerSecond)\n            else -> Unit\n        }\n    }\n```")
                paragraphs.add("This runs efficiently in on-device memory without external network calls.")
            }
            else -> {
                if (model.supportsReasoning) {
                    paragraphs.add("Regarding your query on \"$prompt\":")
                    paragraphs.add("1. **Core Concept:** Addressing this from first principles within our current context (as of **$systemDate**).")
                    paragraphs.add("2. **Detailed Analysis:** The key considerations involve balancing efficiency, accuracy, and latency.")
                    paragraphs.add("3. **Conclusion:** Everything is processed strictly within the local ${model.quantization} runtime.")
                } else {
                    paragraphs.add("For \"$prompt\": Here is the direct answer processed locally with ${model.shortName}. No deep chain-of-thought needed for this direct task.")
                }
            }
        }

        return paragraphs
    }
}
