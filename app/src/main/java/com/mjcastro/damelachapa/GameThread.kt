package com.mjcastro.damelachapa

import android.graphics.Canvas
import android.view.SurfaceHolder

class GameThread(
    private val surfaceHolder: SurfaceHolder,
    private val gameView: GameView
) : Thread() {

    private var running = false
    private val targetFPS = 60

    fun setRunning(isRunning: Boolean) {
        running = isRunning
    }

    override fun run() {
        var lastTime = System.nanoTime()
        var canvas: Canvas?

        while (running) {

            val now = System.nanoTime()
            val deltaTime = (now - lastTime) / 1_000_000_000f
            lastTime = now

            canvas = null
            try {
                canvas = surfaceHolder.lockCanvas()
                if (canvas != null) {
                    synchronized(surfaceHolder) {
                        gameView.update(deltaTime)
                        gameView.drawGame(canvas)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (canvas != null) {
                    try {
                        surfaceHolder.unlockCanvasAndPost(canvas)
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
