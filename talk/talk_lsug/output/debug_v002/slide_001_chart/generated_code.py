# Generated code for slide 1: Output: Structured analysis
# Slide type: chart
# Theme: professional

slide = presentation.slides.add_slide(presentation.slide_layouts[6])

title_box = slide.shapes.add_textbox(Inches(0.5), Inches(0.3), Inches(9), Inches(0.8))
title_frame = title_box.text_frame
title_frame.text = "Output: Structured Analysis"
title_frame.paragraphs[0].font.size = Pt(28)
title_frame.paragraphs[0].font.bold = True
title_frame.paragraphs[0].font.color.rgb = RGBColor(44, 62, 80)

code_box = slide.shapes.add_textbox(Inches(0.5), Inches(1.2), Inches(5), Inches(2))
code_frame = code_box.text_frame
code_frame.text = 'val analysis = client.analyzeImage(\n  imageFile = "screenshot.png",\n  prompt = "Describe this UI and suggest improvements"\n)\n// Returns detailed component analysis'
code_frame.paragraphs[0].font.size = Pt(12)
code_frame.paragraphs[0].font.name = 'Courier New'

chart_data = CategoryChartData()
chart_data.categories = ['UI/UX Analysis', 'Document Processing', 'Accessibility Checking', 'Content Moderation']
chart_data.add_series('Analysis Accuracy %', [92, 88, 95, 89])
chart_data.add_series('Processing Time (ms)', [450, 320, 380, 280])

chart_shape = slide.shapes.add_chart(
    XL_CHART_TYPE.COLUMN_CLUSTERED,
    chart_data
)

chart = chart_shape.chart
chart.has_legend = True
chart.legend.position = XL_LEGEND_POSITION.BOTTOM
chart.legend.font.size = Pt(10)

for series in chart.series:
    series.format.line.width = Pt(1.5)

chart.series[0].format.fill.solid()
chart.series[0].format.fill.fore_color.rgb = RGBColor(52, 152, 219)

chart.series[1].format.fill.solid()
chart.series[1].format.fill.fore_color.rgb = RGBColor(155, 89, 182)

use_cases_box = slide.shapes.add_textbox(Inches(0.5), Inches(3.5), Inches(9), Inches(2))
use_cases_frame = use_cases_box.text_frame
use_cases_frame.text = "Use Cases:"
use_cases_frame.paragraphs[0].font.size = Pt(16)
use_cases_frame.paragraphs[0].font.bold = True

p1 = use_cases_frame.add_paragraph()
p1.text = "📱 UI/UX analysis"
p1.font.size = Pt(14)

p2 = use_cases_frame.add_paragraph()
p2.text = "🔍 Document processing"
p2.font.size = Pt(14)

p3 = use_cases_frame.add_paragraph()
p3.text = "♿ Accessibility checking"
p3.font.size = Pt(14)

p4 = use_cases_frame.add_paragraph()
p4.text = "🛡️ Content moderation"
p4.font.size = Pt(14)

notes_slide = slide.notes_slide
notes_text_frame = notes_slide.notes_text_frame
notes_text_frame.text = "Not just generation - understanding too. Upload any image and get structured analysis. Perfect for automated testing, documentation, or accessibility compliance."