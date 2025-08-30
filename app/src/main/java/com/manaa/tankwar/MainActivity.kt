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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.*
import kotlin.math.*

enum class MatchState { WAITING, COUNTDOWN, PLAYING, GAMEOVER }

data class Explosion(val xPx: Float, val yPx: Float, val startMs: Long)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(color = Color(0xFF101015)) { GameScreen() } } }
    }
}

@Composable
fun GameScreen() {
    val context = LocalContext.current

    // Monde
    var worldW by remember { mutableFloatStateOf(0f) }
    var worldH by remember { mutableFloatStateOf(0f) }

    // Mon tank
    var tankX by rememberSaveable { mutableFloatStateOf(0f) }
    var tankY by rememberSaveable { mutableFloatStateOf(0f) }
    var tankAngle by rememberSaveable { mutableFloatStateOf(0f) }
    var initDone by rememberSaveable { mutableStateOf(false) }
    var myHp by rememberSaveable { mutableIntStateOf(3) }

    // Réseau
    val others  = remember { mutableStateMapOf<String, NetPlayerState>() }
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
    val tankRadiusPx = (spriteSize * 0.38f)
    val bulletSpeedNorm = 1.2f         // vitesse normalisée (0..1 par seconde)
    val bulletRadiusVisualPx = 12f
    val bulletAngleOffsetDeg = 0f
    val fireCooldownMs = 220L
    val hitCooldownMs = 350L
    val bulletLifeMs = 3000L

    // Sprites
    val background: ImageBitmap     = ImageBitmap.imageResource(R.drawable.battlefield)
    val tankSprite: ImageBitmap     = ImageBitmap.imageResource(R.drawable.tank)
    val bulletSprite: ImageBitmap   = ImageBitmap.imageResource(R.drawable.bullet)
    val explosionSheet: ImageBitmap = ImageBitmap.imageResource(R.drawable.explosion) // ⚠️ placer en drawable-nodpi

    // Explosion sheet (8 x 1)
    val EXP_COLS = 8
    val EXP_TOTAL = 8
    val EXP_FRAME_MS = 60L
    val EXP_W = explosionSheet.width / EXP_COLS
    val EXP_H = explosionSheet.height

    // Sons réels
    val sound = remember { SoundManager(context) }
    DisposableEffect(Unit) { onDispose { sound.release() } }

    // FX
    val explosions = remember { mutableStateListOf<Explosion>() }
    var clockMs by remember { mutableLongStateOf(0L) }

    // Match
    var matchState by remember { mutableStateOf(MatchState.WAITING) }
    var countdown by remember { mutableIntStateOf(3) }
    var lastShotMs by remember { mutableLongStateOf(0L) }
    var lastHitTime by remember { mutableLongStateOf(0L) }
    val recentHits = remember { mutableSetOf<String>() } // empêche double-dégât par même balle

    // Backend Firebase (inchangé)
    val online = remember {
        Online(
            roomId = "room-1",
            databaseUrl = "https://tank-74afa-default-rtdb.europe-west1.firebasedatabase.app"
        )
    }

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

            online.bulletsLiveRef().addValueEventListener(object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(s: com.google.firebase.database.DataSnapshot) {
                    val alive = mutableSetOf<String>()
                    bullets.clear()
                    for (c in s.children) {
                        val b = c.getValue(BulletState::class.java) ?: continue
                        bullets[b.id] = b
                        alive += b.id
                    }
                    recentHits.retainAll(alive)
                }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
            })
        } catch (e: IllegalStateException) {
            roomError = if (e.message == "ROOM_FULL") "Room pleine (2 joueurs)." else "Erreur: ${e.message}"
        }
    }

    // UID adversaire
    val opponentId by remember(slots, myId) {
        mutableStateOf(when (myId) { slots.p1 -> slots.p2; slots.p2 -> slots.p1; else -> null })
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

        // Spawn selon slot
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
            clockMs = nowMs

            val active = (matchState == MatchState.PLAYING && myHp > 0 && opponentHp > 0)

            // Mouvement
            val joyDx = if (active) joyDxRaw else 0f
            val joyDy = if (active) joyDyRaw else 0f
            tankX = (tankX + joyDx * tankSpeed * dt).coerceIn(half, worldW - half)
            tankY = (tankY + joyDy * tankSpeed * dt).coerceIn(half, worldH - half)
            if (joyDx != 0f || joyDy != 0f) {
                tankAngle = Math.toDegrees(atan2(joyDy.toDouble(), joyDx.toDouble())).toFloat()
            }

            // Collision tank↔tank (pas de traversée)
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

            // Tir : le tireur crée la balle côté serveur + son
            if (active && firePressed && (nowMs - lastShotMs) > fireCooldownMs) {
                lastShotMs = nowMs
                val rad = Math.toRadians((tankAngle + bulletAngleOffsetDeg).toDouble())
                val dirx = cos(rad).toFloat()
                val diry = sin(rad).toFloat()
                val id = "$myId-$nowMs"
                val bullet = BulletState(
                    id = id, owner = myId,
                    x = (tankX / worldW).coerceIn(0f, 1f),
                    y = (tankY / worldH).coerceIn(0f, 1f),
                    vx = dirx * bulletSpeedNorm,
                    vy = diry * bulletSpeedNorm,
                    ts = nowMs
                )
                online.spawnBullet(bullet)
                // pour ressenti immédiat, on seed localement la même balle
                bullets[id] = bullet
                sound.playShot()
            }

            // Physique / collisions (le propriétaire pousse ses balles)
            val snapshot = bullets.values.toList()
            val toRemoveByOwner = mutableListOf<String>()

            for (b in snapshot) {
                val ageMs = nowMs - b.ts
                val xn = b.x + b.vx * dt
                val yn = b.y + b.vy * dt
                val alive = (ageMs <= bulletLifeMs) && xn in 0f..1f && yn in 0f..1f

                // propriétaire = mise à jour serveur
                if (b.owner == myId) {
                    if (!alive) toRemoveByOwner += b.id
                    else online.updateBullet(b.copy(x = xn, y = yn))
                }

                // impact uniquement sur moi (jamais sur le tireur)
                if (b.owner != myId && active && alive && b.id !in recentHits) {
                    val bx = xn * worldW
                    val by = yn * worldH
                    val hit = hypot(bx - tankX, by - tankY) < (tankRadiusPx + bulletRadiusVisualPx)
                    if (hit && nowMs - lastHitTime > hitCooldownMs) {
                        lastHitTime = nowMs
                        myHp = max(0, myHp - 1)
                        online.setMyHp(myHp)
                        recentHits += b.id
                        explosions += Explosion(bx, by, nowMs) // explosion sur la CIBLE
                        sound.playExplosion()
                        // on laisse le propriétaire supprimer la balle (évite course)
                    }
                }

                // rendu local fluide
                if (alive) bullets[b.id] = b.copy(x = xn, y = yn, ts = nowMs) else bullets.remove(b.id)
            }

            // suppression côté serveur par propriétaire
            for (id in toRemoveByOwner) online.removeBullet(id)

            // Nettoyage explosions terminées
            val it = explosions.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val frame = ((nowMs - e.startMs) / EXP_FRAME_MS).toInt()
                if (frame >= EXP_TOTAL) it.remove()
            }

            // Sync tank throttlé
            if (nowMs - lastSend > 66 && myId.isNotEmpty()) {
                online.updateMyState((tankX / worldW).coerceIn(0f, 1f), (tankY / worldH).coerceIn(0f, 1f), tankAngle)
                lastSend = nowMs
            }
        }
    }

    DisposableEffect(Unit) { onDispose { online.disconnect() } }

    // ---------- Rendu ----------
    Box(Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { sz -> worldW = sz.width.toFloat(); worldH = sz.height.toFloat() }
        ) {
            // fond
            drawImage(background, dstSize = IntSize(size.width.toInt(), size.height.toInt()))

            // adversaire
            val oppId = when (myId) { slots.p1 -> slots.p2; slots.p2 -> slots.p1; else -> null }
            oppId?.let { oid ->
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

            // balles
            bullets.values.forEach { b ->
                val bx = b.x * worldW
                val by = b.y * worldH
                val ang = Math.toDegrees(atan2(b.vy.toDouble(), b.vx.toDouble())).toFloat()
                val sz = 32
                withTransform({
                    translate(bx, by)
                    rotate(ang, pivot = Offset.Zero)
                }) {
                    drawImage(
                        image = bulletSprite,
                        dstSize = IntSize(sz, sz),
                        dstOffset = IntOffset(-sz / 2, -sz / 2)
                    )
                }
            }

            // mon tank
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

            // explosions (alpha doux + taille ×2.5 pour lisibilité)
            explosions.forEach { e ->
                val frame = ((clockMs - e.startMs) / EXP_FRAME_MS).toInt().coerceIn(0, EXP_TOTAL - 1)
                val col = frame % EXP_COLS
                val srcOffset = IntOffset(col * EXP_W, 0)
                val srcSize   = IntSize(EXP_W, EXP_H)
                val scale = 2.5f
                val dstW = (EXP_W * scale).toInt()
                val dstH = (EXP_H * scale).toInt()
                val dstOffset = IntOffset((e.xPx - dstW / 2).toInt(), (e.yPx - dstH / 2).toInt())
                // pas d'import drawImage : DrawScope l’expose directement
                drawImage(
                    image = explosionSheet,
                    srcOffset = srcOffset,
                    srcSize = srcSize,
                    dstOffset = dstOffset,
                    dstSize = IntSize(dstW, dstH)
                )
            }
        }

        // joystick
        Box(Modifier.align(Alignment.BottomStart).padding(24.dp)) {
            Joystick(160.dp, 28.dp) { dx, dy -> joyDxRaw = dx; joyDyRaw = dy }
        }

        // tir
        Box(Modifier.align(Alignment.BottomEnd).padding(24.dp)) {
            Button(onClick = { firePressed = true }, modifier = Modifier.size(88.dp)) { Text("FIRE") }
            LaunchedEffect(firePressed) { if (firePressed) { kotlinx.coroutines.delay(80); firePressed = false } }
        }

        // PV
        HeartsRow(myHp, Modifier.align(Alignment.TopStart).padding(8.dp))
        HeartsRow(opponentHp, Modifier.align(Alignment.TopEnd).padding(8.dp))

        // overlays
        when {
            roomError != null -> CenterOverlay(roomError!!)
            matchState == MatchState.WAITING -> CenterOverlay("En attente d’un second joueur…")
            matchState == MatchState.COUNTDOWN -> CenterOverlay(if (countdown > 0) countdown.toString() else "GO!", big = true)
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
                }
            }
        }
    }
}

/* ---------- UI helpers ---------- */

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

/* ---------- Joystick ---------- */
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

    fun normalized(delta: Offset): Pair<Float, Float> {
        val len = hypot(delta.x, delta.y)
        if (len < 1f) return 0f to 0f
        val maxLen = baseR - knobRpx
        val clamped = min(len, maxLen)
        val nx = (delta.x / len) * (clamped / maxLen)
        val ny = (delta.y / len) * (clamped / maxLen)
        return nx.coerceIn(-1f, 1f) to ny.coerceIn(-1f, 1f)
    }

    Canvas(
        modifier = Modifier
            .size(diameter)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o ->
                        val center = Offset(baseR, baseR)
                        knob = o - center
                        val (dx, dy) = normalized(knob); onVectorChange(dx, dy)
                    },
                    onDrag = { _, drag ->
                        val cand = knob + drag
                        val len = hypot(cand.x, cand.y)
                        val maxLen = baseR - knobRpx
                        knob = if (len > maxLen) cand * (maxLen / len) else cand
                        val (dx, dy) = normalized(knob); onVectorChange(dx, dy)
                    },
                    onDragEnd = { knob = Offset.Zero; onVectorChange(0f, 0f) },
                    onDragCancel = { knob = Offset.Zero; onVectorChange(0f, 0f) }
                )
            }
    ) {
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color = Color(0x44222222), radius = baseR, center = c)
        drawCircle(color = Color(0x88444444), radius = baseR * 0.6f, center = c)
        drawCircle(color = Color(0xFF888888), radius = knobRpx, center = c + knob)
    }
}
