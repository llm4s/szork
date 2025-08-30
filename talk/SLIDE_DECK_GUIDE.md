# Slide Deck Preparation Guide for AI Generation

This guide helps you organize materials for AI-powered slide deck generation using the LLM4S presentation system.

## 🚨 CRITICAL: Parser Rules

The presentation generator uses specific markdown heading levels to determine slide boundaries:

| Markdown | Parser Behavior | Use For |
|----------|----------------|---------|
| `#` (single hash) | **Creates a new slide** | Slide titles (ONE per slide) |
| `##` (double hash) | Adds content to current slide | Subtitles within slides |
| `###` (triple hash) | Adds content to current slide | Section headers in content |
| `<!-- comment -->` | Ignored by parser | Organizational notes |
| `---` | Visual separator only | Between slides (optional) |

⚠️ **Most Common Error:** Using both `## Slide N:` AND `#` creates duplicate slides!

## Table of Contents
1. [Folder Structure](#folder-structure)
2. [Slide Markdown Format](#slide-markdown-format)
3. [Asset Management](#asset-management)
4. [Data Binding](#data-binding)
5. [Code Examples](#code-examples)
6. [Output Versioning](#output-versioning)
7. [Complete Example](#complete-example)

## Folder Structure

Create a dedicated folder for your presentation with this required structure:

```
your-presentation/
├── slides.md           # REQUIRED: Main slide descriptions
├── assets/            # REQUIRED: Supporting materials
│   ├── logos/         # Company/project logos
│   ├── photos/        # Headshots, team photos
│   ├── screenshots/   # UI screenshots, demos
│   ├── diagrams/      # Architecture, flow diagrams
│   ├── icons/         # Custom icons, symbols
│   └── backgrounds/   # Background images/gradients
├── data/              # REQUIRED: Structured data
│   ├── metrics.json   # Performance metrics, stats
│   ├── speakers.json  # Speaker information
│   ├── timeline.json  # Project milestones
│   └── links.json     # External URLs, social media
├── code/              # OPTIONAL: Code examples
│   ├── examples/      # Code snippets by language
│   └── configs/       # Configuration examples
├── output/            # AUTO-GENERATED: Output versions
│   ├── v001_presentation.pptx
│   ├── v002_presentation.pptx
│   └── latest.pptx -> (symlink to latest)
└── README.md          # OPTIONAL: Presentation context
```

## Slide Markdown Format

### IMPORTANT: Slide Boundaries

**The parser uses `#` (single hash) as the primary slide delimiter.** Each slide should start with a `#` heading that becomes the slide title. 

⚠️ **Critical Rules:**
- `#` (single hash) = Creates a new slide with that title
- `##` (double hash) = Creates a subtitle within the current slide
- `###` (triple hash) = Creates section headers within slide content
- Use `---` to separate slide metadata sections (optional)

### Basic Slide Structure

Each slide in `slides.md` should follow this format:

```markdown
# [Slide Title]
**Page Layout:** [Layout type - see options below]

**Visual Specification:**
- Background: [Color/gradient/image specification]
- [Additional visual elements]

**Content:**
## [Optional Subtitle]
[Main slide content]
### [Section headers as needed]

**Speaker Notes:**
[Notes for presenter]

---
```

### Alternative: Metadata-First Format

If you prefer to have slide metadata/description first:

```markdown
<!-- Slide 1: Title Slide -->
**Page Layout:** title

**Visual Specification:**
- Background: gradient

# Actual Slide Title
## Subtitle if needed

[Content continues...]

---
```

**Note:** Comments `<!-- -->` are ignored by the parser and can be used for organizing your markdown file.

### Layout Types

Available layout types:
- `title` - Title slide with centered content
- `title-subtitle` - Title with subtitle
- `split-screen` - Two-column layout
- `full-screen` - Edge-to-edge content
- `grid-2x2` - 2x2 grid layout
- `grid-3x3` - 3x3 grid layout
- `content-with-sidebar` - Main content with side panel
- `comparison` - Side-by-side comparison
- `timeline` - Horizontal timeline layout
- `network-diagram` - Network/architecture visualization

### Visual Elements

Specify visual elements using markdown with asset references:

```markdown
**Visual Specification:**
- Background: ![gradient](assets/backgrounds/blue-gradient.png)
- Logo: ![logo](assets/logos/company-logo.svg)
- Icon: 🚀 or ![icon](assets/icons/rocket.svg)
- Overlay: Semi-transparent panel (#000000, 50% opacity)
```

## Asset Management

### Image References

Reference images using relative paths from the presentation folder:

```markdown
![alt text](assets/category/filename.ext)

Examples:
![Company Logo](assets/logos/company-logo.png)
![CEO Photo](assets/photos/ceo-headshot.jpg)
![Architecture](assets/diagrams/system-architecture.svg)
```

### Supported Image Formats
- **Logos:** SVG (preferred), PNG with transparency
- **Photos:** JPG, PNG (min 800x800px for headshots)
- **Screenshots:** PNG, JPG (min 1920x1080px)
- **Diagrams:** SVG (preferred), PNG with transparency
- **Backgrounds:** JPG, PNG (min 1920x1080px)

## Data Binding

### Metrics File (data/metrics.json)

```json
{
  "github_stars": 2547,
  "contributors": 89,
  "production_deployments": 156,
  "monthly_active_users": 45000,
  "performance": {
    "api_response_time": "45ms",
    "throughput": "10K req/s",
    "uptime": "99.99%",
    "accuracy": "99.5%"
  },
  "growth": {
    "monthly_rate": "15%",
    "yoy": "380%"
  }
}
```

### Speakers File (data/speakers.json)

```json
{
  "speakers": [
    {
      "id": "jane-smith",
      "name": "Dr. Jane Smith",
      "title": "Co-founder & CTO",
      "photo": "assets/photos/jane-smith.jpg",
      "bio": [
        "15 years enterprise AI",
        "Former Google Research",
        "PhD Computer Science, MIT"
      ],
      "contact": {
        "email": "jane@company.com",
        "twitter": "@janesmith",
        "linkedin": "linkedin.com/in/janesmith"
      }
    },
    {
      "id": "john-doe",
      "name": "John Doe",
      "title": "Co-founder & CEO",
      "photo": "assets/photos/john-doe.jpg",
      "bio": [
        "12 years ML engineering",
        "Former Microsoft Azure",
        "Startup scaling expert"
      ],
      "contact": {
        "email": "john@company.com",
        "twitter": "@johndoe",
        "linkedin": "linkedin.com/in/johndoe"
      }
    }
  ]
}
```

### Timeline File (data/timeline.json)

```json
{
  "milestones": [
    {
      "date": "2021-01",
      "title": "Project Inception",
      "description": "Started as internal tool"
    },
    {
      "date": "2022-03",
      "title": "Open Source Release",
      "description": "First public version on GitHub"
    },
    {
      "date": "2023-06",
      "title": "Google Summer of Code",
      "description": "5 students, major features added"
    },
    {
      "date": "2024-01",
      "title": "Enterprise Version",
      "description": "Production-ready with MCP support"
    }
  ]
}
```

### Links File (data/links.json)

```json
{
  "github": "https://github.com/llm4s/llm4s",
  "documentation": "https://docs.llm4s.io",
  "discord": "https://discord.gg/llm4s",
  "demo": "https://demo.llm4s.io/szork",
  "conference": {
    "name": "ScalaCon 2024",
    "date": "March 15-17, 2024",
    "location": "San Francisco, CA"
  }
}
```

### Using Data in Slides

Reference data using the binding syntax:

```markdown
## Slide 1: Title
**Content:**
- GitHub Stars: {data:metrics.github_stars}
- Lead Speaker: {speaker:jane-smith.name}
- Conference: {data:links.conference.name}
- Timeline: {timeline:2023-06.title}
```

## Code Examples

### Structure (code/examples/)

Place complete, working code examples in appropriate files:

```
code/
├── examples/
│   ├── scala/
│   │   ├── agent-example.scala
│   │   ├── tool-calling.scala
│   │   └── rag-implementation.scala
│   ├── python/
│   │   └── comparison.py
│   └── config/
│       └── build.sbt
```

### Referencing Code in Slides

```markdown
**Code Example:**
```scala
{code:examples/scala/agent-example.scala}
```

Or for partial code:
```scala
{code:examples/scala/agent-example.scala:10-25}
```
```

## Output Versioning

The system automatically manages presentation versions:

### Automatic Versioning
- First generation: `output/v001_presentation.pptx`
- Second generation: `output/v002_presentation.pptx`
- Latest symlink: `output/latest.pptx` → current version

### Version Metadata
Each version includes metadata:
```
output/
├── v001_presentation.pptx
├── v001_metadata.json     # Generation timestamp, parameters
├── v002_presentation.pptx
├── v002_metadata.json
└── latest.pptx -> v002_presentation.pptx
```

## Complete Example

### Example slides.md

```markdown
<!-- Slide 1: Title Slide -->
# Scala Meets GenAI
**Page Layout:** title

**Visual Specification:**
- Background: ![gradient](assets/backgrounds/scala-ai-gradient.png)
- Logo: ![logo](assets/logos/llm4s-logo.svg) (bottom center)

**Content:**
## Build the Cool Stuff with LLM4S

**Presenters:** {speaker:jane-smith.name} & {speaker:john-doe.name}
**Event:** {data:links.conference.name} | {data:links.conference.date}

**Speaker Notes:**
Welcome everyone! Today we'll explore how Scala and AI come together to build amazing applications.

---

<!-- Slide 2: Metrics Dashboard -->
# Our Metrics
**Page Layout:** grid-2x2

**Visual Specification:**
- Background: #f5f5f5
- Icons: Use category icons for each metric

**Content:**
| Metric | Value |
|--------|-------|
| GitHub Stars | {data:metrics.github_stars} |
| Contributors | {data:metrics.contributors} |
| Response Time | {data:metrics.performance.api_response_time} |
| Uptime | {data:metrics.performance.uptime} |

**Speaker Notes:**
These metrics demonstrate our production readiness and community adoption.

---

<!-- Slide 3: Code Example -->
# Type-Safe Agent Implementation
**Page Layout:** content-with-sidebar

**Visual Specification:**
- Syntax highlighting: Scala
- Side panel: Generation parameters

**Content:**
```scala
{code:examples/scala/agent-example.scala:1-15}
```

**Side Panel:**
- ✅ Compile-time safety
- ✅ Immutable state
- ✅ Functional approach

**Speaker Notes:**
Notice how Scala's type system prevents runtime errors that are common in dynamic languages.

---

<!-- Slide 4: Team Introduction -->
# Meet the Team
**Page Layout:** split-screen

**Visual Specification:**
- Photos: Professional headshots
- Layout: Two columns with photos and bio

**Content:**
### {speaker:jane-smith.name}
![Photo]({speaker:jane-smith.photo})
**{speaker:jane-smith.title}**
{speaker:jane-smith.bio}

### {speaker:john-doe.name}
![Photo]({speaker:john-doe.photo})
**{speaker:john-doe.title}**
{speaker:john-doe.bio}

**Speaker Notes:**
We're the original founders of LLM4S, bringing combined 27 years of experience.
```

## Common Mistakes to Avoid

### Slide Boundary Issues
- ❌ **Wrong:** Using `## Slide N:` as slide delimiter (creates duplicate slides)
- ✅ **Right:** Using `#` for slide titles (single hash = new slide)

### Heading Hierarchy  
- ❌ **Wrong:** Multiple `#` headings in one slide (creates multiple slides)
- ✅ **Right:** One `#` per slide, use `##` for subtitles, `###` for sections

### Example of Incorrect Format (Creates Duplicates):
```markdown
## Slide 1: Introduction     # Parser treats this as a slide
# Welcome to Our Product      # Parser treats this as ANOTHER slide
Content here...
```

### Example of Correct Format:
```markdown
<!-- Slide 1: Introduction -->  # Comment for organization (ignored)
# Welcome to Our Product         # Single slide created with this title
## Innovative Solutions          # Subtitle within the same slide
### Key Features                 # Section header within content
Content here...
```

## Validation Checklist

Before generating your presentation, ensure:

- [ ] `slides.md` exists in the root folder
- [ ] All referenced images exist in `assets/`
- [ ] `data/` folder contains all required JSON files
- [ ] No placeholder text ([Name], ???, TBD) remains
- [ ] All metrics are current and accurate
- [ ] Code examples compile/run successfully
- [ ] External links are valid and accessible
- [ ] Image resolutions meet minimum requirements
- [ ] Speaker notes are complete for all slides
- [ ] File paths use correct relative references

## Quick Slide Template

Copy this template for each slide in your `slides.md`:

```markdown
<!-- Slide N: Description for your reference -->
# Your Slide Title Here
**Page Layout:** [choose: title, content, split-screen, grid-2x2, etc.]

**Visual Specification:**
- Background: [color or image]
- [other visual elements]

**Content:**
## Optional Subtitle
Your main content here
### Section Headers as needed
- Bullet points
- More content

**Speaker Notes:**
Notes for the presenter

---
```

## Running the Generator

```bash
# Generate presentation from folder
python src/main.py /path/to/your-presentation/

# The output will be created in:
# /path/to/your-presentation/output/v001_presentation.pptx
```

## Tips for Best Results

1. **High-Quality Assets**: Use vector formats (SVG) when possible
2. **Consistent Naming**: Use kebab-case for all files (my-image.png)
3. **Backup Originals**: Keep source files for all diagrams
4. **Test Early**: Generate frequently during development
5. **Version Control**: Use git-lfs for large binary assets
6. **Accessibility**: Provide alt text for all images
7. **Responsive Design**: Consider different screen sizes
8. **Color Contrast**: Ensure text is readable on backgrounds
9. **File Size**: Optimize images for presentation use
10. **Licensing**: Ensure you have rights to all assets used