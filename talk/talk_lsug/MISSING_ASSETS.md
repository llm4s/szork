# Missing Assets for LSUG Presentation

## Required Files and Copy Commands

Please provide the following assets and use these commands to copy them to the correct locations:

### 1. Presenter Photos (REQUIRED)

```bash
# Founder 1 headshot (professional photo, min 800x800px)
cp [path/to/your/founder1_photo.jpg] /Users/rory.graves/workspace/home/llm4s/szork/talk/talk_lsug/assets/photos/founder1.jpg

# Founder 2 headshot (professional photo, min 800x800px)
cp [path/to/your/founder2_photo.jpg] /Users/rory.graves/workspace/home/llm4s/szork/talk/talk_lsug/assets/photos/founder2.jpg
```

### 2. Conference/Event Logos (OPTIONAL)

```bash
# LSUG logo if available
cp [path/to/lsug_logo.png] /Users/rory.graves/workspace/home/llm4s/szork/talk/talk_lsug/assets/logos/lsug_logo.png

# Scala logo if you have a high-res version
cp [path/to/scala_logo.svg] /Users/rory.graves/workspace/home/llm4s/szork/talk/talk_lsug/assets/logos/scala_logo.svg
```

### 3. Screenshots to Capture (RECOMMENDED)

```bash
# Take a screenshot of SZork opening/main screen
cp [screenshot_path] /Users/rory.graves/workspace/home/llm4s/szork/talk/talk_lsug/assets/screenshots/opening_screen.png

# Take a screenshot of browser network tab showing API calls during game
cp [screenshot_path] /Users/rory.graves/workspace/home/llm4s/szork/talk/talk_lsug/assets/screenshots/network_tab.png

# Take a screenshot of a code example in your IDE
cp [screenshot_path] /Users/rory.graves/workspace/home/llm4s/szork/talk/talk_lsug/assets/screenshots/ide_code.png

# Take a screenshot of Langfuse dashboard (if available)
cp [screenshot_path] /Users/rory.graves/workspace/home/llm4s/szork/talk/talk_lsug/assets/screenshots/langfuse_dashboard.png
```

### 4. Additional Game Screenshots (OPTIONAL)

```bash
# Any interesting game scenes you want to showcase
cp [path/to/game_scene.png] /Users/rory.graves/workspace/home/llm4s/szork/talk/talk_lsug/assets/screenshots/game_scene_[name].png
```

### 5. Architecture Diagrams (OPTIONAL)

If you have any architecture diagrams or flow charts:

```bash
# System architecture diagram
cp [path/to/architecture.png] /Users/rory.graves/workspace/home/llm4s/szork/talk/talk_lsug/assets/diagrams/architecture.png

# Agent flow diagram
cp [path/to/agent_flow.png] /Users/rory.graves/workspace/home/llm4s/szork/talk/talk_lsug/assets/diagrams/agent_flow.png
```

## Data to Update in JSON Files

### data/speakers.json
Please update these placeholders:
- `[PLEASE UPDATE: First Founder Name]` → Actual name
- `[PLEASE UPDATE: Second Founder Name]` → Actual name
- `[PLEASE UPDATE: Line 1 of bio]` → Real bio points
- Email addresses, Twitter handles, LinkedIn profiles

### data/metrics.json
Please update these placeholders:
- `[PLEASE UPDATE: Current GitHub stars count]` → Actual number
- `[PLEASE UPDATE: Number of contributors]` → Actual number
- `[PLEASE UPDATE: Number of production deployments]` → Actual number
- `[PLEASE UPDATE: Monthly active users if available]` → Actual number or "N/A"

### data/links.json
Please update:
- `[PLEASE UPDATE: Event Date, e.g., September 25, 2024]` → Actual LSUG date
- `[PLEASE UPDATE: Venue name]` → Actual venue

## Quick Update Commands

You can also update the JSON files directly using these commands:

```bash
# Edit speaker information
vim /Users/rory.graves/workspace/home/llm4s/szork/talk/talk_lsug/data/speakers.json

# Edit metrics
vim /Users/rory.graves/workspace/home/llm4s/szork/talk/talk_lsug/data/metrics.json

# Edit links and conference info
vim /Users/rory.graves/workspace/home/llm4s/szork/talk/talk_lsug/data/links.json
```

## Verification

After adding assets, verify everything is in place:

```bash
# Check that all required images exist
ls -la /Users/rory.graves/workspace/home/llm4s/szork/talk/talk_lsug/assets/photos/
ls -la /Users/rory.graves/workspace/home/llm4s/szork/talk/talk_lsug/assets/screenshots/

# Check JSON files have been updated
grep "PLEASE UPDATE" /Users/rory.graves/workspace/home/llm4s/szork/talk/talk_lsug/data/*.json
```

If the grep command returns results, those fields still need updating.