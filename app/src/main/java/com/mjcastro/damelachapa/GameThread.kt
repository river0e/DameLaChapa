package com.mjcastro.damelachapa

import android.graphics.Canvas
import android.view.SurfaceHolder

class GameThread(
    private val surfaceHolder: SurfaceHolder,
    private val gameView: GameView
) : Thread() {

    private var running = false

    // Objetivo de FPS para no quemar batería (60 FPS)
    private val targetFPS = 60
    private val targetTime = 1000L / targetFPS

    fun setRunning(isRunning: Boolean) {
        running = isRunning
    }

    override fun run() {
        var startTime: Long
        var timeMillis: Long
        var waitTime: Long

        // Variable para controlar el tiempo real
        var lastTime = System.nanoTime()

        while (running) {
            startTime = System.nanoTime()
            var canvas: Canvas? = null

            try {
                canvas = surfaceHolder.lockCanvas()
                if (canvas != null) {
                    synchronized(surfaceHolder) {
                        // 1. CALCULAR DELTA TIME REAL
                        // Cuánto tiempo ha pasado desde la última vuelta (en segundos)
                        val now = System.nanoTime()
                        // Dividimos por mil millones para pasar de nanos a segundos
                        // .coerceAtMost(0.05f) evita saltos gigantes si el juego se queda pillado un momento
                        val deltaTime = ((now - lastTime) / 1_000_000_000f).coerceAtMost(0.05f)
                        lastTime = now

                        // 2. ACTUALIZAR FÍSICA CON TIEMPO REAL
                        gameView.update(deltaTime)

                        // 3. DIBUJAR
                        gameView.drawGame(canvas)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (canvas != null) {
                    try {
                        surfaceHolder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            // --- CONTROL DE FPS (Sleep) ---
            // Dormimos el hilo solo si vamos sobrados de tiempo para ahorrar batería
            timeMillis = (System.nanoTime() - startTime) / 1_000_000
            waitTime = targetTime - timeMillis

            try {
                if (waitTime > 0) {
                    sleep(waitTime)
                }
            } catch (e: Exception) {
                // Ignorar
            }
        }
    }
}