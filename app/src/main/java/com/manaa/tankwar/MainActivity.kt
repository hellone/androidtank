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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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
    // Taille du monde (= taille du canvas)
    var worldW by remember { mutableFloatStateOf(0f) }
    var worldH by remember { mutableFloatStateOf(0f) }

    // Tank state
    var tankX by remember { mutableFloatStateOf(0f) }
    var tankY by remember { mutableFloatStateOf(0f) }
    var tankAngle by remember { mutableFloatStateOf(0f) }

    // Sortie joystick (normalisée [-1,1])
    var joyDx by remember { mutableFloatStateOf(0f) }
    var joyDy by remember { mutableFloatStateOf(0f) }

    val tankSpeed = 220f
    val spriteSize = 160
    val half = spriteSize / 2f

    // Images
    val background: ImageBitmap = ImageBitmap.imageResource(id = R.drawable.battlefield)
    val tankSprite: ImageBitmap = ImageBitmap.imageResource(id = R.drawable.tank)

    // Boucle d’update – démarre quand on connaît la taille du monde
    LaunchedEffect(worldW, worldH) {
        if (worldW <= 0f || worldH <= 0f) return@LaunchedEffect
        // init position au centre si 0
        if (tankX == 0f && tankY == 0f) {
            tankX = worldW / 2f
            tankY = worldH / 2f
        }
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000.0).toFloat()
            last = now

            tankX = (tankX + joyDx * tankSpeed * dt).coerceIn(half, worldW - half)
            tankY = (tankY + joyDy * tankSpeed * dt).coerceIn(half, worldH - half)

            if (joyDx != 0f || joyDy != 0f) {
                tankAngle = Math.toDegrees(atan2(joyDy.toDouble(), joyDx.toDouble())).toFloat()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { sz ->
                    worldW = sz.width.toFloat()
                    worldH = sz.height.toFloat()
                }
        ) {
            // Fond plein écran
            drawImage(
                image = background,
                dstSize = IntSize(size.width.toInt(), size.height.toInt())
            )

            // Tank pivoté autour de son centre
            withTransform({
                translate(tankX, tankY)
                rotate(degrees = tankAngle, pivot = Offset.Zero)
            }) {
                drawImage(
                    image = tankSprite,
                    dstSize = IntSize(spriteSize, spriteSize),
                    dstOffset = IntOffset(-spriteSize / 2, -spriteSize / 2)
                )
            }
        }

        // Joystick bas-gauche
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(24.dp)
        ) {
            Joystick(
                diameter = 160.dp,
                knobRadius = 28.dp,
                onVectorChange = { dx, dy ->
                    joyDx = dx
                    joyDy = dy
                }
            )
        }

        // HUD debug
        Text(
            text = "dx=%.2f dy=%.2f angle=%.0f".format(joyDx, joyDy, tankAngle),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
            color = Color.White,
            fontSize = 14.sp
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

    var knob by remember { mutableStateOf(Offset.Zero) } // relatif au centre

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
        // Base
        drawCircle(color = Color(0x44222222), radius = baseR, center = center)
        drawCircle(color = Color(0x88444444), radius = baseR * 0.6f, center = center)
        // Knob
        val knobCenter = center + knob
        drawCircle(color = Color(0xFF888888), radius = knobRpx, center = knobCenter)
    }
}
