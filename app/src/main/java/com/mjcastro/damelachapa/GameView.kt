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
    var id: Int = 0
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

    private val chapaJ1Bmp by lazy { BitmapFactory.decodeResource(resources, R.drawable.chapa_daruma) }
    private val chapaJ2Bmp by lazy { BitmapFactory.decodeResource(resources, R.drawable.chapa_inari) }

    // NUEVO: Cargamos las flechas UNA sola vez al inicio para que no vaya lento
    private val arrowLeftBmp by lazy { BitmapFactory.decodeResource(resources, R.drawable.flecha_izq_negativa) }
    private val arrowRightBmp by lazy { BitmapFactory.decodeResource(resources, R.drawable.flecha_der_negativa) }

    // NUEVO: Un Paint específico para la flecha
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Imágenes de victoria (se cargan en setupGame)
    private var victoriaJ1Bmp: Bitmap? = null
    private var victoriaJ2Bmp: Bitmap? = null

    // --- VARIABLES DEL JUEGO ---
    private val chapas = mutableListOf<Chapa>()
    private var turnoActual = Turno.JUGADOR1
    private var fichasEnMovimiento = false

    // --- VARIABLES DE ESTADO ---
    private var estadoJuego = EstadoJuego.JUGANDO
    private var ganador = 0 // 1 o 2
    private val botonReiniciarRect = RectF()

    // --- LOGICA DE DISPARO ---
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

    // --- NUEVO: Variable para el trazo del pincel ---
    private val linePath = Path()

    // --- PINCEL DE COLA DE TINTA ---
    private val paintBrush = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL // IMPORTANTE: Relleno, no línea
        isAntiAlias = true
    }

    // Variable para calcular la geometría de la pincelada
    private val brushPath = Path()

    private val chapaPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // --- AUDIO Y VIBRACIÓN ---
    private var soundPool: SoundPool? = null
    private var vibrator: Vibrator? = null

    // IDs de los sonidos cargados
    private var soundClickId = 0
    private var soundChoqueId = 0
    private var soundWinJ1Id = 0
    private var soundWinJ2Id = 0

    // Variable para evitar que el sonido de victoria se repita en bucle
    private var sonidoVictoriaReproducido = false

    private var bgMusic: MediaPlayer? = null

    init {
        holder.addCallback(this)
        isFocusable = true
        initAudio()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (chapas.isEmpty()) {
            // Usamos post para asegurar que width y height ya tienen valor
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

        // Detener el hilo del juego
        thread?.setRunning(false)

        while (retry) {
            try {
                thread?.join()
                retry = false
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }

        // 🔊 Liberar efectos de sonido
        soundPool?.release()
        soundPool = null

        // 🎵 Liberar música de fondo
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
        // 1. Inicializar SoundPool (Compatible con versiones nuevas y viejas)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(5) // Máximo 5 sonidos a la vez
            .setAudioAttributes(audioAttributes)
            .build()

        // 2. Cargar sonidos

        try {
            soundClickId = soundPool!!.load(context, R.raw.start_click, 1)
            soundChoqueId = soundPool!!.load(context, R.raw.choque, 1)
            soundWinJ1Id = soundPool!!.load(context, R.raw.victoria_j1, 1)
            soundWinJ2Id = soundPool!!.load(context, R.raw.victoria_j2, 1)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Inicializar Vibrator (Compatible con Android 12+ y versiones anteriores)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Para Android 12 (API 31) en adelante
            val vibratorManager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
            vibrator = vibratorManager.defaultVibrator
        } else {
            // Para versiones anteriores (Android 11 o inferior)
            @Suppress("DEPRECATION")
            vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    // Función auxiliar para reproducir sonidos fácilmente
    private fun playSound(soundId: Int) {
        if (soundId != 0) {
            // params: id, volIzquierda, volDerecha, prioridad, bucle, velocidad
            soundPool?.play(soundId, 1f, 1f, 0, 0, 1f)
        }
    }

    // Función auxiliar para vibrar
    private fun vibrateImpact() {
        if (vibrator?.hasVibrator() == true) {
            post {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator?.vibrate(
                        VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(
                        VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(70)
                }
            }
        }
    }

    private fun setupGame() {
        // 1. Reiniciar variables lógicas
        sonidoVictoriaReproducido = false
        chapas.clear()
        chapasGolpeadas.clear()
        turnoActual = Turno.JUGADOR1
        fichasEnMovimiento = false
        shooterChapa = null
        estadoJuego = EstadoJuego.JUGANDO // Resetear estado
        ganador = 0

        // 2. Definir dimensiones tablero
        boardCX = width / 2f
        boardCY = height * 0.53f

        // Tomamos el menor lado para que el círculo nunca se deforme
        val minSide = min(width, height)

        // Escalamos el radio en base al lado más pequeño
        boardRadius = minSide * 0.35f   // puedes subir a 0.40f si quieres ocupar más pantalla

        // El tamaño de la chapa lo calculamos en base a la altura (Y) para que no queden gigantes
        val radioChapa = boardRadius * 0.12f

        // 3. Crear fichas iniciales

        for (i in 0 until 5) {
            val spacing = radioChapa * 2.5f
            val startY = boardCY - (spacing * 2) + (spacing * i)
            chapas.add(Chapa(boardCX - boardRadius * 0.5f, startY, radioChapa, true, id = i))
            chapas.add(Chapa(boardCX + boardRadius * 0.5f, startY, radioChapa, false, id = i + 10))
        }

        // 4. Cargar imágenes de victoria
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

        // 5. Definir área del botón "Volver a Jugar" (Parte inferior central)
        val buttonWidth = width * 0.6f
        val buttonHeight = height * 0.15f
        val buttonX = (width - buttonWidth) / 2f
        val buttonY = height * 0.82f
        botonReiniciarRect.set(buttonX, buttonY, buttonX + buttonWidth, buttonY + buttonHeight)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {

        val x = event.x
        val y = event.y

        // --- NUEVO: Lógica para Pantalla de Victoria ---
        if (estadoJuego == EstadoJuego.GAME_OVER) {
            if (event.action == MotionEvent.ACTION_DOWN) {
                // Si tocan dentro del área del botón "Volver a Jugar"
                if (botonReiniciarRect.contains(x, y)) {
                    playSound(soundClickId) // <--- SONIDO CLICK
                    setupGame() // Reinicia el juego
                }
            }
            return true // Consumir el evento
        }
        // -----------------------------------------------

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

                    val distanciaArrastre = min(hypot(dx, dy), 300f)
                    val potencia = distanciaArrastre * 2f
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
        val rozamiento = 0.90f
        val maxVel = 300f
        val VELOCIDAD_MINIMA_PARA_MOVER = 5f

        chapas.filter { it.enJuego }.forEach { c ->
            c.vx = c.vx.coerceIn(-maxVel, maxVel)
            c.vy = c.vy.coerceIn(-maxVel, maxVel)

            // CAMBIO AQUÍ: Usamos un umbral de velocidad más alto
            if (abs(c.vx) > VELOCIDAD_MINIMA_PARA_MOVER || abs(c.vy) > VELOCIDAD_MINIMA_PARA_MOVER) {
                movimientoDetectado = true
                c.x += c.vx * deltaTime
                c.y += c.vy * deltaTime
                c.vx *= rozamiento
                c.vy *= rozamiento
            } else {
                // Si la velocidad es menor que el umbral, la consideramos parada y la fijamos a cero.
                c.vx = 0f
                c.vy = 0f
            }
        }

        // El resto de la lógica de fin de movimiento se mantiene igual
        if (!movimientoDetectado && fichasEnMovimiento) {
            fichasEnMovimiento = false
            evaluarReglasTurno()
        }

        // COLISIONES
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
                    // Solo reproducir si no estaban ya solapadas en el frame anterior (opcional, pero recomendado)
                    // Para simplificar, lo ponemos directo:
                    playSound(soundChoqueId)
                    vibrateImpact()
                    // -------------------
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
                        c1.vx -= impulse * nx
                        c1.vy -= impulse * ny
                        c2.vx += impulse * nx
                        c2.vy += impulse * ny
                    }
                }
            }
        }

        // LIMITE TABLERO

        chapas.filter { it.enJuego }.forEach { chapa ->
            val distCentro = hypot(chapa.x - boardCX, chapa.y - boardCY)
            if (distCentro > boardRadius + chapa.radius * 0.5f) {
                chapa.enJuego = false
                if (shooterChapa != null && chapa.esJugador1 != shooterChapa!!.esJugador1) {
                    enemigosExpulsadosEnTurno++
                }
            }
        }

        // --- REVISAR CONDICIÓN DE VICTORIA ---
        val f1 = chapas.count { it.esJugador1 && it.enJuego }
        val f2 = chapas.count { !it.esJugador1 && it.enJuego }

        if (f1 == 0) {
            estadoJuego = EstadoJuego.GAME_OVER
            ganador = 2 // Gana J2
            fichasEnMovimiento = false
            // --- SONIDO VICTORIA J2 ---
            if (!sonidoVictoriaReproducido) {
                playSound(soundWinJ2Id)
                sonidoVictoriaReproducido = true
            }
            return
        } else if (f2 == 0) {
            estadoJuego = EstadoJuego.GAME_OVER
            ganador = 1 // Gana J1
            fichasEnMovimiento = false
            // --- SONIDO VICTORIA J1 ---
            if (!sonidoVictoriaReproducido) {
                playSound(soundWinJ1Id)
                sonidoVictoriaReproducido = true
            }
            return
        }
        // -------------------------------------

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
        if (cambiaTurno) {
            turnoActual = if (turnoActual == Turno.JUGADOR1) Turno.JUGADOR2 else Turno.JUGADOR1
        }
    }

    fun drawGame(canvas: Canvas?) {
        if (canvas == null) return

        // --- 1. Si es Game Over, dibujar solo la imagen de victoria ---
        if (estadoJuego == EstadoJuego.GAME_OVER) {
            if (ganador == 1 && victoriaJ1Bmp != null) {
                canvas.drawBitmap(victoriaJ1Bmp!!, 0f, 0f, null)
            } else if (ganador == 2 && victoriaJ2Bmp != null) {
                canvas.drawBitmap(victoriaJ2Bmp!!, 0f, 0f, null)
            }
            return // IMPORTANTE: Salimos para no dibujar nada más
        }

        // --- 2. Dibujar Juego Normal ---
        canvas.drawBitmap(backgroundBmp, 0f, 0f, null)

        // CHAPAS
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

        // --- DIBUJAR LA PINCELADA SUMI-E (COLA DE TINTA) ---
        if (isDragging && chapaSeleccionada != null) {
            val chapa = chapaSeleccionada!!

            // 1. Calcular el ángulo y la distancia entre la chapa y el dedo
            val dx = dragX - chapa.x
            val dy = dragY - chapa.y
            val dist = hypot(dx, dy)
            val angle = atan2(dy, dx)

            // Si la distancia es muy pequeña, no dibujamos nada para evitar errores
            if (dist > 20f) {
                brushPath.reset()

                // 2. Configuración del grosor de la pincelada
                // baseWidth: Qué tan ancha es la tinta pegada a la chapa (ajusta a tu gusto)
                val baseWidth = 35f

                // 3. Calcular el PUNTO DE INICIO (En el borde de la chapa, no en el centro)
                // Nos movemos desde el centro en la dirección del ángulo por el radio de la chapa
                val startX =
                    chapa.x + cos(angle) * (chapa.radius * 0.8f) // 0.8f para que se meta un pelín debajo
                val startY = chapa.y + sin(angle) * (chapa.radius * 0.8f)

                // 4. Calcular los puntos de la BASE del triángulo (perpendiculares al ángulo)
                // Esto le da el grosor al inicio
                val anglePerpendicular = angle + (PI / 2).toFloat()
                val x1 = startX + cos(anglePerpendicular) * (baseWidth / 2)
                val y1 = startY + sin(anglePerpendicular) * (baseWidth / 2)
                val x2 = startX - cos(anglePerpendicular) * (baseWidth / 2)
                val y2 = startY - sin(anglePerpendicular) * (baseWidth / 2)

                // 5. El punto FINAL es tu dedo (dragX, dragY)
                // Creamos el camino: Esquina 1 -> Dedo -> Esquina 2 -> Cerrar
                brushPath.moveTo(x1, y1)
                brushPath.lineTo(dragX, dragY)
                brushPath.lineTo(x2, y2)
                brushPath.close()

                // 6. EL TOQUE MAESTRO: Degradado (Shader)
                // Hacemos que la tinta se vaya volviendo transparente hacia el final (efecto pincel seco)
                // Color inicio: Negro (#FF000000) -> Color fin: Transparente (#00000000)
                paintBrush.shader = LinearGradient(
                    startX, startY, dragX, dragY,
                    Color.parseColor("#E6111111"), // Negro casi sólido (90% opacidad)
                    Color.TRANSPARENT,             // Transparente al final
                    Shader.TileMode.CLAMP
                )

                // 7. Dibujar la forma
                canvas.drawPath(brushPath, paintBrush)
            }
        }

        // MARCADOR (SELLOS)
        val f1 = chapas.count { it.esJugador1 && it.enJuego }
        val f2 = chapas.count { !it.esJugador1 && it.enJuego }

        // Dibujar sellos
        val selloSize = (height * 0.15f).toInt()
        drawSello(canvas, f1, width * 0.12f, height * 0.43f, selloSize)
        drawSello(canvas, f2, width * 0.88f, height * 0.43f, selloSize)

        // --- FLECHA TURNO (ESTILO CHAPA/SELLO) ---

        val currentArrowBmp = if (turnoActual == Turno.JUGADOR1) arrowLeftBmp else arrowRightBmp

        if (currentArrowBmp != null) {
            // Configuración de tamaño y posición (Esto sigue igual)
            val arrowDiameter = height * 0.15f
            val arrowRadius = arrowDiameter / 2f
            val arrowCX = width / 2f
            val arrowCY = (height * 0.009f) + arrowRadius

            val shader = BitmapShader(currentArrowBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

            // --- INICIO DE LA CORRECCIÓN DE PROPORCIÓN ---

            val bmpWidth = currentArrowBmp.width.toFloat()
            val bmpHeight = currentArrowBmp.height.toFloat()

            // 1. Calculamos cuánto hay que escalar para llenar el ancho y el alto
            val scaleXNeeded = arrowDiameter / bmpWidth
            val scaleYNeeded = arrowDiameter / bmpHeight

            // 2. CORRECCIÓN IMPORTANTE: Elegimos la escala MAYOR para asegurar que
            // la imagen llene todo el círculo sin deformarse (Center Crop).
            // Usamos la misma escala para ambos ejes.
            val finalScale = max(scaleXNeeded, scaleYNeeded)

            val matrix = Matrix()
            // Aplicamos la escala uniforme
            matrix.postScale(finalScale, finalScale)

            // 3. Calculamos el desplazamiento para centrar la imagen escalada
            val scaledBitmapWidth = bmpWidth * finalScale
            val scaledBitmapHeight = bmpHeight * finalScale
            val dx = (arrowDiameter - scaledBitmapWidth) / 2f
            val dy = (arrowDiameter - scaledBitmapHeight) / 2f

            // Movemos la imagen para centrarla en su caja y luego a la posición final en pantalla
            matrix.postTranslate(dx + (arrowCX - arrowRadius), dy + (arrowCY - arrowRadius))

            // --- FIN DE LA CORRECCIÓN ---

            shader.setLocalMatrix(matrix)
            arrowPaint.shader = shader

            // Dibujamos el círculo final. Ahora la textura interna no debería estar deformada.
            canvas.drawCircle(arrowCX, arrowCY, arrowRadius, arrowPaint)
        }
    }

    // Función auxiliar para dibujar sellos (sacada fuera para limpieza)
    private fun drawSello(canvas: Canvas, vidas: Int, posX: Float, posY: Float, size: Int) {
        val resId = when (vidas.coerceIn(1, 5)) {
            1 -> R.drawable.sello1
            2 -> R.drawable.sello2
            3 -> R.drawable.sello3
            4 -> R.drawable.sello4
            else -> R.drawable.sello5
        }

        val bmp = BitmapFactory.decodeResource(resources, resId) ?: return
        val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val radius = size / 2f
        val scaleX = (radius * 2) / bmp.width.toFloat()
        val scaleY = (radius * 2) / bmp.height.toFloat()

        val m = Matrix()
        m.postScale(scaleX, scaleY)
        m.postTranslate(posX - radius, posY - radius)
        shader.setLocalMatrix(m)

        val p = Paint().apply { isAntiAlias = true; this.shader = shader }
        val shadow = Paint().apply { color = Color.WHITE; alpha = 20; isAntiAlias = true }

        canvas.drawCircle(posX + 4, posY + 6, radius * 1.05f, shadow)
        canvas.drawCircle(posX, posY, radius, p)
    }
}