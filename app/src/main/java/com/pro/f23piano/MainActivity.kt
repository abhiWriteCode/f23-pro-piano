package com.pro.f23piano

import android.graphics.Paint
import android.graphics.Typeface
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

enum class Instrument(val label: String) {
    GRAND("Grand"),
    EPIANO("E-Piano"),
    SYNTH("Synth"),
    HARMONIUM("Harmonium")
}

enum class LoopState {
    IDLE, RECORDING, PLAYING
}

data class RecordedNote(
    val timeOffsetMs: Long,
    val noteOffset: Int,
    val octaveShift: Int,
    val instrument: Instrument,
    val sustainEnabled: Boolean,
    var holdDurationMs: Long = 400L
)

data class PianoKey(
    val noteOffset: Int,
    val isBlack: Boolean,
    val xStartRatio: Float,
    val xEndRatio: Float
)

class ActiveVoice(
    val noteId: Int,
    val sustainEnabled: Boolean
) {
    @Volatile var isReleased: Boolean = false
    @Volatile var isForceStopped: Boolean = false
}

class SynthEngine {
    private val sampleRate = 44100
    private val scope = CoroutineScope(Dispatchers.Default)
    private val heldVoices = ConcurrentHashMap<Int, ActiveVoice>()
    private val allRunningVoices = ConcurrentLinkedQueue<ActiveVoice>()

    fun startNote(
        noteId: Int,
        semitoneFromA4: Int,
        instrument: Instrument,
        sustainEnabled: Boolean
    ) {
        // Release any existing voice on this same key before starting a new one
        heldVoices.remove(noteId)?.let { oldVoice ->
            oldVoice.isReleased = true
        }

        val voice = ActiveVoice(noteId, sustainEnabled)
        heldVoices[noteId] = voice
        allRunningVoices.add(voice)

        // Prevent hardware AudioTrack exhaustion during rapid glissandos
        while (allRunningVoices.size > 12) {
            val oldest = allRunningVoices.poll()
            if (oldest != null && oldest !== voice) {
                oldest.isForceStopped = true
                heldVoices.remove(oldest.noteId, oldest)
            }
        }

        scope.launch {
            val freq = 440.0 * 2.0.pow(semitoneFromA4 / 12.0)
            val chunkSamples = 512
            val minBufBytes = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(chunkSamples * 4)

            val track = runCatching {
                AudioTrack.Builder()
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
                    .setBufferSizeInBytes(minBufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            }.getOrNull() ?: return@launch

            val buffer = ShortArray(chunkSamples)
            var sampleIndex = 0L
            var releaseSampleIndex = 0L

            // Fast damp when Sustain is OFF; smooth pedal decay when Sustain is ON
            val releaseRate = if (sustainEnabled) 2.8 else 22.0

            runCatching {
                track.play()

                while (!voice.isForceStopped) {
                    var finishedRelease = false

                    for (i in 0 until chunkSamples) {
                        val t = sampleIndex.toDouble() / sampleRate
                        sampleIndex++

                        val releaseEnv = if (voice.isReleased) {
                            val rt = releaseSampleIndex.toDouble() / sampleRate
                            releaseSampleIndex++
                            val env = exp(-releaseRate * rt)
                            if (env < 0.002) finishedRelease = true
                            env
                        } else {
                            1.0
                        }

                        val rawWave = when (instrument) {
                            Instrument.GRAND -> {
                                // Initial hammer punch settling into continuous string resonance while held
                                val attack = 1.0 - exp(-220.0 * t)
                                val hammerBoost = 0.72 + 0.28 * exp(-6.0 * t)
                                val warmChorus = 1.0 + 0.0012 * sin(2.0 * Math.PI * 4.5 * t)
                                val wave = 0.58 * sin(2.0 * Math.PI * freq * t) +
                                           0.24 * sin(4.0 * Math.PI * (freq * warmChorus) * t) +
                                           0.11 * sin(6.0 * Math.PI * freq * t) +
                                           0.05 * sin(8.0 * Math.PI * freq * t)
                                wave * attack * hammerBoost
                            }
                            Instrument.EPIANO -> {
                                // Metallic Rhodes tine attack + continuous warm body with tremolo
                                val attack = 1.0 - exp(-190.0 * t)
                                val tineEnv = exp(-20.0 * t)
                                val tremolo = 1.0 + 0.18 * sin(2.0 * Math.PI * 5.5 * t)
                                val body = 0.65 * sin(2.0 * Math.PI * freq * t) +
                                           0.22 * sin(4.0 * Math.PI * freq * t) +
                                           0.09 * sin(6.0 * Math.PI * freq * t)
                                val tine = 0.24 * sin(2.0 * Math.PI * freq * 7.0 * t) * tineEnv
                                (body * attack + tine) * tremolo
                            }
                            Instrument.SYNTH -> {
                                // Dual detuned sawtooth lead that sustains continuously at full power
                                val attack = 1.0 - exp(-100.0 * t)
                                val f1 = freq * 0.996
                                val f2 = freq * 1.004
                                var saw = 0.0
                                for (h in 1..6) {
                                    saw += (sin(2.0 * Math.PI * f1 * h * t) + sin(2.0 * Math.PI * f2 * h * t)) / h
                                }
                                saw * 0.34 * attack
                            }
                            Instrument.HARMONIUM -> {
                                // Authentic Indian Reed Harmonium: Bellows air attack + dual detuned brass reeds
                                val bellowsAttack = 1.0 - exp(-42.0 * t)
                                val fMale = freq * 0.9985
                                val fFemale = freq * 1.0015
                                val reedWave =
                                    0.42 * (sin(2.0 * Math.PI * fMale * t) + sin(2.0 * Math.PI * fFemale * t)) +
                                    0.28 * (sin(4.0 * Math.PI * fMale * t) + sin(4.0 * Math.PI * fFemale * t)) +
                                    0.18 * sin(6.0 * Math.PI * freq * t) +
                                    0.10 * sin(8.0 * Math.PI * freq * t) +
                                    0.06 * sin(10.0 * Math.PI * freq * t) +
                                    0.03 * sin(12.0 * Math.PI * freq * t)
                                reedWave * 0.52 * bellowsAttack
                            }
                        }

                        val finalSample = (rawWave * releaseEnv).coerceIn(-1.0, 1.0)
                        buffer[i] = (finalSample * Short.MAX_VALUE * 0.48).toInt().toShort()
                    }

                    track.write(buffer, 0, chunkSamples)
                    if (finishedRelease) break
                }
            }

            allRunningVoices.remove(voice)
            heldVoices.remove(noteId, voice)
            runCatching {
                track.stop()
                track.release()
            }
        }
    }

    fun stopNote(noteId: Int) {
        heldVoices.remove(noteId)?.let { voice ->
            voice.isReleased = true
        }
    }

    fun stopAllNotes() {
        heldVoices.keys.toList().forEach { stopNote(it) }
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

    override fun onStop() {
        super.onStop()
        synth.stopAllNotes()
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PianoAppScreen(synth: SynthEngine) {
    var octaveShift by remember { mutableStateOf(0) }
    var sustainEnabled by remember { mutableStateOf(false) } // Disabled (OFF) by default
    var showNoteNames by remember { mutableStateOf(true) }
    var currentInstrument by remember { mutableStateOf(Instrument.GRAND) }

    // Loop Recorder State
    var loopState by remember { mutableStateOf(LoopState.IDLE) }
    var recordStartTimeMs by remember { mutableStateOf(0L) }
    var loopDurationMs by remember { mutableStateOf(0L) }
    val recordedNotes = remember { mutableStateListOf<RecordedNote>() }
    val activeRecordingStarts = remember { mutableStateMapOf<Int, Pair<Long, RecordedNote>>() }
    val loopActiveKeys = remember { mutableStateListOf<Int>() }

    // Multi-touch pointer tracking map (pointerId -> noteOffset) for smooth glissando & hold
    val pointerToKey = remember { mutableStateMapOf<Int, Int>() }
    var canvasSize by remember { mutableStateOf(IntSize(2408, 1080)) }
    val keys = remember { buildTwoOctaveLayout() }

    val whiteKeyTextPaint = remember {
        Paint().apply {
            color = android.graphics.Color.rgb(45, 45, 45)
            textSize = 30f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
    }

    val blackKeyTextPaint = remember {
        Paint().apply {
            color = android.graphics.Color.rgb(220, 235, 255)
            textSize = 23f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
    }

    // Coroutine for continuous Loop Playback with recorded hold durations
    LaunchedEffect(loopState) {
        if (loopState == LoopState.PLAYING && recordedNotes.isNotEmpty() && loopDurationMs > 0L) {
            val snapshot = recordedNotes.toList().sortedBy { it.timeOffsetMs }
            var loopVoiceCounter = 10000
            while (isActive && loopState == LoopState.PLAYING) {
                var elapsedMs = 0L
                for (event in snapshot) {
                    if (loopState != LoopState.PLAYING) break
                    val waitTime = (event.timeOffsetMs - elapsedMs).coerceAtLeast(0L)
                    if (waitTime > 0L) delay(waitTime)
                    elapsedMs = event.timeOffsetMs

                    if (loopState == LoopState.PLAYING) {
                        val voiceId = loopVoiceCounter++
                        val holdMs = event.holdDurationMs.coerceIn(120L, 6000L)
                        synth.startNote(
                            noteId = voiceId,
                            semitoneFromA4 = event.noteOffset + (event.octaveShift * 12),
                            instrument = event.instrument,
                            sustainEnabled = event.sustainEnabled
                        )
                        launch {
                            loopActiveKeys.add(event.noteOffset)
                            delay(holdMs)
                            synth.stopNote(voiceId)
                            loopActiveKeys.remove(event.noteOffset)
                        }
                    }
                }
                val remaining = (loopDurationMs - elapsedMs).coerceAtLeast(50L)
                delay(remaining)
            }
        } else {
            loopActiveKeys.clear()
        }
    }

    fun pressNote(noteOffset: Int) {
        synth.startNote(
            noteId = noteOffset,
            semitoneFromA4 = noteOffset + (octaveShift * 12),
            instrument = currentInstrument,
            sustainEnabled = sustainEnabled
        )
        if (loopState == LoopState.RECORDING) {
            val now = System.currentTimeMillis()
            if (recordedNotes.isEmpty()) {
                recordStartTimeMs = now
            }
            val recNote = RecordedNote(
                timeOffsetMs = (now - recordStartTimeMs).coerceAtLeast(0L),
                noteOffset = noteOffset,
                octaveShift = octaveShift,
                instrument = currentInstrument,
                sustainEnabled = sustainEnabled,
                holdDurationMs = 400L
            )
            recordedNotes.add(recNote)
            activeRecordingStarts[noteOffset] = Pair(now, recNote)
        }
    }

    fun releaseNote(noteOffset: Int) {
        synth.stopNote(noteOffset)
        activeRecordingStarts.remove(noteOffset)?.let { (pressTime, recNote) ->
            recNote.holdDurationMs = (System.currentTimeMillis() - pressTime).coerceAtLeast(100L)
        }
    }

    val compactPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        // Top Studio Control Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .background(Color(0xFF1E1E1E))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Instrument Selector Buttons (Grand, E-Piano, Synth, Harmonium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Instrument.values().forEach { inst ->
                    val selected = currentInstrument == inst
                    Button(
                        onClick = { currentInstrument = inst },
                        contentPadding = compactPadding,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) Color(0xFF1976D2) else Color(0xFF333333)
                        ),
                        modifier = Modifier.padding(end = 4.dp).height(38.dp)
                    ) {
                        Text(inst.label, fontSize = 12.sp, color = Color.White)
                    }
                }
            }

            // 2. Octave, Note Names & Sustain Controls
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        if (octaveShift > -2) {
                            synth.stopAllNotes()
                            pointerToKey.clear()
                            octaveShift--
                        }
                    },
                    contentPadding = compactPadding,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                    modifier = Modifier.height(38.dp)
                ) { Text("-Oct", fontSize = 12.sp) }

                Text(
                    text = " ${if (octaveShift >= 0) "+$octaveShift" else octaveShift} ",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                Button(
                    onClick = {
                        if (octaveShift < 2) {
                            synth.stopAllNotes()
                            pointerToKey.clear()
                            octaveShift++
                        }
                    },
                    contentPadding = compactPadding,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                    modifier = Modifier.padding(end = 6.dp).height(38.dp)
                ) { Text("+Oct", fontSize = 12.sp) }

                // Single Enable/Disable Button for Note Names
                Button(
                    onClick = { showNoteNames = !showNoteNames },
                    contentPadding = compactPadding,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (showNoteNames) Color(0xFF00897B) else Color(0xFF424242)
                    ),
                    modifier = Modifier.padding(end = 6.dp).height(38.dp)
                ) {
                    Text(if (showNoteNames) "Notes: ON" else "Notes: OFF", fontSize = 12.sp)
                }

                // Sustain Toggle (OFF by default)
                Button(
                    onClick = { sustainEnabled = !sustainEnabled },
                    contentPadding = compactPadding,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (sustainEnabled) Color(0xFF5E35B1) else Color(0xFF424242)
                    ),
                    modifier = Modifier.height(38.dp)
                ) {
                    Text(if (sustainEnabled) "Sus: ON" else "Sus: OFF", fontSize = 12.sp)
                }
            }

            // 3. Built-in Loop Recorder Controls
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (loopState) {
                    LoopState.IDLE -> {
                        Button(
                            onClick = {
                                recordedNotes.clear()
                                activeRecordingStarts.clear()
                                recordStartTimeMs = System.currentTimeMillis()
                                loopState = LoopState.RECORDING
                            },
                            contentPadding = compactPadding,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                            modifier = Modifier.height(38.dp)
                        ) {
                            Text("● REC", fontSize = 12.sp, color = Color.White)
                        }
                        if (recordedNotes.isNotEmpty()) {
                            Button(
                                onClick = { loopState = LoopState.PLAYING },
                                contentPadding = compactPadding,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                modifier = Modifier.padding(start = 4.dp).height(38.dp)
                            ) {
                                Text("▶ LOOP", fontSize = 12.sp, color = Color.White)
                            }
                        }
                    }
                    LoopState.RECORDING -> {
                        Button(
                            onClick = {
                                val now = System.currentTimeMillis()
                                activeRecordingStarts.forEach { (_, pair) ->
                                    pair.second.holdDurationMs = (now - pair.first).coerceAtLeast(100L)
                                }
                                activeRecordingStarts.clear()

                                if (recordedNotes.isEmpty()) {
                                    loopState = LoopState.IDLE
                                } else {
                                    loopDurationMs = (now - recordStartTimeMs).coerceAtLeast(400L)
                                    loopState = LoopState.PLAYING
                                }
                            },
                            contentPadding = compactPadding,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF6C00)),
                            modifier = Modifier.height(38.dp)
                        ) {
                            Text("▶ START LOOP (${recordedNotes.size})", fontSize = 12.sp, color = Color.White)
                        }
                    }
                    LoopState.PLAYING -> {
                        Button(
                            onClick = { loopState = LoopState.IDLE },
                            contentPadding = compactPadding,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64)),
                            modifier = Modifier.height(38.dp)
                        ) {
                            Text("■ STOP LOOP", fontSize = 12.sp, color = Color.White)
                        }
                    }
                }
            }
        }

        // Multi-Touch + Continuous Hold + Glissando Piano Canvas
        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize = it }
                    .pointerInteropFilter { event ->
                        val w = canvasSize.width.toFloat().coerceAtLeast(1f)
                        val h = canvasSize.height.toFloat().coerceAtLeast(1f)

                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN,
                            MotionEvent.ACTION_POINTER_DOWN,
                            MotionEvent.ACTION_MOVE -> {
                                val currentPointerIds = mutableSetOf<Int>()
                                for (i in 0 until event.pointerCount) {
                                    val pid = event.getPointerId(i)
                                    currentPointerIds.add(pid)
                                    val x = event.getX(i)
                                    val y = event.getY(i)
                                    val hitKey = detectKeyHit(x, y, w, h, keys)
                                    val prevNote = pointerToKey[pid]

                                    if (hitKey != null) {
                                        if (prevNote != hitKey.noteOffset) {
                                            val newNote = hitKey.noteOffset
                                            val wasNewNoteAlreadyPressed = pointerToKey.containsValue(newNote)
                                            pointerToKey[pid] = newNote

                                            if (prevNote != null && !pointerToKey.containsValue(prevNote)) {
                                                releaseNote(prevNote)
                                            }
                                            if (!wasNewNoteAlreadyPressed) {
                                                pressNote(newNote)
                                            }
                                        }
                                    } else {
                                        pointerToKey.remove(pid)
                                        if (prevNote != null && !pointerToKey.containsValue(prevNote)) {
                                            releaseNote(prevNote)
                                        }
                                    }
                                }
                                // Clean up any stale pointers and release their notes
                                val stalePids = pointerToKey.keys.filter { it !in currentPointerIds }
                                stalePids.forEach { pid ->
                                    val oldNote = pointerToKey.remove(pid)
                                    if (oldNote != null && !pointerToKey.containsValue(oldNote)) {
                                        releaseNote(oldNote)
                                    }
                                }
                            }
                            MotionEvent.ACTION_POINTER_UP -> {
                                val liftedPid = event.getPointerId(event.actionIndex)
                                val oldNote = pointerToKey.remove(liftedPid)
                                if (oldNote != null && !pointerToKey.containsValue(oldNote)) {
                                    releaseNote(oldNote)
                                }
                            }
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL -> {
                                val activeNotes = pointerToKey.values.toSet()
                                pointerToKey.clear()
                                activeNotes.forEach { releaseNote(it) }
                            }
                        }
                        true
                    }
            ) {
                val w = size.width
                val h = size.height
                val pressedNotes = pointerToKey.values.toSet()
                val loopingNotes = loopActiveKeys.toSet()

                // 1. Draw White Keys
                keys.filter { !it.isBlack }.forEach { key ->
                    val isFingerPressed = key.noteOffset in pressedNotes
                    val isLoopPressed = key.noteOffset in loopingNotes
                    val keyColor = when {
                        isFingerPressed -> Color(0xFF64B5F6)
                        isLoopPressed -> Color(0xFF81C784)
                        else -> Color(0xFFFDFDFD)
                    }

                    val left = key.xStartRatio * w + 2f
                    val keyWidth = (key.xEndRatio - key.xStartRatio) * w - 4f
                    val keyHeight = h - 6f

                    drawRoundRect(
                        color = keyColor,
                        topLeft = Offset(left, 0f),
                        size = Size(keyWidth, keyHeight),
                        cornerRadius = CornerRadius(10f, 10f)
                    )

                    if (showNoteNames) {
                        val label = formatNoteName(key.noteOffset, octaveShift)
                        drawContext.canvas.nativeCanvas.drawText(
                            label,
                            left + keyWidth / 2f,
                            keyHeight - 34f,
                            whiteKeyTextPaint
                        )
                    }
                }

                // 2. Draw Black Keys
                keys.filter { it.isBlack }.forEach { key ->
                    val isFingerPressed = key.noteOffset in pressedNotes
                    val isLoopPressed = key.noteOffset in loopingNotes
                    val keyColor = when {
                        isFingerPressed -> Color(0xFF1E88E5)
                        isLoopPressed -> Color(0xFF2E7D32)
                        else -> Color(0xFF141414)
                    }

                    val left = key.xStartRatio * w
                    val keyWidth = (key.xEndRatio - key.xStartRatio) * w
                    val keyHeight = h * 0.60f

                    drawRoundRect(
                        color = keyColor,
                        topLeft = Offset(left, 0f),
                        size = Size(keyWidth, keyHeight),
                        cornerRadius = CornerRadius(8f, 8f)
                    )

                    if (showNoteNames) {
                        val label = formatNoteName(key.noteOffset, octaveShift)
                        drawContext.canvas.nativeCanvas.drawText(
                            label,
                            left + keyWidth / 2f,
                            keyHeight - 24f,
                            blackKeyTextPaint
                        )
                    }
                }
            }
        }
    }
}

fun formatNoteName(noteOffset: Int, octaveShift: Int): String {
    val midiNote = 69 + noteOffset + (octaveShift * 12)
    val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val name = names[((midiNote % 12) + 12) % 12]
    val octave = (midiNote / 12) - 1
    return "$name$octave"
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
