package org.llm4s.szork.api

import com.typesafe.config.ConfigFactory
import org.llm4s.config.{ConfigReader, Llm4sConfig}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The LLM provider selection and the llm4s 0.5 named provider sections in application.conf. No network. */
class LLMProviderConfigSpec extends AnyFunSuite with Matchers {

  private final case class FakeReader(values: Map[String, String]) extends ConfigReader {
    override def get(key: String): Option[String] = values.get(key)
  }

  private def select(values: (String, String)*) = SzorkConfig.loadLLMConfig(FakeReader(values.toMap)).map(_.provider)

  test("SZORK_LLM_PROVIDER picks the section, and needs that provider's key") {
    select("SZORK_LLM_PROVIDER" -> "anthropic", "ANTHROPIC_API_KEY" -> "k") shouldBe Some("anthropic")
    select("SZORK_LLM_PROVIDER" -> "openai") shouldBe None
    select("SZORK_LLM_PROVIDER" -> "llama") shouldBe Some("ollama")
    select("SZORK_LLM_PROVIDER" -> "unknown", "OPENAI_API_KEY" -> "k") shouldBe None
  }

  test("without SZORK_LLM_PROVIDER the first provider with credentials is used") {
    select("OPENAI_API_KEY" -> "k", "ANTHROPIC_API_KEY" -> "k") shouldBe Some("openai")
    select("ANTHROPIC_API_KEY" -> "k") shouldBe Some("anthropic")
    select("LLAMA_BASE_URL" -> "http://localhost:11434") shouldBe Some("ollama")
    select() shouldBe None
  }

  test("the ollama section loads through the llm4s-ollama provider module") {
    val config = Llm4sConfig.provider("ollama")
    config.map(_.model) shouldBe Right("llama3.1")
  }

  test("a key set as a system property (as .env entries are exported) reaches the openai section") {
    System.setProperty("OPENAI_API_KEY", "sk-test-not-a-real-key")
    ConfigFactory.invalidateCaches()
    try {
      val config = Llm4sConfig.provider("openai")
      config.map(_.model) shouldBe Right("gpt-4o")
    } finally {
      System.clearProperty("OPENAI_API_KEY")
      ConfigFactory.invalidateCaches()
    }
  }
}
