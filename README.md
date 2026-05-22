# AndroidUse

AndroidUse is an autonomous AI agent for Android that can navigate apps and perform tasks based on natural language instructions. It leverages advanced Large Language Models (LLMs) to perceive the screen and interact with the UI.

## Features

-   **Multi-Model Support**: Integrated with Google Gemini, OpenAI GPT, and Anthropic Claude.
-   **Autonomous Navigation**: Captures screenshots and UI hierarchies to decide on actions.
-   **Action Execution**: Can perform clicks, typing, and swipes on the device.
-   **Floating Overlay**: A persistent chat interface to provide instructions and monitor the agent's progress.
-   **Accessibility Service**: Built on Android's Accessibility framework to interact with any application.

## Prerequisites

-   Android device or emulator running Android 11 (API 30) or higher.
-   API Keys for the models you intend to use (Gemini, OpenAI, or Anthropic).

## Getting Started

### 1. Configuration

Once the app is installed, you can enter your API keys (Gemini, OpenAI, or Anthropic) directly in the **Settings** screen. These keys are stored securely using `EncryptedSharedPreferences`.

### 2. Build and Install

1.  Open the project in Android Studio.
2.  Build and run the `app` module on your device.

### 3. Enabling the Service

1.  Launch the **AndroidUse** app.
2.  Tap on **Open Accessibility Settings**.
3.  Find **AndroidUse Agent** in the list of installed services and enable it.
4.  Grant the necessary permissions (Allow the service to have full control of your device).

## Usage

1.  In the main screen of the app, select the AI model you wish to use.
2.  Enter a task description (e.g., "Open Spotify and play some jazz music" or "Go to contacts and add a new contact John Smith").
3.  Tap **RUN TASK**.
4.  The app will return to the home screen, and a floating overlay will appear.
5.  Watch as the agent processes the task. You can use the overlay to:
    -   Monitor the current status and step count.
    -   View the agent's "thoughts" in the chat history.
    -   Provide further instructions mid-task.
    -   Stop the agent.

## Project Structure

-   `UIAgentAccessibilityService`: The core service that manages the agent loop, screen capture, and action execution.
-   `IAgent`: Interface for LLM implementations.
-   `GeminiAgent`, `OpenAIAgent`, `AnthropicAgent`: Specific implementations for different AI providers.
-   `SecurityManager`: Handles secure storage and retrieval of API keys.
-   `MainActivity`: Main UI for starting tasks and checking service status.

## Disclaimer

This app uses Accessibility Services to interact with other apps. Use it responsibly and only on devices where you have permission to perform actions. Be cautious when providing the agent with tasks involving sensitive information.
