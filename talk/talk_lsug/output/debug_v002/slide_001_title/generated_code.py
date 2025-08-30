# Generated code for slide 1: Q&A
# Slide type: title
# Theme: professional

slide_layout = presentation.slide_layouts[0]
slide = presentation.slides.add_slide(slide_layout)

title = slide.shapes.title
title.text = "Q&A"
title.text_frame.paragraphs[0].font.size = Pt(44)
title.text_frame.paragraphs[0].font.bold = True
title.text_frame.paragraphs[0].alignment = PP_ALIGN.CENTER
title.text_frame.paragraphs[0].font.color.rgb = RGBColor(44, 62, 80)

if len(slide.placeholders) > 1:
    subtitle = slide.placeholders[1]
    subtitle.text = "Prepared questions listed"
    subtitle.text_frame.paragraphs[0].font.size = Pt(24)
    subtitle.text_frame.paragraphs[0].alignment = PP_ALIGN.CENTER
    subtitle.text_frame.paragraphs[0].font.color.rgb = RGBColor(52, 152, 219)