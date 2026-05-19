## Chat Overlay & Interactivity Documentation

This document explains the implementation of the conversational chat overlay and how we solved the technical challenge of overlay interference with AI actions.

### 1. The Problem: Overlay Interference

When a persistent chat window is added as a floating overlay (`TYPE_ACCESSIBILITY_OVERLAY`), it introduces two major issues for an AI-driven automation agent:

#### A. Visual Confusion (Screenshot Pollution)
If the agent takes a standard screenshot of the display, the chat window will appear in the image. The AI might then try to interact with the chat window itself (e.g., clicking the "Send" button) instead of the target application it is supposed to automate.

#### B. Touch Interception (Click Blockage)
Android overlays are physical layers. If the AI decides to click a button in the target app that is currently hidden behind the chat window, the click will be intercepted by the chat overlay. The target app will never receive the event.

---

### 2. The Solution: "Ghosting" and Focused Vision

We solved these issues using a combination of Android 14 window APIs and dynamic flag management.

#### A. Window-Specific Capture (AI Vision)
Instead of capturing the full display, the service now performs the following steps for every "thought" cycle:
1.  **Iterate Windows**: It uses `AccessibilityService.getWindows()` to find the window with `TYPE_APPLICATION`.
2.  **Target ID**: It retrieves the specific `windowId` of that app.
3.  **Filtered Screenshot**: It uses `takeScreenshotOfWindow(windowId, ...)` (introduced in API 34).
4.  **Result**: The AI receives a clean screenshot of *only* the app it is automating. The chat window is completely invisible to the AI's "vision."

#### B. The "Ghosting" System (Interactivity)
To ensure AI clicks hit the target app even when covered by the chat, we implemented a **Ghosting Mode** during gesture injection:

1.  **Flag Update**: Immediately before a click or swipe, the service applies the `FLAG_NOT_TOUCHABLE` flag to both the status bar and the chat window.
2.  **Synchronization Delay**: A small 100ms delay is introduced to ensure the `WindowManager` has applied the new flags.
3.  **Gesture Injection**: The `dispatchGesture()` API is called to perform the action.
4.  **Callback Restoration**: We use a `GestureResultCallback`. Only once Android confirms the gesture is finished (or cancelled) do we remove the `FLAG_NOT_TOUCHABLE` flag.
5.  **Opacity Tuning**: The chat window background is set to **60% opacity** (`#99111111`) to ensure compliance with Android 12+ "Untrusted Touch" security policies, preventing the system from ever blocking an AI-initiated gesture.

---

### 3. Technical Summary
- **Primary API**: `takeScreenshotOfWindow` (API 34+)
- **Fallback**: Standard `takeScreenshot` if window ID is unavailable.
- **Gesture Reliability**: Verified via `GestureResultCallback` and temporary flag toggling.
- **Visibility**: Overlays remain visible to the user at all times but are "ghosted" for the milliseconds during which an automated action occurs.

---

# Implementation Plan - Chat Overlay & Conversation History

This plan transforms the current "one-off task" execution into a persistent conversation between the user and the AI agent, accessible via a floating chat window.

## Proposed Changes

### [NEW] [ChatMessage.kt](file:///Users/t/AndroidStudioProjects/AndroidUse/app/src/main/java/org/goldenpass/androiduse/ChatMessage.kt)
- Define a data class `ChatMessage(val text: String, val isUser: Boolean, val timestamp: Long = System.currentTimeMillis())`.

---

### Agent & History Support

#### [IAgent.kt](file:///Users/t/AndroidStudioProjects/AndroidUse/app/src/main/java/org/goldenpass/androiduse/IAgent.kt)
- Update `getNextAction` to accept a list of `ChatMessage` instead of a single prompt string.
- `suspend fun getNextAction(history: List<ChatMessage>, screenshot: Bitmap, uiTree: String): String?`

#### [GeminiAgent.kt](file:///Users/t/AndroidStudioProjects/AndroidUse/app/src/main/java/org/goldenpass/androiduse/GeminiAgent.kt)
- Update implementation to handle `history`.
- Use Gemini's chat capabilities (or map history to Content objects) to maintain context.

---

### UI - Chat Overlay

#### [UIAgentAccessibilityService.kt](file:///Users/t/AndroidStudioProjects/AndroidUse/app/src/main/java/org/goldenpass/androiduse/UIAgentAccessibilityService.kt)

- **Overlay Bar Updates**:
    - Add a `chatButton` (💬) to the main bar.
    - Implement a toggle for the `chatOverlayView`.
- **Chat Window Implementation**:
    - `showChatOverlay()`: Create a new window (initially hidden or collapsed).
    - Components: `RecyclerView` for message history, `EditText` for user input, and a `Send` button.
    - Style: Semi-transparent dark background matching the main bar.
- **Interference Prevention**:
    - Update `captureScreenshot` to find the `rootInActiveWindow`'s `windowId` and use `takeScreenshotOfWindow(windowId, ...)`.
    - Ensure `getClickableElementsJson` only traverses the active app window, excluding service overlays.
- **Conversation State**:
    - Maintain a `mutableListOf<ChatMessage>` called `conversationHistory`.
    - Every agent "thought" or "action" can optionally be added to history as an AI message.

---

## Verification Plan

### Automated Tests
- None.

### Manual Verification
1.  **Chat Toggle**: Tap 💬 on the overlay bar. Verify the chat window expands/collapses.
2.  **Sending Messages**: Type a task in the chat (e.g., "Open Settings") and hit send.
    - Verify the message appears in the chat UI.
    - Verify the AI starts processing the task.
3.  **Conversation Context**:
    - Start a task ("Go to Contacts").
    - Halfway through, send a chat message: "Actually, open the Clock app instead."
    - Verify the AI switches tasks based on the new context.
4.  **Screenshot Integrity**:
    - Open the chat window so it covers a significant part of the screen.
    - Let the AI perform a click.
    - Verify (via logs) that the AI's "view" of the screenshot did NOT include the chat window.
5.  **Stop Functionality**: Click the red stop button ■. Verify both overlays are handled correctly (stopped, but chat can remain if desired).
