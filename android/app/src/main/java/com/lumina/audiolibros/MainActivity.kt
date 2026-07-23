package com.lumina.audiolibros

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.lumina.audiolibros.ui.LuminaApp
import com.lumina.audiolibros.ui.theme.LuminaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LuminaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    LuminaApp(Modifier.padding(innerPadding))
                }
            }
        }
    }
}
