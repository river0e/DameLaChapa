package com.mjcastro.damelachapa

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.activity_main) // Carga tu layout principal

        // Configuración para Edge-to-Edge y paddings.
        // Solo es necesaria si usas enableEdgeToEdge() y si tu layout principal
        // (el ConstraintLayout) tiene el id R.id.main.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // --- ¡EL CAMBIO ESTÁ EN ESTA LÍNEA! ---
        // Ahora buscamos un Button, no un ImageView
        val btnPlay = findViewById<Button>(R.id.btnPlay)

        // Configura un listener para cuando se haga clic en el botón
        btnPlay.setOnClickListener {
            // Inicia la GameActivity cuando se pulsa el botón
            startActivity(Intent(this, GameActivity::class.java))
            // Opcional: finish() para que el usuario no pueda volver a esta pantalla
            // con el botón de atrás después de iniciar el juego.
            // finish()
        }
    }
}