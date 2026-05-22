package org.goldenpass.androiduse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.MotionEvent
import android.widget.Toast
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor
import kotlin.math.roundToInt

class UIAgentAccessibilityService : AccessibilityService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var agent: IAgent? = null
    private var isProcessing by mutableStateOf(false)
    private lateinit var windowManager: WindowManager
    
    private var overlayComposeView: ComposeView? = null
    private var currentModelName by mutableStateOf("")
    private var conversationHistory = mutableStateListOf<ChatMessage>()
    private var isChatVisible by mutableStateOf(false)
    private var statusText by mutableStateOf("Initializing...")
    private var currentStepCount by mutableStateOf(0)

    private var currentStepCountInt = 0 // Internal count to match original
    private var lastActionJson: String? = null
    private var repeatCount = 0
    private val MAX_STEPS = 30
    private val MAX_REPEATS = 7

    private var overlayX = 0f
    private var overlayY = 100f

    // Lifecycle components for ComposeView in Service
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val _viewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = _viewModelStore
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    companion object {
        var instance: UIAgentAccessibilityService? = null
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Handle accessibility events here
    }

    override fun onInterrupt() {
        Log.e("UIAgentAccessibilityService", "Service Interrupted")
        instance = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("UIAgentAccessibilityService", "Service Connected")
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        
        // Default model
        updateAgent("gemini-3.1-pro-preview")
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    fun updateAgent(modelName: String) {
        currentModelName = modelName
        updateOverlay(model = modelName)
        val securityManager = SecurityManager(this)
        if (modelName.startsWith("gemini")) {
            val apiKey = securityManager.getGeminiApiKey()
            if (apiKey != null) {
                agent = GeminiAgent(apiKey, modelName)
                Log.d("UIAgentAccessibilityService", "Agent updated to Gemini ($modelName)")
            } else {
                Log.e("UIAgentAccessibilityService", "Gemini API Key is missing!")
            }
        } else if (modelName.startsWith("gpt")) {
            val apiKey = securityManager.getOpenAIApiKey()
            if (apiKey != null) {
                agent = OpenAIAgent(apiKey, modelName)
                Log.d("UIAgentAccessibilityService", "Agent updated to OpenAI ($modelName)")
            } else {
                Log.e("UIAgentAccessibilityService", "OpenAI API Key is missing!")
            }
        } else if (modelName.startsWith("claude")) {
            val apiKey = securityManager.getAnthropicApiKey()
            if (apiKey != null) {
                agent = AnthropicAgent(apiKey, modelName)
                Log.d("UIAgentAccessibilityService", "Agent updated to Anthropic ($modelName)")
            } else {
                Log.e("UIAgentAccessibilityService", "Anthropic API Key is missing!")
            }
        } else {
            Log.e("UIAgentAccessibilityService", "Unknown model type: $modelName")
        }
    }

    fun startAgentLoop(taskDescription: String) {
        if (isProcessing) return
        isProcessing = true
        currentStepCountInt = 0
        lastActionJson = null
        repeatCount = 0
        
        if (conversationHistory.none { it.isUser && it.text == taskDescription }) {
            conversationHistory.add(ChatMessage(taskDescription, true))
            updateChatUI()
        }
        
        showOverlay()
        updateOverlay("Starting...", 0, currentModelName)
        
        serviceScope.launch {
            processNextStep()
        }
    }

    private suspend fun processNextStep() {
        if (!isProcessing) return
        
        currentStepCountInt++
        if (currentStepCountInt > MAX_STEPS) {
            updateOverlay("Timeout: MAX_STEPS", currentStepCountInt)
            stopWithNotification("Task timed out: Maximum steps ($MAX_STEPS) reached.")
            return
        }

        updateOverlay("Capturing screen...", currentStepCountInt)
        Log.d("UIAgentAccessibilityService", "Capturing screen for step $currentStepCountInt...")
        
        // Find the target application window (skip our own overlays)
        val windows = windows
        val targetWindow = windows.find { 
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive 
        } ?: windows.find { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        
        val targetWindowId = targetWindow?.id ?: rootInActiveWindow?.windowId ?: -1
        
        captureScreenshot(mainExecutor, targetWindowId) { bitmap ->
            if (bitmap == null) {
                Log.e("UIAgentAccessibilityService", "Failed to capture screenshot")
                isProcessing = false
                return@captureScreenshot
            }

            // Convert Hardware Bitmap to Software Bitmap for better SDK compatibility
            val softwareBitmap = try {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) {
                Log.e("UIAgentAccessibilityService", "Failed to convert bitmap", e)
                bitmap
            }

            val uiTree = getClickableElementsJson()
            
            serviceScope.launch {
                updateOverlay("Thinking...", currentStepCountInt)
                val agentResponse = agent?.getNextAction(conversationHistory.toList(), softwareBitmap, uiTree)
                if (agentResponse != null) {
                    handleAgentAction(agentResponse)
                } else {
                    Log.e("UIAgentAccessibilityService", "No response from Agent")
                    isProcessing = false
                }
            }
        }
    }

    private suspend fun handleAgentAction(jsonResponse: String) {
        try {
            val jsonStr = if (jsonResponse.contains("{")) {
                jsonResponse.substring(jsonResponse.indexOf("{"), jsonResponse.lastIndexOf("}") + 1)
            } else jsonResponse

            val json = JSONObject(jsonStr)
            val action = json.optString("action")
            val thought = json.optString("thought", "No reasoning provided")
            
            // Add AI thought to conversation if it's new
            if (thought.isNotBlank()) {
                conversationHistory.add(ChatMessage(thought, false))
                updateChatUI()
            }
            val actionContent = JSONObject(json.toString()).apply { remove("thought") }.toString()
            
            Log.i("UIAgentAccessibilityService", "Agent Thought: $thought")
            Log.d("UIAgentAccessibilityService", "Agent decided: $action")

            if (actionContent == lastActionJson) {
                repeatCount++
                if (repeatCount >= MAX_REPEATS) {
                    updateOverlay("Error: Loop detected", currentStepCountInt)
                    stopWithNotification("Agent is stuck in a loop. Same action repeated $MAX_REPEATS times.")
                    return
                }
            } else {
                repeatCount = 0
            }
            lastActionJson = actionContent

            when (action) {
                "click" -> {
                    val x = json.getDouble("x").toFloat()
                    val y = json.getDouble("y").toFloat()
                    updateOverlay("Clicking at (${x.toInt()}, ${y.toInt()})", currentStepCountInt)
                    showVisualCue(x, y, Color.RED)
                    delay(800)
                    performClickAt(x, y)
                    delay(2000)
                    processNextStep()
                }
                "type" -> {
                    val text = json.getString("text")
                    updateOverlay("Typing: $text", currentStepCountInt)
                    showTypeCue()
                    delay(800)
                    typeText(text)
                    delay(2000)
                    processNextStep()
                }
                "swipe" -> {
                    val startX = json.getDouble("startX").toFloat()
                    val startY = json.getDouble("startY").toFloat()
                    val endX = json.getDouble("endX").toFloat()
                    val endY = json.getDouble("endY").toFloat()
                    updateOverlay("Swiping...", currentStepCountInt)
                    showVisualCue(startX, startY, Color.GREEN)
                    delay(500)
                    showVisualCue(endX, endY, Color.YELLOW)
                    delay(300)
                    performSwipe(startX, startY, endX, endY)
                    delay(2000)
                    processNextStep()
                }
                "done" -> {
                    Log.i("UIAgentAccessibilityService", "Task completed!")
                    updateOverlay("Task Completed Successfully!", currentStepCountInt)
                    conversationHistory.add(ChatMessage("Task Completed Successfully!", false))
                    updateChatUI()
                    stopWithNotification("Task Completed Successfully!")
                }
                else -> {
                    Log.w("UIAgentAccessibilityService", "Unknown action: $action")
                    updateOverlay("Error: Unknown action", currentStepCountInt)
                    conversationHistory.add(ChatMessage("Error: Unknown action received: $action", false))
                    updateChatUI()
                    stopWithNotification("Unknown action received: $action")
                }
            }
        } catch (e: Exception) {
            Log.e("UIAgentAccessibilityService", "Error parsing agent action", e)
            isProcessing = false
        }
    }

    private fun showVisualCue(x: Float, y: Float, color: Int) {
        val size = 60
        val view = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(4, Color.WHITE)
            }
            alpha = 0.7f
        }

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = (x - size / 2).toInt()
            this.y = (y - size / 2).toInt()
        }

        try {
            windowManager.addView(view, params)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    windowManager.removeView(view)
                } catch (e: Exception) {
                    Log.e("UIAgentAccessibilityService", "Error removing visual cue", e)
                }
            }, 1000)
        } catch (e: Exception) {
            Log.e("UIAgentAccessibilityService", "Error showing visual cue", e)
        }
    }

    private fun showTypeCue() {
        val rootNode = rootInActiveWindow ?: return
        val focusedNode = findFocusedNode(rootNode)
        if (focusedNode != null) {
            val bounds = Rect()
            focusedNode.getBoundsInScreen(bounds)
            showVisualCue(bounds.centerX().toFloat(), bounds.centerY().toFloat(), Color.BLUE)
            focusedNode.recycle()
        }
    }

    /**
     * Types text into the currently focused input field.
     */
    private fun typeText(text: String) {
        val rootNode = rootInActiveWindow ?: return
        val focusedNode = findFocusedNode(rootNode)
        if (focusedNode != null) {
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            focusedNode.recycle()
        } else {
            Log.w("UIAgentAccessibilityService", "No focused node found to type text into.")
        }
    }

    private fun findFocusedNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val focused = findFocusedNode(child)
            if (focused != null) return focused
            child.recycle()
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun captureScreenshot(executor: Executor, windowId: Int = -1, callback: (Bitmap?) -> Unit) {
        if (windowId != -1) {
            takeScreenshotOfWindow(windowId, executor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    callback(bitmap)
                }
                override fun onFailure(errorCode: Int) {
                    Log.e("UIAgentAccessibilityService", "Window screenshot failed ($errorCode), falling back")
                    captureScreenshotLegacy(executor, callback)
                }
            })
        } else {
            captureScreenshotLegacy(executor, callback)
        }
    }

    private fun captureScreenshotLegacy(executor: Executor, callback: (Bitmap?) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                callback(bitmap)
            }
            override fun onFailure(errorCode: Int) {
                Log.e("UIAgentAccessibilityService", "Screenshot failed with error code: $errorCode")
                callback(null)
            }
        })
    }

    fun performClickAt(x: Float, y: Float) {
        serviceScope.launch {
            Log.d("UIAgentAccessibilityService", "Performing click at ($x, $y) - Ghosting overlays")
            setOverlaysTouchable(false)
            delay(100) // Wait for WindowManager to update flags
            val clickPath = Path()
            clickPath.moveTo(x, y)
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(GestureDescription.StrokeDescription(clickPath, 0, 100))
            dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.d("UIAgentAccessibilityService", "Gesture completed")
                    setOverlaysTouchable(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w("UIAgentAccessibilityService", "Gesture cancelled")
                    setOverlaysTouchable(true)
                }
            }, null)
        }
    }

    private fun setOverlaysTouchable(touchable: Boolean) {
        Handler(Looper.getMainLooper()).post {
            overlayComposeView?.let { view ->
                val params = view.layoutParams as WindowManager.LayoutParams
                if (touchable) {
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                } else {
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }
                windowManager.updateViewLayout(view, params)
            }
        }
    }

    private fun stopWithNotification(message: String) {
        Log.w("UIAgentAccessibilityService", message)
        isProcessing = false
        // Removed hideOverlay() to allow user to see result
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun showOverlay() {
        if (overlayComposeView != null) return

        overlayComposeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@UIAgentAccessibilityService)
            setViewTreeViewModelStoreOwner(this@UIAgentAccessibilityService)
            setViewTreeSavedStateRegistryOwner(this@UIAgentAccessibilityService)
            
            setContent {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = androidx.compose.ui.graphics.Color(0xFFBB86FC),
                        surface = androidx.compose.ui.graphics.Color(0xCC222222),
                        onSurface = androidx.compose.ui.graphics.Color.White
                    )
                ) {
                    OverlayContent()
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = overlayX.roundToInt()
            y = overlayY.roundToInt()
            width = (resources.displayMetrics.widthPixels * 0.95).toInt()
        }

        windowManager.addView(overlayComposeView, params)
    }

    @Composable
    fun OverlayContent() {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = "Drag",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                overlayX += dragAmount.x
                                overlayY += dragAmount.y
                                updateOverlayPosition()
                            }
                        }
                )
                Text(
                    text = "🤖 $currentModelName",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { toggleChat() }) {
                    Icon(Icons.Default.Chat, contentDescription = "Chat", tint = MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    text = "Step $currentStepCount/$MAX_STEPS",
                    fontSize = 12.sp,
                    color = androidx.compose.ui.graphics.Color.LightGray,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                IconButton(
                    onClick = {
                        if (isProcessing) {
                            updateOverlay("Stopped by user", currentStepCountInt)
                            stopWithNotification("Agent stopped by user.")
                        } else {
                            hideOverlay()
                        }
                    },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = androidx.compose.ui.graphics.Color.Red)
                ) {
                    Icon(if (isProcessing) Icons.Default.Stop else Icons.Default.Close, contentDescription = "Stop", tint = androidx.compose.ui.graphics.Color.White)
                }
            }
            
            Text(
                text = statusText,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp)
            )

            if (isChatVisible) {
                ChatContent()
            }
        }
    }

    @Composable
    fun ChatContent() {
        var chatInput by remember { mutableStateOf("") }
        val scrollState = rememberScrollState()
        
        LaunchedEffect(conversationHistory.size) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }

        Column(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .heightIn(max = 300.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
            ) {
                conversationHistory.takeLast(10).forEach { msg ->
                    Text(
                        text = (if (msg.isUser) "👤 " else "🤖 ") + msg.text,
                        color = if (msg.isUser) androidx.compose.ui.graphics.Color.Cyan else androidx.compose.ui.graphics.Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                TextField(
                    value = chatInput,
                    onValueChange = { chatInput = it },
                    placeholder = { Text("Send instruction...", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = androidx.compose.ui.graphics.Color.White,
                        unfocusedTextColor = androidx.compose.ui.graphics.Color.White
                    )
                )
                IconButton(onClick = {
                    conversationHistory.clear()
                    updateChatUI()
                    Toast.makeText(this@UIAgentAccessibilityService, "Chat Context Cleared", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear", tint = androidx.compose.ui.graphics.Color.Gray)
                }
                IconButton(onClick = {
                    if (chatInput.isNotBlank()) {
                        val text = chatInput
                        conversationHistory.add(ChatMessage(text, true))
                        chatInput = ""
                        updateChatUI()
                        if (!isProcessing) {
                            startAgentLoop(text)
                        }
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    private fun updateOverlayPosition() {
        overlayComposeView?.let { view ->
            val params = view.layoutParams as WindowManager.LayoutParams
            params.x = overlayX.roundToInt()
            params.y = overlayY.roundToInt()
            windowManager.updateViewLayout(view, params)
        }
    }

    private fun hideOverlay() {
        hideChatOverlay()
        overlayComposeView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e("UIAgentAccessibilityService", "Error removing overlay", e)
            }
            overlayComposeView = null
        }
    }

    private fun toggleChat() {
        isChatVisible = !isChatVisible
        updateWindowFlags()
    }

    private fun hideChatOverlay() {
        isChatVisible = false
        updateWindowFlags()
    }

    private fun updateWindowFlags() {
        overlayComposeView?.let { view ->
            val params = view.layoutParams as WindowManager.LayoutParams
            if (isChatVisible) {
                // Remove FLAG_NOT_FOCUSABLE to allow keyboard
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            } else {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
            }
            windowManager.updateViewLayout(view, params)
        }
    }

    private fun updateChatUI() {
        // Compose handles this automatically via SnapshotStateList
    }

    private fun updateOverlay(status: String? = null, step: Int? = null, model: String? = null) {
        Handler(Looper.getMainLooper()).post {
            status?.let { statusText = it }
            step?.let { currentStepCount = it }
            model?.let { currentModelName = it }
        }
    }

    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 500L) {
        serviceScope.launch {
            Log.d("UIAgentAccessibilityService", "Performing swipe from ($startX, $startY) to ($endX, $endY)")
            setOverlaysTouchable(false)
            delay(100)
            val swipePath = Path()
            swipePath.moveTo(startX, startY)
            swipePath.lineTo(endX, endY)
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(GestureDescription.StrokeDescription(swipePath, 0, duration))
            dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    setOverlaysTouchable(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    setOverlaysTouchable(true)
                }
            }, null)
        }
    }

    fun getClickableElementsJson(): String {
        // Look for the application window specifically
        val windows = windows
        val targetWindow = windows.find { 
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive 
        } ?: windows.find { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        
        val rootNode = targetWindow?.root ?: rootInActiveWindow ?: return "[]"

        if (rootNode.packageName == packageName) {
            Log.w("UIAgentAccessibilityService", "Root node belongs to our service, skipping UI tree")
            return "[]"
        }
        val clickableItems = JSONArray()
        traverseAndCollectClickable(rootNode, clickableItems)
        return clickableItems.toString()
    }

    private fun traverseAndCollectClickable(node: AccessibilityNodeInfo?, items: JSONArray) {
        if (node == null) return
        if (node.isClickable) {
            val item = JSONObject()
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            item.put("text", node.text?.toString() ?: "")
            item.put("contentDescription", node.contentDescription?.toString() ?: "")
            item.put("bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
            items.put(item)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            traverseAndCollectClickable(child, items)
            child?.recycle()
        }
    }
}
