package app.halo.effects

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.halo.MainActivity
import app.halo.Notifications
import app.halo.R
import app.halo.light.Diagnostics
import app.halo.light.LightController
import app.halo.light.LightHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

enum class EffectType(val title: String, val blurb: String) {
    BREATHE("Breathe", "Slow, calm pulsing of your colour"),
    CANDLE("Candle", "Warm, living flicker"),
    RAINBOW("Spectrum", "Glides through every colour"),
    OCEAN("Tide", "Rolling blues and teals"),
    PARTY("Party", "Bold colour jumps"),
    LIGHTNING("Storm", "Dim blue with sudden flashes"),
    MUSIC("Music sync", "Reacts to sound around your phone"),
}

data class EffectConfig(
    val type: EffectType,
    val speed: Float = 0.5f,
    val intensity: Float = 0.7f,
    val hue: Float = 30f,
    val saturation: Float = 1f,
)

/** Plays an effect by streaming colours to the bulb; runs as a foreground service so it survives the screen turning off. */
class EffectService : Service() {

    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEffect()
            return START_NOT_STICKY
        }
        val config = Effects.pending ?: run { stopSelf(); return START_NOT_STICKY }
        val controller = LightHub.activeController() ?: run { stopSelf(); return START_NOT_STICKY }

        val type = if (config.type == EffectType.MUSIC && Build.VERSION.SDK_INT >= 30)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, Notifications.ID_EFFECT, notification(config), type)
        } catch (e: Exception) {
            Diagnostics.log("effects", "couldn't start: ${e.message}")
            Effects.markStopped()
            stopSelf()
            return START_NOT_STICKY
        }

        job?.cancel()
        LightHub.keepAlive++
        Effects.markRunning(config)
        job = LightHub.scope.launch {
            try {
                run(controller, config)
            } catch (e: Exception) {
                Diagnostics.log("effects", "stopped: ${e.message}")
            } finally {
                LightHub.keepAlive--
            }
        }
        return START_NOT_STICKY
    }

    private fun stopEffect() {
        job?.cancel()
        job = null
        Effects.markStopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        Effects.markStopped()
        super.onDestroy()
    }

    private fun notification(config: EffectConfig): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, EffectService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, Notifications.CHANNEL_EFFECTS)
            .setSmallIcon(R.drawable.ic_bulb)
            .setContentTitle("${config.type.title} is playing")
            .setContentText("Tap Stop to return your light to normal")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private suspend fun run(c: LightController, cfg: EffectConfig) {
        c.refresh()
        val startMs = System.currentTimeMillis()
        fun t() = (System.currentTimeMillis() - startMs) / 1000.0
        val amp = cfg.intensity.coerceIn(0.05f, 1f)
        when (cfg.type) {
            EffectType.BREATHE -> {
                val period = 10.0 - cfg.speed * 8.0
                while (currentCoroutineContextActive()) {
                    val phase = 0.5 - 0.5 * cos(2 * PI * t() / period)
                    val v = (1f - amp) + amp * phase.toFloat()
                    c.previewColour(cfg.hue, cfg.saturation, v.coerceIn(0.03f, 1f))
                    delay(140)
                }
            }
            EffectType.CANDLE -> {
                var v = 0.5f
                while (currentCoroutineContextActive()) {
                    val target = 0.55f + (Random.nextFloat() - 0.5f) * amp * 0.8f
                    v += (target - v) * (0.35f + cfg.speed * 0.5f)
                    val hue = 22f + Random.nextFloat() * 10f
                    c.previewColour(hue, 0.95f, v.coerceIn(0.08f, 1f))
                    delay((90 + Random.nextInt(220) * (1.2f - cfg.speed)).toLong())
                }
            }
            EffectType.RAINBOW -> {
                val period = 60.0 - cfg.speed * 54.0
                while (currentCoroutineContextActive()) {
                    val hue = ((t() / period * 360.0) % 360.0).toFloat()
                    c.previewColour(hue, cfg.saturation.coerceAtLeast(0.6f), 0.4f + 0.6f * amp)
                    delay(150)
                }
            }
            EffectType.OCEAN -> {
                val period = 16.0 - cfg.speed * 12.0
                while (currentCoroutineContextActive()) {
                    val w1 = 0.5 + 0.5 * kotlin.math.sin(2 * PI * t() / period)
                    val w2 = 0.5 + 0.5 * kotlin.math.sin(2 * PI * t() / (period * 0.63) + 1.3)
                    val hue = (178 + 46 * w1).toFloat()
                    val v = (0.35 + 0.65 * amp * (0.55 + 0.45 * w2)).toFloat()
                    c.previewColour(hue, 0.85f, v)
                    delay(160)
                }
            }
            EffectType.PARTY -> {
                var hue = Random.nextFloat() * 360f
                while (currentCoroutineContextActive()) {
                    hue = (hue + 70f + Random.nextFloat() * 150f) % 360f
                    c.previewColour(hue, 1f, 0.5f + 0.5f * amp)
                    delay((1800 - cfg.speed * 1500).toLong().coerceAtLeast(200))
                }
            }
            EffectType.LIGHTNING -> {
                while (currentCoroutineContextActive()) {
                    c.previewColour(225f, 0.8f, 0.06f + 0.06f * amp)
                    delay((2500 + Random.nextInt(9000) * (1.1f - cfg.speed)).toLong())
                    if (!currentCoroutineContextActive()) break
                    repeat(1 + Random.nextInt(3)) {
                        c.previewColour(215f, 0.08f, 1f)
                        delay(110)
                        c.previewColour(225f, 0.6f, 0.15f)
                        delay(90 + Random.nextLong(160))
                    }
                }
            }
            EffectType.MUSIC -> runMusic(c, cfg)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runMusic(c: LightController, cfg: EffectConfig) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Diagnostics.log("effects", "microphone permission missing")
            return
        }
        val rate = 22050
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 4096))
        val buf = ShortArray(rate / 20) // 50ms
        var env = 0f
        var peak = 0.05f
        var hue = cfg.hue
        var lastBeat = 0L
        withContext(Dispatchers.IO) {
            rec.startRecording()
            try {
                var lastSend = 0L
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                    val rms = (sqrt(sum / n) / 32768.0).toFloat()
                    peak = max(rms, peak * 0.995f).coerceAtLeast(0.02f)
                    val level = (rms / peak).coerceIn(0f, 1f)
                    env = if (level > env) env + (level - env) * 0.7f else env * (0.8f - cfg.speed * 0.2f)
                    val now = System.currentTimeMillis()
                    if (level > 0.85f && now - lastBeat > 250) {
                        hue = (hue + 35f + cfg.speed * 60f) % 360f
                        lastBeat = now
                    }
                    if (now - lastSend >= 110) {
                        lastSend = now
                        val v = 0.05f + 0.95f * env * cfg.intensity.coerceIn(0.2f, 1f)
                        withContext(Dispatchers.Main) { c.previewColour(hue, cfg.saturation, v) }
                    }
                }
            } finally {
                rec.stop()
                rec.release()
            }
        }
    }

    private suspend fun currentCoroutineContextActive() = kotlin.coroutines.coroutineContext.isActive

    companion object {
        const val ACTION_STOP = "app.halo.effects.STOP"
    }
}

object Effects {
    @Volatile internal var pending: EffectConfig? = null
    private val _running = MutableStateFlow<EffectConfig?>(null)
    val running: StateFlow<EffectConfig?> = _running

    fun start(context: Context, config: EffectConfig) {
        pending = config
        ContextCompat.startForegroundService(context, Intent(context, EffectService::class.java))
    }

    fun stop(context: Context) {
        if (_running.value == null) return
        context.startService(Intent(context, EffectService::class.java).setAction(EffectService.ACTION_STOP))
    }

    internal fun markRunning(c: EffectConfig) { _running.value = c }
    internal fun markStopped() { _running.value = null }
}
