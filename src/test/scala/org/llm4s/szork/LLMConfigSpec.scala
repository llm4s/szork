package org.llm4s.szork

import org.llm4s.szork.config.ConfigReader
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.szork.api.{LLMConfig, SzorkConfig}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class LLMConfigSpec extends AnyFunSuite with Matchers {

  private final case class FakeReader(values: Map[String, String]) extends ConfigReader {
    override def get(key: String): Option[String] = values.get(key)
  }

  private def baseUrl(env: Map[String, String]): String =
    SzorkConfig.loadLLMConfig(FakeReader(env)).toOption.flatten.map(_.toProviderConfig) match {
      case Some(Right(c: OpenAIConfig)) => c.baseUrl.asUrl
      case other => fail(s"expected an OpenAI-compatible config, got $other")
    }

  test("a provider Szork does not know is an error, not an exception") {
    LLMConfig("gemini", "key").toProviderConfig shouldBe Left("Unknown LLM provider: gemini")
  }

  test("llama without LLAMA_BASE_URL uses the local server's /v1 API") {
    baseUrl(Map("SZORK_LLM_PROVIDER" -> "llama")) shouldBe "http://localhost:11434/v1"
  }

  test("llama uses LLAMA_BASE_URL when it is set") {
    baseUrl(Map("SZORK_LLM_PROVIDER" -> "llama", "LLAMA_BASE_URL" -> "http://gpu:8000/v1")) shouldBe
      "http://gpu:8000/v1"
  }
}
