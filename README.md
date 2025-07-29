# szork
Voice-controlled Zork-style adventure demo using LLM4S: speech, image, tools, and agentic gameplay.

# Szork 🧙‍♂️🎙️

**Szork** is a voice-controlled, Zork-style text adventure game demo built using [LLM4S](https://github.com/llm4s/llm4s) — a Scala toolkit for integrating large language models with real-world tools.

It showcases an LLM agent acting as a **Dungeon Master**, allowing players to explore a fantasy world using natural language input.


## 🎮 Preview

![Szork gameplay preview](./A_screenshot_or_animation_of_Szork,_an_interactive.png)


## 📢 About

This project was created as part of the **"Scala Meets GenAI: Build the Cool Stuff with LLM4S"** talk:

- 🗓 **August 21, 2025**  
- 🎤 **Scala Days 2025**  
- 📍 **SwissTech Convention Center**, EPFL campus, Lausanne, Switzerland 🇨🇭  
- 🔗 [Talk Details](https://scaladays.org/editions/2025/talks/scala-meets-genai-build-the)  
- 🔗 [LinkedIn Post](https://www.linkedin.com/feed/update/urn:li:activity:7348123421945262080/)


## 🚀 Features

- 🧠 LLM-driven gameplay logic (agent as Dungeon Master)
- 💬 Natural language input
- 🖼 Scene-by-scene narration + AI-generated visuals
- 🧰 Tool calling for inventory, puzzles, and logic


## 🧠 Demo Use Case

A voice-controlled Zork-style adventure game is an excellent demo to showcase LLM4S:

### Perfect Feature Alignment

- **LLM as DM**: Narrative generation & game state tracking  
- **Speech-to-Text**: Voice commands  
- **Text-to-Speech**: Spoken responses  
- **Image Generation**: Scene illustrations  
- **Tool Calling**: Inventory, combat, puzzles  
- **Agentic Workflows**: DM manages flow and reasoning  

### Suggested Flow

1. Title screen with narration via TTS  
2. Scene image is generated  
3. Player speaks a command (e.g. “go north”)  
4. Agent processes → updates state → describes outcome  
5. New image + TTS narration  

### Advanced Features

- RAG referencing game lore  
- Complex puzzles using reasoning  
- Dice rolls, state memory, multi-turn logic  


## 📦 Setup

### Requirements

- Java 17+
- Scala 2.13
- sbt 1.7+ (recommended: 1.8+)

### Run Locally

```bash
git clone git@github.com:llm4s/szork.git
cd szork
sbt run



