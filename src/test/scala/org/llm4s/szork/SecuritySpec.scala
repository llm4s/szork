package org.llm4s.szork

import java.net.{InetSocketAddress, ServerSocket, URI}
import java.nio.file.{Files, Paths}
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._
import org.llm4s.szork.api.{Capabilities, SessionManager}
import org.llm4s.szork.game.IdGenerator
import org.llm4s.szork.media.MediaCache
import org.llm4s.szork.persistence.{GameDirs, StepPersistence}
import org.llm4s.szork.websocket.TypedWebSocketServer

/** Regression tests for issue #25: game ids reaching the filesystem, and cross-site WebSocket access. */
class SecuritySpec extends AnyFunSuite with Matchers {

  private val hostile =
    List("..", "../..", "game-../../etc", "/tmp", "game-ABCDEF12", "game-1234567", "", "game-12345678/x")

  test("only generated game ids are valid") {
    IdGenerator.isValidGameId(IdGenerator.gameId()) shouldBe true
    hostile.foreach(id => withClue(id)(IdGenerator.isValidGameId(id) shouldBe false))
  }

  test("game directories stay under their root") {
    val root = Paths.get("some-root")
    GameDirs.resolve(root, "game-0123abcd") shouldBe root.toAbsolutePath.normalize().resolve("game-0123abcd")
    hostile.foreach { id =>
      withClue(id)(an[IllegalArgumentException] should be thrownBy GameDirs.resolve(root, id))
    }
  }

  test("deleting or loading with a hostile id fails without touching the filesystem") {
    val marker = Files.createTempFile(Paths.get("").toAbsolutePath, "szork-security", ".tmp")
    try {
      hostile.foreach { id =>
        withClue(id) {
          StepPersistence.deleteGame(id).isLeft shouldBe true
          StepPersistence.loadGameMetadata(id).isLeft shouldBe true
          MediaCache.clearGameCache(id).isLeft shouldBe true
          StepPersistence.gameExists(id) shouldBe false
        }
      }
      Files.exists(marker) shouldBe true // the working directory was not deleted
    } finally Files.deleteIfExists(marker)
  }

  test("browser origins must be allowed; clients without an Origin header may connect") {
    val allowed = Some(Set("http://localhost:3090"))
    TypedWebSocketServer.originAllowed(Some("http://localhost:3090"), allowed) shouldBe true
    TypedWebSocketServer.originAllowed(Some("https://evil.example"), allowed) shouldBe false
    TypedWebSocketServer.originAllowed(None, allowed) shouldBe true
    TypedWebSocketServer.originAllowed(Some("https://evil.example"), None) shouldBe true
  }

  test("a WebSocket handshake from a disallowed origin is refused") {
    val port = {
      val s = new ServerSocket(0);
      try s.getLocalPort
      finally s.close()
    }
    val pool = Executors.newFixedThreadPool(2)
    val server = new TypedWebSocketServer(
      address = new InetSocketAddress("127.0.0.1", port),
      sessionManager = new SessionManager(),
      capabilities = Capabilities(tts = false, stt = false, image = false, music = false),
      engineFactory = (_, _, _, _, _) => Left("not used"),
      sttClient = None,
      workPool = pool,
      mediaEC = ExecutionContext.fromExecutor(pool),
      autoSave = false,
      allowedOrigins = Some(Set("http://localhost:3090"))
    )
    server.setReuseAddr(true)
    server.start()
    Thread.sleep(300)

    def connects(origin: String): Boolean = {
      val opened = new CountDownLatch(1)
      val client = new WebSocketClient(new URI(s"ws://127.0.0.1:$port"), Map("Origin" -> origin).asJava) {
        override def onOpen(h: ServerHandshake): Unit = opened.countDown()
        override def onMessage(m: String): Unit = ()
        override def onClose(c: Int, r: String, remote: Boolean): Unit = ()
        override def onError(e: Exception): Unit = ()
      }
      try client.connectBlocking(5, TimeUnit.SECONDS) && opened.await(1, TimeUnit.SECONDS)
      finally client.close()
    }

    try {
      connects("http://localhost:3090") shouldBe true
      connects("https://evil.example") shouldBe false
    } finally {
      server.stop(1000)
      pool.shutdownNow()
    }
  }
}
