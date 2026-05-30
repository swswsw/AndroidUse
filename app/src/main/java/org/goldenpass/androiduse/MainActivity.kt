package org.goldenpass.androiduse

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    MainScreen()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen() {
        val context = LocalContext.current
        val isAccessibilityEnabled by produceState(initialValue = isAccessibilityServiceEnabled(context)) {
            while (true) {
                value = isAccessibilityServiceEnabled(context)
                delay(1000)
            }
        }

        val models = listOf(
            "gemini-3.5-flash",
            "gemini-3.1-pro-preview",
            "gemini-3.1-flash-preview",
            "gemini-3.1-flash-lite-preview",
            "gemini-3-pro-preview",
            "gemini-3-flash-preview",
            "gemini-3-deep-think",
            "gpt-5.4",
            "gpt-5.4-mini",
            "gpt-5.4-nano",
            "gpt-5.4-thinking",
            "gpt-5.4-pro",
            "gpt-5.3-codex",
            "gpt-5.3-instant",
            "gpt-5.3-codex-spark",
            "gpt-5.2",
            "gpt-5.2-instant",
            "claude-opus-4-7",
            "claude-sonnet-4-6",
            "claude-Haiku-4-6",
            "claude-opus-4-5",
            "claude-haiku-4-5",
            "claude-sonnet-4-5"
        )

        var selectedModel by remember { mutableStateOf(models[0]) }
        var taskText by remember { mutableStateOf("go to contacts, and add a new contact John Smith with email johnsmith123@gmail.com") }
        var expanded by remember { mutableStateOf(false) }

        var hasRecordAudioPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            )
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = { isGranted ->
                hasRecordAudioPermission = isGranted
                if (!isGranted) {
                    Toast.makeText(context, "Microphone permission is required for voice input", Toast.LENGTH_SHORT).show()
                }
            }
        )

        var isListening by remember { mutableStateOf(false) }
        var isVoiceModeEnabled by remember { mutableStateOf(false) }
        var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

        DisposableEffect(Unit) {
            onDispose {
                speechRecognizer?.destroy()
            }
        }

        fun startListening() {
            if (!hasRecordAudioPermission) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }

            if (isListening) {
                try {
                    speechRecognizer?.stopListening()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error stopping listening", e)
                }
                isListening = false
                return
            }

            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                }
                override fun onError(error: Int) {
                    isListening = false
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        else -> "Speech recognition error: $error"
                    }
                    Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        taskText = matches[0]
                        isVoiceModeEnabled = true
                    }
                    isListening = false
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        taskText = matches[0]
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            try {
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Error starting listening", e)
                isListening = false
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("AndroidUse Agent") },
                    actions = {
                        IconButton(onClick = {
                            context.startActivity(Intent(context, SettingsActivity::class.java))
                        }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .padding(16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Status Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isAccessibilityEnabled) 
                            MaterialTheme.colorScheme.primaryContainer 
                        else 
                            MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (isAccessibilityEnabled) "Service Enabled" else "Service Disabled",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open Accessibility Settings")
                        }
                    }
                }

                // Model Selection
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = selectedModel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Select Model") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryEditable, true)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        models.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model) },
                                onClick = {
                                    selectedModel = model
                                    expanded = false
                                    UIAgentAccessibilityService.instance?.updateAgent(model)
                                }
                            )
                        }
                    }
                }

                // Task Input with Voice Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = taskText,
                        onValueChange = { taskText = it },
                        label = { Text("Target Task") },
                        placeholder = { Text("Enter task here...") },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 150.dp),
                        minLines = 5
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        IconButton(
                            onClick = { startListening() },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isListening) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                            ),
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(
                                if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = "Voice Input"
                            )
                        }
                        if (isListening) {
                            Text("Listening...", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                // Voice Mode Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Voice Mode (Speak Responses)", fontWeight = FontWeight.Medium)
                    Switch(
                        checked = isVoiceModeEnabled,
                        onCheckedChange = { isVoiceModeEnabled = it }
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Run Button
                Button(
                    onClick = {
                        runTask(context, selectedModel, taskText, isVoiceModeEnabled)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("RUN TASK", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    private fun runTask(context: Context, model: String, task: String, useVoice: Boolean = false) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val isOnline = capabilities != null && (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        
        if (!isOnline) {
            Toast.makeText(context, "ERROR: No Internet Connection detected", Toast.LENGTH_LONG).show()
            Log.e("MainActivity", "Task failed to start: No internet connection.")
            return
        }

        val service = UIAgentAccessibilityService.instance
        if (service != null) {
            Log.i("MainActivity", "Starting task with model: $model")
            service.updateAgent(model)
            
            Log.i("MainActivity", "Task Description: $task")
            service.startAgentLoop(task, useVoice)
            Toast.makeText(context, "Agent Started ($model): Processing task...", Toast.LENGTH_LONG).show()
            
            val startMain = Intent(Intent.ACTION_MAIN)
            startMain.addCategory(Intent.CATEGORY_HOME)
            startMain.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(startMain)
        } else {
            Toast.makeText(context, "Please enable Accessibility Service first", Toast.LENGTH_SHORT).show()
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val service = context.packageName + "/" + UIAgentAccessibilityService::class.java.canonicalName
        val enabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        if (enabled == 1) {
            val settingValue = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (settingValue != null) {
                return settingValue.contains(service)
            }
        }
        return false
    }
}
