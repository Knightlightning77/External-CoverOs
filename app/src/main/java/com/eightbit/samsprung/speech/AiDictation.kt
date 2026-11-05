package com.eightbit.samsprung.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.eightbit.samsprung.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Dictation with AI: it records the voice and sends the audio to Gemini, which transcribes it and
 * cleans the text (punctuation, filler words...) in a single request. The text arrives once, when
 * the recording is stopped.
 *
 * Without an API key, or if the microphone cannot be recorded, it falls back to the recognizer
 * of the system ([DictationHelper]).
 *
 * All methods must be called from the main thread.
 */
class AiDictation(
    context: Context,
    private val listener: DictationHelper.Listener
) {
    private val context: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val fallback = DictationHelper(context, listener)
    // One thread per request, so a request that hangs never blocks the next ones
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var connection: HttpURLConnection? = null

    @Volatile private var recording = false
    private var usingFallback = false
    @Volatile private var generation = 0

    /** When false the normal recognizer of the system is always used, without AI. */
    var useAi = true

    /** True while the audio is sent to Gemini and the answer has not arrived. */
    var isProcessing = false
        private set

    /** True while the microphone is recording. */
    val isListening: Boolean
        get() = recording || (usingFallback && fallback.isListening)

    fun start() {
        if (isProcessing || recording) return
        usingFallback = false
        if (!useAi) {
            useFallback()
            return
        }
        if (AiSettings.apiKey(context).isEmpty() && AiSettings.openAiKey(context).isEmpty()) {
            DictationLog.add(context, "IA: sin claves de IA, dictado normal")
            useFallback()
            return
        }
        if (!fallback.hasPermission()) {
            listener.onError(DictationHelper.ERROR_PERMISSION)
            return
        }
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ), 8192
        )
        val record = try { createRecord(bufferSize) } catch (ex: Exception) { null }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            DictationLog.add(context, "IA: no se pudo preparar la grabación, dictado normal")
            record?.release()
            useFallback()
            return
        }
        try {
            record.startRecording()
        } catch (ex: Exception) {
            DictationLog.add(context, "IA: ERROR al grabar ${ex.javaClass.simpleName}: ${ex.message}")
            record.release()
            useFallback()
            return
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            DictationLog.add(context, "IA: el sistema no deja grabar, dictado normal")
            record.release()
            useFallback()
            return
        }
        recording = true
        val gen = ++generation
        listener.onListening(true)
        Thread { readLoop(record, bufferSize, gen) }.start()
    }

    /** Stops the recording and sends it. */
    fun stop() {
        if (usingFallback) {
            fallback.stop()
            return
        }
        stopRecordingState()
    }

    /** Cancels everything: the recording, the request in progress and the fallback recognizer. */
    fun destroy() {
        generation++
        stopRecordingState()
        if (isProcessing) {
            isProcessing = false
            listener.onProcessing(false)
        }
        fallback.destroy()
        usingFallback = false
        // Closes the request in progress, if any (off the main thread: it is a network call)
        executor.execute { try { connection?.disconnect() } catch (_: Exception) { } }
    }

    private fun useFallback() {
        usingFallback = true
        fallback.start()
    }

    private fun stopRecordingState() {
        if (recording) {
            recording = false
            listener.onListening(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(bufferSize: Int) = AudioRecord(
        MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
    )

    private fun readLoop(record: AudioRecord, bufferSize: Int, gen: Int) {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(bufferSize)
        try {
            while (recording && gen == generation && out.size() < MAX_BYTES) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) out.write(buffer, 0, read) else if (read < 0) break
            }
        } finally {
            try { record.stop() } catch (_: Exception) { }
            record.release()
        }
        main.post {
            if (gen == generation) finishRecording(out.toByteArray(), gen)
        }
    }

    private fun finishRecording(pcm: ByteArray, gen: Int) {
        // Also covers the maximum length being reached without the user stopping
        stopRecordingState()
        if (pcm.size < MIN_BYTES) {
            DictationLog.add(context, "IA: grabación demasiado corta (${pcm.size} bytes)")
            return
        }
        isProcessing = true
        listener.onProcessing(true)
        listener.onStatus(context.getString(R.string.ai_status_sending))
        DictationLog.add(context, "IA: enviando ${pcm.size / 32000} s de audio a Gemini")
        executor.execute {
            var text: String? = null
            var error = 0
            try {
                text = requestText(pcm) { gen != generation }
            } catch (ex: ApiException) {
                error = ex.code
                DictationLog.add(context, "IA: ERROR ${ex.status} ${ex.message}")
            } catch (ex: IOException) {
                error = DictationHelper.ERROR_AI_NETWORK
                DictationLog.add(context, "IA: ERROR de red ${ex.javaClass.simpleName}: ${ex.message}")
            } catch (ex: Exception) {
                error = DictationHelper.ERROR_AI_FAILED
                DictationLog.add(context, "IA: ERROR ${ex.javaClass.simpleName}: ${ex.message}")
            }
            main.post {
                // Cancelled or replaced while waiting for the answer
                if (gen != generation || !isProcessing) return@post
                isProcessing = false
                listener.onProcessing(false)
                if (error != 0) {
                    listener.onError(error)
                } else {
                    val clean = text?.trim().orEmpty()
                    if (clean.isEmpty() || clean.contains(NO_VOICE)) {
                        DictationLog.add(context, "IA: sin voz reconocible")
                    } else {
                        DictationLog.add(context, "IA: texto recibido (${clean.length} caracteres)")
                        listener.onText(clean, true)
                    }
                }
            }
        }
    }

    /** Tells the user what is happening. Can be called from the request thread. */
    private fun status(text: String) {
        main.post { if (isProcessing) listener.onStatus(text) }
    }

    private class ApiException(val code: Int, val status: Int, message: String) : Exception(message)

    /**
     * Gemini is sometimes saturated (503). The request is repeated a few times and, if the model
     * is still busy, the lighter model is tried too.
     */
    /** Gemini first; if it fails after its retries, ChatGPT (when its key is set). */
    private fun requestText(pcm: ByteArray, cancelled: () -> Boolean): String {
        val audio = Base64.encodeToString(wav(pcm), Base64.NO_WRAP)
        val hasOpenAi = AiSettings.openAiKey(context).isNotEmpty()
        if (AiSettings.apiKey(context).isNotEmpty()) {
            try {
                return geminiText(audio, cancelled)
            } catch (ex: Exception) {
                if (!hasOpenAi || cancelled()) throw ex
                DictationLog.add(context, "IA: Gemini falló (${ex.message}), probando con ChatGPT")
                status(context.getString(R.string.ai_status_openai_fallback))
            }
        }
        return openAiText(audio, cancelled)
    }

    private fun geminiText(audio: String, cancelled: () -> Boolean): String {
        val main = AiSettings.model(context)
        var last: ApiException? = null
        for (attempt in 0 until ATTEMPTS) {
            if (cancelled()) break
            // The first try uses the chosen model, the next ones the lighter one
            val model = if (attempt == 0) main else FALLBACK_MODEL
            try {
                return call(model, audio)
            } catch (ex: SocketTimeoutException) {
                last = ApiException(DictationHelper.ERROR_AI_BUSY, 0, "sin respuesta a tiempo")
                DictationLog.add(context, "IA: $model no respondió a tiempo, intento ${attempt + 1}")
                if (attempt + 1 < ATTEMPTS)
                    status(context.getString(R.string.ai_status_retry, attempt + 2, ATTEMPTS))
            } catch (ex: ApiException) {
                if (ex.status !in RETRY_STATUS) throw ex
                last = ex
                DictationLog.add(context, "IA: $model ocupado (${ex.status}), intento ${attempt + 1}")
                if (attempt + 1 < ATTEMPTS)
                    status(context.getString(R.string.ai_status_retry, attempt + 2, ATTEMPTS))
                Thread.sleep(1500L * (attempt + 1))
            }
        }
        throw last ?: ApiException(DictationHelper.ERROR_AI_FAILED, 0, "cancelado")
    }

    private fun openAiText(audio: String, cancelled: () -> Boolean): String {
        val model = AiSettings.openAiModel(context)
        var last: Exception? = null
        for (attempt in 0 until OPENAI_ATTEMPTS) {
            if (cancelled()) break
            try {
                // Text-only answer; if the model refuses that, ask for the audio answer too
                return try {
                    callOpenAi(model, audio, true)
                } catch (ex: ApiException) {
                    if (ex.status == 400) callOpenAi(model, audio, false) else throw ex
                }
            } catch (ex: SocketTimeoutException) {
                last = ApiException(DictationHelper.ERROR_AI_BUSY, 0, "sin respuesta a tiempo")
                DictationLog.add(context, "IA: ChatGPT no respondió a tiempo, intento ${attempt + 1}")
            } catch (ex: ApiException) {
                if (ex.status !in RETRY_STATUS) throw ex
                last = ex
                DictationLog.add(context, "IA: ChatGPT ocupado (${ex.status}), intento ${attempt + 1}")
                Thread.sleep(1500L)
            }
        }
        throw last ?: ApiException(DictationHelper.ERROR_AI_FAILED, 0, "cancelado")
    }

    private fun callOpenAi(model: String, audio: String, textOnly: Boolean): String {
        val started = System.currentTimeMillis()
        DictationLog.add(context, "IA: petición a ChatGPT ($model)" + if (textOnly) "" else " (con salida de audio)")
        val message = JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt()))
            .put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject()
                .put("data", audio)
                .put("format", "wav"))))
        val json = JSONObject().put("model", model).put("messages", JSONArray().put(message))
        if (textOnly) {
            json.put("modalities", JSONArray().put("text"))
        } else {
            json.put("modalities", JSONArray().put("text").put("audio"))
            json.put("audio", JSONObject().put("voice", "alloy").put("format", "wav"))
        }
        val answer = postJson(
            "https://api.openai.com/v1/chat/completions",
            "Authorization", "Bearer ${AiSettings.openAiKey(context)}",
            json.toString().toByteArray(Charsets.UTF_8), 30000
        ) { status(context.getString(R.string.ai_status_openai)) }
        DictationLog.add(context, "IA: respuesta en ${System.currentTimeMillis() - started} ms")
        val reply = JSONObject(answer).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw ApiException(DictationHelper.ERROR_AI_FAILED, 200, "respuesta sin texto")
        val text = reply.optString("content", "")
        return if (text.isNotBlank() && text != "null") text
        else reply.optJSONObject("audio")?.optString("transcript", "") ?: ""
    }

    /** Sends a JSON body and returns the answer; errors of the service become [ApiException]. */
    private fun postJson(
        address: String, header: String, value: String, body: ByteArray,
        readTimeout: Int, onSent: () -> Unit
    ): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        this.connection = connection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = readTimeout
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty(header, value)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            onSent()
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val answer = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                val code = when (status) {
                    401, 403 -> DictationHelper.ERROR_AI_KEY
                    429 -> DictationHelper.ERROR_AI_QUOTA
                    in RETRY_STATUS -> DictationHelper.ERROR_AI_BUSY
                    else -> DictationHelper.ERROR_AI_FAILED
                }
                val detail = try {
                    JSONObject(answer).optJSONObject("error")?.optString("message") ?: ""
                } catch (_: Exception) { "" }
                throw ApiException(code, status, "HTTP $status ${detail.take(160)}")
            }
            return answer
        } finally {
            connection.disconnect()
        }
    }

    /** Asks without "thinking" (much faster); if the model refuses that option, asks normally. */
    private fun call(model: String, audio: String): String = try {
        callGemini(model, audio, true)
    } catch (ex: ApiException) {
        if (ex.status == 400) callGemini(model, audio, false) else throw ex
    }

    private fun callGemini(model: String, audio: String, noThinking: Boolean): String {
        val started = System.currentTimeMillis()
        DictationLog.add(context, "IA: petición a $model" + if (noThinking) "" else " (sin ajuste de razonamiento)")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", prompt()))
                .put(JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", "audio/wav")
                    .put("data", audio)))
            )))
            .put("generationConfig", JSONObject().put("temperature", 0).apply {
                if (noThinking) put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            })
            .toString().toByteArray(Charsets.UTF_8)

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/" +
                "$model:generateContent")
        val connection = url.openConnection() as HttpURLConnection
        this.connection = connection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 20000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("x-goog-api-key", AiSettings.apiKey(context))
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            // The audio is sent: Gemini now transcribes it and cleans the text in the same request
            status(context.getString(R.string.ai_status_working))

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val answer = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                val code = when (status) {
                    400, 401, 403 -> DictationHelper.ERROR_AI_KEY
                    429 -> DictationHelper.ERROR_AI_QUOTA
                    in RETRY_STATUS -> DictationHelper.ERROR_AI_BUSY
                    else -> DictationHelper.ERROR_AI_FAILED
                }
                val detail = try {
                    JSONObject(answer).optJSONObject("error")?.optString("message") ?: ""
                } catch (_: Exception) { "" }
                throw ApiException(code, status, "HTTP $status ${detail.take(160)}")
            }
            DictationLog.add(context, "IA: respuesta en ${System.currentTimeMillis() - started} ms")
            return parse(answer)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(answer: String): String {
        val root = JSONObject(answer)
        val parts = root.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw ApiException(DictationHelper.ERROR_AI_FAILED, 200, "respuesta sin texto")
        val text = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought", false)) continue
            text.append(part.optString("text", ""))
        }
        return text.toString()
    }

    private fun prompt(): String {
        val language = Locale.getDefault().getDisplayLanguage(Locale("es"))
        return "Eres un servicio de dictado. Transcribe el audio adjunto y devuelve ÚNICAMENTE " +
                "el texto final listo para escribir, en el mismo idioma en que habla la persona " +
                "(normalmente $language). Reglas: añade puntuación, mayúsculas y saltos de " +
                "párrafo donde corresponda; elimina muletillas, titubeos, repeticiones y " +
                "autocorrecciones; conserva el significado y el tono sin añadir, resumir ni " +
                "traducir; interpreta órdenes habladas como \"coma\", \"punto\" o \"nueva línea\" " +
                "como signos; no incluyas comentarios, comillas ni explicaciones. Si no hay voz " +
                "inteligible responde exactamente $NO_VOICE."
    }

    /** Raw 16 bit mono PCM with the header of a WAV file. */
    private fun wav(pcm: ByteArray): ByteArray {
        val total = pcm.size + 36
        val rate = SAMPLE_RATE
        val header = ByteArray(44)
        fun int(offset: Int, value: Int) {
            for (i in 0 until 4) header[offset + i] = (value shr (8 * i) and 0xFF).toByte()
        }
        fun short(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = (value shr 8 and 0xFF).toByte()
        }
        "RIFF".toByteArray().copyInto(header, 0)
        int(4, total)
        "WAVEfmt ".toByteArray().copyInto(header, 8)
        int(16, 16)
        short(20, 1)
        short(22, 1)
        int(24, rate)
        int(28, rate * 2)
        short(32, 2)
        short(34, 16)
        "data".toByteArray().copyInto(header, 36)
        int(40, pcm.size)
        return header + pcm
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        /** About 0.4 seconds: anything shorter is a stray tap. */
        private const val MIN_BYTES = 12800
        /** Five minutes of audio, well under the size limit of the request. */
        private const val MAX_BYTES = 5 * 60 * 32000
        private const val NO_VOICE = "[SIN_VOZ]"
        /** Lighter alias of Gemini, used when the main model is saturated. */
        private const val FALLBACK_MODEL = "gemini-flash-lite-latest"
        private const val ATTEMPTS = 3
        private const val OPENAI_ATTEMPTS = 2
        private val RETRY_STATUS = setOf(429, 500, 502, 503, 504)
    }
}
