package com.mjcastro.damelachapa

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.*
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

enum class Turno { JUGADOR1, JUGADOR2 }
enum class EstadoJuego { JUGANDO, GAME_OVER }

data class Chapa(
    var x: Float, var y: Float, val radius: Float,
    val esJugador1: Boolean,
    var vx: Float = 0f, var vy: Float = 0f,
    var enJuego: Boolean = true,
    var id: Int = 0,
    var alpha: Float = 1f
)

class GameView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private var thread: GameThread? = null

    // --- RECURSOS GRÁFICOS ---
    private val backgroundBmp by lazy {
        val original = BitmapFactory.decodeResource(resources, R.drawable.game_background_landscape)
        Bitmap.createScaledBitmap(original, width, height, true)
    }

    // --- LOGO ---
    private val logoBmp by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.mi_logo)
    }
    private val paintLogo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chapaJ1Bmp by lazy { BitmapFactory.decodeResource(resources, R.drawable.chapa_daruma) }
    private val chapaJ2Bmp by lazy { BitmapFactory.decodeResource(resources, R.drawable.chapa_inari) }

    private val arrowLeftBmp by lazy { BitmapFactory.decodeResource(resources, R.drawable.flecha_izq_negativa) }
    private val arrowRightBmp by lazy { BitmapFactory.decodeResource(resources, R.drawable.flecha_der_negativa) }

    // --- VARIABLES REUTILIZABLES ---
    // He sacado estas variables aquí arriba para aplicar la regla de "Reciclar es vivir".
    // Al tenerlas creadas aquí, evito hacer 'new Matrix()' o 'new Paint()' 60 veces por segundo en el draw.

    // 1. Matriz compartida para todas las transformaciones del juego.
    private val sharedMatrix = Matrix()

    // 2. Shaders pre-cargados. En lugar de crear el BitmapShader en cada frame, lo guardo aquí.
    private var shaderChapaJ1: BitmapShader? = null
    private var shaderChapaJ2: BitmapShader? = null
    private var shaderArrowLeft: BitmapShader? = null
    private var shaderArrowRight: BitmapShader? = null

    // 3. Array para guardar los shaders de los 5 sellos y no cargarlos de disco continuamente.
    private val shadersSellos = arrayOfNulls<BitmapShader>(5)

    // 4. Lista auxiliar para recordar cuánto medían los sellos originales (ancho, alto) y escalar bien.
    private val sellosDimensions = mutableListOf<Pair<Int, Int>>()

    // 5. Paints reciclables. Crear Paints es costoso, mejor reusar estos dos.
    private val paintSello = Paint().apply { isAntiAlias = true }
    private val paintSelloSombra = Paint().apply {
        color = Color.WHITE
        alpha = 20
        isAntiAlias = true
    }

    // Un Paint específico para la flecha
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val ondaPaintJ1 = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        color = Color.parseColor("#AA660000") // rojo oscuro semitransparente
        isAntiAlias = true
    }

    private val ondaPaintJ2 = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        color = Color.parseColor("#CCEEEEEE") // blanco roto intenso
        isAntiAlias = true
    }

    // Imágenes de victoria
    private var victoriaJ1Bmp: Bitmap? = null
    private var victoriaJ2Bmp: Bitmap? = null

    //Efecto explosivo al salir la chapa del tablero

    data class EfectoOnda(
        val x: Float,
        val y: Float,
        var radio: Float = 20f,
        var alpha: Float = 1f,
        val esJugador1: Boolean
    )

    private val ondas = mutableListOf<EfectoOnda>()


    // --- VARIABLES DEL JUEGO ---
    private val chapas = mutableListOf<Chapa>()
    private var turnoActual = Turno.JUGADOR1
    private var fichasEnMovimiento = false

    // --- VARIABLES DE ESTADO ---
    private var estadoJuego = EstadoJuego.JUGANDO
    private var ganador = 0
    private val botonReiniciarRect = RectF()

    // --- LOGICA DE DISPARO ---
    private var shooterChapa: Chapa? = null
    private val chapasGolpeadas = mutableSetOf<Int>()
    private var enemigosExpulsadosEnTurno = 0

    private var boardCX = 0f
    private var boardCY = 0f
    private var boardRadiusX = 0f
    private var boardRadiusY = 0f
    private var chapaSeleccionada: Chapa? = null
    private var dragX = 0f
    private var dragY = 0f
    private var isDragging = false

    private val linePath = Path()

    // --- PINCEL DE COLA DE TINTA ---
    private val paintBrush = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val brushPath = Path()
    private val chapaPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // --- AUDIO Y VIBRACIÓN ---
    private var soundPool: SoundPool? = null
    private var vibrator: Vibrator? = null

    private var soundClickId = 0
    private var soundChoqueId = 0
    private var soundWinJ1Id = 0
    private var soundWinJ2Id = 0
    private var soundExpulsionId = 0

    private var sonidoVictoriaReproducido = false
    private var bgMusic: MediaPlayer? = null

    init {
        holder.addCallback(this)
        isFocusable = true
        initAudio()
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
        soundPool?.release()
        soundPool = null
        bgMusic?.stop()
        bgMusic?.release()
        bgMusic = null
    }

    private fun initMusic() {
        bgMusic = MediaPlayer.create(context, R.raw.musica_fondo)
        bgMusic?.isLooping = true
        bgMusic?.setVolume(0.25f, 0.25f)
        bgMusic?.start()
    }

    private fun initAudio() {
        initMusic()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(5)
            .setAudioAttributes(audioAttributes)
            .build()

        try {
            soundClickId = soundPool!!.load(context, R.raw.start_click, 1)
            soundChoqueId = soundPool!!.load(context, R.raw.choque, 1)
            soundWinJ1Id = soundPool!!.load(context, R.raw.victoria_j1, 1)
            soundWinJ2Id = soundPool!!.load(context, R.raw.victoria_j2, 1)
            soundExpulsionId = soundPool!!.load(context, R.raw.expulsion, 1)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
            vibrator = vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun playSound(soundId: Int) {
        if (soundId != 0) {
            soundPool?.play(soundId, 1f, 1f, 0, 0, 1f)
        }
    }

    private fun vibrateImpact() {
        if (vibrator?.hasVibrator() == true) {
            post {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(70)
                }
            }
        }
    }

    // --- INIT GRAPHICS ---
    // He creado esta función para cargar todos los recursos pesados (Bitmaps y Shaders) UNA SOLA VEZ.
    // Antes esto se hacía en el drawGame, causando los tirones. Ahora preparamos la "paleta de pintura" al inicio.
    private fun initGraphics() {
        // A. Prepara Shaders de Chapas
        if (shaderChapaJ1 == null) shaderChapaJ1 = BitmapShader(chapaJ1Bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        if (shaderChapaJ2 == null) shaderChapaJ2 = BitmapShader(chapaJ2Bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

        // B. Prepara Shaders de Flechas
        if (shaderArrowLeft == null && arrowLeftBmp != null) {
            shaderArrowLeft = BitmapShader(arrowLeftBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        if (shaderArrowRight == null && arrowRightBmp != null) {
            shaderArrowRight = BitmapShader(arrowRightBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }

        // C. Prepara Shaders de Sellos (ESTA ERA LA CAUSA PRINCIPAL DEL LAG)
        // Cargamos los 5 sellos ahora y nunca más tocamos el disco duro durante la partida.
        val resourcesIds = listOf(R.drawable.sello1, R.drawable.sello2, R.drawable.sello3, R.drawable.sello4, R.drawable.sello5)

        // Solo cargamos si la lista de dimensiones está vacía (para no repetir si reiniciamos)
        if (sellosDimensions.isEmpty()) {
            for (i in resourcesIds.indices) {
                if (shadersSellos[i] == null) {
                    val bmp = BitmapFactory.decodeResource(resources, resourcesIds[i])
                    if (bmp != null) {
                        shadersSellos[i] = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                        // Guardo el tamaño para saber escalarlo luego sin necesitar el bitmap
                        sellosDimensions.add(Pair(bmp.width, bmp.height))
                    }
                }
            }
        }
    }

    private fun setupGame() {
        sonidoVictoriaReproducido = false
        chapas.clear()
        chapasGolpeadas.clear()
        turnoActual = Turno.JUGADOR1
        fichasEnMovimiento = false
        shooterChapa = null
        estadoJuego = EstadoJuego.JUGANDO
        ganador = 0

        // Llamo a la carga de gráficos aquí para asegurarme de que todo esté listo antes de pintar el primer frame.
        initGraphics()

        boardCX = width * 0.505f
        boardCY = height * 0.525f
        boardRadiusY = height * 0.31f
        boardRadiusX = width * 0.185f

        val radioChapa = boardRadiusY * 0.12f

        for (i in 0 until 5) {
            val spacing = radioChapa * 2.5f
            val startY = boardCY - (spacing * 2) + (spacing * i)
            chapas.add(Chapa(boardCX - boardRadiusX * 0.5f, startY, radioChapa, true, id = i))
            chapas.add(Chapa(boardCX + boardRadiusX * 0.5f, startY, radioChapa, false, id = i + 10))
        }

        if (victoriaJ1Bmp == null) {
            try {
                val originalJ1 = BitmapFactory.decodeResource(resources, R.drawable.victoria_p1)
                victoriaJ1Bmp = Bitmap.createScaledBitmap(originalJ1, width, height, true)
            } catch (e: Exception) { e.printStackTrace() }
        }
        if (victoriaJ2Bmp == null) {
            try {
                val originalJ2 = BitmapFactory.decodeResource(resources, R.drawable.victoria_p2)
                victoriaJ2Bmp = Bitmap.createScaledBitmap(originalJ2, width, height, true)
            } catch (e: Exception) { e.printStackTrace() }
        }

        val buttonWidth = width * 0.6f
        val buttonHeight = height * 0.15f
        val buttonX = (width - buttonWidth) / 2f
        val buttonY = height * 0.82f
        botonReiniciarRect.set(buttonX, buttonY, buttonX + buttonWidth, buttonY + buttonHeight)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        if (estadoJuego == EstadoJuego.GAME_OVER) {
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (botonReiniciarRect.contains(x, y)) {
                    playSound(soundClickId)
                    setupGame()
                }
            }
            return true
        }

        if (fichasEnMovimiento) return true

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
                    val distancia = hypot(dx, dy)
                    val distanciaMax = 450f

                    if (distancia > 0f) {
                        val nx = dx / distancia
                        val ny = dy / distancia
                        val distNorm = min(distancia / distanciaMax, 1f)

                        val fuerzaMaxima = 2000f
                        val potencia = (distNorm * distNorm * distNorm) * fuerzaMaxima

                        chapaSeleccionada!!.vx = nx * potencia
                        chapaSeleccionada!!.vy = ny * potencia

                        shooterChapa = chapaSeleccionada
                        chapasGolpeadas.clear()
                        enemigosExpulsadosEnTurno = 0
                        fichasEnMovimiento = true
                        isDragging = false
                        chapaSeleccionada = null
                    }
                }
            }
        }
        return true
    }

    fun update(deltaTime: Float) {
        if (!fichasEnMovimiento) return

        var movimientoDetectado = false
        val maxVel = 2000f
        val VELOCIDAD_MINIMA_PARA_MOVER = 100f

        chapas.filter { it.enJuego }.forEach { c ->

            val velocidadTotalInicial = hypot(c.vx, c.vy)
            if (velocidadTotalInicial > maxVel) {
                val factorEscala = maxVel / velocidadTotalInicial
                c.vx *= factorEscala
                c.vy *= factorEscala
            }

            val currentSpeed = hypot(c.vx, c.vy)

            if (currentSpeed > VELOCIDAD_MINIMA_PARA_MOVER) {
                movimientoDetectado = true
                c.x += c.vx * deltaTime
                c.y += c.vy * deltaTime

                var friccion = 0.98f
                if (currentSpeed < 1000f) friccion = 0.92f
                if (currentSpeed < 400f) friccion = 0.85f

                c.vx *= friccion
                c.vy *= friccion
            } else {
                c.vx = 0f
                c.vy = 0f
            }
        }

        if (!movimientoDetectado && fichasEnMovimiento) {
            fichasEnMovimiento = false
            evaluarReglasTurno()
        }

        // --- COLISIONES ---
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
                    playSound(soundChoqueId)
                    vibrateImpact()

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
                        val restitution = 0.5f
                        val impulse = -(1 + restitution) * velAlongNormal / 2f
                        c1.vx -= impulse * nx
                        c1.vy -= impulse * ny
                        c2.vx += impulse * nx
                        c2.vy += impulse * ny

                        val factorFrenadoChoque = 0.8f
                        c1.vx *= factorFrenadoChoque
                        c1.vy *= factorFrenadoChoque
                        c2.vx *= factorFrenadoChoque
                        c2.vy *= factorFrenadoChoque
                    }
                }
            }
        }

        // --- LIMITE TABLERO ---
        chapas.filter { it.enJuego }.forEach { chapa ->
            val dx = chapa.x - boardCX
            val dy = chapa.y - boardCY
            val limiteX = boardRadiusX + (chapa.radius * 0.5f)
            val limiteY = boardRadiusY + (chapa.radius * 0.5f)
            val estaFuera = (dx / limiteX).pow(2) + (dy / limiteY).pow(2) > 1.0

            if (estaFuera) { chapa.enJuego = false

                //Añado efectos de sonido y visuales para la expulsión de la chapa del tablero
                ondas.add(EfectoOnda(chapa.x, chapa.y, esJugador1 = chapa.esJugador1))
                playSound(soundExpulsionId)

                if (shooterChapa != null && chapa.esJugador1 != shooterChapa!!.esJugador1) { enemigosExpulsadosEnTurno++ } } }

        // --- CONDICIÓN DE VICTORIA ---
        val f1 = chapas.count { it.esJugador1 && it.enJuego }
        val f2 = chapas.count { !it.esJugador1 && it.enJuego }

        if (f1 == 0) {
            estadoJuego = EstadoJuego.GAME_OVER; ganador = 2; fichasEnMovimiento = false
            if (!sonidoVictoriaReproducido) { playSound(soundWinJ2Id); sonidoVictoriaReproducido = true }
        } else if (f2 == 0) {
            estadoJuego = EstadoJuego.GAME_OVER; ganador = 1; fichasEnMovimiento = false
            if (!sonidoVictoriaReproducido) { playSound(soundWinJ1Id); sonidoVictoriaReproducido = true }
        }
    }

    private fun evaluarReglasTurno() {
        val shooter = shooterChapa ?: return
        val esJ1 = shooter.esJugador1
        val amigos = chapasGolpeadas.count { id -> chapas.any { it.id == id && it.esJugador1 == esJ1 } }
        val enemigos = chapasGolpeadas.count { id -> chapas.any { it.id == id && it.esJugador1 != esJ1 } }

        val repiteTurno = enemigos == 1 && enemigosExpulsadosEnTurno == 1 && shooter.enJuego
        val pierdeTurno = amigos > 0 || enemigos > 1 || (enemigos == 1 && enemigosExpulsadosEnTurno == 0) || enemigos == 0

        when {
            repiteTurno -> return
            pierdeTurno -> cambiarTurno()
            else -> cambiarTurno()
        }
    }

    private fun cambiarTurno() {
        turnoActual = if (turnoActual == Turno.JUGADOR1) Turno.JUGADOR2 else Turno.JUGADOR1
    }

    // --- DRAWGAME REFACTORIZADO ---
    // He eliminado todos los 'new' y 'BitmapFactory' de aquí.
    // Ahora solo uso los recursos que ya preparamos en initGraphics().

    fun drawGame(canvas: Canvas?) {
        if (canvas == null) return

        // --- 1. Game Over ---
        if (estadoJuego == EstadoJuego.GAME_OVER) {
            if (ganador == 1 && victoriaJ1Bmp != null) {
                canvas.drawBitmap(victoriaJ1Bmp!!, 0f, 0f, null)
            } else if (ganador == 2 && victoriaJ2Bmp != null) {
                canvas.drawBitmap(victoriaJ2Bmp!!, 0f, 0f, null)
            }
            return
        }

        // --- 2. Fondo ---
        canvas.drawBitmap(backgroundBmp, 0f, 0f, null)

        // --- 3. DIBUJAR CHAPAS (Optimizado) ---
        chapas.filter { it.enJuego }.forEach { chapa ->
            // Selecciono el shader ya creado, no creo uno nuevo.
            val shader = if (chapa.esJugador1) shaderChapaJ1 else shaderChapaJ2
            val bmpWidth = if (chapa.esJugador1) chapaJ1Bmp.width else chapaJ2Bmp.width
            val bmpHeight = if (chapa.esJugador1) chapaJ1Bmp.height else chapaJ2Bmp.height

            if (shader != null) {
                // RESETEAR matriz compartida (clave del rendimiento, reciclamos la misma matriz)
                sharedMatrix.reset()

                val scaleX = chapa.radius * 2 / bmpWidth
                val scaleY = chapa.radius * 2 / bmpHeight

                sharedMatrix.postScale(scaleX, scaleY)
                sharedMatrix.postTranslate(chapa.x - chapa.radius, chapa.y - chapa.radius)

                shader.setLocalMatrix(sharedMatrix)
                chapaPaint.shader = shader
                // Aplicar alpha al paint antes de dibujar
                val easedAlpha = chapa.alpha * chapa.alpha   // ease-out

                canvas.drawCircle(chapa.x, chapa.y, chapa.radius, chapaPaint)
            }
        }

        // --- 4. DIBUJAR PINCELADA (Sumi-e) ---

        if (isDragging && chapaSeleccionada != null) {
            val chapa = chapaSeleccionada!!
            val dx = dragX - chapa.x
            val dy = dragY - chapa.y
            val dist = hypot(dx, dy)

            if (dist > 20f) {
                val angle = atan2(dy, dx)
                brushPath.reset() // Importante resetear

                val baseWidth = 35f
                val startX = chapa.x + cos(angle) * (chapa.radius * 0.8f)
                val startY = chapa.y + sin(angle) * (chapa.radius * 0.8f)
                val anglePerpendicular = angle + (PI / 2).toFloat()

                val x1 = startX + cos(anglePerpendicular) * (baseWidth / 2)
                val y1 = startY + sin(anglePerpendicular) * (baseWidth / 2)
                val x2 = startX - cos(anglePerpendicular) * (baseWidth / 2)
                val y2 = startY - sin(anglePerpendicular) * (baseWidth / 2)

                brushPath.moveTo(x1, y1)
                brushPath.lineTo(dragX, dragY)
                brushPath.lineTo(x2, y2)
                brushPath.close()

                paintBrush.shader = LinearGradient(
                    startX, startY, dragX, dragY,
                    Color.parseColor("#E68B0000"),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
                canvas.drawPath(brushPath, paintBrush)
            }
        }

        // --- 4.5 EFECTOS DE EXPULSIÓN ---
        val itOnda = ondas.iterator()
        while (itOnda.hasNext()) {
            val o = itOnda.next()

            // Seleccionar paint según jugador
            val paint = if (o.esJugador1) ondaPaintJ1 else ondaPaintJ2

            // Aplicar alpha dinámico
            paint.alpha = (o.alpha * 255).toInt()

            // Dibujar onda expansiva
            canvas.drawCircle(o.x, o.y, o.radio, paint)

            // Animación
            o.radio += 14f        // velocidad explosiva fuerte
            o.alpha -= 0.08f      // se desvanece rápido

            if (o.alpha <= 0f) itOnda.remove()
        }

        // --- 5. MARCADOR (Optimizado) ---
        val f1 = chapas.count { it.esJugador1 && it.enJuego }
        val f2 = chapas.count { !it.esJugador1 && it.enJuego }
        val selloSize = (height * 0.25f).toInt()

        // Llamamos a la nueva función optimizada
        drawSelloOptimizado(canvas, f1, width * 0.18f, height * 0.52f, selloSize)
        drawSelloOptimizado(canvas, f2, width * 0.82f, height * 0.52f, selloSize)

        // --- 6. FLECHA TURNO (Optimizado) ---
        val currentArrowShader = if (turnoActual == Turno.JUGADOR1) shaderArrowLeft else shaderArrowRight
        val currentArrowBmp = if (turnoActual == Turno.JUGADOR1) arrowLeftBmp else arrowRightBmp

        if (currentArrowShader != null && currentArrowBmp != null) {
            val arrowDiameter = height * 0.15f
            val arrowRadius = arrowDiameter / 2f
            val arrowCX = width / 2f
            val arrowCY = (height * 0.009f) + arrowRadius

            val bmpWidth = currentArrowBmp.width.toFloat()
            val bmpHeight = currentArrowBmp.height.toFloat()

            val scaleXNeeded = arrowDiameter / bmpWidth
            val scaleYNeeded = arrowDiameter / bmpHeight
            val finalScale = max(scaleXNeeded, scaleYNeeded)

            sharedMatrix.reset() // Reciclamos matriz
            sharedMatrix.postScale(finalScale, finalScale)

            val scaledBitmapWidth = bmpWidth * finalScale
            val scaledBitmapHeight = bmpHeight * finalScale
            val dx = (arrowDiameter - scaledBitmapWidth) / 2f
            val dy = (arrowDiameter - scaledBitmapHeight) / 2f

            sharedMatrix.postTranslate(dx + (arrowCX - arrowRadius), dy + (arrowCY - arrowRadius))

            currentArrowShader.setLocalMatrix(sharedMatrix)
            arrowPaint.shader = currentArrowShader
            canvas.drawCircle(arrowCX, arrowCY, arrowRadius, arrowPaint)
        }

        // --- 7. LOGO INFERIOR DERECHO ---
        logoBmp?.let { bmp ->
            val logoDiameter = height * 0.12f
            val radius = logoDiameter / 2f
            val posX = width - radius - 20f
            val posY = height - radius - 20f

            sharedMatrix.reset()
            sharedMatrix.postScale(logoDiameter / bmp.width.toFloat(), logoDiameter / bmp.height.toFloat())
            sharedMatrix.postTranslate(posX - radius, posY - radius)

            val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setLocalMatrix(sharedMatrix)
            paintLogo.shader = shader

            canvas.drawCircle(posX, posY, radius, paintLogo)
        }
    }


    // --- NUEVA FUNCIÓN DE SELLOS ---
    // Esta función sustituye a la antigua. Usamos los shaders del array 'shadersSellos'
    // y las dimensiones guardadas en 'sellosDimensions'. Así no leemos el PNG otra vez.
    private fun drawSelloOptimizado(canvas: Canvas, vidas: Int, posX: Float, posY: Float, size: Int) {
        val index = (vidas - 1).coerceIn(0, 4)

        // Recuperamos shader y dimensiones de memoria
        val shader = shadersSellos[index] ?: return
        // Si por lo que sea no se cargaron dimensiones (ej. error de lectura), salimos para no fallar
        if (index >= sellosDimensions.size) return
        val (bmpW, bmpH) = sellosDimensions[index]

        sharedMatrix.reset()

        val radius = size / 2f
        val scaleX = (radius * 2) / bmpW.toFloat()
        val scaleY = (radius * 2) / bmpH.toFloat()

        sharedMatrix.postScale(scaleX, scaleY)
        sharedMatrix.postTranslate(posX - radius, posY - radius)

        shader.setLocalMatrix(sharedMatrix)
        paintSello.shader = shader

        // Usamos los Paints reutilizables
        canvas.drawCircle(posX + 4, posY + 6, radius * 1.05f, paintSelloSombra)
        canvas.drawCircle(posX, posY, radius, paintSello)
    }
}