package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters._

class GameMigrationSpec extends AnyFunSuite with Matchers with BeforeAndAfterEach {

  val testGameId = "test-migration-game"
  var oldFormatFile: Path = _
  var newFormatDir: Path = _

  override def beforeEach(): Unit = {
    super.beforeEach()
    // Clean up any existing test data
    oldFormatFile = Paths.get("szork-saves", s"$testGameId.json")
    newFormatDir = Paths.get("szork-saves", testGameId)

    if (Files.exists(oldFormatFile)) {
      Files.delete(oldFormatFile)
    }
    if (Files.exists(newFormatDir)) {
      deleteRecursively(newFormatDir)
    }
  }

  override def afterEach(): Unit = {
    // Clean up test data
    if (Files.exists(oldFormatFile)) {
      Files.delete(oldFormatFile)
    }
    if (Files.exists(newFormatDir)) {
      deleteRecursively(newFormatDir)
    }
    super.afterEach()
  }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) {
      Files.list(path).iterator().asScala.foreach(deleteRecursively)
    }
    Files.delete(path)
  }

  test("isOldFormat detects old single-file format") {
    // Create old format file
    val oldFormatJson = createOldFormatJson()
    Files.createDirectories(oldFormatFile.getParent)
    Files.write(oldFormatFile, oldFormatJson.getBytes(StandardCharsets.UTF_8))

    // Should detect as old format
    GamePersistence.isOldFormat(testGameId) shouldBe true

    // After migration, should not be old format
    GamePersistence.migrateOldSaveToStepFormat(testGameId)
    GamePersistence.isOldFormat(testGameId) shouldBe false
  }

  test("isOldFormat returns false for new format") {
    // Create new format
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

    // Should not detect as old format
    GamePersistence.isOldFormat(testGameId) shouldBe false
  }

  test("isOldFormat returns false for nonexistent game") {
    GamePersistence.isOldFormat("nonexistent-game") shouldBe false
  }

  test("migrateOldSaveToStepFormat converts old format to new") {
    // Create old format file
    val oldFormatJson = createOldFormatJson()
    Files.createDirectories(oldFormatFile.getParent)
    Files.write(oldFormatFile, oldFormatJson.getBytes(StandardCharsets.UTF_8))

    // Verify old format exists
    Files.exists(oldFormatFile) shouldBe true
    GamePersistence.isOldFormat(testGameId) shouldBe true

    // Migrate
    val migrateResult = GamePersistence.migrateOldSaveToStepFormat(testGameId)
    migrateResult shouldBe a [Right[_, _]]

    // Verify old file is gone
    Files.exists(oldFormatFile) shouldBe false

    // Verify new format exists
    StepPersistence.gameExists(testGameId) shouldBe true

    // Load and verify metadata
    val metadataResult = StepPersistence.loadGameMetadata(testGameId)
    metadataResult shouldBe a [Right[_, _]]
    val metadata = metadataResult.getOrElse(fail("Failed to load metadata"))
    metadata.gameId shouldBe testGameId
    metadata.theme shouldBe "Fantasy"
    metadata.artStyle shouldBe "Illustration"
    metadata.currentStep shouldBe 1
    metadata.totalSteps shouldBe 1

    // Load and verify step 1
    val stepResult = StepPersistence.loadStep(testGameId, 1)
    stepResult shouldBe a [Right[_, _]]
    val step = stepResult.getOrElse(fail("Failed to load step"))
    step.metadata.stepNumber shouldBe 1
    step.metadata.success shouldBe true
    step.gameState.inventory should contain("torch")
    step.gameState.visitedLocations should contain("start")
  }

  test("migrateOldSaveToStepFormat fails for nonexistent game") {
    val result = GamePersistence.migrateOldSaveToStepFormat("nonexistent-game")
    result shouldBe a [Left[_, _]]
    result.left.getOrElse(fail("Expected error")) shouldBe a [org.llm4s.szork.error.NotFoundError]
  }

  test("loadGameWithMigration automatically migrates old format") {
    // Create old format file
    val oldFormatJson = createOldFormatJson()
    Files.createDirectories(oldFormatFile.getParent)
    Files.write(oldFormatFile, oldFormatJson.getBytes(StandardCharsets.UTF_8))

    // Load with migration
    val loadResult = GamePersistence.loadGameWithMigration(testGameId)
    loadResult shouldBe a [Right[_, _]]

    // Verify migration happened
    Files.exists(oldFormatFile) shouldBe false
    StepPersistence.gameExists(testGameId) shouldBe true

    // Verify loaded state
    val state = loadResult.getOrElse(fail("Failed to load game"))
    state.gameId shouldBe testGameId
    state.inventory should contain("torch")
    state.visitedLocations should contain("start")
  }

  test("loadGameWithMigration loads new format without migration") {
    // Create new format
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
      theme = Some(GameTheme("fantasy", "Fantasy", "A fantasy world")),
      artStyle = Some(ArtStyle("illustration", "Illustration")),
      adventureOutline = None,
      currentScene = None,
      visitedLocations = Set("start"),
      conversationHistory = List(),
      inventory = List("sword"),
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

    // Load with migration (should just load without migrating)
    val loadResult = GamePersistence.loadGameWithMigration(testGameId)
    loadResult shouldBe a [Right[_, _]]

    val state = loadResult.getOrElse(fail("Failed to load game"))
    state.gameId shouldBe testGameId
    state.inventory should contain("sword")
  }

  private def createOldFormatJson(): String = {
    s"""{
      |  "gameId": "$testGameId",
      |  "theme": {
      |    "id": "fantasy",
      |    "name": "Fantasy",
      |    "prompt": "A fantasy world"
      |  },
      |  "artStyle": {
      |    "id": "illustration",
      |    "name": "Illustration"
      |  },
      |  "currentScene": {
      |    "locationId": "start",
      |    "locationName": "Starting Location",
      |    "narrationText": "You are at the start.",
      |    "imageDescription": "A starting area",
      |    "musicDescription": "Ambient music",
      |    "musicMood": "calm",
      |    "exits": [
      |      {
      |        "direction": "north",
      |        "locationId": "next-room"
      |      }
      |    ],
      |    "items": ["torch"],
      |    "npcs": []
      |  },
      |  "visitedLocations": ["start"],
      |  "conversationHistory": [],
      |  "inventory": ["torch"],
      |  "createdAt": ${System.currentTimeMillis()},
      |  "lastSaved": ${System.currentTimeMillis()},
      |  "lastPlayed": ${System.currentTimeMillis()},
      |  "totalPlayTime": 0,
      |  "adventureTitle": "Test Adventure",
      |  "agentMessages": [],
      |  "mediaCache": {},
      |  "systemPrompt": "Test system prompt"
      |}""".stripMargin
  }
}
