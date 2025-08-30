package org.llm4s.szork

import java.nio.file.{Files, Paths}
import java.util.Base64
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Script to generate sample images for each art style to display on the style selection page
 * Run with: sbt "szork/runMain org.llm4s.szork.GenerateStyleSamples"
 */
object GenerateStyleSamples {
  
  case class StyleSample(
    id: String,
    name: String,
    stylePrompt: String,
    sceneDescription: String
  )
  
  val styles = List(
    StyleSample(
      "pixel",
      "Pixel Art",
      "pixel art style, 16-bit retro game aesthetic, low resolution pixelated graphics, limited color palette, nostalgic video game art",
      "A brave adventurer standing at the entrance of a mysterious dungeon, torches on stone walls, treasure chest visible in the shadows"
    ),
    StyleSample(
      "illustration",
      "Pencil Art",
      "professional pencil drawing, detailed graphite art, realistic shading and texture, artist sketchbook quality, fine pencil strokes, subtle gradients, architectural precision",
      "A brave adventurer standing at the entrance of a mysterious dungeon, torches on stone walls, treasure chest visible in the shadows"
    ),
    StyleSample(
      "painting",
      "Painting",
      "fully rendered oil painting style, realistic lighting and textures, atmospheric depth, rich colors, fantasy art masterpiece, concept art quality",
      "A brave adventurer standing at the entrance of a mysterious dungeon, torches on stone walls, treasure chest visible in the shadows"
    ),
    StyleSample(
      "comic",
      "Comic/Graphic Novel",
      "comic book art style, bold black outlines, cel-shaded coloring, dynamic composition, graphic novel aesthetic, dramatic perspectives",
      "A brave adventurer standing at the entrance of a mysterious dungeon, torches on stone walls, treasure chest visible in the shadows"
    )
  )
  
  def main(args: Array[String]): Unit = {
    println("Starting style sample generation...")
    
    val outputDir = Paths.get("szork/frontend/public/style-samples")
    if (!Files.exists(outputDir)) {
      Files.createDirectories(outputDir)
      println(s"Created directory: $outputDir")
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
    
    // Generate images sequentially to avoid rate limits
    val futures = styles.map { style =>
      Future {
        println(s"\nGenerating ${style.name} sample...")
        val fullPrompt = s"${style.sceneDescription}, rendered in ${style.stylePrompt}"
        
        println(s"  Prompt: ${fullPrompt.take(100)}...")
        
        imageGen.generateScene(fullPrompt, ImageGeneration.STYLE_FANTASY) match {
          case Right(base64Image) =>
            // Save the base64 image to a file
            val imageData = Base64.getDecoder.decode(base64Image)
            val outputPath = outputDir.resolve(s"${style.id}-sample.png")
            Files.write(outputPath, imageData)
            println(s"  ✓ Saved ${style.name} sample to: $outputPath")
            println(s"    Size: ${imageData.length / 1024} KB")
            Some((style.id, outputPath.toString))
            
          case Left(error) =>
            println(s"  ✗ Failed to generate ${style.name} sample: $error")
            None
        }
      }
    }
    
    // Process futures sequentially with delays to respect rate limits
    val results = futures.zipWithIndex.flatMap { case (future, index) =>
      if (index > 0) {
        println(s"  Waiting 3 seconds before next request (rate limit)...")
        Thread.sleep(3000) // 3 second delay between requests
      }
      val result = Await.result(future, 30.seconds)
      result
    }
    
    println("\n" + "="*60)
    println("GENERATION COMPLETE!")
    println("="*60)
    
    if (results.nonEmpty) {
      println(s"\nSuccessfully generated ${results.length} style samples:")
      results.foreach { case (id, path) =>
        println(s"  - $id: $path")
      }
      
      println("\nTo use these images in the frontend:")
      println("1. The images have been saved to: szork/frontend/public/style-samples/")
      println("2. Update AdventureSetup.vue to reference these images")
      println("3. Images can be accessed as: /style-samples/[style-id]-sample.png")
      
      // Generate a JSON manifest for the frontend
      val manifest = ujson.Obj(
        "generated" -> java.time.Instant.now().toString,
        "styles" -> results.map { case (id, _) =>
          ujson.Obj(
            "id" -> id,
            "image" -> s"/style-samples/$id-sample.png"
          )
        }
      )
      
      val manifestPath = outputDir.resolve("manifest.json")
      Files.write(manifestPath, ujson.write(manifest, indent = 2).getBytes)
      println(s"\nGenerated manifest at: $manifestPath")
    } else {
      println("\nNo images were generated. Please check the errors above.")
    }
  }
}