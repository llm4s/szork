package org.llm4s.szork

import org.llm4s.szork.adventures.{AdventureLibrary, BakeAdventure}
import org.llm4s.szork.game.PromptBuilder
import org.llm4s.szork.media.MediaPlanner
import org.llm4s.szork.game.SceneMedia
import org.llm4s.szork.tools.GenerateStyleSamples
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

/** The command-line tools: what BakeAdventure and GenerateStyleSamples accept and how they exit. */
class BakeAdventureSpec extends AnyFunSuite with Matchers {

  private def parse(args: String*) = BakeAdventure.parse(args.toList)

  test("with nothing chosen to bake, the command line is refused") {
    parse().left.toOption.get should include("Nothing to bake")
    parse("--art").isLeft shouldBe true
    parse("--style", "pixel", "--no-music").isLeft shouldBe true
    BakeAdventure.Usage should include("--no-images")
  }

  test("an adventure, --all, --new, --music or --list is something to bake") {
    parse("the-drowned-archive").toOption.get.ids shouldBe List("the-drowned-archive")
    parse("--all").toOption.get.all shouldBe true
    parse("--new").toOption.get.designNew shouldBe true
    parse("--list").toOption.get.list shouldBe true
    val musicOnly = parse("--music").toOption.get
    (musicOnly.images, musicOnly.music, musicOnly.musicOnly) shouldBe (false, true, true)
  }

  test("--no-images and --no-music leave their part out") {
    val none = parse("a", "--no-images", "--no-music").toOption.get
    (none.images, none.music) shouldBe (false, false)
    val both = parse("a").toOption.get
    (both.images, both.music) shouldBe (true, true)
  }

  test("a theme goes with --new, and only with --new") {
    val designing = parse("--new", "--theme", "a haunted lighthouse", "--theme-name", "Haunted").toOption.get
    (designing.newTheme, designing.themeName) shouldBe (Some("a haunted lighthouse"), Some("Haunted"))
    parse("a-id", "--theme", "a haunted lighthouse").left.toOption.get should include("--new")
    parse("a-id", "--theme-name", "Haunted").isLeft shouldBe true
  }

  test("the art style is checked and normalised") {
    parse("a", "--style", " Painting ").toOption.get.style shouldBe Some("painting")
    parse("a", "--style", "fantasy").toOption.get.style shouldBe Some("fantasy")
    parse("a", "--style", "gothic").left.toOption.get should (include("gothic") and include("pixel"))
    parse("a", "--style").isLeft shouldBe true
    parse("a", "--bogus").left.toOption.get should include("--bogus")
  }

  test("an adventure that is not in the library is a failure, and fails the bake") {
    val library = new AdventureLibrary(Files.createTempDirectory("szork-bake-test"))
    val (found, report) = BakeAdventure.lookup(library, List("no-such-adventure"))
    found shouldBe empty
    report.failed.map(_._1) shouldBe List("no-such-adventure")
    BakeAdventure.exitCode(report) shouldBe 1
    BakeAdventure.exitCode(BakeAdventure.Report(List("made"), List("kept"), Nil)) shouldBe 0
    BakeAdventure.exitCode(BakeAdventure.Report.empty) shouldBe 0
  }

  // ============= GenerateStyleSamples =============

  test("a style sample is made for every style, or the one named") {
    GenerateStyleSamples.select(Nil) shouldBe Right(MediaPlanner.StyleIds)
    GenerateStyleSamples.select(List("comic")) shouldBe Right(List("comic"))
    GenerateStyleSamples.select(List("gothic")).isLeft shouldBe true
    GenerateStyleSamples.select(List("pixel", "comic")).isLeft shouldBe true
  }

  test("a style sample is drawn from the prompt the game builds, and the tool fails when it made nothing") {
    MediaPlanner.StyleIds.foreach { style =>
      GenerateStyleSamples.promptFor(style) shouldBe
        MediaPlanner.styledImagePrompt(
          Some(style),
          GenerateStyleSamples.SampleScene,
          PromptBuilder.artStyleDescription(Some(style)))
      GenerateStyleSamples
        .promptFor(style) shouldBe SceneMedia.imagePrompt(Some(style), GenerateStyleSamples.SampleScene)
    }
    GenerateStyleSamples.exitCode(wanted = 4, made = 0) shouldBe 1
    GenerateStyleSamples.exitCode(wanted = 4, made = 1) shouldBe 0
    GenerateStyleSamples.exitCode(wanted = 0, made = 0) shouldBe 0
  }
}
