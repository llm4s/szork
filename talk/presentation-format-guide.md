# Professional Presentation Format Guide for LLMs

## Overview
This guide teaches LLMs how to create structured, professional presentations with explicit specifications for designers. The format emphasizes clarity, data accuracy, and actionable visual instructions.

---

## Core Structure Template

Each slide must contain four essential sections:

```markdown
## Slide [Number]: [Title]
**Page Layout:** [Specific layout description]

**Visual Specification:**
[Detailed visual requirements]

**[Content Section]:**
[Data, text, or other content]

**Speaker Notes:**
"[What the presenter should say]"
```

---

## Section 1: Page Layout

The **Page Layout** section describes the overall arrangement of elements on the slide. Be specific about positioning and structure.

### Examples of Page Layouts:
- `Full-screen with split background effect`
- `Center-aligned with large numbers`
- `Two-column comparison layout`
- `Timeline spanning width of slide`
- `Grid layout with 2x2 cards`
- `Pyramid hierarchy with three tiers`
- `World map with data overlay`
- `Split screen with clear dividing line`

### Good Example:
```markdown
**Page Layout:** Split screen with clear dividing line
```

### Bad Example:
```markdown
**Page Layout:** Nice looking slide with some content
```

---

## Section 2: Visual Specification

The **Visual Specification** section provides detailed instructions for visual elements. Include:
- Colors and styling
- Specific graphic elements
- Animations or effects
- Font suggestions
- Icon requirements

### Template:
```markdown
**Visual Specification:**
- Background: [Color/gradient/image description]
- Main elements: [List of visual components]
- Color scheme: [Specific colors or themes]
- Typography: [Font suggestions if any]
- Special effects: [Animations, transitions]
```

### Good Example:
```markdown
**Visual Specification:**
- Background: Clean white with subtle gradient
- Numbers displayed as large cards with animated count-up effect
- Each number in a rounded rectangle with drop shadow
- Use modern sans-serif font (suggested: Inter or Helvetica Neue)
- Color code: Primary numbers in bold blue (#0066CC), descriptions in grey (#666666)
```

---

## Section 3: Content Sections

Content should be provided as data, not as visual descriptions. Use tables for structured data that designers can easily implement.

### For Data Tables:

Always provide tables in markdown format that can be copied and used:

```markdown
**[Table Name]:**
| Column 1 | Column 2 | Column 3 |
|----------|----------|----------|
| Data 1   | Data 2   | Data 3   |
| Data 4   | Data 5   | Data 6   |
```

### Good Example:
```markdown
**Regional Talent Distribution:**
| Region | % of Global AI Graduates | Growth Rate |
|--------|-------------------------|-------------|
| China | 50% | +45%/year |
| USA | 18% | +15%/year |
| Europe | 15% | +25%/year |
```

### Bad Example:
```markdown
**Regional Talent Distribution:**
Show a nice chart with China having the most graduates
```

### For Text Content:

Structure text hierarchically:

```markdown
**Main Points:**
- Point 1: Clear statement
- Point 2: Another statement
  - Sub-point A
  - Sub-point B
```

### For Code or Technical Examples:

Use code blocks with syntax highlighting:

```markdown
**Code Example:**
```python
def neural_network(input, weights):
    return activate(sum(input * weights))
```
```

---

## Section 4: Speaker Notes

Speaker notes should be conversational, engaging, and provide context beyond what's on the slide. Include:
- Key talking points
- Transitions to next slide
- Anecdotes or examples
- Audience engagement prompts

### Template:
```markdown
**Speaker Notes:**
"[Opening statement]. [Key points to elaborate on]. [Transition or engagement prompt]."
```

### Good Example:
```markdown
**Speaker Notes:**
"Remember that English major you told to 'learn to code'? They're having the last laugh. Turns out, making AI do what you want requires understanding language, logic, and human psychology - exactly what humanities majors study. The best prompt engineers aren't computer scientists; they're people who can argue with a machine and win. This completely breaks traditional recruiting patterns. Embrace it."
```

---

## Special Elements

### 1. Charts and Graphs

When requesting charts, specify:
- Chart type (bar, line, pie, etc.)
- Axis labels
- Data points
- Visual style

```markdown
**Competition Results Chart:**
```
2010: 28.2% error |████████████████████████████|
2011: 25.8% error |█████████████████████████|
2012: 15.3% error |███████████████|  ← AlexNet
                    "HOLY $#!@" - Everyone
```
```

### 2. Comparison Tables

Use clear headers and consistent formatting:

```markdown
**Safety Performance Comparison:**
| Metric | Tesla | Waymo |
|--------|-------|-------|
| Approach | Cameras only | LiDAR + Multi-sensor |
| Fatal crashes | 51 | 0 |
| Total crashes | 736 | Minor only |
| Safety vs humans | Worse in many scenarios | 79% fewer crashes |
```

### 3. Timeline Data

Present timelines as structured data:

```markdown
**Knight Capital Timeline:**
| Time | Event | Cumulative Loss |
|------|-------|-----------------|
| 9:30 AM | Market opens | $0 |
| 9:31 AM | Old code activates | $0 |
| 9:35 AM | First alerts ignored | $5 million |
| 9:45 AM | Panic sets in | $100 million |
```

### 4. Visual Indicators

Use Unicode or emoji sparingly for emphasis:

```markdown
**Red Flags (Avoid These):**
| Warning Sign | What It Means |
|--------------|---------------|
| "AI Expert since 2023" | Bandwagon jumper |
| Lists 47 frameworks | No depth anywhere |

✅ Green Flags (Hire These):
| Positive Sign | What It Means |
|---------------|---------------|
| Survived AI winter | Proven adaptability |
```

---

## Best Practices

### 1. Be Explicit About Visuals
❌ Don't: "Show a nice graph of funding over time"
✅ Do: "Line graph with X-axis: Years (1970-2025), Y-axis: Funding level (indexed to 100 in 1970)"

### 2. Provide Actual Data
❌ Don't: "Table showing salaries are high"
✅ Do: Provide exact figures in a markdown table

### 3. Specify Colors Purposefully
❌ Don't: "Use some colors"
✅ Do: "Use red (#FF0000) for warnings, green (#00AA00) for success"

### 4. Structure for Clarity
❌ Don't: Write long paragraphs of mixed content
✅ Do: Use clear sections, bullet points, and tables

### 5. Make Speaker Notes Conversational
❌ Don't: Repeat slide content
✅ Do: Add context, stories, and engagement prompts

---

## Complete Slide Example

Here's a complete example following all guidelines:

```markdown
## Slide 15: When AI Goes Wrong - The $300K Arrests
**Page Layout:** Split layout with stats and chart

**Visual Specification:**
- Left side: Key statistics in large numbers
- Right side: Error rate comparison chart
- Bottom: Company response quote
- Use red accent color for emphasis

**Facial Recognition Failure Statistics:**
| Metric | Value |
|--------|-------|
| Documented wrongful arrests | 7 |
| Victims who were Black | 6 (86%) |
| Average detention time | 30+ hours |
| Average settlement cost | $300,000+ |

**Error Rate by Demographics:**
| Demographic | Error Rate |
|-------------|------------|
| White men | 1% |
| White women | 7% |
| Black men | 12% |
| Black women | 35% |

**Bottom Quote:** "This is why AI Safety Engineers now make $180K+"

**Speaker Notes:**
"Robert Williams was arrested in front of his family for a crime he didn't commit. The AI was wrong. This isn't just PR - it's legal liability. Companies desperately need talent that makes AI both smart AND fair."
```

---

## Common Patterns for Different Slide Types

### Title Slides
```markdown
**Page Layout:** Full-screen visual with overlaid text
**Visual Specification:**
- Background: [Specific image or gradient]
- Title placement: [Position and styling]
- Subtitle: [If applicable]
- Branding elements: [Logo placement, etc.]
```

### Data-Heavy Slides
```markdown
**Page Layout:** [Table/chart focus with supporting elements]
**Visual Specification:**
- Primary focus on data visualization
- Supporting text minimal
- Clear data labels
```

### Comparison Slides
```markdown
**Page Layout:** Two-column or split-screen comparison
**Visual Specification:**
- Clear visual separation between options
- Consistent formatting for both sides
- Highlighting for key differences
```

### Timeline Slides
```markdown
**Page Layout:** Horizontal or vertical timeline
**Visual Specification:**
- Timeline as primary visual element
- Events marked with consistent styling
- Time progression clear
```

---

## Final Checklist

Before completing any slide, verify:

- [ ] Page layout is explicitly described
- [ ] Visual specifications are detailed enough for a designer
- [ ] All data is provided in usable table format
- [ ] Content is structured, not in paragraph form
- [ ] Speaker notes add value beyond slide content
- [ ] Any requested charts/graphs have specific parameters
- [ ] Color suggestions include hex codes where appropriate
- [ ] The slide serves a clear purpose in the presentation flow

---

## Usage Instructions for LLMs

When creating presentations:

1. **Start with structure**: Define the overall presentation flow before creating individual slides
2. **Be specific**: Every visual element should have clear specifications
3. **Provide real data**: Never use placeholder data or vague descriptions
4. **Think like a designer**: Consider how someone would actually create this slide
5. **Write for speakers**: Make speaker notes conversational and engaging
6. **Maintain consistency**: Use similar formatting patterns throughout
7. **Test readability**: Ensure all content would be readable on a projected screen

Remember: The goal is to create a blueprint so detailed that any competent designer could create the exact presentation you envision without asking clarifying questions.