package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._
import org.llm4s.llmconnect.model.{UserMessage, AssistantMessage}

class StepPersistenceSpec extends AnyFunSuite with Matchers with BeforeAndAfterEach {

  var testGameDir: Path = _
  val testGameId = "test-game-persistence"

  override def beforeEach(): Unit = {
    super.beforeEach()
    // Clean up any existing test data
    testGameDir = java.nio.file.Paths.get("szork-saves", testGameId)
    if (Files.exists(testGameDir)) {
      deleteRecursively(testGameDir)
    }
  }

  override def afterEach(): Unit = {
    // Clean up test data
    if (Files.exists(testGameDir)) {
      deleteRecursively(testGameDir)
    }
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
    saveResult shouldBe a [Right[_, _]]

    // Load metadata
    val loadResult = StepPersistence.loadGameMetadata(testGameId)
    loadResult shouldBe a [Right[_, _]]

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
      currentScene = Some(GameScene(
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
      conversationHistory = List(),
      inventory = List("torch"),
      createdAt = System.currentTimeMillis(),
      lastSaved = System.currentTimeMillis(),
      lastPlayed = System.currentTimeMillis(),
      totalPlayTime = 0L,
      adventureTitle = Some("Test Adventure"),
      agentMessages = List(),
      mediaCache = Map.empty,
      systemPrompt = Some("Test system prompt")
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
      agentMessages = List(
        UserMessage("Start adventure"),
        AssistantMessage(Some("You find yourself at the beginning."), List())
      ),
      outline = None
    )

    // Save step
    val saveResult = StepPersistence.saveStep(stepData)
    saveResult shouldBe a [Right[_, _]]

    // Load step
    val loadResult = StepPersistence.loadStep(testGameId, 1)
    loadResult shouldBe a [Right[_, _]]

    val loaded = loadResult.getOrElse(fail("Failed to load step"))
    loaded.metadata.gameId shouldBe testGameId
    loaded.metadata.stepNumber shouldBe 1
    loaded.metadata.success shouldBe true
    loaded.gameState.gameId shouldBe testGameId
    loaded.gameState.inventory should contain("torch")
    loaded.narrationText shouldBe "You find yourself at the beginning."
    loaded.agentMessages should have size 2
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
      conversationHistory = List(),
      inventory = List(),
      createdAt = System.currentTimeMillis(),
      lastSaved = System.currentTimeMillis(),
      lastPlayed = System.currentTimeMillis(),
      totalPlayTime = 0L,
      adventureTitle = Some("Test Adventure"),
      agentMessages = List(),
      mediaCache = Map.empty,
      systemPrompt = None
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
      agentMessages = List(UserMessage("go north")),
      outline = None
    )

    StepPersistence.saveStep(stepData)

    // Load latest step
    val loadResult = StepPersistence.loadLatestStep(testGameId)
    loadResult shouldBe a [Right[_, _]]

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
      conversationHistory = List(),
      inventory = List(),
      createdAt = System.currentTimeMillis(),
      lastSaved = System.currentTimeMillis(),
      lastPlayed = System.currentTimeMillis(),
      totalPlayTime = 0L,
      adventureTitle = Some("Test Adventure"),
      agentMessages = List(),
      mediaCache = Map.empty,
      systemPrompt = None
    )

    // Save steps 1, 2, and 3
    for (stepNum <- List(1, 3, 2)) {  // Save out of order
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
        agentMessages = List(),
        outline = None
      )
      StepPersistence.saveStep(stepData)
    }

    // List steps
    val steps = StepPersistence.listSteps(testGameId)
    steps shouldBe List(1, 2, 3)  // Should be sorted
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
      conversationHistory = List(),
      inventory = List(),
      createdAt = System.currentTimeMillis(),
      lastSaved = System.currentTimeMillis(),
      lastPlayed = System.currentTimeMillis(),
      totalPlayTime = 0L,
      adventureTitle = Some("Test Adventure"),
      agentMessages = List(),
      mediaCache = Map.empty,
      systemPrompt = None
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
      agentMessages = List(),
      outline = None
    )
    StepPersistence.saveStep(stepData)

    // Verify game exists
    StepPersistence.gameExists(testGameId) shouldBe true

    // Delete game
    val deleteResult = StepPersistence.deleteGame(testGameId)
    deleteResult shouldBe a [Right[_, _]]

    // Verify game no longer exists
    StepPersistence.gameExists(testGameId) shouldBe false
    Files.exists(testGameDir) shouldBe false
  }

  test("listGames returns all saved games") {
    // Create two test games
    val gameId1 = s"$testGameId-1"
    val gameId2 = s"$testGameId-2"

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
}
