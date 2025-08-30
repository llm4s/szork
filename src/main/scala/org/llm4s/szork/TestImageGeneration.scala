package org.llm4s.szork

/**
 * Simple test program to verify the DALL-E integration works
 */
object TestImageGeneration {
  def main(args: Array[String]): Unit = {
    println("Testing LLM4S DALL-E integration...")
    
    // Environment is loaded automatically by EnvLoader
    
    try {
      val imageGen = new ImageGeneration()
      val prompt = "A mystical dungeon entrance with ancient stone steps leading down into darkness, torches flickering on the walls"
      val style = ImageGeneration.STYLE_DUNGEON
      
      println(s"Generating image with prompt: $prompt")
      println(s"Style: $style")
      
      imageGen.generateScene(prompt, style) match {
        case Right(base64Image) =>
          println(s"✅ Image generation successful!")
          println(s"   Base64 image length: ${base64Image.length} characters")
          println(s"   First 100 chars: ${base64Image.take(100)}...")
          
        case Left(error) =>
          println(s"❌ Image generation failed: $error")
      }
      
      // Test without style
      println("\nTesting without additional style...")
      imageGen.generateScene("A simple wooden door") match {
        case Right(base64Image) =>
          println(s"✅ Image generation successful (no style)!")
          println(s"   Base64 image length: ${base64Image.length} characters")
          
        case Left(error) =>
          println(s"❌ Image generation failed: $error")
      }
      
    } catch {
      case e: Exception =>
        println(s"❌ Error during test: ${e.getMessage}")
        e.printStackTrace()
    }
  }
}