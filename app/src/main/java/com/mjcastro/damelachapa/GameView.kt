package com.mjcastro.damelachapa

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.*

enum class Turno { JUGADOR1, JUGADOR2 }

data class Chapa(
    var x: Float, var y: Float, val radius: Float,
    val esJugador1: Boolean,
    var vx: Float = 0f, var vy: Float = 0f,
    var enJuego: Boolean = true,
    var id: Int = 0
)



class GameView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private var thread: GameThread? = null

    private val backgroundBmp by lazy {
        val original = BitmapFactory.decodeResource(resources, R.drawable.game_background_landscape)
        Bitmap.createScaledBitmap(original, width, height, true)
    }

    private val chapaJ1Bmp by lazy { BitmapFactory.decodeResource(resources, R.drawable.chapa_daruma) }
    private val chapaJ2Bmp by lazy { BitmapFactory.decodeResource(resources, R.drawable.chapa_inari) }

    private val chapas = mutableListOf<Chapa>()
    private var turnoActual = Turno.JUGADOR1
    private var mensajeEstado = "Tu turno: Jugador 1"
    private var fichasEnMovimiento = false

    private var shooterChapa: Chapa? = null
    private val chapasGolpeadas = mutableSetOf<Int>()
    private var enemigosExpulsadosEnTurno = 0

    private var boardCX = 0f
    private var boardCY = 0f
    private var boardRadius = 0f

    private var chapaSeleccionada: Chapa? = null
    private var dragX = 0f
    private var dragY = 0f
    private var isDragging = false

    private val paintLine = Paint().apply {
        color = Color.WHITE
        strokeWidth = 8f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }

    private val paintText = Paint().apply {
        color = Color.parseColor("#5A4A3A")
        textSize = 60f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val chapaPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (chapas.isEmpty()) {
            post {
                setupGame()
                thread = GameThread(holder, this)
                thread?.setRunning(true)
                thread?.start()
            }
        } else {
            thread = GameThread(holder, this)
            thread?.setRunning(true)
            thread?.start()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        var retry = true
        thread?.setRunning(false)
        while (retry) {
            try {
                thread?.join()
                retry = false
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
    }

    private fun setupGame() {
        boardCX = width / 2f
        boardCY = height / 2f
        boardRadius = height * 0.40f

        val radioChapa = boardRadius * 0.12f

        for (i in 0 until 5) {
            val spacing = radioChapa * 2.5f
            val startY = boardCY - (spacing * 2) + (spacing * i)

            chapas.add(Chapa(boardCX - boardRadius * 0.5f, startY, radioChapa, true, id = i))
            chapas.add(Chapa(boardCX + boardRadius * 0.5f, startY, radioChapa, false, id = i + 10))
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (fichasEnMovimiento) return true

        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val esTurnoJ1 = turnoActual == Turno.JUGADOR1
                chapaSeleccionada = chapas.find {
                    it.enJuego &&
                            it.esJugador1 == esTurnoJ1 &&
                            hypot(it.x - x, it.y - y) <= it.radius * 1.5f
                }
                if (chapaSeleccionada != null) {
                    isDragging = true
                    dragX = x
                    dragY = y
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    dragX = x
                    dragY = y
                }
            }

            MotionEvent.ACTION_UP -> {
                if (isDragging && chapaSeleccionada != null) {
                    val dx = chapaSeleccionada!!.x - dragX
                    val dy = chapaSeleccionada!!.y - dragY

                    val distanciaArrastre = min(hypot(dx, dy), 300f)
                    val potencia = distanciaArrastre * 0.08f
                    val angulo = atan2(dy, dx)

                    chapaSeleccionada!!.vx = cos(angulo) * potencia
                    chapaSeleccionada!!.vy = sin(angulo) * potencia

                    shooterChapa = chapaSeleccionada
                    chapasGolpeadas.clear()
                    enemigosExpulsadosEnTurno = 0
                    fichasEnMovimiento = true

                    isDragging = false
                    chapaSeleccionada = null
                }
            }
        }
        return true
    }

    fun update(deltaTime: Float) {
        if (!fichasEnMovimiento) return

        var movimientoDetectado = false
        val rozamiento = 0.96f

        val maxVel = 25f

        chapas.filter { it.enJuego }.forEach { c ->
            c.vx = c.vx.coerceIn(-maxVel, maxVel)
            c.vy = c.vy.coerceIn(-maxVel, maxVel)

            if (abs(c.vx) > 0.05f || abs(c.vy) > 0.05f) {
                movimientoDetectado = true

                c.x += c.vx * deltaTime * 60
                c.y += c.vy * deltaTime * 60

                c.vx *= rozamiento
                c.vy *= rozamiento
            } else {
                c.vx = 0f
                c.vy = 0f
            }
        }

        // ---------------------------------------------------
        // COLISIONES ARCADE SUAVES (SIN EXPLOSIONES)
        // ---------------------------------------------------
        val activas = chapas.filter { it.enJuego }

        for (i in activas.indices) {
            for (j in i + 1 until activas.size) {

                val c1 = activas[i]
                val c2 = activas[j]

                val dx = c2.x - c1.x
                val dy = c2.y - c1.y
                val dist = hypot(dx, dy)
                val minDist = c1.radius + c2.radius

                if (dist < minDist && dist > 0f) {

                    if (c1 == shooterChapa) chapasGolpeadas.add(c2.id)
                    if (c2 == shooterChapa) chapasGolpeadas.add(c1.id)

                    val overlap = min(minDist - dist, c1.radius * 0.3f)

                    val nx = dx / dist
                    val ny = dy / dist

                    val correction = overlap / 2f

                    c1.x -= nx * correction
                    c1.y -= ny * correction
                    c2.x += nx * correction
                    c2.y += ny * correction

                    val rvx = c2.vx - c1.vx
                    val rvy = c2.vy - c1.vy

                    val velAlongNormal = rvx * nx + rvy * ny

                    if (velAlongNormal < 0) {
                        val restitution = 0.25f

                        val impulse = -(1 + restitution) * velAlongNormal / 2f

                        val ix = impulse * nx
                        val iy = impulse * ny

                        c1.vx -= ix
                        c1.vy -= iy
                        c2.vx += ix
                        c2.vy += iy
                    }
                }
            }
        }

        chapas.filter { it.enJuego }.forEach { chapa ->
            val distCentro = hypot(chapa.x - boardCX, chapa.y - boardCY)

            if (distCentro > boardRadius + chapa.radius * 0.5f) {
                chapa.enJuego = false

                if (shooterChapa != null && chapa.esJugador1 != shooterChapa!!.esJugador1) {
                    enemigosExpulsadosEnTurno++
                }
            }
        }

        if (!movimientoDetectado && fichasEnMovimiento) {
            fichasEnMovimiento = false
            evaluarReglasTurno()
        }
    }

    private fun evaluarReglasTurno() {
        val shooter = shooterChapa ?: return
        val esJ1 = shooter.esJugador1

        val amigos = chapas.count { it.id in chapasGolpeadas && it.esJugador1 == esJ1 }
        val enemigos = chapas.count { it.id in chapasGolpeadas && it.esJugador1 != esJ1 }

        var cambiaTurno = true
        var motivo = ""

        when {
            amigos > 0 -> motivo = "¡Fuego amigo! Pierdes turno."

            enemigos > 1 -> motivo = "¡Golpeaste demasiadas! Turno perdido."

            enemigos == 1 && enemigosExpulsadosEnTurno == 0 && shooter.enJuego ->
                motivo = "No la sacaste. Cambio."

            enemigos == 1 && enemigosExpulsadosEnTurno == 1 && shooter.enJuego -> {
                motivo = "¡BUEN TIRO! Repites turno."
                cambiaTurno = false
            }

            else -> motivo = "Cambio de turno."
        }

        mensajeEstado = motivo
        if (cambiaTurno) {
            turnoActual = if (turnoActual == Turno.JUGADOR1)
                Turno.JUGADOR2 else Turno.JUGADOR1
        }
    }

    fun drawGame(canvas: Canvas?) {
        if (canvas == null) return

        canvas.drawBitmap(backgroundBmp, 0f, 0f, null)

        // --- DIBUJAR CHAPAS ---
        chapas.filter { it.enJuego }.forEach { chapa ->
            val bmp = if (chapa.esJugador1) chapaJ1Bmp else chapaJ2Bmp
            val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

            val scaleX = chapa.radius * 2 / bmp.width.toFloat()
            val scaleY = chapa.radius * 2 / bmp.height.toFloat()

            val matrix = Matrix()
            matrix.postScale(scaleX, scaleY)
            matrix.postTranslate(chapa.x - chapa.radius, chapa.y - chapa.radius)
            shader.setLocalMatrix(matrix)

            chapaPaint.shader = shader
            canvas.drawCircle(chapa.x, chapa.y, chapa.radius, chapaPaint)
        }

        // --- LINEA DE DISPARO ---
        if (isDragging && chapaSeleccionada != null) {
            canvas.drawLine(
                chapaSeleccionada!!.x, chapaSeleccionada!!.y,
                dragX, dragY,
                paintLine
            )
        }

        // --- CONTADORES DE FICHAS ---
        val f1 = chapas.count { it.esJugador1 && it.enJuego }
        val f2 = chapas.count { !it.esJugador1 && it.enJuego }

        // --- INDICADOR DE TURNO (FLECHA SUPERIOR) ---
        val flechaResId = if (turnoActual == Turno.JUGADOR1)
            R.drawable.flecha_izquierda // Apunta a J1 (Izquierda)
        else
            R.drawable.flecha_derecha   // Apunta a J2 (Derecha)

        val flechaBmp = BitmapFactory.decodeResource(resources, flechaResId)

        if (flechaBmp != null) {
            // 1. Calcular tamaño deseado (ahora basamos la altura en un porcentaje del alto de la pantalla)
            val desiredHeight = height * 0.15f //
            val ratio = desiredHeight / flechaBmp.height.toFloat() // Calculamos el ratio en base a la altura

            val arrowWidth = flechaBmp.width.toFloat() * ratio
            val arrowHeight = flechaBmp.height.toFloat() * ratio

            // 2. Calcular posición (centrada horizontalmente y más arriba)
            val arrowX = (width - arrowWidth) / 2f
            val arrowY = height * 0.009f //

            // 3. Dibujar
            val destRect = RectF(arrowX, arrowY, arrowX + arrowWidth, arrowY + arrowHeight)
            canvas.drawBitmap(flechaBmp, null, destRect, null)
        }

        // ---------------------------------------------------------------------
        //                    NUEVO MARCADOR ESTILO SELLO JAPONÉS
        // ---------------------------------------------------------------------

        val selloSize = (height * 0.15f).toInt()

        fun drawSello(vidas: Int, posX: Float, posY: Float) {
            val resId = when (vidas.coerceIn(1, 5)) {
                1 -> R.drawable.sello1
                2 -> R.drawable.sello2
                3 -> R.drawable.sello3
                4 -> R.drawable.sello4
                else -> R.drawable.sello5
            }

            val bmp = BitmapFactory.decodeResource(resources, resId)

            // Dibujarlo perfectamente escalado en círculo
            val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val radius = selloSize / 2f

            val scaleX = (radius * 2) / bmp.width.toFloat()
            val scaleY = (radius * 2) / bmp.height.toFloat()

            val m = Matrix()
            m.postScale(scaleX, scaleY)
            m.postTranslate(posX - radius, posY - radius)
            shader.setLocalMatrix(m)

            val p = Paint().apply {
                isAntiAlias = true
                this.shader = shader
            }

            // sombreado suave
            val shadow = Paint().apply {
                color = Color.WHITE
                alpha = 20
                isAntiAlias = true
            }
            canvas.drawCircle(posX + 4, posY + 6, radius * 1.05f, shadow)

            canvas.drawCircle(posX, posY, radius, p)
        }

        // DIBUJAR SELLOS (ambos jugadores)
        drawSello(f1, width * 0.12f, height * 0.43f) // Jugador 1
        drawSello(f2, width * 0.88f, height * 0.43f) // Jugador 2

        // ---------------------------------------------------------------------

        // --- MENSAJE DE FIN DE PARTIDA ---
        paintText.textSize = height * 0.08f

        if (f1 == 0)
            canvas.drawText("¡GANA JUGADOR 2!", width / 2f, height / 2f, paintText)

        if (f2 == 0)
            canvas.drawText("¡GANA JUGADOR 1!", width / 2f, height / 2f, paintText)
    }
}
