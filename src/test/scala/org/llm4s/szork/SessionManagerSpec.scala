package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.ExecutionContext
import org.llm4s.szork.api.{GameSession, SessionFeatures, SessionManager}
import org.llm4s.szork.game.GameEngine
import org.llm4s.szork.stubs.ScriptedDm

class SessionManagerSpec extends AnyFunSuite with Matchers {

  private val features = SessionFeatures(tts = false, stt = false, image = false, music = false)

  private def session(id: String, gameId: String): GameSession =
    new GameSession(id, gameId, GameEngine.create(ScriptedDm(), id), None, None, features, ExecutionContext.parasitic)

  test("a game has one session: registering a second joins the first") {
    val manager = new SessionManager()
    val first = session("s1", "game-1")
    manager.registerOrJoin(first)(identity) shouldBe theSameInstanceAs(first)
    manager.registerOrJoin(session("s2", "game-1"))(identity) shouldBe theSameInstanceAs(first)
    manager.getSession("s2") shouldBe None
    manager.sessionForGame("game-1") shouldBe Some(first)
  }

  test("removing a game's session frees the game") {
    val manager = new SessionManager()
    val s = session("s1", "game-1")
    manager.createSession(s)
    manager.removeGame("game-1") shouldBe Some(s)
    manager.getSession("s1") shouldBe None
    manager.joinGame("game-1")(identity) shouldBe None
  }

  test("expiry removes idle sessions not in use, and their games") {
    val manager = new SessionManager()
    val idle = session("idle", "game-1")
    val connected = session("connected", "game-2")
    manager.createSession(idle)
    manager.createSession(connected)
    val later = System.currentTimeMillis() + 60000
    manager.expireIdle(later, 1000, _ == "connected") shouldBe List("idle")
    manager.getSession("idle") shouldBe None
    manager.sessionForGame("game-1") shouldBe None
    manager.getSession("connected") shouldBe Some(connected)
  }

  test("a session being attached cannot expire until the attachment is done") {
    val manager = new SessionManager()
    val s = session("s1", "game-1")
    manager.createSession(s)
    val later = System.currentTimeMillis() + 60000
    @volatile var expired: List[String] = Nil
    val expiry = new Thread(() => expired = manager.expireIdle(later, 1000, _ => false))
    manager.withSession("s1") { _ =>
      expiry.start()
      Thread.sleep(200)
      expiry.isAlive shouldBe true // waiting for the attachment
    }
    expiry.join(5000)
    expired shouldBe List("s1") // still idle by `later`: expiry ran after, not during
  }
}
