package org.goldenpass.androiduse

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

class SettingsActivity : ComponentActivity() {
    private lateinit var securityManager: SecurityManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        securityManager = SecurityManager(this)
        enableEdgeToEdge()

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFF6200EE),
                    onPrimary = androidx.compose.ui.graphics.Color.White,
                    secondary = androidx.compose.ui.graphics.Color(0xFF03DAC6)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsScreen()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SettingsScreen() {
        val context = LocalContext.current
        var geminiKey by remember { mutableStateOf(securityManager.getGeminiApiKey() ?: "") }
        var openAIKey by remember { mutableStateOf(securityManager.getOpenAIApiKey() ?: "") }
        var anthropicKey by remember { mutableStateOf(securityManager.getAnthropicApiKey() ?: "") }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("API Key Settings") },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = {
                    securityManager.setGeminiApiKey(geminiKey.trim())
                    securityManager.setOpenAIApiKey(openAIKey.trim())
                    securityManager.setAnthropicApiKey(anthropicKey.trim())
                    Toast.makeText(context, "Keys saved securely", Toast.LENGTH_SHORT).show()
                    finish()
                }) {
                    Icon(Icons.Default.Check, contentDescription = "Save")
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .padding(16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                ApiKeyInput(
                    label = "Gemini API Key",
                    value = geminiKey,
                    onValueChange = { geminiKey = it }
                )
                
                ApiKeyInput(
                    label = "OpenAI API Key",
                    value = openAIKey,
                    onValueChange = { openAIKey = it }
                )
                
                ApiKeyInput(
                    label = "Anthropic API Key",
                    value = anthropicKey,
                    onValueChange = { anthropicKey = it }
                )
                
                Spacer(modifier = Modifier.height(80.dp)) // Space for FAB
            }
        }
    }

    @Composable
    fun ApiKeyInput(label: String, value: String, onValueChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true
        )
    }
}
