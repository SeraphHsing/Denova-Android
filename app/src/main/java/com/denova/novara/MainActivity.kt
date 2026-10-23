package com.denova.novara

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.denova.novara.feature.connection.ConnectionScreen
import com.denova.novara.feature.connection.ConnectionViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val connectionViewModel: ConnectionViewModel = viewModel { ConnectionViewModel() }
                ConnectionScreen(connectionViewModel)
            }
        }
    }
}
