package com.mjcastro.damelaschapas

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.res.ResourcesCompat
import kotlin.math.*

enum class ChapaEstado { INACTIVA, MOVIMIENTO, ELIMINADA }
enum class Turno { JUGADOR1, JUGADOR2, IA }

data class Chapa(
    var x: Float,
    var y: Float,
    var radius: Float,
    var esDelJugador: Boolean = true,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var estado: ChapaEstado = ChapaEstado.INACTIVA
)

class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private lateinit var gameThread: GameThread
    private val chapas = mutableListOf<Chapa>()

    // Recursos gráficos
    private val ricePaper by lazy { BitmapFactory.decodeResource(resources, R.drawable.rice_paper) }
    private val sumiBoard by lazy { BitmapFactory.decodeResource(resources, R.drawable.tablero_sumi) }
    private val daruma by lazy { BitmapFactory.decodeResource(resources, R.drawable.chapa_daruma) }
    private val inari by lazy { BitmapFactory.decodeResource(resources, R.drawable.chapa_inari) }
    private val sumiFont by lazy { ResourcesCompat.getFont(context, R.font.fontsumi) }

    // Para manchas persistentes
    private var spotsBitmap: Bitmap? = null
    private var spotsCanvas: Canvas? = null

    // Turnos y puntuación
    private var turnoActual = Turno.JUGADOR1
    private var puntosJ1 = 0
    private var puntosJ2 = 0

    // Interacción
    private var selectedChapa: Chapa? = null
    private var touchDownTime: Long = 0L
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var dragX = 0f
    private var dragY = 0f
    private var golpeActivo = false

    // Tablero circular (posición derivada de tamaño de fondo)
    private var boardLeft = 0f
    private var boardTop = 0f
    private var boardRight = 0f
    private var boardBottom = 0f
    private var boardRadius = 0f
    private var boardCX = 0f
    private var boardCY = 0f

    // Modo VS IA o VS Humano
    private var vsIA = false

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    private fun setupGame(width: Int, height: Int) {
        chapas.clear()
        puntosJ1 = 0
        puntosJ2 = 0
        turnoActual = Turno.JUGADOR1
        vsIA = false // Cambia a true para probar IA

        // Tablero central, lo más grande posible dentro del Canvas
        val size = min(width, height) * 0.95f
        boardLeft = (width - size) / 2f
        boardTop = (height - size) / 2f
        boardRight = boardLeft + size
        boardBottom = boardTop + size
        boardCX = width / 2f
        boardCY = height / 2f
        boardRadius = size / 2.05f

        // Inicializar el bitmap de manchas al tamaño de la pantalla
        spotsBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        spotsCanvas = Canvas(spotsBitmap!!)

        val r = size * 0.07f

        // Pone 3 fichas por jugador, simétricas
        chapas.add(Chapa(boardCX - boardRadius*0.6f, boardCY - boardRadius*0.2f, r, esDelJugador = true))
        chapas.add(Chapa(boardCX - boardRadius*0.63f, boardCY + boardRadius*0.15f, r, esDelJugador = true))
        chapas.add(Chapa(boardCX - boardRadius*0.5f, boardCY, r, esDelJugador = true))
        chapas.add(Chapa(boardCX + boardRadius*0.6f, boardCY + boardRadius*0.2f, r, esDelJugador = false))
        chapas.add(Chapa(boardCX + boardRadius*0.63f, boardCY - boardRadius*0.15f, r, esDelJugador = false))
        chapas.add(Chapa(boardCX + boardRadius*0.5f, boardCY, r, esDelJugador = false))

        Log.d("GameView", "Chapas inicializadas: ${chapas.size}")
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) return false
        if (vsIA && turnoActual == Turno.IA) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (chapas.any { it.estado == ChapaEstado.MOVIMIENTO }) return false
                val tx = event.x
                val ty = event.y
                selectedChapa = if (turnoActual == Turno.JUGADOR1)
                    chapas.firstOrNull { it.esDelJugador && hypot(it.x - tx, it.y - ty) < it.radius }
                else
                    chapas.firstOrNull { !it.esDelJugador && hypot(it.x - tx, it.y - ty) < it.radius }
                if (selectedChapa != null) {
                    touchDownTime = System.currentTimeMillis()
                    touchStartX = tx
                    touchStartY = ty
                    dragX = tx
                    dragY = ty
                    golpeActivo = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (golpeActivo && selectedChapa != null) {
                    dragX = event.x
                    dragY = event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                if (golpeActivo && selectedChapa != null) {
                    val touchUpTime = System.currentTimeMillis()
                    val duration = (touchUpTime - touchDownTime).toFloat()
                    val endX = event.x
                    val endY = event.y
                    val dxGolpe = touchStartX - endX
                    val dyGolpe = touchStartY - endY
                    val magGolpe = sqrt(dxGolpe*dxGolpe + dyGolpe*dyGolpe)
                    val baseForce = min(duration * 0.28f, 350f) + min(magGolpe * 1.1f, 420f)
                    if (magGolpe > 10f) {
                        selectedChapa?.let {
                            it.vx = (dxGolpe / magGolpe) * baseForce
                            it.vy = (dyGolpe / magGolpe) * baseForce
                            it.estado = ChapaEstado.MOVIMIENTO
                        }
                    }
                    selectedChapa = null
                    golpeActivo = false
                }
            }
        }
        return true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        setupGame(width, height)
        if (!::gameThread.isInitialized || !gameThread.isAlive) {
            gameThread = GameThread(holder, this)
            gameThread.setRunning(true)
            gameThread.start()
        } else {
            gameThread.setRunning(true)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (::gameThread.isInitialized) {
            gameThread.setRunning(false)
            var retry = true
            while (retry) {
                try {
                    gameThread.join()
                    retry = false
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun todasLasChapasDetenidas() = chapas.none { it.estado == ChapaEstado.MOVIMIENTO }

    fun updateGame(deltaTime: Float) {
        chapas.forEach { chapa ->
            if (chapa.estado == ChapaEstado.MOVIMIENTO) {
                chapa.x += chapa.vx * deltaTime
                chapa.y += chapa.vy * deltaTime
                // fricción
                chapa.vx *= 0.99f
                chapa.vy *= 0.99f
                if (abs(chapa.vx) < 0.05f && abs(chapa.vy) < 0.05f) {
                    chapa.vx = 0f
                    chapa.vy = 0f
                    chapa.estado = ChapaEstado.INACTIVA
                }
                // rebote en bordes circulares (pierde energía)
                val dx = chapa.x - boardCX
                val dy = chapa.y - boardCY
                val dist = sqrt(dx*dx + dy*dy)
                if (dist + chapa.radius > boardRadius) {
                    val angle = atan2(dy, dx)
                    val nx = cos(angle)
                    val ny = sin(angle)
                    val pen = dist + chapa.radius - boardRadius
                    chapa.x -= nx * pen
                    chapa.y -= ny * pen
                    val dot = chapa.vx * nx + chapa.vy * ny
                    chapa.vx -= 2 * dot * nx * 0.7f
                    chapa.vy -= 2 * dot * ny * 0.7f
                    addSumiSpot(chapa.x, chapa.y)
                }
            }
        }
        // colisiones elásticas y manchas sumi
        for (i in 0 until chapas.size) {
            for (j in i + 1 until chapas.size) {
                val a = chapas[i]
                val b = chapas[j]
                val dx = a.x - b.x
                val dy = a.y - b.y
                val dist = sqrt(dx*dx + dy*dy)
                val minDist = a.radius + b.radius
                if (dist < minDist && dist > 0.1f) {
                    val nx = dx / dist
                    val ny = dy / dist
                    val pen = (minDist - dist) / 2f
                    a.x += nx * pen
                    a.y += ny * pen
                    b.x -= nx * pen
                    b.y -= ny * pen
                    // intercambio de velocidad simplificado
                    val k = 0.9f
                    val dpA = a.vx * nx + a.vy * ny
                    val dpB = b.vx * nx + b.vy * ny
                    val va = dpB * k
                    val vb = dpA * k
                    a.vx += (va - dpA) * nx
                    a.vy += (va - dpA) * ny
                    b.vx += (vb - dpB) * nx
                    b.vy += (vb - dpB) * ny
                    a.estado = ChapaEstado.MOVIMIENTO
                    b.estado = ChapaEstado.MOVIMIENTO
                    addSumiSpot((a.x + b.x)/2, (a.y + b.y)/2)
                }
            }
        }
        // Detectar fin de turno y cambiarlo
        if (todasLasChapasDetenidas()) handleEndTurn()
    }

    private fun handleEndTurn() {
        // Checar si alguna chapa salió fuera (centro tablero), suman punto al rival y se retiran
        val fuera = chapas.filter { hypot(it.x - boardCX, it.y - boardCY) + it.radius > boardRadius + 9 }
        fuera.forEach {
            addSumiSpot(it.x, it.y)
            if (it.esDelJugador) puntosJ2++ else puntosJ1++
        }
        chapas.removeAll(fuera)
        // Cambiar turno, o jugada IA si corresponde
        turnoActual = when (turnoActual) {
            Turno.JUGADOR1 -> if (vsIA) Turno.IA else Turno.JUGADOR2
            Turno.JUGADOR2, Turno.IA -> Turno.JUGADOR1
        }
        if (vsIA && turnoActual == Turno.IA) doAITurn()
    }

    // IA básica dispara su ficha más cercana a la más cercana rival
    private fun doAITurn() {
        val own = chapas.filter { !it.esDelJugador }
        val opp = chapas.filter { it.esDelJugador }
        if (own.isNotEmpty() && opp.isNotEmpty()) {
            val aiChapa = own.minBy { (it.x - boardCX).pow(2) + (it.y - boardCY).pow(2) }
            val target = opp.minBy { (it.x - aiChapa.x).pow(2) + (it.y - aiChapa.y).pow(2) }
            val dx = (target.x - aiChapa.x)
            val dy = (target.y - aiChapa.y)
            val mag = sqrt(dx*dx + dy*dy)
            if (mag > 3) {
                aiChapa.vx = (dx / mag) * 360f
                aiChapa.vy = (dy / mag) * 360f
                aiChapa.estado = ChapaEstado.MOVIMIENTO
            }
        }
    }

    private fun addSumiSpot(x: Float, y: Float) {
        val paint = Paint().apply {
            color = Color.BLACK
            alpha = 57 + (Math.random()*140).toInt()
            style = Paint.Style.FILL
        }
        val n = (4..11).random()
        for (i in 0..n) {
            val angle = Math.random().toFloat() * 2 * Math.PI.toFloat()
            val dist = (Math.random()*28+7).toFloat()
            val px = x + cos(angle) * dist
            val py = y + sin(angle) * dist
            val r = (9..33).random().toFloat()
            spotsCanvas?.drawCircle(px, py, r, paint)
        }
    }

    fun drawEverything(canvas: Canvas) {
        // Fondo papel arroz
        canvas.drawBitmap(ricePaper, null, Rect(0, 0, width, height), null)
        // Tablero sumi-e (bitmap, grande)
        val size = boardRadius*2.05f
        val left = boardCX - size/2
        val top = boardCY - size/2
        canvas.drawBitmap(sumiBoard, null, RectF(left, top, left+size, top+size), null)
        // Manchas de tinta (persistentes)
        spotsBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        // Chapas
        chapas.forEach { chapa ->
            val bmp = if (chapa.esDelJugador) daruma else inari
            val dest = RectF(
                chapa.x - chapa.radius,
                chapa.y - chapa.radius,
                chapa.x + chapa.radius,
                chapa.y + chapa.radius
            )
            canvas.drawBitmap(bmp, null, dest, null)
        }
        // Línea de dirección
        if (golpeActivo && selectedChapa != null) {
            val linePaint = Paint().apply {
                color = Color.BLACK
                strokeWidth = 7f
                isAntiAlias = true
                alpha = 170
            }
            canvas.drawLine(selectedChapa!!.x, selectedChapa!!.y, dragX, dragY, linePaint)
        }
        // HUD sumi-e
        val hudPaint = Paint().apply {
            color = Color.BLACK
            textSize = 60f
            isAntiAlias = true
            typeface = sumiFont
        }
        val turnoStr = when(turnoActual) {
            Turno.JUGADOR1 -> "Jugador"
            Turno.JUGADOR2 -> "Rival"
            Turno.IA -> "IA"
        }
        canvas.drawText("Turno: $turnoStr", 42f, 80f, hudPaint)
        canvas.drawText("🀄 ${puntosJ1}  VS  ${puntosJ2} 🃏", width/2f-70, 80f, hudPaint)
    }
}
