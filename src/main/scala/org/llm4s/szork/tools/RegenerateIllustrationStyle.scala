package org.llm4s.szork.tools

import org.llm4s.szork.api.SzorkConfig
import org.llm4s.szork.media.SceneImages
import java.nio.file.{Files, Paths}

/** Regenerates just the illustration style sample, with a black and white sketch aesthetic.
  *
  * Run from the repository root: `sbt "runMain org.llm4s.szork.tools.RegenerateIllustrationStyle"`
  */
object RegenerateIllustrationStyle {

  def main(args: Array[String]): Unit = {
    println("Regenerating illustration style sample with black and white sketch aesthetic...")

    val outputDir = Paths.get("frontend/public/style-samples")
    if (!Files.exists(outputDir)) {
      Files.createDirectories(outputDir)
    }

    // The llm4s image client for the configured provider (SZORK_IMAGE_PROVIDER and its credentials)
    val imageClient =
      SceneImages.config(SzorkConfig.instance).flatMap(SceneImages.llm4sClient(_, tracing = None)) match {
        case Right(client) => client
        case Left(reason) =>
          println(s"ERROR: scene images unavailable: $reason")
          sys.exit(1)
      }

    // Professional pencil art style
    val sceneDescription =
      "A brave adventurer standing at the entrance of a mysterious dungeon, torches on stone walls, treasure chest visible in the shadows"
    val stylePrompt =
      "professional pencil drawing, detailed graphite art, realistic shading and texture, artist sketchbook quality, fine pencil strokes, subtle gradients, architectural precision, professional illustration, pencil on paper texture, detailed crosshatching"

    val fullPrompt = s"$sceneDescription, rendered in $stylePrompt"

    println(s"Generating new illustration sample...")
    println(s"Prompt: ${fullPrompt.take(150)}...")

    imageClient.generateImage(fullPrompt, SceneImages.Options) match {
      case Right(image) =>
        val imageData = image.asBytes
        val outputPath = outputDir.resolve("illustration-sample.png")
        Files.write(outputPath, imageData)
        println(s"✓ Saved new illustration sample to: $outputPath")
        println(s"  Size: ${imageData.length / 1024} KB")

        println("\n" + "=" * 60)
        println("REGENERATION COMPLETE!")
        println("=" * 60)
        println("\nThe illustration style has been updated to a simpler black and white sketch style.")
        println("The new image emphasizes clean line work without shading for a minimalist look.")

      case Left(error) =>
        println(s"✗ Failed to generate illustration sample: ${error.message}")
        sys.exit(1)
    }
  }
}
