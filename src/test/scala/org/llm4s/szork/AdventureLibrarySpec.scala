package org.llm4s.szork

import java.nio.file.{Files, Path}
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import org.llm4s.szork.adventures.{AdventureLibrary, BakeAdventure}
import org.llm4s.szork.api.{ArtStyle, GameTheme}
import org.llm4s.szork.error.ErrorHandling.SzorkResult
import org.llm4s.szork.error.ImageGenerationError
import org.llm4s.szork.game.{AdventureOutline, DungeonMasterOutput, ExitOutline, GameEngine, LocationOutline}
import org.llm4s.szork.media.MediaRef
import org.llm4s.szork.spi.{ImageClient, MusicClient}
import org.llm4s.szork.stubs.{DmAnswers, ScriptedDm}
import org.llm4s.testkit.Reply
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The adventure library: adventures kept to play again, a picture per location made once for every game of an
  * adventure, one music track per mood, and the Dungeon Master asking for a new picture when a place has changed.
  */
class AdventureLibrarySpec extends AnyFunSuite with Matchers {

  private def newLibrary(): AdventureLibrary = new AdventureLibrary(Files.createTempDirectory("szork-adventures-test"))

  // The scripted Dungeon Master's cavern, as an outline
  private val cavern = AdventureOutline(
    title = "The Echoing Cavern!",
    tagline = Some("Something stirs below"),
    mainQuest = "Find the lantern",
    subQuests = Nil,
    keyLocations = List(
      LocationOutline(
        "cavern_entrance",
        "Cavern Entrance",
        "A cold cave mouth. A passage leads north.",
        "The start",
        exits = List(ExitOutline("north", "hall", "A passage leads north", "open"))
      ),
      LocationOutline(
        "hall",
        "Small Hall",
        "A small hall. The way back is south.",
        "Where the lantern lies",
        exits = List(ExitOutline("south", "cavern_entrance", "The way back", "open"))
      )
    ),
    importantItems = Nil,
    keyCharacters = Nil,
    adventureArc = "In, and out again"
  )
  private val theme = Some(GameTheme("caves", "Caves", "an underground realm"))
  private val pixel = Some(ArtStyle("pixel", "Pixel Art"))

  private def base64(text: String): String = Base64.getEncoder.encodeToString(text.getBytes("UTF-8"))

  /** Draws a picture whose bytes are its prompt, and records what it was asked for. */
  private final class RecordingImages extends ImageClient {
    val prompts = new java.util.concurrent.CopyOnWriteArrayList[String]()
    override def generateScene(
      prompt: String,
      style: String,
      gameId: Option[String],
      locationId: Option[String]): SzorkResult[String] = {
      prompts.add(prompt)
      Right(base64(prompt))
    }
    def count: Int = prompts.size
  }

  private final class CountingMusic extends MusicClient {
    val made = new AtomicInteger()
    override def isAvailable: Boolean = true
    override def generate(
      mood: String,
      context: String,
      gameId: Option[String],
      locationId: Option[String]): SzorkResult[String] = {
      made.incrementAndGet()
      Right(base64(s"track for $mood"))
    }
  }

  private def bytesAt(library: AdventureLibrary, stored: MediaRef.Stored): String = {
    val path = stored.url.stripPrefix(AdventureLibrary.UrlPrefix + "/").takeWhile(_ != '?')
    val (file, _) = library.resolve(path.split('/').toSeq).get
    new String(Files.readAllBytes(file), "UTF-8")
  }

  // ============= Adventures =============

  test("a kept adventure is listed and loads as it was kept, under an id made from its title") {
    val library = newLibrary()
    val kept = library.keep(cavern, theme, pixel).toOption.get
    kept.id shouldBe "the-echoing-cavern"

    val listed = library.list
    listed.map(_.id) shouldBe List("the-echoing-cavern")
    listed.head.title shouldBe "The Echoing Cavern!"
    listed.head.theme shouldBe Some("Caves")
    listed.head.artStyle shouldBe Some("pixel")
    (listed.head.locations, listed.head.images) shouldBe (2, 0)

    val loaded = library.load(kept.id).get
    loaded.outline shouldBe cavern
    loaded.theme shouldBe theme
    loaded.artStyle shouldBe pixel
  }

  test("a second adventure with the same title gets its own id, and deleting one leaves the other") {
    val library = newLibrary()
    library.keep(cavern, theme, pixel).toOption.get.id shouldBe "the-echoing-cavern"
    library.keep(cavern, None, None).toOption.get.id shouldBe "the-echoing-cavern-2"

    library.delete("the-echoing-cavern") shouldBe true
    library.delete("the-echoing-cavern") shouldBe false
    library.list.map(_.id) shouldBe List("the-echoing-cavern-2")
  }

  test("an id that is not an adventure id reaches no file") {
    val library = newLibrary()
    library.keep(cavern, theme, pixel)
    Seq("../the-echoing-cavern", "_music", "", "The-Echoing-Cavern", "a/b").foreach { id =>
      withClue(id)(library.load(id) shouldBe None)
      library.delete(id) shouldBe false
      library.imageOr(id, "hall", "pixel")(Right(base64("x"))).isLeft shouldBe true
    }
    Files.isDirectory(library.root.resolve("the-echoing-cavern")) shouldBe true
  }

  // ============= Media =============

  test("a location's picture is made once per art style, and served from the URL it is given") {
    val library = newLibrary()
    val id = library.keep(cavern, theme, pixel).toOption.get.id
    val made = new AtomicInteger()
    def picture(style: String) = library.imageOr(id, "hall", style) {
      made.incrementAndGet()
      Right(base64(s"hall in $style"))
    }

    val first = picture("pixel").toOption.get
    picture("pixel").toOption.get shouldBe first
    library.image(id, "hall", "pixel") shouldBe Some(first)
    made.get shouldBe 1
    first.url should startWith(s"${AdventureLibrary.UrlPrefix}/adventures/$id/images/hall.pixel.png?v=")
    bytesAt(library, first) shouldBe "hall in pixel"

    bytesAt(library, picture("painting").toOption.get) shouldBe "hall in painting"
    made.get shouldBe 2
    library.list.head.images shouldBe 1 // of its two locations, in its own style
    library.hasImages shouldBe true
  }

  test("a picture that could not be made is not kept, and is tried again") {
    val library = newLibrary()
    val id = library.keep(cavern, theme, pixel).toOption.get.id
    library.imageOr(id, "hall", "pixel")(Left(ImageGenerationError("provider down"))).isLeft shouldBe true
    library.image(id, "hall", "pixel") shouldBe None
    library.imageOr(id, "hall", "pixel")(Right(base64("hall"))).isRight shouldBe true
  }

  test("only the library's own media files are served") {
    val library = newLibrary()
    val id = library.keep(cavern, theme, pixel).toOption.get.id
    library.imageOr(id, "hall", "pixel")(Right(base64("hall")))
    library.musicOr("mystery")(Right(base64("track")))

    library.resolve(Seq("adventures", id, "images", "hall.pixel.png")).map(_._2) shouldBe Some("image/png")
    library.resolve(Seq("music", "mystery.mp3")).map(_._2) shouldBe Some("audio/mpeg")
    Seq(
      Seq("adventures", id, "adventure.json"),
      Seq("adventures", id, "images", "..", "adventure.json"),
      Seq("adventures", "..", id, "images", "hall.pixel.png"),
      Seq("adventures", id, "images", "missing.pixel.png"),
      Seq("music", "..", id, "adventure.json"),
      Seq("music", "../mystery.mp3"),
      Seq("music"),
      Nil
    ).foreach(segments => withClue(segments)(library.resolve(segments) shouldBe None))
  }

  test("music is one track per mood, made once") {
    val library = newLibrary()
    val client = new CountingMusic
    library.hasMusic shouldBe false
    val first = library.musicOr("mystery")(client.generate("mystery", "", None, None)).toOption.get
    library.musicOr("mystery")(client.generate("mystery", "", None, None)).toOption.get shouldBe first
    client.made.get shouldBe 1
    first.url should startWith(s"${AdventureLibrary.UrlPrefix}/music/mystery.mp3?v=")
    bytesAt(library, first) shouldBe "track for mystery"
    library.hasMusic shouldBe true
  }

  // ============= Baking =============

  test("baking pictures every location and makes every mood's track; a second bake makes nothing") {
    val library = newLibrary()
    val adventure = library.keep(cavern, theme, pixel).toOption.get
    val images = new RecordingImages
    val music = new CountingMusic

    val pictures = BakeAdventure.images(library, adventure, None, images)
    pictures.made should have size 2
    pictures.failed shouldBe empty
    library.list.head.images shouldBe 2
    // Drawn from the outline's description, in the adventure's art style
    images.prompts.toArray.mkString should (include("A cold cave mouth") and include("pixel art"))

    BakeAdventure.music(library, music).made should have size DungeonMasterOutput.MusicMoods.size
    music.made.get shouldBe DungeonMasterOutput.MusicMoods.size

    val again = BakeAdventure.images(library, adventure, None, images) ++ BakeAdventure.music(library, music)
    again.made shouldBe empty
    again.kept should have size (2 + DungeonMasterOutput.MusicMoods.size)
    images.count shouldBe 2
  }

  test("a bake reports what it could not make and makes the rest") {
    val library = newLibrary()
    val adventure = library.keep(cavern, theme, pixel).toOption.get
    val flaky: ImageClient = (prompt, _, _, _) =>
      if (prompt.contains("Small Hall")) Left(ImageGenerationError("provider down")) else Right(base64(prompt))

    val report = BakeAdventure.images(library, adventure, None, flaky)
    report.made should have size 1
    report.failed.map(_._1) shouldBe List(s"${adventure.id}/hall (pixel)")
  }

  // ============= In a game =============

  private def gameOf(
    library: AdventureLibrary,
    images: Option[ImageClient],
    music: Option[MusicClient] = None,
    rules: PartialFunction[org.llm4s.llmconnect.model.Conversation, Reply] = PartialFunction.empty): GameEngine = {
    val adventure = library.list.headOption.flatMap(a => library.load(a.id)).get
    val engine = GameEngine.create(
      ScriptedDm(rules),
      sessionId = "library-test",
      artStyle = Some("pixel"),
      adventureOutline = Some(adventure.outline),
      imageClient = images,
      musicClient = music,
      library = Some(library)
    )
    engine.playAdventure(adventure.id)
    engine.initialize().isRight shouldBe true
    engine
  }

  test("every game of a library adventure shows the same picture of a location, made once") {
    val library = newLibrary()
    library.keep(cavern, theme, pixel)
    val images = new RecordingImages

    val first = gameOf(library, Some(images))
    val shown = first.generateSceneImage(first.getCurrentScene.get, None)
    shown.get shouldBe a[MediaRef.Stored]
    images.count shouldBe 1
    // From the outline, not the turn's own description
    images.prompts.get(0) should include("A cold cave mouth")

    val second = gameOf(library, Some(images))
    second.generateSceneImage(second.getCurrentScene.get, None) shouldBe shown
    images.count shouldBe 1
  }

  test("a baked adventure shows its pictures and plays its music with no image or music provider") {
    val library = newLibrary()
    val adventure = library.keep(cavern, theme, pixel).toOption.get
    library.imageOr(adventure.id, "cavern_entrance", "pixel")(Right(base64("entrance")))
    library.musicOr("exploration")(Right(base64("track")))

    val engine = gameOf(library, images = None)
    val entrance = engine.getCurrentScene.get
    engine.canPicture(entrance) shouldBe true
    engine.generateSceneImage(entrance, None).get shouldBe a[MediaRef.Stored]
    engine.canPlayMusic(entrance) shouldBe true
    engine.generateBackgroundMusic(entrance, None).map(_._2) shouldBe Some("exploration")

    // The hall was never baked, and its mood has no track: nothing is promised
    engine.processCommand("go north", generateAudio = false).isRight shouldBe true
    val hall = engine.getCurrentScene.get
    engine.canPicture(hall) shouldBe false
    engine.generateSceneImage(hall, None) shouldBe None
    engine.canPlayMusic(hall) shouldBe false
  }

  test("a place the outline does not have is drawn from the scene's own description, for that game") {
    val library = newLibrary()
    library.keep(cavern, theme, pixel)
    val images = new RecordingImages
    val engine = gameOf(
      library,
      Some(images),
      rules = { case ScriptedDm.Command("dig") =>
        Reply.text(DmAnswers.scene("You break into a crypt.", "crypt", "Hidden Crypt", Seq("up" -> "cavern_entrance")))
      }
    )
    engine.processCommand("dig", generateAudio = false).isRight shouldBe true

    engine.generateSceneImage(engine.getCurrentScene.get, None).get shouldBe a[MediaRef.Inline]
    images.prompts.get(0) should include("A view of Hidden Crypt")
    library.list.head.images shouldBe 0
  }

  test("the Dungeon Master asks for a new picture when a place looks different, and only then") {
    val library = newLibrary()
    library.keep(cavern, theme, pixel)
    val images = new RecordingImages
    val engine = gameOf(
      library,
      Some(images),
      rules = { case ScriptedDm.Command("light the brazier") =>
        Reply.text(
          DmAnswers.simple(
            "Flames leap up.",
            "cavern_entrance",
            "use",
            imageUpdate = Some("The cave mouth glows with firelight")))
      }
    )
    engine.imageUpdateLastTurn shouldBe None

    engine.processCommand("examine wall", generateAudio = false).isRight shouldBe true
    engine.imageUpdateLastTurn shouldBe None

    engine.processCommand("light the brazier", generateAudio = false).isRight shouldBe true
    engine.locationChangedLastTurn shouldBe false
    val update = engine.imageUpdateLastTurn
    update shouldBe Some("The cave mouth glows with firelight")

    // The new picture is this game's own: the library's picture of the place is not replaced
    val scene = engine.getCurrentScene.get
    val redrawn = engine.generateSceneImage(scene, None, update).get
    redrawn shouldBe a[MediaRef.Inline]
    images.prompts.get(0) should include("glows with firelight")
    library.list.head.images shouldBe 0

    engine.processCommand("examine wall", generateAudio = false).isRight shouldBe true
    engine.imageUpdateLastTurn shouldBe None
  }

  test("in a game, music is the mood's one track, shared by every location and game") {
    val library = newLibrary()
    library.keep(cavern, theme, pixel)
    val music = new CountingMusic
    val first = gameOf(library, None, Some(music))
    val (track, mood) = first.generateBackgroundMusic(first.getCurrentScene.get, None).get
    mood shouldBe "exploration"
    track shouldBe a[MediaRef.Stored]

    val second = gameOf(library, None, Some(music))
    second.generateBackgroundMusic(second.getCurrentScene.get, None) shouldBe Some((track, mood))
    music.made.get shouldBe 1
  }
}
