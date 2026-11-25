package org.llm4s.szork.stubs

import com.sun.net.httpserver.HttpServer
import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.LLMClient
import org.llm4s.szork.api.SzorkConfig

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters._

/** A local stand-in for the Anthropic Messages API: llm4s's real Anthropic client talks to it over HTTP, so a test can
  * read the requests exactly as they would go on the wire. (`ScriptedLLMClient` sits above the provider, and cannot
  * show a request the API would refuse.)
  *
  * Every request is answered with a text message chosen by `answer` from the request's JSON body; `stopReason` is the
  * `stop_reason` it reports.
  */
final class FakeAnthropic(answer: ujson.Value => String, stopReason: String = "end_turn") {
  private val received = new CopyOnWriteArrayList[ujson.Value]()
  private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)

  server.createContext(
    "/",
    exchange => {
      val request = ujson.read(new String(exchange.getRequestBody.readAllBytes(), UTF_8))
      received.add(request)
      val body = ujson
        .write(
          ujson.Obj(
            "id" -> s"msg_${received.size}",
            "type" -> "message",
            "role" -> "assistant",
            "model" -> request("model"),
            "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> answer(request))),
            "stop_reason" -> stopReason,
            "stop_sequence" -> ujson.Null,
            "usage" -> ujson.Obj("input_tokens" -> 10, "output_tokens" -> 10)
          ))
        .getBytes(UTF_8)
      exchange.getResponseHeaders.add("content-type", "application/json")
      exchange.sendResponseHeaders(200, body.length.toLong)
      exchange.getResponseBody.write(body)
      exchange.close()
    }
  )
  server.start()

  /** The request bodies received, oldest first. */
  def requests: List[ujson.Value] = received.asScala.toList

  /** The client Szork builds for `model` (the default Claude model when None), pointed at this server. */
  def client(model: Option[String] = None): LLMClient = {
    val env = Map(
      "SZORK_LLM_PROVIDER" -> "anthropic",
      "ANTHROPIC_API_KEY" -> "sk-ant-test",
      "ANTHROPIC_BASE_URL" -> s"http://127.0.0.1:${server.getAddress.getPort}"
    ) ++ model.map("SZORK_LLM_MODEL" -> _)
    val config = SzorkConfig.load(new ConfigReader { def get(key: String): Option[String] = env.get(key) })
    SzorkConfig.getLLMClient(config).fold(e => throw new IllegalStateException(s"client: $e"), identity)
  }

  def stop(): Unit = server.stop(0)
}

object FakeAnthropic {

  /** Runs `test` against a server answering with `answer`, and stops the server. */
  def using[A](answer: ujson.Value => String, stopReason: String = "end_turn")(test: FakeAnthropic => A): A = {
    val server = new FakeAnthropic(answer, stopReason)
    try test(server)
    finally server.stop()
  }

  /** The text of a request's system prompt, however the client sent it (a string, or text blocks). */
  def systemOf(request: ujson.Value): String = request.obj.get("system") match {
    case Some(ujson.Str(text)) => text
    case Some(ujson.Arr(blocks)) => blocks.flatMap(_.obj.get("text")).map(_.str).mkString("\n")
    case _ => ""
  }

  /** The names of the tools a request offers. */
  def toolsOf(request: ujson.Value): List[String] =
    request.obj.get("tools").toList.flatMap(_.arr).map(_("name").str)
}
