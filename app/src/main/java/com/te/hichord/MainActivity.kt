package com.te.hichord

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.midi.MidiDevice
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*
import kotlin.random.Random

object TEColors {
    val ChassisGrey = Color(0xFFD6D4CE)
    val DarkModule = Color(0xFF18181A)
    val OledBlack = Color(0xFF0A0A0C)
    val OledGrid = Color(0xFF1F2229)
    val Orange = Color(0xFFFF5500)
    val Cyan = Color(0xFF00E5FF)
    val Ochre = Color(0xFFFFB300)
    val RedLed = Color(0xFFFF2A2A)
    val KeyWhite = Color(0xFFECEAE4)
    val KeyDark = Color(0xFF2B2B2E)
    val KeyMid = Color(0xFF4A4A4F)
    val InkDark = Color(0xFF141416)
    val InkLight = Color(0xFFF0EFEA)
    val MutedText = Color(0xFF7C7C82)
}

enum class ScaleMode(val label: String, val intervals: List<Int>) {
    MAJOR("IONIAN / MAJ", listOf(0, 2, 4, 5, 7, 9, 11)),
    MINOR("AEOLIAN / MIN", listOf(0, 2, 3, 5, 7, 8, 10)),
    DORIAN("DORIAN", listOf(0, 2, 3, 5, 7, 9, 10)),
    PHRYGIAN("PHRYGIAN", listOf(0, 1, 3, 5, 7, 8, 10)),
    LYDIAN("LYDIAN", listOf(0, 2, 4, 6, 7, 9, 11)),
    MIXOLYDIAN("MIXOLYDIAN", listOf(0, 2, 4, 5, 7, 9, 10))
}

enum class Waveform(val label: String) {
    SAW("SAW"),
    SQUARE("SQR"),
    SUPER_SAW("SSAW"),
    TRIANGLE("TRI"),
    FM_PAD("FM")
}

enum class ArpMode(val label: String) {
    OFF("POLY"),
    UP("ARP UP"),
    DOWN("ARP DN"),
    UP_DOWN("ARP U/D"),
    RANDOM("ARP RND")
}

enum class ChordModifier(val code: String, val label: String) {
    SEVENTH("M1", "7TH"),
    NINTH("M2", "9TH"),
    SUS2("M3", "SUS2"),
    SUS4("M4", "SUS4"),
    INV1("M5", "INV 1"),
    INV2("M6", "INV 2"),
    BORROW("M7", "BORROW"),
    BASS("M8", "SUB BASS")
}

val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
val ROMAN_DEGREES = listOf("I", "II", "III", "IV", "V", "VI", "VII")

class PolySynthEngine {
    private val sampleRate = 44100
    private var isRunning = false
    private var audioJob: Job? = null

    private class Voice(
        val note: Int,
        var phase: Double = 0.0,
        var phase2: Double = 0.25,
        var envLevel: Float = 0f,
        var releasing: Boolean = false
    )

    private val activeVoices = ConcurrentHashMap<Int, Voice>()

    @Volatile var waveform: Waveform = Waveform.SUPER_SAW
    @Volatile var cutoffNormalized: Float = 0.65f
    @Volatile var detuneAmount: Float = 0.35f
    @Volatile var attackSpeed: Float = 0.008f
    @Volatile var releaseSpeed: Float = 0.0015f

    private var lpfState1 = 0f
    private var lpfState2 = 0f

    fun start(scope: CoroutineScope) {
        if (isRunning) return
        isRunning = true

        audioJob = scope.launch(Dispatchers.Default) {
            val minBufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = max(minBufSize, 1024)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val buffer = ShortArray(256)
            track.play()

            while (isActive && isRunning) {
                val cutoffFreq = 120f + (cutoffNormalized.pow(2) * 7500f)
                val rc = 1.0f / (2.0f * PI.toFloat() * cutoffFreq)
                val dt = 1.0f / sampleRate
                val alpha = (dt / (rc + dt)).coerceIn(0.02f, 0.99f)

                for (i in buffer.indices) {
                    var mix = 0f
                    val iterator = activeVoices.entries.iterator()

                    while (iterator.hasNext()) {
                        val (_, voice) = iterator.next()

                        if (!voice.releasing) {
                            voice.envLevel = (voice.envLevel + attackSpeed).coerceAtMost(1f)
                        } else {
                            voice.envLevel -= releaseSpeed
                            if (voice.envLevel <= 0.001f) {
                                iterator.remove()
                                continue
                            }
                        }

                        val freq = 440.0 * 2.0.pow((voice.note - 69.0) / 12.0)
                        val inc1 = freq / sampleRate
                        val detuneHz = 1.0 + (detuneAmount * 0.012)
                        val inc2 = (freq * detuneHz) / sampleRate

                        voice.phase = (voice.phase + inc1) % 1.0
                        voice.phase2 = (voice.phase2 + inc2) % 1.0

                        val p1 = voice.phase.toFloat()
                        val p2 = voice.phase2.toFloat()

                        val rawSample = when (waveform) {
                            Waveform.SAW -> (2f * p1 - 1f)
                            Waveform.SQUARE -> if (p1 < 0.5f) 0.8f else -0.8f
                            Waveform.SUPER_SAW -> ((2f * p1 - 1f) + (2f * p2 - 1f)) * 0.6f
                            Waveform.TRIANGLE -> 1f - 4f * abs(round(p1 - 0.25f) - (p1 - 0.25f))
                            Waveform.FM_PAD -> {
                                val mod = sin(2.0 * PI * p2 * 2.0).toFloat() * (0.5f + detuneAmount)
                                sin(2.0 * PI * p1 + mod).toFloat()
                            }
                        }

                        mix += rawSample * voice.envLevel
                    }

                    val attenuated = mix * 0.22f
                    lpfState1 += alpha * (attenuated - lpfState1)
                    lpfState2 += alpha * (lpfState1 - lpfState2)

                    val saturated = tanh(lpfState2 * 1.4f)
                    buffer[i] = (saturated * Short.MAX_VALUE * 0.85f).toInt().toShort()
                }
                track.write(buffer, 0, buffer.size)
            }

            track.stop()
            track.release()
        }
    }

    fun setActiveNotes(newNotes: Set<Int>) {
        for ((note, voice) in activeVoices) {
            if (note !in newNotes) {
                voice.releasing = true
            }
        }
        for (note in newNotes) {
            val existing = activeVoices[note]
            if (existing == null) {
                activeVoices[note] = Voice(note = note)
            } else {
                existing.releasing = false
            }
        }
    }

    fun triggerSingleNote(note: Int) {
        activeVoices[note] = Voice(note = note, envLevel = 0.2f, releasing = false)
    }

    fun releaseSingleNote(note: Int) {
        activeVoices[note]?.releasing = true
    }

    fun stop() {
        isRunning = false
        audioJob?.cancel()
    }
}

class MidiController(context: Context) {
    private val midiManager = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private var inputPort: MidiInputPort? = null
    private var activeMidiNotes = mutableSetOf<Int>()

    init {
        midiManager?.devices?.firstOrNull()?.let { deviceInfo ->
            midiManager.openDevice(deviceInfo, { device: MidiDevice? ->
                inputPort = device?.openInputPort(0)
            }, Handler(Looper.getMainLooper()))
        }
    }

    fun updateMidiNotes(newNotes: Set<Int>) {
        val port = inputPort ?: return
        val toStop = activeMidiNotes - newNotes
        val toStart = newNotes - activeMidiNotes

        val buffer = ByteArray(3)
        for (note in toStop) {
            buffer[0] = 0x80.toByte()
            buffer[1] = (note and 0x7F).toByte()
            buffer[2] = 0x00.toByte()
            runCatching { port.send(buffer, 0, 3) }
        }
        for (note in toStart) {
            buffer[0] = 0x90.toByte()
            buffer[1] = (note and 0x7F).toByte()
            buffer[2] = 100.toByte()
            runCatching { port.send(buffer, 0, 3) }
        }
        activeMidiNotes = newNotes.toMutableSet()
    }
}

class HiChordViewModel : ViewModel() {
    val synth = PolySynthEngine()
    var midiController: MidiController? = null

    private val _rootKey = MutableStateFlow(0)
    val rootKey: StateFlow<Int> = _rootKey.asStateFlow()

    private val _scaleMode = MutableStateFlow(ScaleMode.MAJOR)
    val scaleMode: StateFlow<ScaleMode> = _scaleMode.asStateFlow()

    private val _octave = MutableStateFlow(4)
    val octave: StateFlow<Int> = _octave.asStateFlow()

    private val _bpm = MutableStateFlow(118)
    val bpm: StateFlow<Int> = _bpm.asStateFlow()

    private val _arpMode = MutableStateFlow(ArpMode.OFF)
    val arpMode: StateFlow<ArpMode> = _arpMode.asStateFlow()

    private val _waveform = MutableStateFlow(Waveform.SUPER_SAW)
    val waveform: StateFlow<Waveform> = _waveform.asStateFlow()

    private val _activeDegree = MutableStateFlow<Int?>(null)
    val activeDegree: StateFlow<Int?> = _activeDegree.asStateFlow()

    private val _activeModifiers = MutableStateFlow<Set<ChordModifier>>(emptySet())
    val activeModifiers: StateFlow<Set<ChordModifier>> = _activeModifiers.asStateFlow()

    private val _currentMidiNotes = MutableStateFlow<List<Int>>(emptyList())
    val currentMidiNotes: StateFlow<List<Int>> = _currentMidiNotes.asStateFlow()

    private val _currentChordName = MutableStateFlow("READY")
    val currentChordName: StateFlow<String> = _currentChordName.asStateFlow()

    private val _xyFilter = MutableStateFlow(Offset(0.65f, 0.35f))
    val xyFilter: StateFlow<Offset> = _xyFilter.asStateFlow()

    private var arpJob: Job? = null
    private var arpStepIndex = 0

    init {
        synth.start(viewModelScope)
        startArpLoop()
    }

    private fun computeChord(degreeIndex: Int): Pair<String, List<Int>> {
        val key = _rootKey.value
        val mods = _activeModifiers.value

        val activeScale = if (ChordModifier.BORROW in mods) {
            if (_scaleMode.value == ScaleMode.MAJOR) ScaleMode.MINOR else ScaleMode.MAJOR
        } else {
            _scaleMode.value
        }

        val scaleIntervals = activeScale.intervals
        val baseMidi = (_octave.value + 1) * 12 + key

        fun scaleNote(degreeOffset: Int): Int {
            val totalIndex = degreeIndex + degreeOffset
            val octShift = totalIndex / 7
            val stepInScale = totalIndex % 7
            return baseMidi + scaleIntervals[stepInScale] + (octShift * 12)
        }

        val rootNote = scaleNote(0)
        var thirdNote = scaleNote(2)
        val fifthNote = scaleNote(4)

        if (ChordModifier.SUS2 in mods) {
            thirdNote = scaleNote(1)
        } else if (ChordModifier.SUS4 in mods) {
            thirdNote = scaleNote(3)
        }

        val notes = mutableListOf(rootNote, thirdNote, fifthNote)

        if (ChordModifier.SEVENTH in mods) {
            notes.add(scaleNote(6))
        }
        if (ChordModifier.NINTH in mods) {
            if (ChordModifier.SEVENTH !in mods) notes.add(scaleNote(6))
            notes.add(scaleNote(8))
        }

        val invCount = when {
            ChordModifier.INV2 in mods -> 2
            ChordModifier.INV1 in mods -> 1
            else -> 0
        }
        repeat(invCount) {
            if (notes.isNotEmpty()) {
                val lowest = notes.removeAt(0)
                notes.add(lowest + 12)
            }
        }

        if (ChordModifier.BASS in mods) {
            notes.add(0, rootNote - 12)
        }

        val rootName = NOTE_NAMES[rootNote % 12]
        val thirdInterval = (scaleNote(2) - rootNote)
        val fifthInterval = (fifthNote - rootNote)

        val quality = when {
            ChordModifier.SUS2 in mods -> "sus2"
            ChordModifier.SUS4 in mods -> "sus4"
            thirdInterval == 3 && fifthInterval == 6 -> "dim"
            thirdInterval == 3 -> "m"
            else -> ""
        }
        val ext = buildString {
            if (ChordModifier.NINTH in mods) append("9")
            else if (ChordModifier.SEVENTH in mods) {
                val seventhInt = scaleNote(6) - rootNote
                if (seventhInt == 11 && quality != "m") append("maj7") else append("7")
            }
            if (invCount > 0) append("/INV$invCount")
        }

        return "$rootName$quality$ext" to notes.sorted()
    }

    fun pressDegree(degree: Int) {
        _activeDegree.value = degree
        recalculateAndTrigger()
    }

    fun releaseDegree(degree: Int) {
        if (_activeDegree.value == degree) {
            _activeDegree.value = null
            recalculateAndTrigger()
        }
    }

    fun toggleModifier(mod: ChordModifier) {
        val current = _activeModifiers.value.toMutableSet()
        if (mod in current) current.remove(mod) else current.add(mod)
        _activeModifiers.value = current
        recalculateAndTrigger()
    }

    private fun recalculateAndTrigger() {
        val deg = _activeDegree.value
        if (deg == null) {
            _currentMidiNotes.value = emptyList()
            _currentChordName.value = "STANDBY"
            synth.setActiveNotes(emptySet())
            midiController?.updateMidiNotes(emptySet())
            return
        }

        val (chordName, notes) = computeChord(deg)
        _currentChordName.value = chordName
        _currentMidiNotes.value = notes

        if (_arpMode.value == ArpMode.OFF) {
            synth.setActiveNotes(notes.toSet())
            midiController?.updateMidiNotes(notes.toSet())
        }
    }

    fun strumAtPosition(normalizedPosition: Float) {
        val baseNotes = _currentMidiNotes.value
        if (baseNotes.isEmpty()) return

        val strumPool = (baseNotes + baseNotes.map { it + 12 }).sorted()
        val index = (normalizedPosition * strumPool.size).toInt().coerceIn(0, strumPool.lastIndex)
        val noteToTrigger = strumPool[index]

        viewModelScope.launch {
            synth.triggerSingleNote(noteToTrigger)
            delay(220)
            synth.releaseSingleNote(noteToTrigger)
        }
    }

    private fun startArpLoop() {
        arpJob?.cancel()
        arpJob = viewModelScope.launch {
            while (isActive) {
                val mode = _arpMode.value
                val notes = _currentMidiNotes.value

                if (mode != ArpMode.OFF && notes.isNotEmpty()) {
                    val stepDurationMs = (60_000L / _bpm.value) / 2
                    val noteToPlay = when (mode) {
                        ArpMode.UP -> notes[arpStepIndex % notes.size]
                        ArpMode.DOWN -> notes.reversed()[arpStepIndex % notes.size]
                        ArpMode.UP_DOWN -> {
                            val cycle = if (notes.size > 2) notes + notes.drop(1).dropLast(1).reversed() else notes
                            cycle[arpStepIndex % cycle.size]
                        }
                        ArpMode.RANDOM -> notes[Random.nextInt(notes.size)]
                        else -> notes.first()
                    }
                    arpStepIndex++

                    synth.setActiveNotes(setOf(noteToPlay))
                    midiController?.updateMidiNotes(setOf(noteToPlay))
                    delay((stepDurationMs * 0.65).toLong())
                    synth.setActiveNotes(emptySet())
                    delay((stepDurationMs * 0.35).toLong())
                } else {
                    arpStepIndex = 0
                    delay(40)
                }
            }
        }
    }

    fun cycleKey(delta: Int) {
        _rootKey.value = (_rootKey.value + delta + 12) % 12
        recalculateAndTrigger()
    }

    fun cycleScale() {
        val all = ScaleMode.entries
        _scaleMode.value = all[(_scaleMode.value.ordinal + 1) % all.size]
        recalculateAndTrigger()
    }

    fun cycleWaveform() {
        val all = Waveform.entries
        val next = all[(_waveform.value.ordinal + 1) % all.size]
        _waveform.value = next
        synth.waveform = next
    }

    fun cycleArpMode() {
        val all = ArpMode.entries
        _arpMode.value = all[(_arpMode.value.ordinal + 1) % all.size]
        recalculateAndTrigger()
    }

    fun changeBpm(delta: Int) {
        _bpm.value = (_bpm.value + delta).coerceIn(60, 200)
    }

    fun updateXY(x: Float, y: Float) {
        val clampedX = x.coerceIn(0.05f, 1f)
        val clampedY = y.coerceIn(0f, 1f)
        _xyFilter.value = Offset(clampedX, clampedY)
        synth.cutoffNormalized = clampedX
        synth.detuneAmount = clampedY
    }

    override fun onCleared() {
        super.onCleared()
        synth.stop()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = TEColors.ChassisGrey) {
                val vm: HiChordViewModel = viewModel()
                LaunchedEffect(Unit) {
                    vm.midiController = MidiController(this@MainActivity)
                }
                HiChordHardwareScreen(vm)
            }
        }
    }
}

@Composable
fun HiChordHardwareScreen(vm: HiChordViewModel) {
    val rootKey by vm.rootKey.collectAsState()
    val scaleMode by vm.scaleMode.collectAsState()
    val waveform by vm.waveform.collectAsState()
    val arpMode by vm.arpMode.collectAsState()
    val bpm by vm.bpm.collectAsState()
    val activeDegree by vm.activeDegree.collectAsState()
    val activeModifiers by vm.activeModifiers.collectAsState()
    val currentChordName by vm.currentChordName.collectAsState()
    val currentMidiNotes by vm.currentMidiNotes.collectAsState()
    val xyFilter by vm.xyFilter.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TEColors.ChassisGrey)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(TEColors.Orange)
                )
                Text(
                    text = "TP-CHORD // FIELD SYNTHESIZER",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = TEColors.InkDark
                )
            }
            Text(
                text = "REV 1.4 [WOLFPUNK-CORE]",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = TEColors.MutedText
            )
        }

        TEOledDisplay(
            chordName = currentChordName,
            keyName = NOTE_NAMES[rootKey],
            scaleName = scaleMode.label,
            waveform = waveform,
            arpMode = arpMode,
            bpm = bpm,
            activeNotes = currentMidiNotes,
            cutoff = xyFilter.x
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TEParameterKnob(
                modifier = Modifier.weight(1f),
                title = "KEY",
                value = NOTE_NAMES[rootKey],
                accentColor = TEColors.Orange,
                onClick = { vm.cycleKey(1) }
            )
            TEParameterKnob(
                modifier = Modifier.weight(1f),
                title = "SCALE",
                value = scaleMode.name.take(5),
                accentColor = TEColors.Cyan,
                onClick = { vm.cycleScale() }
            )
            TEParameterKnob(
                modifier = Modifier.weight(1f),
                title = "SYNTH",
                value = waveform.label,
                accentColor = TEColors.Ochre,
                onClick = { vm.cycleWaveform() }
            )
            TEParameterKnob(
                modifier = Modifier.weight(1f),
                title = "MODE",
                value = arpMode.label,
                accentColor = TEColors.KeyWhite,
                onClick = { vm.cycleArpMode() }
            )
            TEParameterKnob(
                modifier = Modifier.weight(1f),
                title = "TEMPO",
                value = "$bpm",
                accentColor = TEColors.Orange,
                onClick = { vm.changeBpm(5) }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(TEColors.OledBlack)
                    .border(1.5.dp, TEColors.KeyDark, RoundedCornerShape(6.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            val x = change.position.x / size.width
                            val y = 1f - (change.position.y / size.height)
                            vm.updateXY(x, y)
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = xyFilter.x * size.width
                    val cy = (1f - xyFilter.y) * size.height
                    drawLine(TEColors.OledGrid, Offset(cx, 0f), Offset(cx, size.height), 2f)
                    drawLine(TEColors.OledGrid, Offset(0f, cy), Offset(size.width, cy), 2f)
                    drawCircle(TEColors.Orange, radius = 6.dp.toPx(), center = Offset(cx, cy))
                    drawCircle(
                        TEColors.Orange.copy(alpha = 0.35f),
                        radius = 14.dp.toPx(),
                        center = Offset(cx, cy),
                        style = Stroke(width = 2f)
                    )
                }
                Text(
                    text = "XY // LPF + DETUNE",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = TEColors.MutedText,
                    modifier = Modifier.padding(6.dp)
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(TEColors.DarkModule)
                    .border(1.5.dp, TEColors.KeyDark, RoundedCornerShape(6.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            val normX = (change.position.x / size.width).coerceIn(0f, 1f)
                            vm.strumAtPosition(normX)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val normX = (offset.x / size.width).coerceIn(0f, 1f)
                            vm.strumAtPosition(normX)
                        }
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(12) { idx ->
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(
                                    if (idx % 3 == 0) TEColors.Cyan.copy(alpha = 0.7f)
                                    else TEColors.KeyMid
                                )
                        )
                    }
                }
                Text(
                    text = "STRUM SENSOR // 2 OCT",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = TEColors.Cyan,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                )
            }
        }

        Text(
            text = "CHORD MODIFIERS // VOICING & EXTENSIONS",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = TEColors.InkDark
        )

        val modifiersList = ChordModifier.entries
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in 0 until 2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (col in 0 until 4) {
                        val mod = modifiersList[row * 4 + col]
                        val isSelected = mod in activeModifiers
                        TEModifierButton(
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp),
                            code = mod.code,
                            label = mod.label,
                            isActive = isSelected,
                            onClick = { vm.toggleModifier(mod) }
                        )
                    }
                }
            }
        }

        Text(
            text = "DIATONIC DEGREE MATRIX // HOLD TO PLAY",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = TEColors.InkDark
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (deg in 0..3) {
                    TEDegreePad(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        degreeIndex = deg,
                        roman = ROMAN_DEGREES[deg],
                        isPressed = activeDegree == deg,
                        onPress = { vm.pressDegree(deg) },
                        onRelease = { vm.releaseDegree(deg) }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (deg in 4..6) {
                    TEDegreePad(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        degreeIndex = deg,
                        roman = ROMAN_DEGREES[deg],
                        isPressed = activeDegree == deg,
                        onPress = { vm.pressDegree(deg) },
                        onRelease = { vm.releaseDegree(deg) }
                    )
                }
            }
        }
    }
}

@Composable
fun TEOledDisplay(
    chordName: String,
    keyName: String,
    scaleName: String,
    waveform: Waveform,
    arpMode: ArpMode,
    bpm: Int,
    activeNotes: List<Int>,
    cutoff: Float
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(128.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(TEColors.OledBlack)
            .border(2.dp, TEColors.DarkModule, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val midY = size.height * 0.68f
            val path = Path()
            path.moveTo(0f, midY)
            val freq = if (activeNotes.isNotEmpty()) 6f else 1.5f
            val amp = if (activeNotes.isNotEmpty()) 22f * cutoff else 3f
            val steps = 80
            for (i in 0..steps) {
                val x = (i.toFloat() / steps) * size.width
                val y = midY + sin(i * 0.35f * freq) * amp
                path.lineTo(x, y)
            }
            drawPath(path, color = TEColors.Cyan.copy(alpha = 0.45f), style = Stroke(width = 2.5f))
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "KEY:$keyName [$scaleName]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TEColors.Cyan
                )
                Text(
                    text = "${waveform.label} // ${arpMode.label} // ${bpm}BPM",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TEColors.Ochre
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = chordName,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 36.sp,
                    color = TEColors.InkLight
                )

                val notesFormatted = if (activeNotes.isEmpty()) {
                    "MIDI: ---"
                } else {
                    "NOTES: " + activeNotes.joinToString(" ") { "${NOTE_NAMES[it % 12]}${it / 12 - 1}" }
                }
                Text(
                    text = notesFormatted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TEColors.Orange
                )
            }
        }
    }
}

@Composable
fun TEParameterKnob(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(TEColors.DarkModule)
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
            Text(
                text = title,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = TEColors.MutedText
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = TEColors.InkLight
        )
    }
}

@Composable
fun TEModifierButton(
    modifier: Modifier = Modifier,
    code: String,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isActive) TEColors.Orange else TEColors.KeyDark)
            .border(
                width = 1.5.dp,
                color = if (isActive) TEColors.InkDark else TEColors.KeyMid,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .align(Alignment.TopEnd)
                .clip(CircleShape)
                .background(if (isActive) TEColors.InkLight else TEColors.MutedText.copy(alpha = 0.4f))
        )
        Text(
            text = code,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = if (isActive) TEColors.InkDark else TEColors.MutedText,
            modifier = Modifier.align(Alignment.TopStart)
        )
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = TEColors.InkLight,
            modifier = Modifier.align(Alignment.BottomStart)
        )
    }
}

@Composable
fun TEDegreePad(
    modifier: Modifier = Modifier,
    degreeIndex: Int,
    roman: String,
    isPressed: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isPressed) TEColors.Orange else TEColors.KeyWhite)
            .border(
                width = 2.dp,
                color = TEColors.InkDark,
                shape = RoundedCornerShape(8.dp)
            )
            .pointerInput(degreeIndex) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        tryAwaitRelease()
                        onRelease()
                    }
                )
            }
            .padding(10.dp)
    ) {
        Text(
            text = "0${degreeIndex + 1}",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = if (isPressed) TEColors.InkLight else TEColors.MutedText,
            modifier = Modifier.align(Alignment.TopStart)
        )

        Box(
            modifier = Modifier
                .size(8.dp)
                .align(Alignment.TopEnd)
                .clip(CircleShape)
                .background(if (isPressed) TEColors.Cyan else TEColors.ChassisGrey)
        )

        Text(
            text = roman,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 26.sp,
            color = if (isPressed) TEColors.InkLight else TEColors.InkDark,
            modifier = Modifier.align(Alignment.BottomStart)
        )
    }
}
