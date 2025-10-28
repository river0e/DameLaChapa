package com.mjcastro.damelaschapas

import android.content.Context
import android.graphics.*
import androidx.core.content.res.ResourcesCompat
import kotlin.math.min

enum class EstadoJuego { JUGANDO, VICTORIA, DERROTA }

class GameLogic(private val context: Context) {

    var estado = EstadoJuego.JUGANDO
    private var animFrame = 0
    private val maxAnimFrames = 60
    private var alphaAnim = 0f
    private var textoAnimado = ""
    private var fuente: Typeface? = null

    // Carga fuentes y recursos de texto kanji
    init {
        fuente = ResourcesCompat.getFont(context, R.font.fontsumi)
    }

    // Llamar cada frame para actualizar animaciones de HUD
    fun update() {
        if (estado == EstadoJuego.VICTORIA || estado == EstadoJuego.DERROTA) {
            if (animFrame < maxAnimFrames) {
                animFrame++
                alphaAnim = min(1f, animFrame / maxAnimFrames.toFloat())
            }
        }
    }

    // Dibuja la transición visual (texto japones caligráfico) en medio pantalla
    fun drawTransitionHUD(canvas: Canvas, width: Int, height: Int) {
        if (estado == EstadoJuego.JUGANDO) return

        val paint = Paint().apply {
            color = if (estado == EstadoJuego.VICTORIA) Color.RED else Color.BLACK
            alpha = (255 * alphaAnim).toInt()
            textSize = min(width, height) * 0.4f
            typeface = fuente
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            setShadowLayer(10f, 0f,0f, Color.argb(150,0,0,0))
        }

        if (estado == EstadoJuego.VICTORIA) textoAnimado = "勝"
        else if (estado == EstadoJuego.DERROTA) textoAnimado = "敗"

        canvas.drawText(textoAnimado, width / 2f, height / 2f + paint.textSize / 3f, paint)
    }

    // Llama para iniciar animación victoria o derrota
    fun iniciarVictoria() {
        estado = EstadoJuego.VICTORIA
        animFrame = 0
        alphaAnim = 0f
    }

    fun iniciarDerrota() {
        estado = EstadoJuego.DERROTA
        animFrame = 0
        alphaAnim = 0f
    }

    // Menú minimalista: dibuja botones (circular) con efecto pincel en esquina inferior derecha
    fun drawMenu(canvas: Canvas, width: Int, height: Int, accionReiniciar: () -> Unit) {
        val radio = 80f
        val cx = width - radio - 30f
        val cy = height - radio - 30f

        // Botón círculo (sumi-e pincel)
        val paintBtn = Paint().apply {
            style = Paint.Style.STROKE
            color = Color.BLACK
            strokeWidth = 8f
            isAntiAlias = true
            setShadowLayer(8f, 2f,2f, Color.argb(90,0,0,0))
        }

        canvas.drawCircle(cx, cy, radio, paintBtn)

        // Texto "再" (reiniciar en Kanji)
        val paintText = Paint().apply {
            color = Color.BLACK
            textSize = radio * 1.1f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = fuente
        }
        canvas.drawText("再", cx, cy + paintText.textSize / 3f, paintText)
    }

    // Detectar toque en menú reiniciar
    fun detectarTouchMenu(x: Float, y: Float, width: Int, height: Int): Boolean {
        val radio = 80f
        val cx = width - radio - 30f
        val cy = height - radio - 30f
        val dist = Math.hypot((x - cx).toDouble(), (y - cy).toDouble())
        return dist <= radio
    }
}
