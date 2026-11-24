package org.llm4s.szork

import com.sun.net.httpserver.HttpServer
import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.model.{Conversation, UserMessage}
import org.llm4s.model.ModelRegistryService
import org.llm4s.szork.api.{SzorkConfig, SzorkModelRegistry}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicReference

class SzorkModelRegistrySpec extends AnyFunSuite with Matchers {

  private val registry: ModelRegistryService =
    SzorkModelRegistry.load().fold(e => fail(s"registry: ${e.message}"), identity)

  private def disallowed(model: String): Set[String] =
    registry.lookup(model).toOption.flatMap(_.capabilities.disallowedParams).getOrElse(Set.empty)

  test("the Claude models that reject a temperature are marked so llm4s does not send one") {
    val models = Seq(
      "claude-sonnet-5-5",
      "claude-sonnet-5",
      "claude-haiku-5-5",
      "claude-fable-5-2",
      "claude-mythos-5-2",
      "anthropic/claude-sonnet-5-5")
    models.foreach(model => withClue(model)(disallowed(model) should contain("temperature")))
  }

  test("models that take a temperature are left as llm4s describes them") {
    val base = ModelRegistryService.default().fold(e => fail(e.message), identity)
    Seq("gpt-4o", "claude-sonnet-4-6", "claude-haiku-4-5", "claude-3-5-sonnet-20241022").foreach { model =>
      withClue(model) {
        disallowed(model) should not contain "temperature"
        registry.lookup(model) shouldBe base.lookup(model)
      }
    }
  }

  test("a model llm4s knows keeps its details when it is marked") {
    val base = ModelRegistryService.default().fold(e => fail(e.message), identity)
    base.lookup("claude-fable-5").foreach { known =>
      val marked = registry.lookup("claude-fable-5").fold(e => fail(e.message), identity)
      marked.maxInputTokens shouldBe known.maxInputTokens
      marked.capabilities.disallowedParams.getOrElse(Set.empty) should contain("temperature")
    }
  }

  test("a request to the default Claude model carries no temperature") {
    val sent = new AtomicReference[String]("")
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      exchange => {
        sent.set(new String(exchange.getRequestBody.readAllBytes(), UTF_8))
        val body =
          """{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5",
            |"content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn","stop_sequence":null,
            |"usage":{"input_tokens":1,"output_tokens":1}}""".stripMargin.getBytes(UTF_8)
        exchange.getResponseHeaders.add("content-type", "application/json")
        exchange.sendResponseHeaders(200, body.length.toLong)
        exchange.getResponseBody.write(body)
        exchange.close()
      }
    )
    server.start()
    try {
      val env = Map(
        "SZORK_LLM_PROVIDER" -> "anthropic",
        "ANTHROPIC_API_KEY" -> "sk-ant-test",
        "ANTHROPIC_BASE_URL" -> s"http://127.0.0.1:${server.getAddress.getPort}"
      )
      val config = SzorkConfig.load(new ConfigReader { def get(key: String): Option[String] = env.get(key) })
      val client = SzorkConfig.getLLMClient(config).fold(e => fail(s"client: $e"), identity)

      val answer = client.complete(Conversation(Seq(UserMessage("hi"))))

      answer.map(_.content) shouldBe Right("hello")
      val request = ujson.read(sent.get)
      request("model").str shouldBe "claude-sonnet-5-5"
      request.obj.keySet should not contain "temperature"
    } finally server.stop(0)
  }
}
