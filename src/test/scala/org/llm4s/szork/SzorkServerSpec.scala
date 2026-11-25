package org.llm4s.szork

import java.net.{ServerSocket, URI}
import java.nio.file.{Files, Path}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters._
import org.llm4s.testkit.{Reply, ScriptedLLMClient}
import org.llm4s.szork.api._
import org.llm4s.szork.game.{GameEngine, IdGenerator}
import org.llm4s.szork.persistence.StepPersistence
import org.llm4s.szork.stubs.ScriptedDm

/** The whole server - HTTP routes and WebSocket gameplay - started from a config and scripted LLMs, over real
  * connections.
  */
class SzorkServerSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private def freePort(): Int = {
    val s = new ServerSocket(0)
    try s.getLocalPort
    finally s.close()
  }

  private val saveRoot: Path = Files.createTempDirectory("szork-server-test")
  private val config = SzorkConfig(
    port = freePort(),
    wsPort = freePort(),
    savesDirectory = saveRoot.resolve("saves").toString,
    adventuresDirectory = saveRoot.resolve("adventures").toString,
    cache = MediaCacheConfig(directory = saveRoot.resolve("cache").toString),
    llmConfig = Some(LLMConfig("openai", "sk-test")),
    musicEnabled = false,
    // Sessions without a connection expire as soon as the sweeper runs
    sessionTtlMinutes = 0
  )

  // The theme validator: approves themes with a lighthouse, rejects "nothing", and otherwise answers in prose
  private val themeLLM = ScriptedLLMClient.respondingTo {
    case ScriptedDm.Command(prompt) if prompt.contains("lighthouse") =>
      Reply.text("""Sure: {"valid": true, "message": "", "enhancedTheme": "A haunted lighthouse on a storm coast"}""")
    case ScriptedDm.Command(prompt) if prompt.contains("Theme: nothing") =>
      Reply.text("""{"valid": false, "message": "There is nothing to explore"}""")
    case _ => Reply.text("I would rather not say.")
  }

  private val engineFactory: EngineFactory = (sessionId, theme, artStyle, outline, _) =>
    Right(GameEngine.create(ScriptedDm(), sessionId, theme, artStyle, outline))

  private val app = new SzorkApp(
    config,
    themeLLM,
    Capabilities(tts = false, stt = false, image = false, music = false),
    engineFactory,
    sttClient = None,
    tracing = None)

  private val http = s"http://127.0.0.1:${config.port}"

  override def beforeAll(): Unit = {
    app.start()
    Thread.sleep(300)
  }

  override def afterAll(): Unit = {
    app.stop()
    deleteRecursively(saveRoot)
  }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) Files.list(path).iterator().asScala.toList.foreach(deleteRecursively)
    Files.deleteIfExists(path)
  }

  private def getJson(path: String): ujson.Value = ujson.read(requests.get(s"$http$path").text())

  private def postJson(path: String, body: String, contentType: String = "application/json"): ujson.Value =
    ujson.read(requests.post(s"$http$path", data = body, headers = Map("Content-Type" -> contentType)).text())

  private def deleteJson(path: String): ujson.Value = ujson.read(requests.delete(s"$http$path").text())

  private final class Client extends WebSocketClient(new URI(s"ws://127.0.0.1:${config.wsPort}")) {
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

  private def eventually(condition: => Boolean, seconds: Int = 5): Unit = {
    val deadline = System.currentTimeMillis() + seconds * 1000L
    while (!condition && System.currentTimeMillis() < deadline) Thread.sleep(20)
    condition shouldBe true
  }

  /** Starts a game over the WebSocket and waits until it is saved; returns (sessionId, gameId). */
  private def startGame(c: Client): (String, String) = {
    c.sendJson("type" -> "newGame", "theme" -> "caves")
    val data = c.await("gameStarted")("data")
    val gameId = data("gameId").str
    eventually(StepPersistence.gameExists(gameId))
    (data("sessionId").str, gameId)
  }

  test("feature flags report the server's configuration and capabilities") {
    val flags = getJson("/api/feature-flags")
    flags("llm") shouldBe ujson.Obj("provider" -> "openai", "available" -> true)
    flags("image")("available").bool shouldBe false
    flags("tts") shouldBe ujson.Obj("enabled" -> true, "available" -> false)
    // Switched off by config
    flags("music") shouldBe ujson.Obj("enabled" -> false, "available" -> false)
  }

  test("theme validation uses the validator's verdict, and lets the theme through when it has none") {
    val approved = postJson("/api/game/validate-theme", """{"theme": "a lighthouse"}""")
    approved("valid").bool shouldBe true
    approved("enhancedTheme").str shouldBe "A haunted lighthouse on a storm coast"

    val rejected = postJson("/api/game/validate-theme", """{"theme": "nothing"}""")
    rejected("valid").bool shouldBe false
    rejected("message").str shouldBe "There is nothing to explore"

    val unread = postJson("/api/game/validate-theme", """{"theme": "a market"}""")
    unread("valid").bool shouldBe true
    unread("validated").bool shouldBe false
    unread("enhancedTheme").str shouldBe "a market"
  }

  test("theme validation rejects a request without a theme, or one that is not JSON") {
    postJson("/api/game/validate-theme", """{"name": "x"}""")("valid").bool shouldBe false
    postJson("/api/game/validate-theme", """{"theme": "a lighthouse"}""", "text/plain")("status").str shouldBe "error"
  }

  test("adventure generation rejects a malformed request without asking the LLM") {
    val calls = themeLLM.calls.size
    postJson("/api/game/generate-adventure", """{"theme": "not an object"}""")("status").str shouldBe "error"
    postJson("/api/game/generate-adventure", "[1, 2]")("status").str shouldBe "error"
    postJson("/api/game/generate-adventure", "{}", "text/plain")("message").str should include("application/json")
    themeLLM.calls.size shouldBe calls
  }

  test("an adventure is kept, listed, played by id, its media served, and deleted") {
    import org.llm4s.szork.game.{AdventureGenerator, AdventureOutline, LocationOutline}
    val outline = AdventureOutline(
      title = "The Lamp Room",
      mainQuest = "Light the lamp",
      subQuests = Nil,
      keyLocations = List(LocationOutline("lamp_room", "Lamp Room", "A round room of glass.", "The only room")),
      importantItems = Nil,
      keyCharacters = Nil,
      adventureArc = "Up, and light"
    )
    getJson("/api/adventures")("adventures").arr shouldBe empty

    val body = ujson.Obj(
      "outline" -> AdventureGenerator.outlineToJson(outline),
      "theme" -> ujson.Obj("id" -> "sea", "name" -> "Sea", "prompt" -> "a lighthouse"),
      "artStyle" -> ujson.Obj("id" -> "painting", "name" -> "Painting")
    )
    val kept = postJson("/api/adventures", ujson.write(body))
    kept("status").str shouldBe "success"
    kept("id").str shouldBe "the-lamp-room"
    postJson("/api/adventures", ujson.write(body), contentType = "text/plain")("status").str shouldBe "error"
    postJson("/api/adventures", """{"outline": {"title": 3}}""")("status").str shouldBe "error"

    val listed = getJson("/api/adventures")("adventures").arr
    listed.map(_("id").str) shouldBe Seq("the-lamp-room")
    listed.head("title").str shouldBe "The Lamp Room"
    listed.head("artStyle").str shouldBe "painting"
    (listed.head("locations").num, listed.head("images").num) shouldBe (1, 0)

    // The library's media is served from the URLs it gives out, and nothing else is
    val picture = app.library
      .imageOr("the-lamp-room", "lamp_room", "painting")(
        Right(java.util.Base64.getEncoder.encodeToString("png".getBytes)))
      .toOption
      .get
    val served = requests.get(s"$http${picture.url}")
    served.text() shouldBe "png"
    served.headers("content-type").head shouldBe "image/png"
    served.headers("cache-control").head should include("immutable")
    requests.get(s"$http/api/library/adventures/the-lamp-room/adventure.json", check = false).statusCode shouldBe 404
    requests.get(s"$http/api/library/adventures/the-lamp-room/images/nope.png", check = false).statusCode shouldBe 404

    val c = new Client
    c.connectBlocking(5, TimeUnit.SECONDS) shouldBe true
    c.sendJson("type" -> "newGame", "adventureId" -> "the-lamp-room")
    val started = c.await("gameStarted")("data")
    app.wsServer.endGame(started("gameId").str)(())
    c.close()

    deleteJson("/api/adventures/the-lamp-room")("status").str shouldBe "success"
    deleteJson("/api/adventures/the-lamp-room")("status").str shouldBe "error"
    getJson("/api/adventures")("adventures").arr shouldBe empty
  }

  test("saved games are listed, and other folders in the save directory are ignored") {
    Files.createDirectories(saveRoot.resolve("saves").resolve("not-a-game"))
    val c = connect()
    val (_, gameId) = startGame(c)
    val games = getJson("/api/games")
    games("status").str shouldBe "success"
    games("games").arr.map(_("gameId").str) should contain(gameId)
    games("games").arr.map(_("gameId").str) should not contain "not-a-game"
    c.close()
  }

  test("deleting a game that is being played ends its session, then removes its saves") {
    val c = connect()
    val (_, gameId) = startGame(c)
    deleteJson(s"/api/game/$gameId")("status").str shouldBe "success"
    StepPersistence.gameExists(gameId) shouldBe false
    getJson("/api/games")("games").arr.map(_("gameId").str) should not contain gameId

    c.sendJson("type" -> "command", "command" -> "look")
    c.await("error")("data")("error").str shouldBe "No active session"
    c.close()
  }

  test("deleting an unknown or invalid game is an error") {
    deleteJson(s"/api/game/${IdGenerator.gameId()}")("status").str shouldBe "error"
    deleteJson("/api/game/not-a-game")("status").str shouldBe "error"
  }

  test("a session expires once it has had no connection for the session TTL") {
    val c = connect()
    val (sessionId, _) = startGame(c)
    // Still connected: never expires
    app.wsServer.expireSessions()
    c.sendJson("type" -> "resumeSession", "sessionId" -> sessionId)
    c.await("sessionResumed")
    c.closeBlocking()

    val d = connect()
    Thread.sleep(200) // the server notices the close, and the session is idle for longer than the TTL
    app.wsServer.expireSessions()
    d.sendJson("type" -> "resumeSession", "sessionId" -> sessionId)
    d.await("error")("data")("details").str shouldBe "SESSION_EXPIRED"
    d.close()
  }
}
