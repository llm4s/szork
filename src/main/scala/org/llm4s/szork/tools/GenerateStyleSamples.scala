package org.llm4s.szork.tools

import org.llm4s.szork.api.SzorkConfig
import org.llm4s.szork.media.SceneImages
import java.nio.file.{Files, Paths}

/** Generates the sample image for each art style shown on the style selection page.
  *
  * Run from the repository root: `sbt "runMain org.llm4s.szork.tools.GenerateStyleSamples"`
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

    val outputDir = Paths.get("frontend/public/style-samples")
    if (!Files.exists(outputDir)) {
      Files.createDirectories(outputDir)
      println(s"Created directory: $outputDir")
    }

    // The llm4s image client for the configured provider (SZORK_IMAGE_PROVIDER and its credentials)
    val imageClient =
      SceneImages.config(SzorkConfig.instance).flatMap(SceneImages.llm4sClient(_, tracing = None)) match {
        case Right(client) => client
        case Left(reason) =>
          println(s"ERROR: scene images unavailable: $reason")
          sys.exit(1)
      }

    // One request at a time, with a pause between them, to stay within provider rate limits
    val results = styles.zipWithIndex.flatMap { case (style, index) =>
      if (index > 0) {
        println("  Waiting 3 seconds before next request (rate limit)...")
        Thread.sleep(3000)
      }
      println(s"\nGenerating ${style.name} sample...")
      val fullPrompt = s"${style.sceneDescription}, rendered in ${style.stylePrompt}"
      println(s"  Prompt: ${fullPrompt.take(100)}...")

      imageClient.generateImage(fullPrompt, SceneImages.Options) match {
        case Right(image) =>
          val imageData = image.asBytes
          val outputPath = outputDir.resolve(s"${style.id}-sample.png")
          Files.write(outputPath, imageData)
          println(s"  ✓ Saved ${style.name} sample to: $outputPath (${imageData.length / 1024} KB)")
          Some((style.id, outputPath.toString))
        case Left(error) =>
          println(s"  ✗ Failed to generate ${style.name} sample: ${error.message}")
          None
      }
    }

    println("\n" + "=" * 60)
    println("GENERATION COMPLETE!")
    println("=" * 60)

    if (results.nonEmpty) {
      println(s"\nSuccessfully generated ${results.length} style samples:")
      results.foreach { case (id, path) =>
        println(s"  - $id: $path")
      }

      println("\nTo use these images in the frontend:")
      println(s"1. The images have been saved to: $outputDir")
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
