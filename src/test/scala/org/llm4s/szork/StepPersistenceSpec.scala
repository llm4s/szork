package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import java.nio.file.{Files, Path}
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters._
import org.llm4s.llmconnect.model.{UserMessage, AssistantMessage}
import org.llm4s.szork.api.{ArtStyle, GameTheme}
import org.llm4s.szork.game.{Exit, GameScene, IdGenerator}
import org.llm4s.szork.persistence.{GameMetadata, GameState, SceneResponse, StepData, StepMetadata, StepPersistence}

class StepPersistenceSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  // Saves go to a fresh temporary directory, never the developer's real szork-saves
  private val saveRoot: Path = Files.createTempDirectory("szork-saves-test")
  var testGameDir: Path = uninitialized
  var testGameId: String = uninitialized

  override def beforeAll(): Unit = {
    super.beforeAll()
    StepPersistence.useSaveDirectory(saveRoot)
  }

  override def afterAll(): Unit = {
    deleteRecursively(saveRoot)
    super.afterAll()
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    testGameId = IdGenerator.gameId()
    testGameDir = saveRoot.resolve(testGameId)
  }

  override def afterEach(): Unit = {
    if (Files.exists(testGameDir)) deleteRecursively(testGameDir)
    super.afterEach()
  }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) {
      Files.list(path).iterator().asScala.foreach(deleteRecursively)
    }
    Files.delete(path)
  }

  test("saveGameMetadata and loadGameMetadata roundtrip") {
    val metadata = GameMetadata(
      gameId = testGameId,
      theme = "fantasy",
      artStyle = "illustration",
      adventureTitle = "Test Adventure",
      createdAt = System.currentTimeMillis(),
      lastSaved = System.currentTimeMillis(),
      lastPlayed = System.currentTimeMillis(),
      totalPlayTime = 0L,
      currentStep = 1,
      totalSteps = 1
    )

    // Save metadata
    val saveResult = StepPersistence.saveGameMetadata(metadata)
    saveResult shouldBe a[Right[?, ?]]

    // Load metadata
    val loadResult = StepPersistence.loadGameMetadata(testGameId)
    loadResult shouldBe a[Right[?, ?]]

    val loaded = loadResult.getOrElse(fail("Failed to load metadata"))
    loaded.gameId shouldBe testGameId
    loaded.theme shouldBe "fantasy"
    loaded.artStyle shouldBe "illustration"
    loaded.adventureTitle shouldBe "Test Adventure"
    loaded.currentStep shouldBe 1
    loaded.totalSteps shouldBe 1
  }

  test("saveStep and loadStep roundtrip") {
    // First save metadata
    val metadata = GameMetadata(
      gameId = testGameId,
      theme = "fantasy",
      artStyle = "illustration",
      adventureTitle = "Test Adventure",
      createdAt = System.currentTimeMillis(),
      lastSaved = System.currentTimeMillis(),
      lastPlayed = System.currentTimeMillis(),
      totalPlayTime = 0L,
      currentStep = 1,
      totalSteps = 1
    )
    StepPersistence.saveGameMetadata(metadata)

    // Create test step data
    val gameState = GameState(
      gameId = testGameId,
      theme = Some(GameTheme("fantasy", "Fantasy", "A fantasy world")),
      artStyle = Some(ArtStyle("illustration", "Illustration")),
      adventureOutline = None,
      currentScene = Some(
        GameScene(
          locationId = "start",
          locationName = "Starting Location",
          narrationText = "You find yourself at the beginning.",
          imageDescription = "A starting area",
          musicDescription = "Ambient music",
          musicMood = "calm",
          exits = List(Exit("north", "next-room", Some("A door to the north"))),
          items = List("torch"),
          npcs = List()
        )),
      visitedLocations = Set("start"),
      inventory = List("torch"),
      createdAt = System.currentTimeMillis(),
      lastSaved = System.currentTimeMillis(),
      lastPlayed = System.currentTimeMillis(),
      totalPlayTime = 0L,
      adventureTitle = Some("Test Adventure"),
      agentMessages = List(
        UserMessage("Start adventure"),
        AssistantMessage(Some("You find yourself at the beginning."), List())
      )
    )

    val stepData = StepData(
      metadata = StepMetadata(
        gameId = testGameId,
        stepNumber = 1,
        timestamp = System.currentTimeMillis(),
        userCommand = None,
        responseLength = 100,
        toolCallCount = 0,
        messageCount = 2,
        success = true,
        executionTimeMs = 1000L
      ),
      gameState = gameState,
      userCommand = None,
      narrationText = "You find yourself at the beginning.",
      response = Some(SceneResponse(gameState.currentScene.get)),
      toolCalls = List(),
      outline = None
    )

    // Save step
    val saveResult = StepPersistence.saveStep(stepData)
    saveResult shouldBe a[Right[?, ?]]

    // Load step
    val loadResult = StepPersistence.loadStep(testGameId, 1)
    loadResult shouldBe a[Right[?, ?]]

    val loaded = loadResult.getOrElse(fail("Failed to load step"))
    loaded.metadata.gameId shouldBe testGameId
    loaded.metadata.stepNumber shouldBe 1
    loaded.metadata.success shouldBe true
    loaded.gameState.gameId shouldBe testGameId
    loaded.gameState.inventory should contain("torch")
    loaded.narrationText shouldBe "You find yourself at the beginning."
    loaded.gameState.agentMessages should have size 2
    loaded.gameState.agentMessages.head.content shouldBe "Start adventure"
  }

  test("loadLatestStep loads most recent step") {
    // Save metadata
    val metadata = GameMetadata(
      gameId = testGameId,
      theme = "fantasy",
      artStyle = "illustration",
      adventureTitle = "Test Adventure",
      createdAt = System.currentTimeMillis(),
      lastSaved = System.currentTimeMillis(),
      lastPlayed = System.currentTimeMillis(),
      totalPlayTime = 0L,
      currentStep = 3,
      totalSteps = 3
    )
    StepPersistence.saveGameMetadata(metadata)

    // Create and save step 3
    val gameState = GameState(
      gameId = testGameId,
      theme = Some(GameTheme("fantasy", "Fantasy", "A fantasy world")),
      artStyle = Some(ArtStyle("illustration", "Illustration")),
      adventureOutline = None,
      currentScene = None,
      visitedLocations = Set(),
      inventory = List(),
      createdAt = System.currentTimeMillis(),
      lastSaved = System.currentTimeMillis(),
      lastPlayed = System.currentTimeMillis(),
      totalPlayTime = 0L,
      adventureTitle = Some("Test Adventure"),
      agentMessages = List()
    )

    val stepData = StepData(
      metadata = StepMetadata(
        gameId = testGameId,
        stepNumber = 3,
        timestamp = System.currentTimeMillis(),
        userCommand = Some("go north"),
        responseLength = 50,
        toolCallCount = 0,
        messageCount = 1,
        success = true,
        executionTimeMs = 500L
      ),
      gameState = gameState,
      userCommand = Some("go north"),
      narrationText = "You go north.",
      response = None,
      toolCalls = List(),
      outline = None
    )

    StepPersistence.saveStep(stepData)

    // Load latest step
    val loadResult = StepPersistence.loadLatestStep(testGameId)
    loadResult shouldBe a[Right[?, ?]]

    val loaded = loadResult.getOrElse(fail("Failed to load latest step"))
    loaded.metadata.stepNumber shouldBe 3
    loaded.userCommand shouldBe Some("go north")
  }

  test("listSteps returns sorted step numbers") {
    // Save metadata
    val metadata = GameMetadata(
      gameId = testGameId,
      theme = "fantasy",
      artStyle = "illustration",
      adventureTitle = "Test Adventure",
      createdAt = System.currentTimeMillis(),
      lastSaved = System.currentTimeMillis(),
      lastPlayed = System.currentTimeMillis(),
      totalPlayTime = 0L,
      currentStep = 3,
      totalSteps = 3
    )
    StepPersistence.saveGameMetadata(metadata)

    // Create minimal game state
    val gameState = GameState(
      gameId = testGameId,
      theme = None,
      artStyle = None,
      adventureOutline = None,
      currentScene = None,
      visitedLocations = Set(),
      inventory = List(),
      createdAt = System.currentTimeMillis(),
      lastSaved = System.currentTimeMillis(),
      lastPlayed = System.currentTimeMillis(),
      totalPlayTime = 0L,
      adventureTitle = Some("Test Adventure"),
      agentMessages = List()
    )

    // Save steps 1, 2, and 3
    for (stepNum <- List(1, 3, 2)) { // Save out of order
      val stepData = StepData(
        metadata = StepMetadata(
          gameId = testGameId,
          stepNumber = stepNum,
          timestamp = System.currentTimeMillis(),
          userCommand = None,
          responseLength = 10,
          toolCallCount = 0,
          messageCount = 1,
          success = true,
          executionTimeMs = 100L
        ),
        gameState = gameState,
        userCommand = None,
        narrationText = s"Step $stepNum",
        response = None,
        toolCalls = List(),
        outline = None
      )
      StepPersistence.saveStep(stepData)
    }

    // List steps
    val steps = StepPersistence.listSteps(testGameId)
    steps shouldBe List(1, 2, 3) // Should be sorted
  }

  test("gameExists returns true for existing game") {
    val metadata = GameMetadata(
      gameId = testGameId,
      theme = "fantasy",
      artStyle = "illustration",
      adventureTitle = "Test Adventure",
      createdAt = System.currentTimeMillis(),
      lastSaved = System.currentTimeMillis(),
      lastPlayed = System.currentTimeMillis(),
      totalPlayTime = 0L,
      currentStep = 1,
      totalSteps = 1
    )
    StepPersistence.saveGameMetadata(metadata)

    StepPersistence.gameExists(testGameId) shouldBe true
    StepPersistence.gameExists("nonexistent-game") shouldBe false
  }

  test("deleteGame removes all game data") {
    // Create game with metadata and steps
    val metadata = GameMetadata(
      gameId = testGameId,
      theme = "fantasy",
      artStyle = "illustration",
      adventureTitle = "Test Adventure",
      createdAt = System.currentTimeMillis(),
      lastSaved = System.currentTimeMillis(),
      lastPlayed = System.currentTimeMillis(),
      totalPlayTime = 0L,
      currentStep = 1,
      totalSteps = 1
    )
    StepPersistence.saveGameMetadata(metadata)

    val gameState = GameState(
      gameId = testGameId,
      theme = None,
      artStyle = None,
      adventureOutline = None,
      currentScene = None,
      visitedLocations = Set(),
      inventory = List(),
      createdAt = System.currentTimeMillis(),
      lastSaved = System.currentTimeMillis(),
      lastPlayed = System.currentTimeMillis(),
      totalPlayTime = 0L,
      adventureTitle = Some("Test Adventure"),
      agentMessages = List()
    )

    val stepData = StepData(
      metadata = StepMetadata(
        gameId = testGameId,
        stepNumber = 1,
        timestamp = System.currentTimeMillis(),
        userCommand = None,
        responseLength = 10,
        toolCallCount = 0,
        messageCount = 1,
        success = true,
        executionTimeMs = 100L
      ),
      gameState = gameState,
      userCommand = None,
      narrationText = "Test",
      response = None,
      toolCalls = List(),
      outline = None
    )
    StepPersistence.saveStep(stepData)

    // Verify game exists
    StepPersistence.gameExists(testGameId) shouldBe true

    // Delete game
    val deleteResult = StepPersistence.deleteGame(testGameId)
    deleteResult shouldBe a[Right[?, ?]]

    // Verify game no longer exists
    StepPersistence.gameExists(testGameId) shouldBe false
    Files.exists(testGameDir) shouldBe false
  }

  test("listGames returns all saved games") {
    // Create two test games
    val gameId1 = IdGenerator.gameId()
    val gameId2 = IdGenerator.gameId()

    val metadata1 = GameMetadata(
      gameId = gameId1,
      theme = "fantasy",
      artStyle = "illustration",
      adventureTitle = "Adventure 1",
      createdAt = System.currentTimeMillis(),
      lastSaved = System.currentTimeMillis(),
      lastPlayed = System.currentTimeMillis() - 1000,
      totalPlayTime = 0L,
      currentStep = 1,
      totalSteps = 1
    )

    val metadata2 = GameMetadata(
      gameId = gameId2,
      theme = "sci-fi",
      artStyle = "pixel",
      adventureTitle = "Adventure 2",
      createdAt = System.currentTimeMillis(),
      lastSaved = System.currentTimeMillis(),
      lastPlayed = System.currentTimeMillis(),
      totalPlayTime = 0L,
      currentStep = 1,
      totalSteps = 1
    )

    StepPersistence.saveGameMetadata(metadata1)
    StepPersistence.saveGameMetadata(metadata2)

    // List games
    val games = StepPersistence.listGames()
    games should have size 2
    games.map(_.gameId) should contain allOf (gameId1, gameId2)

    // Games should be sorted by lastPlayed (most recent first)
    games.head.gameId shouldBe gameId2

    // Cleanup
    StepPersistence.deleteGame(gameId1)
    StepPersistence.deleteGame(gameId2)
  }

  /** A step of the test game with nothing but `narration` in it. */
  private def step(number: Int, narration: String, command: Option[String] = None, toolCalls: Boolean = false) =
    StepData(
      metadata = StepMetadata(testGameId, number, 1L, command, narration.length, 0, 0, success = true),
      gameState = GameState(testGameId, None, None, None, None, Set(), List(), 1L, 1L, 1L, 0L),
      userCommand = command,
      narrationText = narration,
      response = None,
      toolCalls =
        if (toolCalls) List(org.llm4s.szork.persistence.ToolCallInfo("t1", "look", ujson.Obj(), None, 1L)) else Nil
    )

  private def gameMetadata(step: Int) =
    GameMetadata(testGameId, "fantasy", "pixel", "Test", 1L, 1L, 1L, 0L, currentStep = step, totalSteps = step)

  test("a later step of a game that was deleted is not saved, and writes nothing") {
    StepPersistence.saveNewGame(step(1, "Start"), gameMetadata(1)) shouldBe Right(())
    StepPersistence.deleteGame(testGameId) shouldBe Right(())

    StepPersistence.saveNextStep(step(2, "Later", Some("look")), 10L).isLeft shouldBe true
    Files.exists(testGameDir) shouldBe false
  }

  test("saves replace files by an atomic rename, leaving no temporary files") {
    def metadata(title: String) = GameMetadata(
      gameId = testGameId,
      theme = "fantasy",
      artStyle = "pixel",
      adventureTitle = title,
      createdAt = 1L,
      lastSaved = 1L,
      lastPlayed = 1L,
      totalPlayTime = 0L)
    def fileKey(path: Path) =
      Files.readAttributes(path, classOf[java.nio.file.attribute.BasicFileAttributes]).fileKey()
    val gameJson = testGameDir.resolve("game.json")

    StepPersistence.saveGameMetadata(metadata("First")) shouldBe Right(())
    val before = fileKey(gameJson)
    StepPersistence.saveGameMetadata(metadata("Second")) shouldBe Right(())

    // A new file was renamed into place, rather than the old one truncated and rewritten
    fileKey(gameJson) should not be before
    StepPersistence.loadGameMetadata(testGameId).map(_.adventureTitle) shouldBe Right("Second")
    Files.list(testGameDir).iterator().asScala.map(_.getFileName.toString).toList shouldBe List("game.json")
  }
}
