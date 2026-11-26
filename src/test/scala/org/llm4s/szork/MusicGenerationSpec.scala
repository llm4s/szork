package org.llm4s.szork

import org.llm4s.security.Secret
import org.llm4s.szork.api.{MediaNetworkConfig, MusicPollConfig}
import org.llm4s.szork.error.{MusicGenerationError, NetworkError}
import org.llm4s.szork.media.MusicGeneration
import org.llm4s.szork.media.MusicGeneration.MusicMoods
import org.llm4s.szork.stubs.FakeReplicate
import org.llm4s.szork.stubs.FakeReplicate.{json, Audio, Reply, Request}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** [[MusicGeneration]] against a local stand-in for Replicate: every status the API can answer with, as it goes on the
  * wire. No network, no real key.
  */
class MusicGenerationSpec extends AnyFunSuite with Matchers {

  private val net = MediaNetworkConfig(connectTimeoutMs = 2000, readTimeoutMs = 2000)
  private def music(server: FakeReplicate, polls: Int = 20, intervalMs: Int = 20) =
    new MusicGeneration(Some(Secret("test-key")), net, MusicPollConfig(polls, intervalMs), server.baseUrl)

  private val created = json(201, ujson.Obj("id" -> "p1", "status" -> "starting"))
  private def status(state: String, extra: (String, ujson.Value)*) =
    json(200, ujson.Obj.from(Seq("id" -> (ujson.Str("p1"): ujson.Value), "status" -> ujson.Str(state)) ++ extra))
  private def succeeded(server: FakeReplicate) = status("succeeded", "output" -> ujson.Str(server.audioUrl))
  private val audio = Base64.getEncoder.encodeToString(Audio)

  private def route(onCreate: => Reply, onPoll: => Reply, server: FakeReplicate)(request: Request): Reply =
    (request.method, request.path) match {
      case ("POST", "/v1/predictions") => onCreate
      case ("GET", "/v1/predictions/p1") => onPoll
      case ("POST", "/v1/predictions/p1/cancel") => status("canceled")
      case ("GET", "/audio.mp3") => Reply(200, Audio)
      case _ => Reply(404)
    }

  test("a track is made: the prediction is created with the mood, polled, and its audio downloaded") {
    val polls = new AtomicInteger()
    FakeReplicate.using(server =>
      route(created, if (polls.incrementAndGet() < 3) status("processing") else succeeded(server), server)) { server =>
      music(server).generate(MusicMoods.MYSTERY) shouldBe Right(audio)

      val create = server.requests.head
      create.authorization shouldBe Some("Bearer test-key")
      val input = ujson.read(create.body)("input")
      input("prompt").str shouldBe MusicMoods.MYSTERY.basePrompt
      input("output_format").str shouldBe "mp3"
      server.count("GET", "/v1/predictions/p1") shouldBe 3
      server.count("POST", "/v1/predictions/p1/cancel") shouldBe 0
    }
  }

  test("a request Replicate refuses is not retryable, and nothing is polled") {
    Seq(401, 402, 422).foreach { code =>
      FakeReplicate.using(server => route(Reply(code, "no".getBytes), Reply(500), server)) { server =>
        val result = music(server).generate(MusicMoods.MYSTERY)
        withClue(code)(result.left.toOption.get match {
          case e: NetworkError => (e.retryable, e.message) shouldBe (false, s"Failed to create prediction: $code - no")
          case other => fail(s"unexpected $other")
        })
        server.count("GET", "/v1/predictions/p1") shouldBe 0
      }
    }
  }

  test("a busy or failing Replicate is a retryable error") {
    Seq(429, 500, 503).foreach { code =>
      FakeReplicate.using(server => route(Reply(code), Reply(500), server)) { server =>
        music(server).generate(MusicMoods.MYSTERY).left.toOption.get.retryable shouldBe true
      }
    }
  }

  test("a poll that fails once is tried again, and the track is still made") {
    val polls = new AtomicInteger()
    FakeReplicate.using(server =>
      route(
        created,
        polls.incrementAndGet() match {
          case 1 => Reply(503)
          case 2 => status("processing")
          case _ => succeeded(server)
        },
        server)) { server =>
      music(server).generate(MusicMoods.MYSTERY) shouldBe Right(audio)
      server.count("POST", "/v1/predictions/p1/cancel") shouldBe 0
    }
  }

  test("polls that keep failing give up, and the prediction is cancelled") {
    FakeReplicate.using(server => route(created, Reply(502), server)) { server =>
      val result = music(server).generate(MusicMoods.MYSTERY)
      result.isLeft shouldBe true
      server.count("GET", "/v1/predictions/p1") shouldBe 3
      server.count("POST", "/v1/predictions/p1/cancel") shouldBe 1
    }
  }

  test("a poll Replicate refuses ends the wait at once, and the prediction is cancelled") {
    FakeReplicate.using(server => route(created, Reply(401), server)) { server =>
      music(server).generate(MusicMoods.MYSTERY).left.toOption.get.retryable shouldBe false
      server.count("GET", "/v1/predictions/p1") shouldBe 1
      server.count("POST", "/v1/predictions/p1/cancel") shouldBe 1
    }
  }

  test("a prediction that runs out its time is cancelled, and the wait is a deadline") {
    FakeReplicate.using(server => route(created, status("processing"), server)) { server =>
      val start = System.nanoTime()
      val result = music(server, polls = 5, intervalMs = 40).generate(MusicMoods.MYSTERY)
      val elapsedMs = (System.nanoTime() - start) / 1000000

      result.left.toOption.get match {
        case e: MusicGenerationError => e.message should include("Timeout")
        case other => fail(s"unexpected $other")
      }
      server.count("POST", "/v1/predictions/p1/cancel") shouldBe 1
      elapsedMs should be < 5000L
    }
  }

  test("a prediction that failed is reported, and not cancelled (nothing is running)") {
    FakeReplicate.using(server => route(created, status("failed", "error" -> ujson.Str("model fell over")), server)) {
      server =>
        val result = music(server).generate(MusicMoods.MYSTERY)
        result.left.toOption.get.message should include("model fell over")
        result.left.toOption.get.retryable shouldBe false
        server.count("POST", "/v1/predictions/p1/cancel") shouldBe 0
    }
  }

  test("an interrupted wait cancels the prediction and keeps the thread's interrupt") {
    FakeReplicate.using(server => route(created, status("processing"), server)) { server =>
      val generation = music(server, polls = 200, intervalMs = 100)
      @volatile var result: Option[Either[org.llm4s.szork.error.SzorkError, String]] = None
      @volatile var interrupted = false
      val worker = new Thread(() => {
        result = Some(generation.generate(MusicMoods.MYSTERY))
        interrupted = Thread.currentThread().isInterrupted
      })
      worker.start()
      // Wait until it is polling, then interrupt it
      val until = System.currentTimeMillis() + 10000
      while (server.count("GET", "/v1/predictions/p1") == 0 && System.currentTimeMillis() < until) Thread.sleep(10)
      worker.interrupt()
      worker.join(10000)

      worker.isAlive shouldBe false
      result.get.isLeft shouldBe true
      interrupted shouldBe true
      server.count("POST", "/v1/predictions/p1/cancel") shouldBe 1
    }
  }

  test("audio that cannot be downloaded is an error") {
    FakeReplicate.using(server =>
      route(created, status("succeeded", "output" -> ujson.Str(server.audioUrl + "-gone")), server)) { server =>
      music(server).generate(MusicMoods.MYSTERY).isLeft shouldBe true
    }
  }

  test("without a key no request is made") {
    FakeReplicate.using(server => route(created, Reply(500), server)) { server =>
      val unkeyed = new MusicGeneration(None, net, MusicPollConfig(), server.baseUrl)
      unkeyed.isAvailable shouldBe false
      unkeyed.generate(MusicMoods.MYSTERY).isLeft shouldBe true
      server.requests shouldBe empty
    }
  }

  test("scene music moods are looked up by name") {
    MusicMoods.byName("combat") shouldBe Some(MusicMoods.COMBAT)
    MusicMoods.byName(" Mystery ") shouldBe Some(MusicMoods.MYSTERY)
    MusicMoods.byName("tense") shouldBe None
    MusicMoods.orDefault("tense") shouldBe MusicMoods.EXPLORATION
  }
}
