package com.mjcastro.damelachapa

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView // Importante
import android.view.View      // Importante
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.media.AudioAttributes
import android.media.SoundPool


class MainActivity : AppCompatActivity() {

    private var soundPool: SoundPool? = null
    private var soundClickId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initSound()

        // --- REFERENCIAS UI ---
        val btnPlay = findViewById<Button>(R.id.btnPlay)
        val btnRules = findViewById<Button>(R.id.btnRules) // Botón nuevo
        val imgRules = findViewById<ImageView>(R.id.imgRulesOverlay) // Imagen superpuesta

        // 1. Lógica botón JUGAR
        btnPlay.setOnClickListener {
            playSound()
            startActivity(Intent(this, GameActivity::class.java))
        }

        // 2. Lógica botón REGLAS
        btnRules.setOnClickListener {
            playSound()
            // Hacemos visible la imagen de reglas
            imgRules.visibility = View.VISIBLE
        }

        // 3. Lógica para CERRAR reglas
        // Al tocar la imagen de las reglas, se oculta
        imgRules.setOnClickListener {
            imgRules.visibility = View.GONE
        }
    }

    // Función auxiliar para limpiar el código del onCreate
    private fun initSound() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(2) // Subimos a 2 por si se pulsa rápido
            .setAudioAttributes(audioAttributes)
            .build()

        try {
            soundClickId = soundPool!!.load(this, R.raw.start_click, 1)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun playSound() {
        if (soundClickId != 0) {
            soundPool?.play(soundClickId, 1f, 1f, 0, 0, 1f)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        soundPool?.release()
        soundPool = null
    }
}