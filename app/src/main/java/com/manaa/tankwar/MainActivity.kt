package com.manaa.tankwar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import androidx.compose.foundation.background

// Machine d’états AU NIVEAU FICHIER
enum class MatchState { WAITING, COUNTDOWN, PLAYING }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(color = Color(0xFF101015)) {
                    GameScreen()
                }
            }
        }
    }
}

@Composable
fun GameScreen() {
    // Monde
    var worldW by remember { mutableFloatStateOf(0f) }
    var worldH by remember { mutableFloatStateOf(0f) }

    // Mon tank
    var tankX by remember { mutableFloatStateOf(0f) }
    var tankY by remember { mutableFloatStateOf(0f) }
    var tankAngle by remember { mutableFloatStateOf(0f) }

    // Autres joueurs
    val others = remember { mutableStateMapOf<String, NetPlayerState>() }
    var myId by remember { mutableStateOf("") }
    var slots by remember { mutableStateOf(RoomSlots()) }
    var roomError by remember { mutableStateOf<String?>(null) }

    // Joystick brut
    var joyDxRaw by remember { mutableFloatStateOf(0f) }
    var joyDyRaw by remember { mutableFloatStateOf(0f) }

    val tankSpeed = 220f
    val spriteSize = 160
    val half = spriteSize / 2f

    val background: ImageBitmap = ImageBitmap.imageResource(R.drawable.battlefield)
    val tankSprite: ImageBitmap = ImageBitmap.imageResource(R.drawable.tank)

    // Réseau
    val online = remember {
        Online(
            roomId = "room-1",
            databaseUrl = "https://tank-74afa-default-rtdb.europe-west1.firebasedatabase.app"
        )
    }

    // Match state
    var matchState by remember { mutableStateOf(MatchState.WAITING) }
    var countdown by remember { mutableIntStateOf(3) }

    // Connexion + listeners
    LaunchedEffect(Unit) {
        try {
            myId = online.connect(name = "Player")
            // slots p1/p2
            online.slotsLiveRef().addValueEventListener(object :
                com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    val p1 = snapshot.child("p1").getValue(String::class.java)
                    val p2 = snapshot.child("p2").getValue(String::class.java)
                    slots = RoomSlots(p1, p2)
                }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
            })
            // states joueurs (filtrés par slots)
            online.playersRef().addValueEventListener(object :
                com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    val valid = setOfNotNull(slots.p1, slots.p2)
                    others.clear()
                    for (c in snapshot.children) {
                        val id = c.key ?: continue
                        if (id == myId || id !in valid) continue
                        c.getValue(NetPlayerState::class.java)?.let { others[id] = it }
                    }
                }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
            })
        } catch (e: IllegalStateException) {
            roomError = when (e.message) {
                "ROOM_FULL" -> "Room pleine (2 joueurs)."
                else -> "Erreur: ${e.message}"
            }
        }
    }

    // WAITING -> COUNTDOWN -> PLAYING
    LaunchedEffect(slots.p1, slots.p2) {
        val twoPlayers = slots.p1 != null && slots.p2 != null
        if (twoPlayers && matchState == MatchState.WAITING) {
            matchState = MatchState.COUNTDOWN
            countdown = 3
            repeat(3) {
                kotlinx.coroutines.delay(1000)
                countdown -= 1
            }
            countdown = 0
            kotlinx.coroutines.delay(600)
            matchState = MatchState.PLAYING
        }
        if (!twoPlayers && matchState == MatchState.PLAYING) {
            matchState = MatchState.WAITING
        }
    }

    // Boucle frame locale + envoi réseau
    LaunchedEffect(worldW, worldH, myId) {
        if (worldW <= 0f || worldH <= 0f || myId.isEmpty()) return@LaunchedEffect
        if (tankX == 0f && tankY == 0f) { tankX = worldW/2f; tankY = worldH/2f }

        var last = withFrameNanos { it }
        var lastSend = 0L
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000.0f)
            last = now

            val joyDx = if (matchState == MatchState.PLAYING) joyDxRaw else 0f
            val joyDy = if (matchState == MatchState.PLAYING) joyDyRaw else 0f

            tankX = (tankX + joyDx * tankSpeed * dt).coerceIn(half, worldW - half)
            tankY = (tankY + joyDy * tankSpeed * dt).coerceIn(half, worldH - half)
            if (joyDx != 0f || joyDy != 0f) {
                tankAngle = Math.toDegrees(atan2(joyDy.toDouble(), joyDx.toDouble())).toFloat()
            }

            val ms = System.currentTimeMillis()
            if (ms - lastSend > 66 && myId.isNotEmpty()) {
                online.updateMyState(tankX, tankY, tankAngle)
                lastSend = ms
            }
        }
    }

    DisposableEffect(Unit) { onDispose { online.disconnect() } }

    // Rendu
    Box(Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { sz ->
                    worldW = sz.width.toFloat()
                    worldH = sz.height.toFloat()
                }
        ) {
            // fond
            drawImage(background, dstSize = IntSize(size.width.toInt(), size.height.toInt()))

            // autres
            others.values.forEach { st ->
                withTransform({
                    translate(st.x, st.y)
                    rotate(st.angle, pivot = Offset.Zero)
                }) {
                    drawImage(
                        image = tankSprite,
                        dstSize = IntSize(spriteSize, spriteSize),
                        dstOffset = IntOffset(-spriteSize/2, -spriteSize/2)
                    )
                }
            }

            // moi
            withTransform({
                translate(tankX, tankY)
                rotate(tankAngle, pivot = Offset.Zero)
            }) {
                drawImage(
                    image = tankSprite,
                    dstSize = IntSize(spriteSize, spriteSize),
                    dstOffset = IntOffset(-spriteSize/2, -spriteSize/2)
                )
            }
        }

        // Joystick (capture l'entrée brute)
        Box(Modifier.align(Alignment.BottomStart).padding(24.dp)) {
            Joystick(diameter = 160.dp, knobRadius = 28.dp) { dx, dy ->
                joyDxRaw = dx; joyDyRaw = dy
            }
        }

        // Overlays
        when {
            roomError != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(roomError!!, color = Color.White, fontSize = 22.sp)
                }
            }
            matchState == MatchState.WAITING -> {
                Box(
                    Modifier.fillMaxSize().background(Color(0x88000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("En attente d’un second joueur…", color = Color.White, fontSize = 22.sp)
                }
            }
            matchState == MatchState.COUNTDOWN -> {
                Box(
                    Modifier.fillMaxSize().background(Color(0x88000000)),
                    contentAlignment = Alignment.Center
                ) {
                    val label = if (countdown > 0) countdown.toString() else "GO!"
                    Text(label, color = Color.White, fontSize = 64.sp)
                }
            }
        }

        // HUD
        Text(
            text = "room-1   p1=${slots.p1?.take(5) ?: "-"}  p2=${slots.p2?.take(5) ?: "-"}",
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            color = Color.White, fontSize = 14.sp
        )
    }
}

@Composable
fun Joystick(
    diameter: Dp,
    knobRadius: Dp,
    onVectorChange: (dx: Float, dy: Float) -> Unit
) {
    val diameterPx = with(LocalDensity.current) { diameter.toPx() }
    val knobRpx = with(LocalDensity.current) { knobRadius.toPx() }
    val baseR = diameterPx / 2f
    var knob by remember { mutableStateOf(Offset.Zero) }

    fun normalizedVector(fromCenter: Offset): Pair<Float, Float> {
        val len = hypot(fromCenter.x, fromCenter.y)
        if (len < 1f) return 0f to 0f
        val clamped = min(len, baseR - knobRpx)
        val nx = (fromCenter.x / len) * (clamped / (baseR - knobRpx))
        val ny = (fromCenter.y / len) * (clamped / (baseR - knobRpx))
        return max(-1f, min(1f, nx)) to max(-1f, min(1f, ny))
    }

    Canvas(
        modifier = Modifier
            .size(diameter)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val center = Offset(baseR, baseR)
                        knob = offset - center
                        val (dx, dy) = normalizedVector(knob)
                        onVectorChange(dx, dy)
                    },
                    onDrag = { change, dragAmount ->
                        val candidate = knob + dragAmount
                        val len = hypot(candidate.x, candidate.y)
                        val maxLen = baseR - knobRpx
                        knob = if (len > maxLen) candidate * (maxLen / len) else candidate
                        val (dx, dy) = normalizedVector(knob)
                        onVectorChange(dx, dy)
                    },
                    onDragEnd = {
                        knob = Offset.Zero
                        onVectorChange(0f, 0f)
                    },
                    onDragCancel = {
                        knob = Offset.Zero
                        onVectorChange(0f, 0f)
                    }
                )
            }
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color = Color(0x44222222), radius = baseR, center = center)
        drawCircle(color = Color(0x88444444), radius = baseR * 0.6f, center = center)
        val knobCenter = center + knob
        drawCircle(color = Color(0xFF888888), radius = knobRpx, center = knobCenter)
    }
}
