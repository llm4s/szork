package org.llm4s.szork

import org.llm4s.config.ConfigReader
import org.llm4s.szork.api._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class SzorkConfigSpec extends AnyFunSuite with Matchers {

  private final case class FakeReader(values: Map[String, String]) extends ConfigReader {
    override def get(key: String): Option[String] = values.get(key)
  }

  private def load(env: (String, String)*): SzorkConfig = SzorkConfig.load(FakeReader(env.toMap))

  test("with nothing set, every setting has its default") {
    val config = load()
    config shouldBe SzorkConfig(
      allowedOrigins =
        Set("http://localhost:3090", "http://127.0.0.1:3090", "http://localhost:8090", "http://127.0.0.1:8090"))
    config.sessionTtlMillis shouldBe 30L * 60 * 1000
  }

  test("every SZORK_ setting is read") {
    val config = load(
      "SZORK_PORT" -> "8100",
      "SZORK_WS_PORT" -> "9100",
      "SZORK_HOST" -> "0.0.0.0",
      "SZORK_ALLOWED_ORIGINS" -> "https://a.example, https://b.example",
      "SZORK_IMAGE_GENERATION_ENABLED" -> "false",
      "SZORK_IMAGE_PROVIDER" -> "dalle3",
      "SZORK_TTS_ENABLED" -> "false",
      "SZORK_STT_ENABLED" -> "FALSE",
      "SZORK_MUSIC_ENABLED" -> " false ",
      "SZORK_TTS_VOICE" -> "onyx",
      "SZORK_TTS_MODEL" -> "tts-1-hd",
      "SZORK_STT_MODEL" -> "whisper-2",
      "SZORK_MEDIA_CONNECT_TIMEOUT_MS" -> "1000",
      "SZORK_MEDIA_READ_TIMEOUT_MS" -> "2000",
      "SZORK_MUSIC_POLL_MAX_ATTEMPTS" -> "5",
      "SZORK_MUSIC_POLL_INTERVAL_MS" -> "50",
      "SZORK_INPUT_GUARDRAIL" -> "off",
      "SZORK_AUTO_SAVE" -> "false",
      "SZORK_SAVES_DIR" -> "/tmp/saves",
      "SZORK_CACHE_ENABLED" -> "false",
      "SZORK_CACHE_DIR" -> "/tmp/cache",
      "SZORK_CACHE_TTL_MS" -> "1000",
      "SZORK_CACHE_MAX_BYTES" -> "2048",
      "SZORK_SESSION_TTL_MINUTES" -> "5",
      "SZORK_DM_CONTEXT_MAX_TOKENS" -> "off"
    )
    config shouldBe SzorkConfig(
      port = 8100,
      wsPort = 9100,
      host = "0.0.0.0",
      allowedOrigins = Set("https://a.example", "https://b.example"),
      imageGenerationEnabled = false,
      imageProvider = ImageProvider.OpenAIDalle3,
      ttsEnabled = false,
      sttEnabled = false,
      musicEnabled = false,
      speech = SpeechConfig(ttsModel = "tts-1-hd", ttsVoice = "onyx", sttModel = "whisper-2"),
      mediaNetwork = MediaNetworkConfig(connectTimeoutMs = 1000, readTimeoutMs = 2000),
      musicPoll = MusicPollConfig(maxAttempts = 5, pollIntervalMs = 50),
      dmContextWindow = None,
      inputGuardrailEnabled = false,
      autoSaveEnabled = false,
      savesDirectory = "/tmp/saves",
      cache = MediaCacheConfig(enabled = false, directory = "/tmp/cache", ttlMs = 1000, maxBytes = 2048),
      sessionTtlMinutes = 5
    )
  }

  test("numbers that do not parse, and a session TTL that is not positive, fall back to the defaults") {
    val config = load(
      "SZORK_PORT" -> "eighty",
      "SZORK_WS_PORT" -> "",
      "SZORK_CACHE_MAX_BYTES" -> "lots",
      "SZORK_SESSION_TTL_MINUTES" -> "0")
    config.port shouldBe 8090
    config.wsPort shouldBe 9002
    config.cache.maxBytes shouldBe MediaCacheConfig.DefaultMaxBytes
    config.sessionTtlMinutes shouldBe SzorkConfig.DefaultSessionTtlMinutes
  }

  test("the LLM is the provider SZORK_LLM_PROVIDER names, or else the first whose settings are present") {
    load().llmConfig shouldBe None
    load("ANTHROPIC_API_KEY" -> "sk-ant", "OPENAI_API_KEY" -> "sk-oa").llmConfig shouldBe
      Some(LLMConfig("openai", "sk-oa", Some(LLMConfig.DefaultOpenAIModel)))
    load(
      "ANTHROPIC_API_KEY" -> "sk-ant",
      "OPENAI_API_KEY" -> "sk-oa",
      "SZORK_LLM_PROVIDER" -> "anthropic").llmConfig shouldBe Some(
      LLMConfig("anthropic", "sk-ant", Some(LLMConfig.DefaultAnthropicModel)))
    load("LLAMA_BASE_URL" -> "http://gpu:8000/v1", "SZORK_LLM_MODEL" -> "mistral").llmConfig shouldBe
      Some(LLMConfig("llama", "not-required", Some("mistral"), Some("http://gpu:8000/v1")))
    // A named provider without its key is not configured
    load("SZORK_LLM_PROVIDER" -> "anthropic", "OPENAI_API_KEY" -> "sk-oa").llmConfig shouldBe None
  }
}
