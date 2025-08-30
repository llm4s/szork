package org.llm4s.szork

import java.nio.file.{Files, Paths}
import java.util.Base64

/**
 * Script to regenerate just the illustration style with a simpler black and white sketch aesthetic
 * Run with: sbt "szork/runMain org.llm4s.szork.RegenerateIllustrationStyle"
 */
object RegenerateIllustrationStyle {
  
  def main(args: Array[String]): Unit = {
    println("Regenerating illustration style sample with black and white sketch aesthetic...")
    
    val outputDir = Paths.get("szork/frontend/public/style-samples")
    if (!Files.exists(outputDir)) {
      Files.createDirectories(outputDir)
    }
    
    // Try to create ImageGeneration - will throw if API key not configured
    val imageGen = try {
      ImageGeneration()
    } catch {
      case _: IllegalStateException =>
        println("ERROR: OpenAI API key not configured!")
        println("Please set the OPENAI_API_KEY environment variable")
        sys.exit(1)
    }
    
    // Professional pencil art style
    val sceneDescription = "A brave adventurer standing at the entrance of a mysterious dungeon, torches on stone walls, treasure chest visible in the shadows"
    val stylePrompt = "professional pencil drawing, detailed graphite art, realistic shading and texture, artist sketchbook quality, fine pencil strokes, subtle gradients, architectural precision, professional illustration, pencil on paper texture, detailed crosshatching"
    
    val fullPrompt = s"$sceneDescription, rendered in $stylePrompt"
    
    println(s"Generating new illustration sample...")
    println(s"Prompt: ${fullPrompt.take(150)}...")
    
    imageGen.generateScene(fullPrompt, "") match { // Empty style to avoid adding "fantasy art" etc
      case Right(base64Image) =>
        // Save the base64 image to a file
        val imageData = Base64.getDecoder.decode(base64Image)
        val outputPath = outputDir.resolve("illustration-sample.png")
        Files.write(outputPath, imageData)
        println(s"✓ Saved new illustration sample to: $outputPath")
        println(s"  Size: ${imageData.length / 1024} KB")
        
        println("\n" + "="*60)
        println("REGENERATION COMPLETE!")
        println("="*60)
        println("\nThe illustration style has been updated to a simpler black and white sketch style.")
        println("The new image emphasizes clean line work without shading for a minimalist look.")
        
      case Left(error) =>
        println(s"✗ Failed to generate illustration sample: $error")
        sys.exit(1)
    }
  }
}