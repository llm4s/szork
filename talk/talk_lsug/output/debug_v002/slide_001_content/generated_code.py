# Generated code for slide 1: Questions?
# Slide type: content
# Theme: professional

slide_layout = presentation.slide_layouts[1]
slide = presentation.slides.add_slide(slide_layout)

if slide.shapes.title:
    title = slide.shapes.title
    title.text = "Questions?"
    title.text_frame.paragraphs[0].font.size = Pt(32)
    title.text_frame.paragraphs[0].font.bold = True
    title.text_frame.paragraphs[0].font.color.rgb = RGBColor(44, 62, 80)

if len(slide.placeholders) > 1:
    content = slide.placeholders[1]
    tf = content.text_frame
    tf.text = "Rory Graves"
    tf.paragraphs[0].font.size = Pt(20)
    tf.paragraphs[0].font.bold = True
    tf.paragraphs[0].font.color.rgb = RGBColor(44, 62, 80)
    
    p = tf.add_paragraph()
    p.text = "🐦 @a_dev_musing"
    p.level = 1
    p.font.size = Pt(16)
    p.font.color.rgb = RGBColor(52, 152, 219)
    
    p = tf.add_paragraph()
    p.text = "💼 linkedin.com/in/roryjgraves"
    p.level = 1
    p.font.size = Pt(16)
    p.font.color.rgb = RGBColor(52, 152, 219)
    
    p = tf.add_paragraph()
    p.text = ""
    p.font.size = Pt(10)
    
    p = tf.add_paragraph()
    p.text = "Kannupriya Kalra"
    p.level = 0
    p.font.size = Pt(20)
    p.font.bold = True
    p.font.color.rgb = RGBColor(44, 62, 80)
    
    p = tf.add_paragraph()
    p.text = "🐦 @KannupriyaKalra"
    p.level = 1
    p.font.size = Pt(16)
    p.font.color.rgb = RGBColor(52, 152, 219)
    
    p = tf.add_paragraph()
    p.text = "💼 linkedin.com/in/kannupriyakalra"
    p.level = 1
    p.font.size = Pt(16)
    p.font.color.rgb = RGBColor(52, 152, 219)
    
    p = tf.add_paragraph()
    p.text = ""
    p.font.size = Pt(10)
    
    p = tf.add_paragraph()
    p.text = "Project: github.com/llm4s/llm4s"
    p.level = 0
    p.font.size = Pt(18)
    p.font.bold = True
    p.font.color.rgb = RGBColor(231, 76, 60)

notes_slide = slide.notes_slide
notes_text_frame = notes_slide.notes_text_frame
notes_text_frame.text = "Thank you for joining us today! We have a few minutes for questions. Both of us are here to answer anything about LLM4S, SZork, or building AI applications in Scala."