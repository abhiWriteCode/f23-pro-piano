package com.pro.f23piano

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

data class PianoKey(
    val noteOffset: Int,
    val isBlack: Boolean,
    val xStartRatio: Float,
    val xEndRatio: Float
)

class SynthEngine {
    private val sampleRate = 44100
    private val scope = CoroutineScope(Dispatchers.Default)

    fun playNote(semitoneFromA4: Int, sustainMultiplier: Double = 1.0) {
        scope.launch {
            val freq = 440.0 * 2.0.pow(semitoneFromA4 / 12.0)
            val durationSeconds = (1.6 * sustainMultiplier).coerceAtMost(3.5)
            val numSamples = (durationSeconds * sampleRate).toInt()
            val buffer = ShortArray(numSamples)

            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                val envelope = exp(-3.2 * t / sustainMultiplier) * (1.0 - exp(-200.0 * t))
                val wave = (0.60 * sin(2.0 * Math.PI * freq * t) +
                            0.25 * sin(4.0 * Math.PI * freq * t) +
                            0.10 * sin(6.0 * Math.PI * freq * t) +
                            0.05 * sin(8.0 * Math.PI * freq * t))
                buffer[i] = (wave * envelope * Short.MAX_VALUE * 0.45).toInt().toShort()
            }

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(buffer.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            track.write(buffer, 0, buffer.size)
            track.play()
            delay((durationSeconds * 1000).toLong() + 100)
            track.release()
        }
    }
}

class MainActivity : ComponentActivity() {
    private val synth = SynthEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PianoAppScreen(synth)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PianoAppScreen(synth: SynthEngine) {
    var octaveShift by remember { mutableStateOf(0) }
    var sustainEnabled by remember { mutableStateOf(true) }
    var canvasSize by remember { mutableStateOf(IntSize(2408, 1080)) }
    val activeKeys = remember { mutableStateListOf<Int>() }
    val keys = remember { buildTwoOctaveLayout() }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(Color(0xFF1F1F1F))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("SAMSUNG F23 STUDIO GRAND", color = Color(0xFFE0E0E0), fontSize = 15.sp)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { if (octaveShift > -2) octaveShift-- }) { Text("- Oct") }
                Text(
                    " Octave: ${if (octaveShift >= 0) "+$octaveShift" else octaveShift} ",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Button(onClick = { if (octaveShift < 2) octaveShift++ }) { Text("+ Oct") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sustain", color = Color.LightGray, modifier = Modifier.padding(end = 6.dp))
                Switch(checked = sustainEnabled, onCheckedChange = { sustainEnabled = it })
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize = it }
                    .pointerInteropFilter { event ->
                        val action = event.actionMasked
                        val pointerIndex = event.actionIndex

                        when (action) {
                            MotionEvent.ACTION_DOWN,
                            MotionEvent.ACTION_POINTER_DOWN -> {
                                val x = event.getX(pointerIndex)
                                val y = event.getY(pointerIndex)
                                val hitKey = detectKeyHit(
                                    x, y,
                                    canvasSize.width.toFloat().coerceAtLeast(1f),
                                    canvasSize.height.toFloat().coerceAtLeast(1f),
                                    keys
                                )
                                if (hitKey != null && !activeKeys.contains(hitKey.noteOffset)) {
                                    activeKeys.add(hitKey.noteOffset)
                                    synth.playNote(
                                        hitKey.noteOffset + (octaveShift * 12),
                                        if (sustainEnabled) 1.8 else 0.7
                                    )
                                }
                            }
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_POINTER_UP,
                            MotionEvent.ACTION_CANCEL -> {
                                activeKeys.clear()
                            }
                        }
                        true
                    }
            ) {
                val w = size.width
                val h = size.height

                keys.filter { !it.isBlack }.forEach { key ->
                    val isPressed = activeKeys.contains(key.noteOffset)
                    drawRoundRect(
                        color = if (isPressed) Color(0xFF90CAF9) else Color(0xFFFDFDFD),
                        topLeft = Offset(key.xStartRatio * w + 2f, 0f),
                        size = Size((key.xEndRatio - key.xStartRatio) * w - 4f, h - 6f),
                        cornerRadius = CornerRadius(10f, 10f)
                    )
                }

                keys.filter { it.isBlack }.forEach { key ->
                    val isPressed = activeKeys.contains(key.noteOffset)
                    drawRoundRect(
                        color = if (isPressed) Color(0xFF37474F) else Color(0xFF141414),
                        topLeft = Offset(key.xStartRatio * w, 0f),
                        size = Size((key.xEndRatio - key.xStartRatio) * w, h * 0.60f),
                        cornerRadius = CornerRadius(8f, 8f)
                    )
                }
            }
        }
    }
}

fun detectKeyHit(x: Float, y: Float, w: Float, h: Float, keys: List<PianoKey>): PianoKey? {
    val xRatio = (x / w).coerceIn(0f, 1f)
    val yRatio = (y / h).coerceIn(0f, 1f)

    if (yRatio <= 0.60f) {
        keys.firstOrNull { it.isBlack && xRatio in it.xStartRatio..it.xEndRatio }?.let { return it }
    }
    return keys.firstOrNull { !it.isBlack && xRatio in it.xStartRatio..it.xEndRatio }
}

fun buildTwoOctaveLayout(): List<PianoKey> {
    val list = mutableListOf<PianoKey>()
    val whiteNotes = listOf(-9, -7, -5, -4, -2, 0, 2, 3, 5, 7, 8, 10, 12, 14)
    val whiteWidth = 1f / 14f

    whiteNotes.forEachIndexed { idx, semitone ->
        list.add(PianoKey(semitone, false, idx * whiteWidth, (idx + 1) * whiteWidth))
    }

    val blackOffsets = mapOf(0 to -8, 1 to -6, 3 to -3, 4 to -1, 5 to 1, 7 to 4, 8 to 6, 10 to 9, 11 to 11, 12 to 13)
    blackOffsets.forEach { (whiteIdx, semitone) ->
        val center = (whiteIdx + 1) * whiteWidth
        list.add(PianoKey(semitone, true, center - whiteWidth * 0.3f, center + whiteWidth * 0.3f))
    }
    return list
}
