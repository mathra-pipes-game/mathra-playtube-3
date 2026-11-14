/*
 * PlayTube Project Original (2026)
 * arslandaim-hub (GitHub.com/arslandaim-hub)
 * Licenced Under GPL-3.0+
*/
package com.arslandaim.playtube.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalDensity
import android.content.Context
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import com.arslandaim.playtube.R
import kotlin.math.sin
import kotlin.random.Random

/**
 * The 10 "Emoji Effect" styles the user can pick from in Settings.
 * Each style is just a themed set of emojis that fall across the whole app
 * (home screen included), not only while a video is playing.
 */
data class EmojiEffectStyle(
    val id: String,
    val displayName: String,
    val emojis: List<String>
)

val EmojiEffectStyles = listOf(
    EmojiEffectStyle("fire", "Fire", listOf("🔥", "✨")),
    EmojiEffectStyle("hearts", "Hearts", listOf("❤️", "💕", "💖")),
    EmojiEffectStyle("stars", "Stars", listOf("⭐", "✨", "🌟")),
    EmojiEffectStyle("notes", "Music Notes", listOf("🎵", "🎶", "🎧")),
    EmojiEffectStyle("rain", "Rain", listOf("💧", "💦")),
    EmojiEffectStyle("confetti", "Confetti", listOf("🎉", "🎊", "🎈")),
    EmojiEffectStyle("bubbles", "Bubbles", listOf("🫧", "🔵")),
    EmojiEffectStyle("lightning", "Lightning", listOf("⚡", "💥")),
    EmojiEffectStyle("snow", "Snow", listOf("❄️", "🌨️")),
    EmojiEffectStyle("sparkle", "Sparkle", listOf("🌟", "💫", "✨")),
    EmojiEffectStyle("funny", "Funny", listOf("😂", "🤣", "😆", "😹", "😝", "🤪", "😜", "🥳", "😁", "😋"))
)

fun emojiStyleById(id: String): EmojiEffectStyle =
    EmojiEffectStyles.firstOrNull { it.id == id } ?: EmojiEffectStyles.first()

private class EmojiParticle(
    var x: Float,
    var y: Float,
    var emoji: String,
    val sizeDp: Float,
    val speedMul: Float,
    val drift: Float,
    val seed: Float,
    var rotation: Float,
    val spin: Float
)

private fun newParticle(style: EmojiEffectStyle, startAnywhere: Boolean) = EmojiParticle(
    x = Random.nextFloat(),
    y = if (startAnywhere) Random.nextFloat() else -0.08f - Random.nextFloat() * 0.15f,
    emoji = style.emojis[Random.nextInt(style.emojis.size)],
    sizeDp = Random.nextFloat() * 26f + 24f,          // 24dp..50dp - big and clearly visible
    speedMul = Random.nextFloat() * 0.5f + 0.85f,     // every emoji falls at its own pace
    drift = Random.nextFloat() * 0.6f - 0.3f,
    seed = Random.nextFloat() * 1000f,
    rotation = Random.nextFloat() * 40f - 20f,
    spin = Random.nextFloat() * 90f - 45f
)

/** Tiny pop sound for the emoji effect. Mixes with video audio (no audio focus taken). */
private class EmojiSoundPlayer(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private var soundId = 0
    @Volatile private var loaded = false
    private var lastPlayMs = 0L

    init {
        pool.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
        soundId = pool.load(context.applicationContext, R.raw.emoji_pop, 1)
    }

    fun play(volume: Float) {
        if (!loaded) return
        val now = SystemClock.uptimeMillis()
        if (now - lastPlayMs < 120L) return
        lastPlayMs = now
        val v = volume.coerceIn(0.1f, 1f)
        pool.play(soundId, v, v, 1, 0, 0.9f + Random.nextFloat() * 0.5f)
    }

    fun release() = pool.release()
}

/**
 * Falling emoji particles overlaid on top of a screen (home, library, player...).
 * Touches pass straight through - the canvas has no pointer handling.
 *
 * When [beatReactive] is true, fall speed swells from soft to strong
 * (හෙමින් සිට හයියෙන් දක්වා) using a smooth, continuously varying rhythm.
 * When [soundEnabled] is true a soft pop plays whenever an emoji lands.
 */
@Composable
fun EmojiEffectOverlay(
    enabled: Boolean,
    isPlaying: Boolean = true,
    style: EmojiEffectStyle,
    beatReactive: Boolean,
    modifier: Modifier = Modifier,
    soundEnabled: Boolean = false,
    particleCount: Int = 36
) {
    if (!enabled) return

    // Fall speed chosen in Settings (Slow 0.6x / Normal 1x / Fast 1.6x / Super Fast 2.4x).
    val speedScale by androidx.hilt.navigation.compose.hiltViewModel<com.arslandaim.playtube.MainViewModel>()
        .emojiEffectsSpeed
        .collectAsStateWithLifecycle()

    val context = LocalContext.current
    val density = LocalDensity.current.density

    val particles = remember(style.id, particleCount) {
        List(particleCount) { newParticle(style, startAnywhere = true) }
    }

    val sound = remember(soundEnabled) { if (soundEnabled) EmojiSoundPlayer(context) else null }
    DisposableEffect(sound) { onDispose { sound?.release() } }

    var frame by remember { mutableLongStateOf(0L) }
    var rhythm by remember { mutableFloatStateOf(0.75f) }

    LaunchedEffect(isPlaying, style.id, beatReactive, sound) {
        if (!isPlaying) return@LaunchedEffect
        var elapsed = 0f
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = now
                elapsed += dt

                // Soft -> strong -> soft rhythm built from two sine waves so the
                // intensity swells unevenly, like a track building up and dropping.
                val r = if (beatReactive) {
                    val slow = (sin(elapsed * 1.1f) + 1f) / 2f
                    val fast = (sin(elapsed * 3.7f) + 1f) / 2f
                    0.35f + 0.65f * (0.6f * slow + 0.4f * fast)
                } else {
                    0.5f
                }
                rhythm = r

                // Falling speed in screen-heights per second: ~0.17 (soft) .. ~0.45 (strong).
                val speed = (0.15f + r * 0.30f) * speedScale
                particles.forEach { p ->
                    p.y += speed * p.speedMul * dt
                    p.rotation += p.spin * dt * 0.5f
                    if (p.y > 1.08f) {
                        p.y = -0.08f
                        p.x = Random.nextFloat()
                        p.emoji = style.emojis[Random.nextInt(style.emojis.size)]
                        sound?.play(0.3f + r * 0.5f)
                    }
                }
                frame = now // invalidates the Canvas draw block below
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val paint = remember {
            Paint().apply {
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val tickRead = frame
            val r = rhythm
            val t = tickRead / 1_000_000_000f
            val scale = 0.9f + r * 0.4f
            val alpha = (0.7f + r * 0.3f).coerceIn(0f, 1f)
            val canvas = drawContext.canvas.nativeCanvas

            particles.forEach { p ->
                val wobble = sin(t * 1.4f + p.seed) * p.drift * 0.06f
                val drawX = (p.x + wobble).coerceIn(0.02f, 0.98f) * size.width
                val drawY = p.y * size.height

                paint.textSize = p.sizeDp * density * scale
                paint.alpha = (alpha * 255).toInt()
                canvas.save()
                canvas.rotate(p.rotation, drawX, drawY)
                canvas.drawText(p.emoji, drawX, drawY, paint)
                canvas.restore()
            }
        }
    }
}
