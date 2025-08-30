# Generated code for slide 1: Prepared Topics:
# Slide type: comparison
# Theme: professional

slide = presentation.slides.add_slide(presentation.slide_layouts[6])

title_shape = slide.shapes.add_textbox(Inches(0.5), Inches(0.3), Inches(9), Inches(0.8))
title_frame = title_shape.text_frame
title_frame.text = "Prepared Topics:"
title_frame.paragraphs[0].font.size = Pt(28)
title_frame.paragraphs[0].font.bold = True
title_frame.paragraphs[0].font.color.rgb = RGBColor(44, 62, 80)
title_frame.paragraphs[0].alignment = PP_ALIGN.CENTER

left_header = slide.shapes.add_textbox(Inches(0.5), Inches(1.3), Inches(4.2), Inches(0.6))
left_header_frame = left_header.text_frame
left_header_frame.text = "Technical Questions"
left_header_frame.paragraphs[0].font.size = Pt(20)
left_header_frame.paragraphs[0].font.bold = True
left_header_frame.paragraphs[0].font.color.rgb = RGBColor(52, 152, 219)
left_header_frame.paragraphs[0].alignment = PP_ALIGN.CENTER

left_bg = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0.3), Inches(1.2), Inches(4.6), Inches(5.2))
left_bg.fill.solid()
left_bg.fill.fore_color.rgb = RGBColor(245, 248, 252)
left_bg.line.color.rgb = RGBColor(52, 152, 219)
left_bg.line.width = Pt(2)

left_content = slide.shapes.add_textbox(Inches(0.6), Inches(2.0), Inches(4.0), Inches(4.2))
left_frame = left_content.text_frame
left_frame.text = "• How does performance compare to Python?\n\n• Can it work with local models?\n\n• What about streaming responses?\n\n• Rate limiting strategies?"
left_frame.paragraphs[0].font.size = Pt(14)
left_frame.paragraphs[0].font.color.rgb = RGBColor(44, 62, 80)
for paragraph in left_frame.paragraphs:
    paragraph.font.size = Pt(14)
    paragraph.font.color.rgb = RGBColor(44, 62, 80)
    paragraph.space_after = Pt(8)

right_header = slide.shapes.add_textbox(Inches(5.3), Inches(1.3), Inches(4.2), Inches(0.6))
right_header_frame = right_header.text_frame
right_header_frame.text = "Implementation & Deployment"
right_header_frame.paragraphs[0].font.size = Pt(20)
right_header_frame.paragraphs[0].font.bold = True
right_header_frame.paragraphs[0].font.color.rgb = RGBColor(231, 76, 60)
right_header_frame.paragraphs[0].alignment = PP_ALIGN.CENTER

right_bg = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(5.1), Inches(1.2), Inches(4.6), Inches(5.2))
right_bg.fill.solid()
right_bg.fill.fore_color.rgb = RGBColor(252, 245, 245)
right_bg.line.color.rgb = RGBColor(231, 76, 60)
right_bg.line.width = Pt(2)

right_content = slide.shapes.add_textbox(Inches(5.4), Inches(2.0), Inches(4.0), Inches(4.2))
right_frame = right_content.text_frame
right_frame.text = "• RAG implementation details?\n\n• Production deployment tips?\n\n• Contributing to LLM4S?\n\n• Best practices & patterns?"
right_frame.paragraphs[0].font.size = Pt(14)
right_frame.paragraphs[0].font.color.rgb = RGBColor(44, 62, 80)
for paragraph in right_frame.paragraphs:
    paragraph.font.size = Pt(14)
    paragraph.font.color.rgb = RGBColor(44, 62, 80)
    paragraph.space_after = Pt(8)

divider = slide.shapes.add_connector(1, Inches(5), Inches(1.5), Inches(5), Inches(6.2))
divider.line.color.rgb = RGBColor(149, 165, 166)
divider.line.width = Pt(3)

bottom_note = slide.shapes.add_textbox(Inches(0.5), Inches(6.8), Inches(9), Inches(0.7))
bottom_note_frame = bottom_note.text_frame
bottom_note_frame.text = "Prepared backup questions ready for deeper technical discussions"
bottom_note_frame.paragraphs[0].font.size = Pt(12)
bottom_note_frame.paragraphs[0].font.italic = True
bottom_note_frame.paragraphs[0].font.color.rgb = RGBColor(127, 140, 141)
bottom_note_frame.paragraphs[0].alignment = PP_ALIGN.CENTER

notes_slide = slide.notes_slide
notes_text_frame = notes_slide.notes_text_frame
notes_text_frame.text = '[Prepared backup questions if needed]\n• "How does RAG performance compare to vector DBs?" - We support multiple backends...\n• "Can it work with Llama?" - Yes, through Ollama integration...\n• "Performance vs Python?" - JVM advantages at scale...'