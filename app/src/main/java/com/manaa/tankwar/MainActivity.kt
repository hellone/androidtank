package com.manaa.tankwar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.*
import kotlin.math.*

enum class MatchState { WAITING, COUNTDOWN, PLAYING, GAMEOVER }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(color = Color(0xFF101015)) { GameScreen() } } }
    }
}

@Composable
fun GameScreen() {
    // Monde
    var worldW by remember { mutableFloatStateOf(0f) }
    var worldH by remember { mutableFloatStateOf(0f) }

    // Mon tank
    var tankX by rememberSaveable { mutableFloatStateOf(0f) }
    var tankY by rememberSaveable { mutableFloatStateOf(0f) }
    var tankAngle by rememberSaveable { mutableFloatStateOf(0f) }
    var initDone by rememberSaveable { mutableStateOf(false) }
    var myHp by rememberSaveable { mutableIntStateOf(3) }

    // Réseau & états
    val others = remember { mutableStateMapOf<String, NetPlayerState>() }
    val bullets = remember { mutableStateMapOf<String, BulletState>() }

    var myId by remember { mutableStateOf("") }
    var slots by remember { mutableStateOf(RoomSlots()) }
    var roomError by remember { mutableStateOf<String?>(null) }

    // Entrées
    var joyDxRaw by remember { mutableFloatStateOf(0f) }
    var joyDyRaw by remember { mutableFloatStateOf(0f) }
    var firePressed by remember { mutableStateOf(false) }

    // Constantes
    val tankSpeed = 220f
    val spriteSize = 160
    val half = spriteSize / 2f
    val bulletSpeedNorm = 1.2f
    val bulletRadiusPx = 8f
    val tankRadiusPx = (spriteSize * 0.38f)
    val bulletAngleOffsetDeg = 0f    // <- demandé
    val hitCooldownMs = 350L
    val fireCooldownMs = 220L
    val bulletLifeMs = 3000L

    // Assets
    val background: ImageBitmap = ImageBitmap.imageResource(R.drawable.battlefield)
    val tankSprite: ImageBitmap = ImageBitmap.imageResource(R.drawable.tank)

    // Réseau
    val online = remember {
        Online(
            roomId = "room-1",
            databaseUrl = "https://tank-74afa-default-rtdb.europe-west1.firebasedatabase.app"
        )
    }

    // Etat de partie
    var matchState by remember { mutableStateOf(MatchState.WAITING) }
    var countdown by remember { mutableIntStateOf(3) }
    var lastHitTime by remember { mutableLongStateOf(0L) }
    var lastShotMs by remember { mutableLongStateOf(0L) }

    // Anti double-hit par balle (persiste tant que la balle existe côté DB)
    val recentHits = remember { mutableSetOf<String>() }

    // Connexion + listeners
    LaunchedEffect(Unit) {
        try {
            myId = online.connect(name = "Player")

            online.slotsLiveRef().addValueEventListener(object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(s: com.google.firebase.database.DataSnapshot) {
                    slots = RoomSlots(
                        s.child("p1").getValue(String::class.java),
                        s.child("p2").getValue(String::class.java)
                    )
                }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
            })

            online.playersLiveRef().addValueEventListener(object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(s: com.google.firebase.database.DataSnapshot) {
                    val valid = setOfNotNull(slots.p1, slots.p2)
                    others.clear()
                    for (c in s.children) {
                        val id = c.key ?: continue
                        val st = c.getValue(NetPlayerState::class.java) ?: continue
                        if (id == myId) myHp = st.hp else if (id in valid) others[id] = st
                    }
                }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
            })

            // Quand la liste change, on purge recentHits des balles disparues
            online.bulletsLiveRef().addValueEventListener(object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(s: com.google.firebase.database.DataSnapshot) {
                    val aliveIds = mutableSetOf<String>()
                    bullets.clear()
                    for (c in s.children) {
                        val b = c.getValue(BulletState::class.java) ?: continue
                        bullets[b.id] = b
                        aliveIds += b.id
                    }
                    // supprime des hits mémorisés les ids qui n’existent plus
                    recentHits.retainAll(aliveIds)
                }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
            })
        } catch (e: IllegalStateException) {
            roomError = when (e.message) { "ROOM_FULL" -> "Room pleine (2 joueurs)."; else -> "Erreur: ${e.message}" }
        }
    }

    // UID adversaire
    val opponentId by remember(slots, myId) {
        mutableStateOf(
            when (myId) {
                slots.p1 -> slots.p2
                slots.p2 -> slots.p1
                else -> null
            }
        )
    }
    val opponentHp by remember(others, opponentId) {
        derivedStateOf { opponentId?.let { others[it]?.hp } ?: 3 }
    }

    // Transitions d’états
    LaunchedEffect(slots.p1, slots.p2, myHp, opponentHp) {
        val two = slots.p1 != null && slots.p2 != null
        if (myHp <= 0 || opponentHp <= 0) {
            if (matchState != MatchState.GAMEOVER) matchState = MatchState.GAMEOVER
        } else if (two && matchState == MatchState.WAITING) {
            matchState = MatchState.COUNTDOWN
            countdown = 3
            repeat(3) { kotlinx.coroutines.delay(1000); countdown -= 1 }
            countdown = 0
            kotlinx.coroutines.delay(600)
            matchState = MatchState.PLAYING
        } else if (!two && matchState == MatchState.PLAYING) {
            matchState = MatchState.WAITING
        }
    }

    // Boucle frame
    LaunchedEffect(worldW, worldH, myId, slots.p1, slots.p2) {
        if (worldW <= 0f || worldH <= 0f || myId.isEmpty()) return@LaunchedEffect

        // Spawn initial
        if (!initDone && (slots.p1 == myId || slots.p2 == myId)) {
            val left = (slots.p1 == myId)
            tankX = (if (left) 0.15f else 0.85f) * worldW
            tankY = 0.5f * worldH
            tankAngle = if (left) 0f else 180f
            initDone = true
            online.updateMyState(tankX / worldW, tankY / worldH, tankAngle)
        }

        var last = withFrameNanos { it }
        var lastSend = 0L

        while (true) {
            val nowN = withFrameNanos { it }
            val dt = (nowN - last) / 1_000_000_000.0f
            last = nowN
            val nowMs = System.currentTimeMillis()

            val active = (matchState == MatchState.PLAYING && myHp > 0 && (opponentHp > 0))

            // Mouvement
            val joyDx = if (active) joyDxRaw else 0f
            val joyDy = if (active) joyDyRaw else 0f
            tankX = (tankX + joyDx * tankSpeed * dt).coerceIn(half, worldW - half)
            tankY = (tankY + joyDy * tankSpeed * dt).coerceIn(half, worldH - half)
            if (joyDx != 0f || joyDy != 0f) {
                tankAngle = Math.toDegrees(atan2(joyDy.toDouble(), joyDx.toDouble())).toFloat()
            }

            // Collision tank↔tank
            opponentId?.let { oid ->
                others[oid]?.let { st ->
                    val ox = st.x * worldW
                    val oy = st.y * worldH
                    val dx = tankX - ox
                    val dy = tankY - oy
                    val dist = hypot(dx, dy)
                    val minDist = tankRadiusPx * 2f
                    if (dist < minDist && dist > 0.0001f) {
                        val overlap = minDist - dist
                        val nx = dx / dist
                        val ny = dy / dist
                        tankX = (tankX + nx * (overlap / 2f)).coerceIn(half, worldW - half)
                        tankY = (tankY + ny * (overlap / 2f)).coerceIn(half, worldH - half)
                    }
                }
            }

            // Tir
            if (active && firePressed && (nowMs - lastShotMs) > fireCooldownMs) {
                lastShotMs = nowMs
                val rad = Math.toRadians((tankAngle + bulletAngleOffsetDeg).toDouble())
                val dirx = cos(rad).toFloat()
                val diry = sin(rad).toFloat()
                val startXn = (tankX / worldW).coerceIn(0f, 1f)
                val startYn = (tankY / worldH).coerceIn(0f, 1f)
                val bulletId = "$myId-$nowMs"
                online.spawnBullet(
                    BulletState(
                        id = bulletId,
                        owner = myId,
                        x = startXn, y = startYn,
                        vx = dirx * bulletSpeedNorm,
                        vy = diry * bulletSpeedNorm,
                        ts = nowMs
                    )
                )
            }

            // --------- Physique des balles (snapshot + anti double-hit persistant) ----------
            val toRemoveNetwork = mutableListOf<String>()
            val currentBullets = bullets.values.toList() // snapshot immuable

            for (b in currentBullets) {
                val ageMs = nowMs - b.ts
                val xn = b.x + b.vx * dt
                val yn = b.y + b.vy * dt
                val alive = (ageMs <= bulletLifeMs) && xn in 0f..1f && yn in 0f..1f

                // Le propriétaire pousse la position ; la victime ne touche pas au réseau
                if (b.owner == myId) {
                    if (!alive) toRemoveNetwork += b.id
                    else online.updateBullet(b.copy(x = xn, y = yn))
                }

                // Collision contre MON tank uniquement, et une seule fois tant que l’ID existe
                if (b.owner != myId && active && alive && b.id !in recentHits) {
                    val bx = xn * worldW
                    val by = yn * worldH
                    if (hypot(bx - tankX, by - tankY) < (tankRadiusPx + bulletRadiusPx)
                        && nowMs - lastHitTime > hitCooldownMs
                    ) {
                        lastHitTime = nowMs
                        myHp = max(0, myHp - 1)
                        online.setMyHp(myHp)
                        // On mémorise l’ID : tant qu’il reste dans la DB on ne reprendra pas de dégâts
                        recentHits += b.id
                        // Le retrait réseau sera fait par le propriétaire (éventuellement à la frame suivante)
                    }
                }

                // Update local pour le rendu
                if (alive) bullets[b.id] = b.copy(x = xn, y = yn, ts = nowMs)
                else bullets.remove(b.id)
            }

            // Le propriétaire supprime ses balles mortes côté DB
            for (id in toRemoveNetwork) online.removeBullet(id)

            // Sync tank throttlé
            if (nowMs - lastSend > 66 && myId.isNotEmpty()) {
                online.updateMyState(
                    (tankX / worldW).coerceIn(0f, 1f),
                    (tankY / worldH).coerceIn(0f, 1f),
                    tankAngle
                )
                lastSend = nowMs
            }
        }
    }

    DisposableEffect(Unit) { onDispose { online.disconnect() } }

    // ---------------- UI ----------------
    Box(Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { sz -> worldW = sz.width.toFloat(); worldH = sz.height.toFloat() }
        ) {
            drawImage(background, dstSize = IntSize(size.width.toInt(), size.height.toInt()))

            // Adversaire
            val opponentIdLocal = when (myId) {
                slots.p1 -> slots.p2
                slots.p2 -> slots.p1
                else -> null
            }
            opponentIdLocal?.let { oid ->
                others[oid]?.let { st ->
                    val ox = st.x * worldW
                    val oy = st.y * worldH
                    withTransform({
                        translate(ox, oy)
                        rotate(st.angle, pivot = Offset.Zero)
                    }) {
                        drawImage(
                            image = tankSprite,
                            dstSize = IntSize(spriteSize, spriteSize),
                            dstOffset = IntOffset(-spriteSize / 2, -spriteSize / 2)
                        )
                    }
                }
            }

            // Balles
            bullets.values.forEach { b ->
                val bx = b.x * worldW
                val by = b.y * worldH
                drawCircle(
                    color = if (b.owner == myId) Color(0xFF66CCFF) else Color(0xFFFF6666),
                    radius = bulletRadiusPx,
                    center = Offset(bx, by)
                )
            }

            // Mon tank
            withTransform({
                translate(tankX, tankY)
                rotate(tankAngle, pivot = Offset.Zero)
            }) {
                drawImage(
                    image = tankSprite,
                    dstSize = IntSize(spriteSize, spriteSize),
                    dstOffset = IntOffset(-spriteSize / 2, -spriteSize / 2)
                )
            }
        }

        // Joystick
        Box(Modifier.align(Alignment.BottomStart).padding(24.dp)) {
            Joystick(160.dp, 28.dp) { dx, dy -> joyDxRaw = dx; joyDyRaw = dy }
        }

        // FIRE
        Box(Modifier.align(Alignment.BottomEnd).padding(24.dp)) {
            Button(onClick = { firePressed = true }, modifier = Modifier.size(88.dp)) { Text("FIRE") }
            LaunchedEffect(firePressed) { if (firePressed) { kotlinx.coroutines.delay(80); firePressed = false } }
        }

        // PV
        HeartsRow(count = myHp, modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
        HeartsRow(count = opponentHp, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))

        // Overlays
        when {
            roomError != null -> CenterOverlay(roomError!!)
            matchState == MatchState.WAITING -> CenterOverlay("En attente d’un second joueur…")
            matchState == MatchState.COUNTDOWN -> {
                val label = if (countdown > 0) countdown.toString() else "GO!"
                CenterOverlay(label, big = true)
            }
            matchState == MatchState.GAMEOVER -> {
                Column(
                    Modifier.fillMaxSize().background(Color(0x88000000)),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("GAME OVER", color = Color.White, fontSize = 48.sp)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        myHp = 3
                        online.setMyHp(3)
                        online.clearBullets()
                        if (worldW > 0f && worldH > 0f) {
                            val left = (slots.p1 == myId)
                            tankX = (if (left) 0.15f else 0.85f) * worldW
                            tankY = 0.5f * worldH
                            tankAngle = if (left) 0f else 180f
                            online.updateMyState(tankX / worldW, tankY / worldH, tankAngle)
                        }
                        recentHits.clear()
                        matchState = MatchState.WAITING
                    }) { Text("Rejouer") }
                    if (opponentHp <= 0) {
                        Spacer(Modifier.height(8.dp))
                        Text("En attente que l’adversaire rejoue…", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

// ---------- UI helpers ----------

@Composable
private fun HeartsRow(count: Int, modifier: Modifier = Modifier) {
    val hearts = buildString { repeat(max(0, count)) { append('❤') } }
    Text(text = hearts.ifEmpty { "—" }, color = Color.Red, fontSize = 22.sp, modifier = modifier)
}

@Composable
private fun CenterOverlay(text: String, big: Boolean = false) {
    Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = if (big) 64.sp else 22.sp)
    }
}

// ---------- Joystick ----------

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
        val maxLen = baseR - knobRpx
        val clamped = min(len, maxLen)
        val nx = (fromCenter.x / len) * (clamped / maxLen)
        val ny = (fromCenter.y / len) * (clamped / maxLen)
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
                        val (dx, dy) = normalizedVector(knob); onVectorChange(dx, dy)
                    },
                    onDrag = { _, dragAmount ->
                        val candidate = knob + dragAmount
                        val len = hypot(candidate.x, candidate.y)
                        val maxLen = baseR - knobRpx
                        knob = if (len > maxLen) candidate * (maxLen / len) else candidate
                        val (dx, dy) = normalizedVector(knob); onVectorChange(dx, dy)
                    },
                    onDragEnd = { knob = Offset.Zero; onVectorChange(0f, 0f) },
                    onDragCancel = { knob = Offset.Zero; onVectorChange(0f, 0f) }
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
