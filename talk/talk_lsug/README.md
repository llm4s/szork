# LLM4S Presentation - London Scala User Group

## Presentation Overview

**Title:** Scala Meets GenAI: Build the Cool Stuff with LLM4S

**Duration:** 45 minutes (40 min presentation + 5 min Q&A)

**Speakers:** Two co-founders of LLM4S

**Target Audience:** Scala developers interested in AI/ML integration

## Presentation Structure

### Part 1: Opening with SZork Demo (12 minutes)
- Interactive game demonstration
- Audience participation with voting
- Live gameplay showing AI capabilities

### Part 2: Core Capabilities Deep Dive (18 minutes)
- Image understanding demonstration
- Agent architecture explanation
- Tool calling system
- Document processing capabilities
- Why Scala matters for AI

### Part 3: Building Cool Stuff (10 minutes)
- What you can build with LLM4S
- Production features
- Community and ecosystem

### Part 4: Your Turn (5 minutes)
- Getting started guide
- Call to action
- Q&A session

## Technical Requirements

### For Presenters
- [ ] Laptop with SZork running locally
- [ ] Internet connection for API calls
- [ ] Screen sharing capability
- [ ] Backup video recordings of demos
- [ ] API keys for all services (OpenAI, etc.)

### Demo Setup
```bash
# Start SZork locally
cd szork
sbt run

# Open browser to http://localhost:8080
```

## Files Needed from User

### Required Assets

1. **Presenter Photos**
   ```bash
   # Copy presenter photos to assets folder
   cp [path/to/founder1_photo.jpg] szork/talk/talk_lsug/assets/photos/founder1.jpg
   cp [path/to/founder2_photo.jpg] szork/talk/talk_lsug/assets/photos/founder2.jpg
   ```

2. **LSUG Logo** (if available)
   ```bash
   cp [path/to/lsug_logo.png] szork/talk/talk_lsug/assets/logos/lsug_logo.png
   ```

3. **Screenshots to Capture**
   ```bash
   # Take screenshot of SZork opening screen
   cp [screenshot] szork/talk/talk_lsug/assets/screenshots/opening_screen.png
   
   # Take screenshot of network tab showing API calls
   cp [screenshot] szork/talk/talk_lsug/assets/screenshots/network_tab.png
   ```

### Data to Update

Please update the following placeholder values in the JSON files:

#### data/metrics.json
- Current GitHub stars count
- Number of contributors
- Production deployments count
- Monthly active users (if available)

#### data/speakers.json
- Both founders' names
- Titles (CTO/CEO)
- Brief bio points (3 lines each)
- Contact information (email, Twitter, LinkedIn)

#### data/links.json
- Event date for LSUG presentation
- Venue name

## Folder Structure

```
talk_lsug/
├── slides.md              # Main presentation content (20 slides)
├── assets/
│   ├── logos/            # Company and project logos
│   ├── photos/           # Speaker headshots
│   ├── screenshots/      # Game UI and demos
│   ├── diagrams/         # Architecture diagrams
│   ├── icons/            # Service icons
│   └── backgrounds/      # Slide backgrounds
├── data/
│   ├── metrics.json      # Performance and growth metrics
│   ├── speakers.json     # Speaker information
│   ├── timeline.json     # Project milestones
│   └── links.json        # External URLs
├── code/
│   ├── examples/         # Code snippets
│   └── configs/          # Configuration examples
└── output/               # Generated presentations

```

## Interactive Elements

1. **Adventure Theme Vote** (Slide 3)
   - 8 themes to choose from
   - Quick show of hands

2. **Art Style Vote** (Slide 4)
   - 4 visual styles
   - Audience selection

3. **Live Game Commands** (During demo)
   - Audience members provide commands
   - Voice input demonstration

## Key Messages

1. **"Batteries-included toolkit"** - Everything needed for GenAI in Scala
2. **"Community-driven development"** - Google Summer of Code contributions
3. **"Production-ready"** - Real enterprise use
4. **"Pure Scala"** - No Python dependencies
5. **"Build the cool stuff"** - Empowerment message

## Demo Backup Plans

- **If SZork fails:** Have recorded video ready
- **If no audience participation:** Pre-selected choices
- **If voice doesn't work:** Type the command
- **If time runs short:** Skip document processing section

## Presentation Day Checklist

### Technical Setup
- [ ] Test all SZork features
- [ ] Ensure all API keys work
- [ ] Test screen sharing
- [ ] Pre-cache demo data
- [ ] Check internet connection

### Content Preparation
- [ ] Practice presenter handoffs
- [ ] Time each section
- [ ] Prepare backup commands
- [ ] Test audience voting method

### Day-of Setup
- [ ] Both presenters can access system
- [ ] Microphones tested
- [ ] Screen resolution optimized
- [ ] Browser tabs ready
- [ ] API rate limits checked

## Success Metrics

- Audience engagement during game
- Questions during Q&A
- GitHub stars after talk
- Discord joins
- Follow-up conversations

## Contact

For questions about this presentation:
- GitHub: https://github.com/llm4s/llm4s
- Discord: https://discord.gg/llm4s

## Notes

- Keep energy high throughout
- Make it feel accessible, not intimidating
- Show that Scala + AI is powerful AND fun
- End with empowerment - audience can build this too