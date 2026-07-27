package org.goldenpass.androiduse

import android.content.res.Resources
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class GeminiAgent(private val apiKey: String, modelName: String = "gemini-3.5-flash") : IAgent {
    
    private val systemInstructions = """
        You are an expert Android UI Automation Agent.
        Your goal is to complete a user-specified TASK by analyzing a screenshot and a UI Tree.
        
        COORDINATE SYSTEM:
        - All coordinates (x, y, startX, startY, endX, endY) MUST be in normalized 0-1000 format.
        - (0, 0) is the top-left corner.
        - (1000, 1000) is the bottom-right corner.
        
        UI TREE DATA:
        - The UI Tree contains clickable elements and their normalized center coordinates. Use this to help locate precise targets.
        
        REQUIRED RESPONSE FORMAT (JSON ONLY):
        You must respond with a SINGLE JSON object in one of these formats:
        
        1. CLICK ACTION:
        {
          "thought": "Reasoning for the action.",
          "action": "click",
          "x": 500,
          "y": 500
        }
        
        2. TYPE ACTION (Use this after clicking/focusing an input field):
        {
          "thought": "Reasoning for the action.",
          "action": "type",
          "text": "text to type"
        }
        
        3. SWIPE ACTION:
        {
          "thought": "Reasoning for the action.",
          "action": "swipe",
          "startX": 500,
          "startY": 800,
          "endX": 500,
          "endY": 200
        }
        
        4. DONE:
        {
          "thought": "Task is complete.",
          "action": "done"
        }
        
        IMPORTANT RULES:
        - Respond ONLY with the JSON object. No other text.
        - Ensure the JSON is complete and well-formed. Do not truncate the response.
        - Be precise with coordinates.
        - If the task is finished, return the "done" action.
    """.trimIndent()

    private val model = GenerativeModel(
        modelName = modelName,
        apiKey = apiKey,
        systemInstruction = content { text(systemInstructions) },
        generationConfig = generationConfig {
            responseMimeType = "application/json"
        }
    )

    override suspend fun getNextAction(history: List<ChatMessage>, screenshot: Bitmap, uiTree: String): String? = withContext(Dispatchers.IO) {
        val displayMetrics = Resources.getSystem().displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        Log.i("GeminiAgent", "Using Gemini API Key: $apiKey")

        // 1. Process UI Tree into normalized centers (0-1000)
        val normalizedUiTree = normalizeUiTree(uiTree, screenWidth, screenHeight)

        // 2. Resize/Downscale the screenshot to reduce data sent to LLM
        val maxDimension = 1024
        val resizedScreenshot = if (screenshot.width > maxDimension || screenshot.height > maxDimension) {
            val scale = maxDimension.toFloat() / Math.max(screenshot.width, screenshot.height)
            val newWidth = (screenshot.width * scale).toInt()
            val newHeight = (screenshot.height * scale).toInt()
            Log.d("GeminiAgent", "Resizing screenshot from ${screenshot.width}x${screenshot.height} to ${newWidth}x${newHeight}")
            Bitmap.createScaledBitmap(screenshot, newWidth, newHeight, true)
        } else {
            screenshot
        }

        // 3. Build the prompt from history
        val historyStr = history.joinToString("\n") { 
            if (it.isUser) "USER: ${it.text}" else "AI: ${it.text}"
        }

        val userPrompt = """
            CONVERSATION HISTORY:
            $historyStr
            
            CURRENT UI TREE (Normalized Centers):
            $normalizedUiTree
            
            Based on the history and the current screen, what is the NEXT action?
        """.trimIndent()

        Log.d("GeminiAgent", "REQUEST SEND TO LLM (Model: ${model.modelName}):")
        Log.d("GeminiAgent", "Prompt: $userPrompt")

        try {
            val response = model.generateContent(
                content {
                    image(resizedScreenshot)
                    text(userPrompt)
                }
            )
            val result = response.text?.trim()
            Log.d("GeminiAgent", "RESPONSE FROM LLM: ${result ?: "EMPTY RESPONSE"}")
            
            // 4. Post-process the result: Convert 0-1000 back to absolute pixels
            return@withContext denormalizeResponse(result, screenWidth, screenHeight)
        } catch (e: Exception) {
            Log.e("GeminiAgent", "API Error detail: ", e)
            return@withContext null
        } finally {
            if (resizedScreenshot != screenshot) {
                resizedScreenshot.recycle()
            }
        }
    }

    private fun normalizeUiTree(uiTree: String, screenWidth: Int, screenHeight: Int): String {
        try {
            val originalArray = JSONArray(uiTree)
            val normalizedArray = JSONArray()
            for (i in 0 until originalArray.length()) {
                val item = originalArray.getJSONObject(i)
                val boundsStr = item.optString("bounds", "")
                
                if (boundsStr.isNotEmpty()) {
                    val regex = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")
                    val match = regex.find(boundsStr)
                    if (match != null) {
                        val left = match.groupValues[1].toInt()
                        val top = match.groupValues[2].toInt()
                        val right = match.groupValues[3].toInt()
                        val bottom = match.groupValues[4].toInt()
                        
                        val centerX = (left + right) / 2
                        val centerY = (top + bottom) / 2
                        
                        val nx = (centerX * 1000 / screenWidth).coerceIn(0, 1000)
                        val ny = (centerY * 1000 / screenHeight).coerceIn(0, 1000)
                        
                        val normalizedItem = JSONObject()
                        normalizedItem.put("text", item.optString("text"))
                        normalizedItem.put("contentDescription", item.optString("contentDescription"))
                        normalizedItem.put("center", "($nx, $ny)")
                        normalizedArray.put(normalizedItem)
                    }
                }
            }
            return normalizedArray.toString(2)
        } catch (e: Exception) {
            Log.e("GeminiAgent", "Error normalizing UI tree", e)
            return uiTree
        }
    }

    private fun denormalizeResponse(rawResponse: String?, screenWidth: Int, screenHeight: Int): String? {
        if (rawResponse.isNullOrBlank()) return null
        try {
            var jsonStr = rawResponse.trim()
            
            // Handle cases where the model might wrap JSON in backticks
            if (jsonStr.startsWith("```json")) {
                jsonStr = jsonStr.removePrefix("```json").removeSuffix("```").trim()
            } else if (jsonStr.startsWith("```")) {
                jsonStr = jsonStr.removePrefix("```").removeSuffix("```").trim()
            }

            val start = jsonStr.indexOf("{")
            val end = jsonStr.lastIndexOf("}")
            
            if (start == -1) {
                Log.w("GeminiAgent", "No JSON object found in response: $jsonStr")
                return rawResponse
            }

            // If we found a start but no end, or the end is before the start,
            // the JSON might be truncated.
            if (end == -1 || end < start) {
                Log.w("GeminiAgent", "Truncated JSON detected, attempting to fix: $jsonStr")
                jsonStr = jsonStr.substring(start)
                // Basic heuristic: append closing braces until it parses or we reach a limit
                for (i in 1..3) {
                    try {
                        jsonStr += "}"
                        JSONObject(jsonStr)
                        Log.i("GeminiAgent", "Successfully fixed truncated JSON: $jsonStr")
                        break
                    } catch (_: Exception) {
                        if (i == 3) {
                            Log.e("GeminiAgent", "Failed to fix truncated JSON after $i attempts")
                            return rawResponse
                        }
                    }
                }
            } else {
                jsonStr = jsonStr.substring(start, end + 1)
            }

            val json = JSONObject(jsonStr)
            val action = json.optString("action")
            
            if (action == "click") {
                val nx = json.optDouble("x", -1.0)
                val ny = json.optDouble("y", -1.0)
                if (nx >= 0 && ny >= 0) {
                    val x = (nx / 1000.0 * screenWidth).toInt()
                    val y = (ny / 1000.0 * screenHeight).toInt()
                    json.put("x", x)
                    json.put("y", y)
                }
            } else if (action == "swipe") {
                val nsx = json.optDouble("startX", -1.0)
                val nsy = json.optDouble("startY", -1.0)
                val nex = json.optDouble("endX", -1.0)
                val ney = json.optDouble("endY", -1.0)
                
                if (nsx >= 0 && nsy >= 0 && nex >= 0 && ney >= 0) {
                    json.put("startX", (nsx / 1000.0 * screenWidth).toInt())
                    json.put("startY", (nsy / 1000.0 * screenHeight).toInt())
                    json.put("endX", (nex / 1000.0 * screenWidth).toInt())
                    json.put("endY", (ney / 1000.0 * screenHeight).toInt())
                }
            }
            return json.toString()
        } catch (e: Exception) {
            Log.e("GeminiAgent", "Error denormalizing response", e)
            return rawResponse
        }
    }
}
