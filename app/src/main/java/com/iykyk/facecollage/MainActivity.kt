package com.iykyk.facecollage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.iykyk.facecollage.ui.CollageScreen
import com.iykyk.facecollage.ui.CollageViewModel

/** Single-Activity host for the one Compose screen; all real work happens in [CollageViewModel]. */
class MainActivity : ComponentActivity() {

    private val viewModel: CollageViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CollageScreen(viewModel)
                }
            }
        }
    }
}
