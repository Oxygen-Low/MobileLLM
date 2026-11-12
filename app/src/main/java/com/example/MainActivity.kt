package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.ChatScreen
import com.example.ui.ChatViewModel
import com.example.ui.theme.PocketLMTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PocketLMTheme {
                ChatScreen(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppStart()
    }

    override fun onStop() {
        super.onStop()
        // Moving the task to the background releases the weights so the device
        // stops holding the model in RAM (and burns no battery while idle).
        // Configuration changes keep the screen in the foreground, so skip them.
        if (!isChangingConfigurations) {
            viewModel.onAppStop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.onAppStop()
    }
}
