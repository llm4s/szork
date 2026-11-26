package org.llm4s.szork

import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.szork.config.ConfigReader
import org.llm4s.szork.api.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class SzorkConfigSpec extends AnyFunSuite with Matchers {

  private final case class FakeReader(values: Map[String, String]) extends ConfigReader {
    override def get(key: String): Option[String] = values.get(key)
  }

  private def load(env: (String, String)*): SzorkConfig = SzorkConfig.load(FakeReader(env.toMap))

  test("with nothing set, every setting has its default") {
    val config = load()
    config shouldBe SzorkConfig()
    config.allowedOrigins shouldBe
      Set("http://localhost:3090", "http://127.0.0.1:3090", "http://localhost:8090", "http://127.0.0.1:8090")
    config.sessionTtlMillis shouldBe 30L * 60 * 1000
  }

  test("every flag is off when set to false, off, 0 or no, in any case, and on otherwise") {
    val flags = List(
      "SZORK_IMAGE_GENERATION_ENABLED",
      "SZORK_TTS_ENABLED",
      "SZORK_STT_ENABLED",
      "SZORK_MUSIC_ENABLED",
      "SZORK_INPUT_GUARDRAIL",
      "SZORK_AUTO_SAVE",
      "SZORK_CACHE_ENABLED",
      "SZORK_NPC_AGENT",
      "SZORK_HINT_AGENT"
    )
    def values(c: SzorkConfig) = List(
      c.imageGenerationEnabled,
      c.ttsEnabled,
      c.sttEnabled,
      c.musicEnabled,
      c.inputGuardrailEnabled,
      c.autoSaveEnabled,
      c.cache.enabled,
      c.subagents.npcDialogue,
      c.subagents.hints
    )
    List("false", "OFF", " 0 ", "No").foreach { off =>
      withClue(off)(values(load(flags.map(_ -> off)*)) shouldBe List.fill(flags.size)(false))
    }
    List("true", "on", "1", "yes", "").foreach { on =>
      withClue(on)(values(load(flags.map(_ -> on)*)) shouldBe List.fill(flags.size)(true))
    }
  }

  test("a blank key does not count as set: provider detection falls through to the next provider") {
    load("OPENAI_API_KEY" -> "  ", "ANTHROPIC_API_KEY" -> "sk-ant").llmConfig shouldBe
      Some(LLMConfig("anthropic", "sk-ant", Some(LLMConfig.DefaultAnthropicModel)))
    load("OPENAI_API_KEY" -> " sk-oa ", "SZORK_LLM_MODEL" -> " ", "OPENAI_BASE_URL" -> "").llmConfig shouldBe
      Some(LLMConfig("openai", "sk-oa", Some(LLMConfig.DefaultOpenAIModel)))
    load("ANTHROPIC_API_KEY" -> "sk-ant", "ANTHROPIC_BASE_URL" -> " http://127.0.0.1:9999 ").llmConfig shouldBe
      Some(LLMConfig("anthropic", "sk-ant", Some(LLMConfig.DefaultAnthropicModel), Some("http://127.0.0.1:9999")))
    load("LLAMA_BASE_URL" -> " ", "OPENAI_API_KEY" -> "").llmConfig shouldBe None
  }

  test("an unknown provider or reasoning effort is a configuration error, not a silent fallback") {
    import SzorkConfig.ConfigOps
    val unknown = load("SZORK_LLM_PROVIDER" -> "gemini", "OPENAI_API_KEY" -> "sk-oa")
    unknown.llmConfig shouldBe None
    unknown.problems.head should include("SZORK_LLM_PROVIDER 'gemini'")
    val providerError = unknown.validate().left.toOption.get.message
    providerError should include("SZORK_LLM_PROVIDER 'gemini'")
    providerError should not include "No LLM provider configured"

    val effort = load("SZORK_REASONING_EFFORT" -> "hgih", "OPENAI_API_KEY" -> "sk-oa")
    effort.validate().left.toOption.get.message should include("SZORK_REASONING_EFFORT 'hgih'")
    load("SZORK_REASONING_EFFORT" -> "High", "OPENAI_API_KEY" -> "sk-oa").validate() shouldBe Right(())

    val language = load("SZORK_STT_LANGUAGE" -> "english", "OPENAI_API_KEY" -> "sk-oa")
    language.validate().left.toOption.get.message should include("SZORK_STT_LANGUAGE 'english'")
  }

  test("ports the server cannot listen on stop it from starting") {
    import SzorkConfig.ConfigOps
    val llm = Some(LLMConfig("openai", "sk-oa"))
    SzorkConfig(llmConfig = llm).validate() shouldBe Right(())
    SzorkConfig(llmConfig = llm, port = 70000).validate().left.toOption.get.message should include("Invalid port")
    SzorkConfig(llmConfig = llm, port = 9002).validate().left.toOption.get.message should include(
      "equals the HTTP port")
    SzorkConfig().validate().left.toOption.get.message should include("No LLM provider configured")
  }

  test("every SZORK_ setting is read") {
    val config = load(
      "SZORK_PORT" -> "8100",
      "SZORK_WS_PORT" -> "9100",
      "SZORK_HOST" -> "0.0.0.0",
      "SZORK_ALLOWED_ORIGINS" -> "https://a.example, https://b.example",
      "SZORK_ALLOWED_HOSTS" -> "game.example, proxy.example:443",
      "SZORK_IMAGE_GENERATION_ENABLED" -> "false",
      "SZORK_IMAGE_PROVIDER" -> "dalle3",
      "SZORK_TTS_ENABLED" -> "false",
      "SZORK_STT_ENABLED" -> "FALSE",
      "SZORK_MUSIC_ENABLED" -> " false ",
      "SZORK_TTS_VOICE" -> "onyx",
      "SZORK_TTS_MODEL" -> "tts-1-hd",
      "SZORK_STT_MODEL" -> "whisper-2",
      "SZORK_STT_LANGUAGE" -> "fr-FR",
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
      allowedHosts = Set("game.example", "proxy.example:443"),
      imageGenerationEnabled = false,
      imageProvider = ImageProvider.OpenAIDalle3,
      ttsEnabled = false,
      sttEnabled = false,
      musicEnabled = false,
      speech =
        SpeechConfig(ttsModel = "tts-1-hd", ttsVoice = "onyx", sttModel = "whisper-2", sttLanguage = Some("fr-FR")),
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

  test("a provider Szork does not know is an error, not an exception") {
    LLMConfig("gemini", "key").toProviderConfig shouldBe Left("Unknown LLM provider: gemini")
  }

  private def llamaBaseUrl(env: (String, String)*): String =
    SzorkConfig.loadLLMConfig(FakeReader(env.toMap)).toOption.flatten.map(_.toProviderConfig) match {
      case Some(Right(c: OpenAIConfig)) => c.baseUrl.asUrl
      case other => fail(s"expected an OpenAI-compatible config, got $other")
    }

  test("llama without LLAMA_BASE_URL uses the local server's /v1 API") {
    llamaBaseUrl("SZORK_LLM_PROVIDER" -> "llama") shouldBe "http://localhost:11434/v1"
  }

  test("llama uses LLAMA_BASE_URL when it is set") {
    llamaBaseUrl("SZORK_LLM_PROVIDER" -> "llama", "LLAMA_BASE_URL" -> "http://gpu:8000/v1") shouldBe
      "http://gpu:8000/v1"
  }

  test("media timeouts and music polling are read, and each falls back alone when its value does not parse") {
    def net(env: (String, String)*) = MediaNetworkConfig.load(FakeReader(env.toMap))
    def poll(env: (String, String)*) = MusicPollConfig.load(FakeReader(env.toMap))

    net() shouldBe MediaNetworkConfig(connectTimeoutMs = 10000, readTimeoutMs = 30000)
    net("SZORK_MEDIA_CONNECT_TIMEOUT_MS" -> "1234", "SZORK_MEDIA_READ_TIMEOUT_MS" -> "56789") shouldBe
      MediaNetworkConfig(1234, 56789)
    net("SZORK_MEDIA_CONNECT_TIMEOUT_MS" -> "abc", "SZORK_MEDIA_READ_TIMEOUT_MS" -> "56789") shouldBe
      MediaNetworkConfig(10000, 56789)
    net("SZORK_MEDIA_CONNECT_TIMEOUT_MS" -> "1234", "SZORK_MEDIA_READ_TIMEOUT_MS" -> "xyz") shouldBe
      MediaNetworkConfig(1234, 30000)

    poll() shouldBe MusicPollConfig(maxAttempts = 30, pollIntervalMs = 1000)
    poll("SZORK_MUSIC_POLL_MAX_ATTEMPTS" -> "7", "SZORK_MUSIC_POLL_INTERVAL_MS" -> "250") shouldBe
      MusicPollConfig(7, 250)
    poll("SZORK_MUSIC_POLL_MAX_ATTEMPTS" -> "abc", "SZORK_MUSIC_POLL_INTERVAL_MS" -> "250") shouldBe
      MusicPollConfig(30, 250)
    poll("SZORK_MUSIC_POLL_MAX_ATTEMPTS" -> "7", "SZORK_MUSIC_POLL_INTERVAL_MS" -> "xyz") shouldBe
      MusicPollConfig(7, 1000)
  }
}
