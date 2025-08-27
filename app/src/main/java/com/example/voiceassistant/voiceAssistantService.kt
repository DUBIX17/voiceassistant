package com.example.voiceassistant

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.PixelFormat
import android.widget.ImageView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

class VoiceAssistantService : Service() {

    private val client = OkHttpClient()
    private var recorder: AudioRecord? = null
    private var isRecording = false
    private var wakeWordDetected = false
    private var lastSpeechTime = 0L

    private val silenceThresholdRms = 1500.0
    private val silenceTimeoutMs = 1200L

    private val WAKE_WS_URL = BuildConfig.WAKE_WS_URL
    private val STT_WS_URL = BuildConfig.STT_WS_URL
    private val AI_URL = BuildConfig.AI_URL
    private val TTS_URL = BuildConfig.TTS_URL

    private var sttWebSocket: WebSocket? = null
    private var mediaPlayer: MediaPlayer? = null

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // Overlay
    private var overlayView: ImageView? = null
    private var windowManager: WindowManager? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()
        startWakeWordListening()
        return START_STICKY
    }

    override fun onDestroy() {
        stopMicStream()
        hideMicOverlay()
        sttWebSocket?.close(1000, "Service destroyed")
        mediaPlayer?.release()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun startForegroundService() {
        val channelId = "voice_assistant_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Voice Assistant", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Voice Assistant Running")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(1, notification)
    }

    // --- Wake Word ---
    private fun startWakeWordListening() {
        serviceScope.launch {
            val request = Request.Builder().url(WAKE_WS_URL).build()
            client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    startMicStream(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("wake word", ignoreCase = true)) {
                        wakeWordDetected = true
                        playPopSound()
                        showMicOverlay()
                        stopMicStream()
                        startSttListening()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("WakeWordWS", "Error", t)
                }
            })
        }
    }

    // --- Mic streaming ---
    private fun startMicStream(webSocket: WebSocket) {
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        recorder?.startRecording()
        isRecording = true

        serviceScope.launch {
            val buffer = ByteArray(bufferSize)
            while (isRecording) {
                val read = recorder?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    webSocket.send(ByteString.of(buffer, 0, read))
                    val rms = calculateRms(buffer, read)
                    if (rms > silenceThresholdRms) lastSpeechTime = System.currentTimeMillis()
                }
            }
        }
    }

    private fun calculateRms(buffer: ByteArray, read: Int): Double {
        val shorts = ShortArray(read / 2)
        ByteBuffer.wrap(buffer, 0, read).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        val sum = shorts.fold(0.0) { acc, s -> acc + s * s }
        return if (shorts.isNotEmpty()) Math.sqrt(sum / shorts.size) else 0.0
    }

    private fun stopMicStream() {
        recorder?.apply {
            try { stop() } catch (_: Exception) {}
            try { release() } catch (_: Exception) {}
        }
        recorder = null
        isRecording = false
    }

    // --- STT ---
    private fun startSttListening() {
        serviceScope.launch {
            val request = Request.Builder().url(STT_WS_URL).build()
            sttWebSocket = client.newWebSocket(request, object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    startMicStream(webSocket)
                    monitorSilence(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    processTranscript(text)
                    hideMicOverlay()
                    webSocket.close(1000, "Transcript received")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    stopMicStream()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    stopMicStream()
                    Log.e("STTWS", "Error", t)
                }
            })
        }
    }

    private fun monitorSilence(webSocket: WebSocket) {
        serviceScope.launch {
            while (isRecording) {
                if (System.currentTimeMillis() - lastSpeechTime > silenceTimeoutMs) {
                    stopMicStream()
                    Log.d("STT", "Silence detected, mic stopped")
                    break
                }
                delay(100)
            }
        }
    }

    // --- Overlay ---
    private fun showMicOverlay() {
        if (!Settings.canDrawOverlays(this) || overlayView != null) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.END
        layoutParams.x = 50
        layoutParams.y = 100

        overlayView = ImageView(this).apply { setImageResource(android.R.drawable.ic_btn_speak_now) }
        windowManager?.addView(overlayView, layoutParams)
    }

    private fun hideMicOverlay() {
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
    }

    // --- TTS ---
    private fun speak(text: String) {
        val url = "$TTS_URL?text=$text"
        val req = Request.Builder().url(url).build()
        serviceScope.launch {
            try {
                val resp = client.newCall(req).execute()
                val mp3Bytes = resp.body?.bytes()
                mp3Bytes?.let { playMp3(it) }
            } catch (e: Exception) {
                Log.e("TTS", "Error", e)
            }
        }
    }

    private fun playMp3(bytes: ByteArray) {
        try {
            val tempFile = File.createTempFile("tts", ".mp3", cacheDir)
            tempFile.writeBytes(bytes)

            if (mediaPlayer == null) mediaPlayer = MediaPlayer()
            else mediaPlayer?.reset()

            mediaPlayer?.apply {
                setDataSource(tempFile.absolutePath)
                prepareAsync()
                setOnPreparedListener { start() }
                setOnCompletionListener { reset() }
            }
        } catch (e: Exception) {
            Log.e("Audio", "Playback error", e)
        }
    }

    private fun playPopSound() {
        val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 200)
        serviceScope.launch {
            delay(250)
            toneGen.release()
        }
    }

    // --- App map & transcript processing ---
    private val appNameMap = mapOf(
        "chrome" to "com.android.chrome",
        "youtube" to "com.google.android.youtube",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps"
    )

    private fun processTranscript(transcript: String) {
        val lower = transcript.lowercase(Locale.getDefault())
        when {
            listOf("time", "clock", "hour").any { lower.contains(it) } -> {
                val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                speak("The time is $time")
            }
            listOf("date", "day", "today").any { lower.contains(it) } -> {
                val date = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
                speak("Today is $date")
            }
            listOf("location", "where am i", "position", "place").any { lower.contains(it) } -> {
                speak(getCurrentLocation())
            }
            lower.contains("open") -> {
                val spokenApp = lower.substringAfter("open").trim().replace(" ", "")
                val packageName = appNameMap.entries.find { it.key.replace(" ", "") == spokenApp }?.value
                if (packageName != null) {
                    openApp(packageName)
                    speak("Opening $spokenApp")
                } else callAi(transcript)
            }
            else -> callAi(transcript)
        }
    }

    private fun getCurrentLocation(): String {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return "unknown"

        val loc: Location? = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (loc == null) return "unknown"

        return try {
            val geocoder = android.location.Geocoder(this, Locale.getDefault())
            val addresses = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
            if (!addresses.isNullOrEmpty()) "You are near ${addresses[0].getAddressLine(0)}"
            else "Your coordinates are ${loc.latitude}, ${loc.longitude}"
        } catch (e: Exception) {
            "Your coordinates are ${loc.latitude}, ${loc.longitude}"
        }
    }

    private fun openApp(packageName: String) {
        val pm = packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
        launchIntent?.let { startActivity(it) }
    }

    // --- AI Pipeline ---
    private fun callAi(text: String) {
        serviceScope.launch {
            try {
                val json = """{"query":"$text"}"""
                val body = RequestBody.create(
                    "application/json; charset=utf-8".toMediaTypeOrNull(),
                    json
                )
                val request = Request.Builder().url(AI_URL).post(body).build()
                val response = client.newCall(request).execute()
                val aiText = response.body?.string()?.trim() ?: "I couldn't get a response"
                speak(aiText)
            } catch (e: Exception) {
                Log.e("AI", "Error calling AI", e)
                speak("I encountered an error while fetching response")
            }
        }
    }
}
