package com.example.model

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Random

data class LlamaConfig(
    val dim: Int,
    val hiddenDim: Int,
    val nLayers: Int,
    val nHeads: Int,
    val nKvHeads: Int,
    val vocabSize: Int,
    val seqLen: Int,
    val sharedWeights: Boolean
)

class LlamaTokenizer {
    var vocabSize: Int = 32000
    var vocab: Array<String> = Array(32000) { "" }
    var vocabScores: FloatArray = FloatArray(32000)
    var maxTokenLength: Int = 32
    private val sortedVocab = mutableMapOf<String, Int>()
    var isLoaded = false

    fun load(file: File) {
        if (!file.exists()) return
        FileInputStream(file).channel.use { channel ->
            val buf = ByteBuffer.allocateDirect(file.length().toInt()).order(ByteOrder.LITTLE_ENDIAN)
            channel.read(buf)
            buf.flip()

            maxTokenLength = buf.int
            vocab = Array(vocabSize) { "" }
            vocabScores = FloatArray(vocabSize)
            sortedVocab.clear()

            for (i in 0 until vocabSize) {
                if (buf.remaining() < 8) break
                val score = buf.float
                val len = buf.int
                if (buf.remaining() < len) break
                val bytes = ByteArray(len)
                buf.get(bytes)
                val str = String(bytes, Charsets.UTF_8)
                vocab[i] = str
                vocabScores[i] = score
                sortedVocab[str] = i
            }
            isLoaded = true
        }
    }

    fun decode(token: Int): String {
        if (!isLoaded || token < 0 || token >= vocab.size) {
            return if (token in 32..126) token.toChar().toString() else " "
        }
        val piece = vocab[token]
        // Parse raw bytes like <0x01>
        if (piece.startsWith("<0x") && piece.endsWith(">") && piece.length == 6) {
            val hex = piece.substring(3, 5)
            val byteVal = hex.toIntOrNull(16)
            if (byteVal != null) {
                return byteArrayOf(byteVal.toByte()).toString(Charsets.ISO_8859_1)
            }
        }
        return piece.replace(" ", " ")
    }

    fun encode(text: String): List<Int> {
        if (!isLoaded || sortedVocab.isEmpty()) {
            // Simple byte fallback
            val tokens = mutableListOf<Int>()
            tokens.add(1) // BOS
            for (b in text.toByteArray(Charsets.UTF_8)) {
                tokens.add((b.toInt() and 0xFF) + 3)
            }
            return tokens
        }

        val tokens = mutableListOf<Int>()
        tokens.add(1) // BOS

        // Start with individual characters / bytes
        if (text.isNotEmpty()) {
            sortedVocab[" "]?.let { tokens.add(it) }
        }

        for (c in text) {
            val s = c.toString()
            val id = sortedVocab[s]
            if (id != null) {
                tokens.add(id)
            } else {
                for (b in s.toByteArray(Charsets.UTF_8)) {
                    tokens.add((b.toInt() and 0xFF) + 3)
                }
            }
        }

        // Merge pairs according to vocab scores
        while (tokens.size > 1) {
            var bestScore = -1e10f
            var bestId = -1
            var bestIdx = -1

            for (i in 0 until tokens.size - 1) {
                val t1 = tokens[i]
                val t2 = tokens[i + 1]
                if (t1 in vocab.indices && t2 in vocab.indices) {
                    val merged = vocab[t1] + vocab[t2]
                    val id = sortedVocab[merged]
                    if (id != null && vocabScores[id] > bestScore) {
                        bestScore = vocabScores[id]
                        bestId = id
                        bestIdx = i
                    }
                }
            }

            if (bestIdx == -1) break
            tokens[bestIdx] = bestId
            tokens.removeAt(bestIdx + 1)
        }

        return tokens
    }
}

class Llama2Engine {
    var config: LlamaConfig? = null
    private var weightsBuffer: ByteBuffer? = null

    // Weights offsets (in floats)
    private var tokenEmbOffset = 0
    private var rmsAttOffset = 0
    private var wqOffset = 0
    private var wkOffset = 0
    private var wvOffset = 0
    private var woOffset = 0
    private var rmsFfnOffset = 0
    private var w1Offset = 0
    private var w2Offset = 0
    private var w3Offset = 0
    private var rmsFinalOffset = 0
    private var wclsOffset = 0

    // Activation buffers
    private var x: FloatArray = FloatArray(0)
    private var xb: FloatArray = FloatArray(0)
    private var xb2: FloatArray = FloatArray(0)
    private var hb: FloatArray = FloatArray(0)
    private var hb2: FloatArray = FloatArray(0)
    private var q: FloatArray = FloatArray(0)
    private var keyCache: FloatArray = FloatArray(0)
    private var valueCache: FloatArray = FloatArray(0)
    private var att: FloatArray = FloatArray(0)
    private var logits: FloatArray = FloatArray(0)

    val tokenizer = LlamaTokenizer()
    val random = Random()
    var isLoaded = false

    fun load(modelFile: File, tokenizerFile: File? = null) {
        if (!modelFile.exists() || modelFile.length() < 28) {
            throw IllegalArgumentException("Model file does not exist or is too small")
        }

        val fileLength = modelFile.length()
        FileInputStream(modelFile).channel.use { channel ->
            val buf = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileLength)
            buf.order(ByteOrder.LITTLE_ENDIAN)

            val dim = buf.int
            val hiddenDim = buf.int
            val nLayers = buf.int
            val nHeads = buf.int
            val nKvHeads = buf.int
            val rawVocab = buf.int
            val seqLen = buf.int

            val sharedWeights = rawVocab > 0
            val vocabSize = Math.abs(rawVocab)

            val conf = LlamaConfig(
                dim = dim,
                hiddenDim = hiddenDim,
                nLayers = nLayers,
                nHeads = nHeads,
                nKvHeads = nKvHeads,
                vocabSize = vocabSize,
                seqLen = seqLen,
                sharedWeights = sharedWeights
            )
            config = conf

            // Map offsets
            val headSize = dim / nHeads
            var ptr = 7 // 7 ints header

            tokenEmbOffset = ptr
            ptr += vocabSize * dim

            rmsAttOffset = ptr
            ptr += nLayers * dim

            wqOffset = ptr
            ptr += nLayers * dim * (nHeads * headSize)

            wkOffset = ptr
            ptr += nLayers * dim * (nKvHeads * headSize)

            wvOffset = ptr
            ptr += nLayers * dim * (nKvHeads * headSize)

            woOffset = ptr
            ptr += nLayers * (nHeads * headSize) * dim

            rmsFfnOffset = ptr
            ptr += nLayers * dim

            w1Offset = ptr
            ptr += nLayers * dim * hiddenDim

            w2Offset = ptr
            ptr += nLayers * hiddenDim * dim

            w3Offset = ptr
            ptr += nLayers * dim * hiddenDim

            rmsFinalOffset = ptr
            ptr += dim

            ptr += seqLen * headSize // freq_cis_real & imag

            wclsOffset = if (sharedWeights) tokenEmbOffset else ptr

            // Allocate runstate
            val kvDim = (dim * nKvHeads) / nHeads
            x = FloatArray(dim)
            xb = FloatArray(dim)
            xb2 = FloatArray(dim)
            hb = FloatArray(hiddenDim)
            hb2 = FloatArray(hiddenDim)
            q = FloatArray(dim)
            keyCache = FloatArray(nLayers * seqLen * kvDim)
            valueCache = FloatArray(nLayers * seqLen * kvDim)
            att = FloatArray(nHeads * seqLen)
            logits = FloatArray(vocabSize)

            weightsBuffer = buf
            tokenizer.vocabSize = vocabSize
            if (tokenizerFile != null && tokenizerFile.exists()) {
                tokenizer.load(tokenizerFile)
            }
            isLoaded = true
        }
    }

    private fun getWeight(offset: Int, index: Int): Float {
        val buf = weightsBuffer ?: return 0f
        val bytePos = (offset + index) * 4
        if (bytePos + 4 > buf.capacity()) return 0f
        return buf.getFloat(bytePos)
    }

    private fun rmsnorm(o: FloatArray, x: FloatArray, weightOffset: Int, size: Int) {
        var ss = 0.0f
        for (j in 0 until size) {
            ss += x[j] * x[j]
        }
        ss /= size
        ss += 1e-5f
        val invScale = (1.0f / Math.sqrt(ss.toDouble())).toFloat()
        for (j in 0 until size) {
            o[j] = getWeight(weightOffset, j) * (invScale * x[j])
        }
    }

    private fun matmul(xout: FloatArray, x: FloatArray, wOffset: Int, n: Int, d: Int) {
        for (i in 0 until d) {
            var sum = 0.0f
            val rowOffset = wOffset + i * n
            for (j in 0 until n) {
                sum += getWeight(rowOffset, j) * x[j]
            }
            xout[i] = sum
        }
    }

    private fun softmax(arr: FloatArray, offset: Int, size: Int) {
        var maxVal = arr[offset]
        for (i in 1 until size) {
            if (arr[offset + i] > maxVal) {
                maxVal = arr[offset + i]
            }
        }
        var sum = 0.0f
        for (i in 0 until size) {
            val exp = Math.exp((arr[offset + i] - maxVal).toDouble()).toFloat()
            arr[offset + i] = exp
            sum += exp
        }
        val invSum = 1.0f / (if (sum > 0f) sum else 1f)
        for (i in 0 until size) {
            arr[offset + i] *= invSum
        }
    }

    fun forward(token: Int, pos: Int): FloatArray {
        val conf = config ?: return logits
        val dim = conf.dim
        val kvDim = (dim * conf.nKvHeads) / conf.nHeads
        val kvMul = conf.nHeads / conf.nKvHeads
        val hiddenDim = conf.hiddenDim
        val headSize = dim / conf.nHeads

        // Copy token embedding
        val rowOffset = tokenEmbOffset + token * dim
        for (i in 0 until dim) {
            x[i] = getWeight(rowOffset, i)
        }

        val kVec = FloatArray(kvDim)
        val vVec = FloatArray(kvDim)

        for (l in 0 until conf.nLayers) {
            // Attention RMSNorm
            rmsnorm(xb, x, rmsAttOffset + l * dim, dim)

            val loff = l * conf.seqLen * kvDim
            val posKOffset = loff + pos * kvDim

            // Q, K, V projections
            matmul(q, xb, wqOffset + l * dim * dim, dim, dim)
            matmul(kVec, xb, wkOffset + l * dim * kvDim, dim, kvDim)
            matmul(vVec, xb, wvOffset + l * dim * kvDim, dim, kvDim)

            // Save to KV cache
            System.arraycopy(kVec, 0, keyCache, posKOffset, kvDim)
            System.arraycopy(vVec, 0, valueCache, posKOffset, kvDim)

            // RoPE
            for (i in 0 until dim step 2) {
                val headDim = i % headSize
                val freq = (1.0f / Math.pow(10000.0, headDim.toDouble() / headSize)).toFloat()
                val valAngle = pos * freq
                val fcr = Math.cos(valAngle.toDouble()).toFloat()
                val fci = Math.sin(valAngle.toDouble()).toFloat()

                val v0 = q[i]
                val v1 = q[i + 1]
                q[i] = v0 * fcr - v1 * fci
                q[i + 1] = v0 * fci + v1 * fcr

                if (i < kvDim) {
                    val k0 = keyCache[posKOffset + i]
                    val k1 = keyCache[posKOffset + i + 1]
                    keyCache[posKOffset + i] = k0 * fcr - k1 * fci
                    keyCache[posKOffset + i + 1] = k0 * fci + k1 * fcr
                }
            }

            // Multi-head attention
            for (h in 0 until conf.nHeads) {
                val qOffset = h * headSize
                val attOffset = h * conf.seqLen

                for (t in 0..pos) {
                    val kOffset = loff + t * kvDim + (h / kvMul) * headSize
                    var score = 0.0f
                    for (i in 0 until headSize) {
                        score += q[qOffset + i] * keyCache[kOffset + i]
                    }
                    score /= Math.sqrt(headSize.toDouble()).toFloat()
                    att[attOffset + t] = score
                }

                softmax(att, attOffset, pos + 1)

                val xbOffset = h * headSize
                for (i in 0 until headSize) {
                    xb[xbOffset + i] = 0f
                }
                for (t in 0..pos) {
                    val vOffset = loff + t * kvDim + (h / kvMul) * headSize
                    val a = att[attOffset + t]
                    for (i in 0 until headSize) {
                        xb[xbOffset + i] += a * valueCache[vOffset + i]
                    }
                }
            }

            // Output projection
            matmul(xb2, xb, woOffset + l * dim * dim, dim, dim)
            for (i in 0 until dim) {
                x[i] += xb2[i]
            }

            // FFN RMSNorm
            rmsnorm(xb, x, rmsFfnOffset + l * dim, dim)
            matmul(hb, xb, w1Offset + l * dim * hiddenDim, dim, hiddenDim)
            matmul(hb2, xb, w3Offset + l * dim * hiddenDim, dim, hiddenDim)

            // SwiGLU
            for (i in 0 until hiddenDim) {
                val val1 = hb[i]
                val silu = val1 * (1.0f / (1.0f + Math.exp(-val1.toDouble()).toFloat()))
                hb[i] = silu * hb2[i]
            }

            matmul(xb, hb, w2Offset + l * hiddenDim * dim, hiddenDim, dim)
            for (i in 0 until dim) {
                x[i] += xb[i]
            }
        }

        // Final RMSNorm
        rmsnorm(x, x, rmsFinalOffset, dim)
        // Logits classifier
        matmul(logits, x, wclsOffset, dim, conf.vocabSize)
        return logits
    }

    fun sample(logits: FloatArray, temperature: Float = 0.8f): Int {
        val vocabSize = logits.size
        if (temperature <= 0f) {
            // Argmax
            var maxIdx = 0
            var maxVal = logits[0]
            for (i in 1 until vocabSize) {
                if (logits[i] > maxVal) {
                    maxVal = logits[i]
                    maxIdx = i
                }
            }
            return maxIdx
        }

        // Softmax with temperature
        val probs = FloatArray(vocabSize)
        var maxVal = logits[0] / temperature
        for (i in 1 until vocabSize) {
            val v = logits[i] / temperature
            if (v > maxVal) maxVal = v
        }
        var sum = 0.0f
        for (i in 0 until vocabSize) {
            val exp = Math.exp(((logits[i] / temperature) - maxVal).toDouble()).toFloat()
            probs[i] = exp
            sum += exp
        }
        val r = random.nextFloat() * sum
        var cdf = 0.0f
        for (i in 0 until vocabSize) {
            cdf += probs[i]
            if (r < cdf) return i
        }
        return vocabSize - 1
    }

    fun close() {
        weightsBuffer = null
        isLoaded = false
    }
}
