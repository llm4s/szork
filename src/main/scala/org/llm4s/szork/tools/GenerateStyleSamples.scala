package org.llm4s.szork.tools

import org.llm4s.szork.api.SzorkConfig
import org.llm4s.szork.game.PromptBuilder
import org.llm4s.szork.media.{MediaPlanner, SceneImages}
import java.nio.file.{Files, Paths}

/** Generates the sample picture of each art style shown on the style selection page.
  *
  * The prompt is the one the game builds for a scene in that style ([[MediaPlanner.styledImagePrompt]]), so a sample
  * shows what the game draws. Run from the repository root:
  *
  * {{{
  * sbt "runMain org.llm4s.szork.tools.GenerateStyleSamples"            # every style
  * sbt "runMain org.llm4s.szork.tools.GenerateStyleSamples painting"   # one style
  * }}}
  *
  * Pictures are written to `frontend/public/style-samples/<style>-sample.png`; the style picker reads those paths from
  * `frontend/src/data/adventureThemes.ts`. The exit code is non-zero when no picture could be made.
  */
object GenerateStyleSamples {

  /** The art styles that have a sample (the ids of the setup screen's styles). */
  val styleIds: List[String] = MediaPlanner.StyleIds

  /** The scene every sample shows. */
  val SampleScene =
    "A brave adventurer standing at the entrance of a mysterious dungeon, torches on stone walls, treasure chest visible in the shadows."

  /** The prompt for the sample of `styleId`: what the game would send for [[SampleScene]] in that style. */
  def promptFor(styleId: String): String =
    MediaPlanner.styledImagePrompt(Some(styleId), SampleScene, PromptBuilder.artStyleDescription(Some(styleId)))

  /** The styles to make: all of them with no argument, otherwise the one named. */
  def select(args: List[String]): Either[String, List[String]] = args match {
    case Nil => Right(styleIds)
    case style :: Nil if styleIds.contains(style) => Right(List(style))
    case other => Left(s"Unknown art style '${other.mkString(" ")}'; the styles are ${styleIds.mkString(", ")}")
  }

  /** 1 when pictures were wanted and none could be made, otherwise 0. */
  def exitCode(wanted: Int, made: Int): Int = if (wanted > 0 && made == 0) 1 else 0

  def main(args: Array[String]): Unit = {
    val styles = select(args.toList) match {
      case Right(selected) => selected
      case Left(problem) =>
        println(s"ERROR: $problem")
        sys.exit(2)
    }

    val config = SzorkConfig.instance
    // The llm4s image client for the configured provider (SZORK_IMAGE_PROVIDER and its credentials)
    val imageClient = SceneImages.config(config).flatMap(SceneImages.llm4sClient(_, tracing = None)) match {
      case Right(client) => client
      case Left(reason) =>
        println(s"ERROR: scene images unavailable: $reason")
        sys.exit(1)
    }
    val options = SceneImages.options(config)

    val outputDir = Paths.get("frontend/public/style-samples")
    Files.createDirectories(outputDir)

    // One request at a time, with a pause between them, to stay within provider rate limits
    val made = styles.zipWithIndex.flatMap { case (style, index) =>
      if (index > 0) {
        println("  Waiting 3 seconds before the next request (rate limit)...")
        Thread.sleep(3000)
      }
      println(s"Generating the $style sample...")
      imageClient.generateImage(promptFor(style), options) match {
        case Right(image) =>
          val bytes = image.asBytes
          val path = outputDir.resolve(s"$style-sample.png")
          Files.write(path, bytes)
          println(s"  saved $path (${bytes.length / 1024} KB)")
          Some(style)
        case Left(error) =>
          println(s"  FAILED $style: ${error.message}")
          None
      }
    }

    println(
      s"\n${made.size} of ${styles.size} samples made in $outputDir (the style picker's paths are in frontend/src/data/adventureThemes.ts)")
    sys.exit(exitCode(styles.size, made.size))
  }
}
