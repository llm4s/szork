package org.llm4s.szork

import java.net.{InetSocketAddress, ServerSocket, URI}
import java.nio.file.{Files, Path}
import java.util.concurrent.{CountDownLatch, Executors, LinkedBlockingQueue, TimeUnit}
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import org.llm4s.szork.api._
import org.llm4s.szork.error.ErrorHandling.SzorkResult
import org.llm4s.szork.game.GameEngine
import org.llm4s.szork.persistence.StepPersistence
import org.llm4s.szork.spi.MusicClient
import org.llm4s.szork.stubs.{FakeImageClient, ScriptedDm}
import org.llm4s.szork.websocket.TypedWebSocketServer

/** Session lifecycle over real connections, with saves on: one session per game, ending a game's session, music on its
  * own pool, and what clients are told when a request fails.
  */
class GameSessionsSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private val saveRoot: Path = Files.createTempDirectory("szork-sessions-test")
  private val port = {
    val s = new ServerSocket(0)
    try s.getLocalPort
    finally s.close()
  }
  private val workPool = Executors.newFixedThreadPool(4)
  // One media thread: a blocked music job on it would hold up every image
  private val mediaPool = Executors.newFixedThreadPool(1)
  private val musicPool = Executors.newFixedThreadPool(2)

  // Music generation waits for this gate, so a test can hold it "in progress"
  @volatile private var musicGate = new CountDownLatch(0)
  private final class GatedMusicClient extends MusicClient {
    override def isAvailable: Boolean = true
    override def generate(
      mood: String,
      context: String,
      gameId: Option[String],
      locationId: Option[String]): SzorkResult[String] = {
      musicGate.await(30, TimeUnit.SECONDS)
      Right("FAKE_MUSIC_BASE64")
    }
  }

  private val engineFactory: EngineFactory = (sessionId, theme, artStyle, outline, features) =>
    if (theme.contains("explode")) throw new IllegalStateException("secret internal detail")
    else
      Right(
        GameEngine.create(
          ScriptedDm(),
          sessionId,
          theme,
          artStyle,
          outline,
          imageClient = Option.when(features.image)(new FakeImageClient()),
          musicClient = Option.when(features.music)(new GatedMusicClient())
        ))

  private val server = new TypedWebSocketServer(
    address = new InetSocketAddress("127.0.0.1", port),
    sessionManager = new SessionManager(),
    capabilities = Capabilities(tts = false, stt = false, image = true, music = true),
    engineFactory = engineFactory,
    sttClient = None,
    workPool = workPool,
    mediaEC = ExecutionContext.fromExecutor(mediaPool),
    musicEC = ExecutionContext.fromExecutor(musicPool),
    autoSave = true
  )

  override def beforeAll(): Unit = {
    StepPersistence.useSaveDirectory(saveRoot)
    server.setReuseAddr(true)
    server.start()
    Thread.sleep(300)
  }

  override def afterAll(): Unit = {
    musicGate.countDown()
    server.stop(1000)
    workPool.shutdownNow()
    mediaPool.shutdownNow()
    musicPool.shutdownNow()
    deleteRecursively(saveRoot)
  }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) Files.list(path).iterator().asScala.toList.foreach(deleteRecursively)
    Files.deleteIfExists(path)
  }

  private final class Client extends WebSocketClient(new URI(s"ws://127.0.0.1:$port")) {
    val received = new LinkedBlockingQueue[ujson.Value]()
    override def onOpen(handshake: ServerHandshake): Unit = ()
    override def onMessage(message: String): Unit = received.put(ujson.read(message))
    override def onClose(code: Int, reason: String, remote: Boolean): Unit = ()
    override def onError(ex: Exception): Unit = ()

    def sendJson(fields: (String, ujson.Value)*): Unit = send(ujson.write(ujson.Obj.from(fields)))

    /** The next message of type `tpe`, skipping others. */
    def await(tpe: String, seconds: Int = 10): ujson.Value = {
      val deadline = System.currentTimeMillis() + seconds * 1000L
      while (System.currentTimeMillis() < deadline) {
        val msg = received.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
        if (msg != null && msg("type").str == tpe) return msg
        if (msg != null && msg("type").str == "error" && tpe != "error") fail(s"unexpected error: $msg")
      }
      fail(s"no '$tpe' message within ${seconds}s")
    }
  }

  private def connect(): Client = {
    val c = new Client
    c.connectBlocking(5, TimeUnit.SECONDS) shouldBe true
    c.await("connected")
    c
  }

  /** Starts a game and waits until its first step is saved; returns (sessionId, gameId). */
  private def startGame(c: Client): (String, String) = {
    c.sendJson("type" -> "newGame", "theme" -> "caves", "music" -> false)
    val data = c.await("gameStarted")("data")
    val gameId = data("gameId").str
    eventually(StepPersistence.gameExists(gameId))
    (data("sessionId").str, gameId)
  }

  private def eventually(condition: => Boolean, seconds: Int = 5): Unit = {
    val deadline = System.currentTimeMillis() + seconds * 1000L
    while (!condition && System.currentTimeMillis() < deadline) Thread.sleep(20)
    condition shouldBe true
  }

  private def loadGame(c: Client, gameId: String): Unit =
    c.sendJson("type" -> "loadGame", "gameId" -> gameId, "music" -> false)

  test("loading a game that is already open joins its session instead of starting a second one") {
    val first = connect()
    val (sessionId, gameId) = startGame(first)
    first.sendJson("type" -> "streamCommand", "command" -> "go north")
    val lastIndex = first.await("streamComplete")("data")("messageIndex").num

    val second = connect()
    loadGame(second, gameId)
    val loaded = second.await("gameLoaded")("data")
    loaded("sessionId").str shouldBe sessionId
    loaded("currentScene")("locationName").str shouldBe "Small Hall" // the live state, including the last turn
    loaded("messageIndex").num shouldBe lastIndex

    // The game has one writer: the first connection is told it lost the session, and no longer plays it
    val takenOver = first.await("sessionTakenOver")("data")
    takenOver("sessionId").str shouldBe sessionId
    takenOver("gameId").str shouldBe gameId
    first.sendJson("type" -> "streamCommand", "command" -> "look")
    first.await("error")("data")("error").str shouldBe "No active session"
    second.sendJson("type" -> "streamCommand", "command" -> "look")
    second.await("streamComplete")
    eventually(StepPersistence.loadGameMetadata(gameId).exists(_.currentStep == 3))
    first.close()
    second.close()
  }

  test("loading an open game applies the features it asks for to the session") {
    val first = connect()
    val (sessionId, gameId) = startGame(first) // music off

    val second = connect()
    second.sendJson("type" -> "loadGame", "gameId" -> gameId, "music" -> true, "image" -> false)
    val loaded = second.await("gameLoaded")("data")
    loaded("sessionId").str shouldBe sessionId
    loaded("musicEnabled").bool shouldBe true
    loaded("imageEnabled").bool shouldBe false

    second.sendJson("type" -> "streamCommand", "command" -> "go north")
    val complete = second.await("streamComplete")("data")
    complete("hasMusic").bool shouldBe true
    complete("hasImage").bool shouldBe false
    second.await("musicReady")
    Seq(first, second).foreach(_.close())
  }

  test("a loaded game reports the session's message index, as a resume does") {
    val c = connect()
    val (_, gameId) = startGame(c)
    Await.result(server.endGame(gameId)(()), 10.seconds)

    loadGame(c, gameId)
    val loaded = c.await("gameLoaded")("data")
    loaded("messageIndex").num should be > 0.0
    val other = connect()
    other.sendJson("type" -> "resumeSession", "sessionId" -> loaded("sessionId").str)
    other.await("sessionResumed")("data")("messageIndex").num shouldBe loaded("messageIndex").num
    Seq(c, other).foreach(_.close())
  }

  test("two loads of a saved game at once share one session") {
    val c = connect()
    val (_, gameId) = startGame(c)
    Await.result(server.endGame(gameId)(()), 10.seconds)

    val (a, b) = (connect(), connect())
    loadGame(a, gameId)
    loadGame(b, gameId)
    val sessionA = a.await("gameLoaded")("data")("sessionId").str
    val sessionB = b.await("gameLoaded")("data")("sessionId").str
    sessionA shouldBe sessionB
    Seq(a, b, c).foreach(_.close())
  }

  test("ending a game waits for its session's work, after which nothing saves it") {
    val c = connect()
    val (_, gameId) = startGame(c)
    c.sendJson("type" -> "streamCommand", "command" -> "go north")
    c.await("textChunk") // the turn, and the save after it, are under way

    Await.result(server.endGame(gameId)(StepPersistence.deleteGame(gameId)), 10.seconds) shouldBe Right(())
    Files.exists(saveRoot.resolve(gameId)) shouldBe false

    c.sendJson("type" -> "streamCommand", "command" -> "look")
    c.await("error")("data")("error").str shouldBe "No active session"
    Thread.sleep(300)
    Files.exists(saveRoot.resolve(gameId)) shouldBe false
    c.close()
  }

  test("slow music does not hold up scene images") {
    musicGate = new CountDownLatch(1)
    try {
      val c = connect()
      c.sendJson("type" -> "newGame", "theme" -> "caves")
      c.await("gameStarted")("data")("hasMusic").bool shouldBe true
      c.await("imageReady")
      // The opening scene's music is still generating; the next scene's image must not wait for it
      c.sendJson("type" -> "streamCommand", "command" -> "go north")
      val index = c.await("streamComplete")("data")("messageIndex").num
      c.await("imageReady")("data")("messageIndex").num shouldBe index
      musicGate.countDown()
      c.await("musicReady")
      c.close()
    } finally musicGate.countDown()
  }

  test("a failed request reports a generic error without the exception's details") {
    val c = connect()
    c.sendJson("type" -> "newGame", "theme" -> "explode")
    val error = c.await("error")("data")
    error("error").str shouldBe "Internal server error"
    ujson.write(error) should not include "secret"
    c.close()
  }
}
