package org.llm4s.szork.api

import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._
import org.llm4s.agent.ContextWindowConfig
import org.llm4s.config.{ConfigReader, EnvLoader}
import org.llm4s.szork.game.GameEngine
import org.llm4s.szork.media.{MediaCache, SceneImages}
import org.llm4s.szork.persistence.StepPersistence
import org.llm4s.security.Secret
import org.slf4j.Logger
import java.nio.file.Paths

/** Image generation provider options
  */
sealed trait ImageProvider
object ImageProvider {
  case object None extends ImageProvider
  case object HuggingFace extends ImageProvider
  case object HuggingFaceSDXL extends ImageProvider
  case object OpenAIDalle2 extends ImageProvider
  case object OpenAIDalle3 extends ImageProvider
  case object LocalStableDiffusion extends ImageProvider

  def fromString(s: String): ImageProvider = s.toLowerCase match {
    case "none" | "" => None
    case "huggingface" | "hf" => HuggingFace
    case "huggingface-sdxl" | "hf-sdxl" | "sdxl" => HuggingFaceSDXL
    case "dalle2" | "dall-e-2" | "openai-dalle2" => OpenAIDalle2
    case "dalle3" | "dall-e-3" | "openai-dalle3" => OpenAIDalle3
    case "local" | "localsd" | "local-sd" | "stable-diffusion" | "sd" => LocalStableDiffusion
    case _ => None
  }

  def toString(provider: ImageProvider): String = provider match {
    case None => "None"
    case HuggingFace => "HuggingFace"
    case HuggingFaceSDXL => "HuggingFace SDXL"
    case OpenAIDalle2 => "OpenAI DALL-E 2"
    case OpenAIDalle3 => "OpenAI DALL-E 3"
    case LocalStableDiffusion => "Local Stable Diffusion"
  }
}

/** Configuration for the Szork game server.
  *
  * [[SzorkConfig.load]] is the one reader of Szork's own `SZORK_*` settings; everything else is handed the values it
  * needs. Provider credentials (OPENAI_API_KEY and the like) are read where the clients that use them are built.
  */
case class SzorkConfig(
  // Server configuration
  port: Int = 8090,
  wsPort: Int = 9002,
  host: String = "127.0.0.1",

  // Image generation configuration
  imageGenerationEnabled: Boolean = true,
  imageProvider: ImageProvider = ImageProvider.None,

  // Audio/speech/media feature flags and settings
  ttsEnabled: Boolean = true,
  sttEnabled: Boolean = true,
  musicEnabled: Boolean = true,
  speech: SpeechConfig = SpeechConfig(),
  mediaNetwork: MediaNetworkConfig = MediaNetworkConfig(),
  musicPoll: MusicPollConfig = MusicPollConfig(),

  // LLM configuration
  llmConfig: Option[LLMConfig] = None,
  // How much of the game's conversation each Dungeon Master model call is sent (None: all of it)
  dmContextWindow: Option[ContextWindowConfig] = Some(GameEngine.DefaultContextWindow),
  // Whether player commands pass the Dungeon Master's prompt-injection guardrail (PlayerInputGuardrail)
  inputGuardrailEnabled: Boolean = true,
  // The Dungeon Master's subagents: NPC dialogue and hints, and the model they use
  subagents: SubagentConfig = SubagentConfig(),

  // Saves and the media cache
  autoSaveEnabled: Boolean = true,
  savesDirectory: String = "szork-saves",
  cache: MediaCacheConfig = MediaCacheConfig(),

  // How long a session with no connection lives before it expires
  sessionTtlMinutes: Int = SzorkConfig.DefaultSessionTtlMinutes,

  // Browser origins allowed to open the WebSocket
  allowedOrigins: Set[String] = Set("http://localhost:3090", "http://127.0.0.1:3090")
) {
  def sessionTtlMillis: Long = sessionTtlMinutes.toLong * 60 * 1000
}

case class LLMConfig(
  provider: String,
  apiKey: String,
  model: Option[String] = None,
  baseUrl: Option[String] = None
) {

  /** Convert to llm4s ProviderConfig for the new API */
  def toProviderConfig: org.llm4s.llmconnect.config.ProviderConfig =
    provider.toLowerCase match {
      case "openai" =>
        org.llm4s.llmconnect.config.OpenAIConfig(
          apiKey = Secret(apiKey),
          model = model.getOrElse(LLMConfig.DefaultOpenAIModel),
          organization = None,
          baseUrl = baseUrl.getOrElse("https://api.openai.com/v1"),
          contextWindow = 128000,
          reserveCompletion = 4096
        )
      case "anthropic" =>
        org.llm4s.llmconnect.config.AnthropicConfig(
          apiKey = Secret(apiKey),
          model = model.getOrElse(LLMConfig.DefaultAnthropicModel),
          baseUrl = baseUrl.getOrElse("https://api.anthropic.com"),
          contextWindow = 200000,
          reserveCompletion = 4096
        )
      case "llama" | "local" =>
        // Use OpenAI-compatible config for local LLaMA servers
        org.llm4s.llmconnect.config.OpenAIConfig(
          apiKey = Secret(if (apiKey == "not-required") "sk-no-key-required" else apiKey),
          model = model.getOrElse(LLMConfig.DefaultLlamaModel),
          organization = None,
          baseUrl = baseUrl.getOrElse("http://localhost:11434/v1"),
          contextWindow = 8192,
          reserveCompletion = 2048
        )
      case other =>
        throw new IllegalArgumentException(s"Unknown LLM provider: $other")
    }
}

object LLMConfig {

  /** Models used when SZORK_LLM_MODEL is not set. */
  val DefaultOpenAIModel = "gpt-4o"
  val DefaultAnthropicModel = "claude-sonnet-5-5"
  val DefaultLlamaModel = "llama3"
}

object SzorkConfig {
  val DefaultSessionTtlMinutes: Int = 30

  /** The configuration from the environment (.env and the process environment), for the command-line tools. */
  lazy val instance: SzorkConfig = load()

  /** An LLM client for `config`'s provider. */
  def getLLMClient(config: SzorkConfig = instance): Either[String, org.llm4s.llmconnect.LLMClient] =
    config.llmConfig match {
      case Some(llm) =>
        try
          SzorkModelRegistry.load().left.map(_.message).flatMap { registry =>
            given org.llm4s.model.ModelRegistryService = registry
            org.llm4s.llmconnect.LLMConnect.getClient(llm.toProviderConfig).left.map(_.message)
          }
        catch {
          case e: Exception => Left(s"Failed to create LLM client: ${e.getMessage}")
        }
      case None =>
        Left("No LLM configuration found")
    }

  /** The configuration in `env`. A feature flag is on unless set to `false`; a number that does not parse, and an empty
    * value, are ignored.
    */
  def load(env: ConfigReader = EnvLoader): SzorkConfig = {
    def setting(key: String): Option[String] = env.get(key).map(_.trim).filter(_.nonEmpty)
    def flag(key: String): Boolean = !setting(key).exists(_.equalsIgnoreCase("false"))
    def int(key: String): Option[Int] = setting(key).flatMap(_.toIntOption)

    val port = int("SZORK_PORT").getOrElse(8090)
    // Browser origins allowed to open the WebSocket (comma-separated); defaults to the dev frontend and this server
    val allowedOrigins = setting("SZORK_ALLOWED_ORIGINS")
      .map(_.split(",").map(_.trim).filter(_.nonEmpty).toSet)
      .getOrElse(Set(3090, port).flatMap(p => Set(s"http://localhost:$p", s"http://127.0.0.1:$p")))

    SzorkConfig(
      port = port,
      wsPort = int("SZORK_WS_PORT").getOrElse(9002),
      host = setting("SZORK_HOST").getOrElse("127.0.0.1"),
      allowedOrigins = allowedOrigins,
      imageGenerationEnabled = flag("SZORK_IMAGE_GENERATION_ENABLED"),
      imageProvider = setting("SZORK_IMAGE_PROVIDER").map(ImageProvider.fromString).getOrElse(ImageProvider.None),
      ttsEnabled = flag("SZORK_TTS_ENABLED"),
      sttEnabled = flag("SZORK_STT_ENABLED"),
      musicEnabled = flag("SZORK_MUSIC_ENABLED"),
      speech = SpeechConfig.load(env),
      mediaNetwork = MediaNetworkConfig.load(env),
      musicPoll = MusicPollConfig.load(env),
      llmConfig = loadLLMConfig(env),
      dmContextWindow = loadDmContextWindow(env),
      inputGuardrailEnabled =
        setting("SZORK_INPUT_GUARDRAIL").map(_.toLowerCase).forall(v => v != "false" && v != "off"),
      subagents = SubagentConfig.load(env),
      autoSaveEnabled = flag("SZORK_AUTO_SAVE"),
      savesDirectory = setting("SZORK_SAVES_DIR").getOrElse("szork-saves"),
      cache = MediaCacheConfig.load(env),
      sessionTtlMinutes = int("SZORK_SESSION_TTL_MINUTES").filter(_ > 0).getOrElse(DefaultSessionTtlMinutes)
    )
  }

  /** The Dungeon Master's context window: SZORK_DM_CONTEXT_MAX_MESSAGES limits each model call to that many
    * conversation messages; otherwise SZORK_DM_CONTEXT_MAX_TOKENS limits it to that many tokens (default
    * [[GameEngine.DefaultContextTokens]]; 0 or `off` sends the whole conversation). The system prompt is never counted
    * or pruned. A value that is not a positive number (other than 0/off) is ignored.
    */
  private[szork] def loadDmContextWindow(env: ConfigReader = EnvLoader): Option[ContextWindowConfig] = {
    def positive(key: String): Option[Int] =
      env.get(key).flatMap(v => v.trim.toIntOption).filter(_ > 0)
    positive("SZORK_DM_CONTEXT_MAX_MESSAGES") match {
      case Some(messages) => Some(ContextWindowConfig(maxMessages = Some(messages)))
      case None =>
        env.get("SZORK_DM_CONTEXT_MAX_TOKENS").map(_.trim.toLowerCase) match {
          case Some("0" | "off") => None
          case _ =>
            val tokens = positive("SZORK_DM_CONTEXT_MAX_TOKENS").getOrElse(GameEngine.DefaultContextTokens)
            Some(ContextWindowConfig(maxTokens = Some(tokens)))
        }
    }
  }

  /** The LLM to use, from SZORK_LLM_PROVIDER or else whichever provider's settings are present.
    *
    * Hand-written rather than llm4s's `Llm4sConfig.defaultProvider()`: that reads `llm4s.providers` from Typesafe
    * config, which sees neither Szork's .env file nor SZORK_LLM_PROVIDER/SZORK_LLM_MODEL, and has no "whichever key is
    * set" fallback.
    */
  private[szork] def loadLLMConfig(env: ConfigReader = EnvLoader): Option[LLMConfig] = {
    val openAIKey = env.get("OPENAI_API_KEY")
    val anthropicKey = env.get("ANTHROPIC_API_KEY")
    val llamaUrl = env.get("LLAMA_BASE_URL")
    val model = env.get("SZORK_LLM_MODEL")

    def openAI = openAIKey.map(key =>
      LLMConfig("openai", key, model.orElse(Some(LLMConfig.DefaultOpenAIModel)), env.get("OPENAI_BASE_URL")))
    def anthropic = anthropicKey.map(key =>
      LLMConfig("anthropic", key, model.orElse(Some(LLMConfig.DefaultAnthropicModel)), env.get("ANTHROPIC_BASE_URL")))
    // Without LLAMA_BASE_URL, LLMConfig.toProviderConfig's default, which ends in /v1
    def llama = LLMConfig("llama", "not-required", model.orElse(Some(LLMConfig.DefaultLlamaModel)), llamaUrl)

    env.get("SZORK_LLM_PROVIDER").map(_.toLowerCase) match {
      case Some("openai") => openAI
      case Some("anthropic") => anthropic
      case Some("llama") | Some("local") => Some(llama)
      case _ => openAI.orElse(anthropic).orElse(llamaUrl.map(_ => llama))
    }
  }

  implicit class ConfigOps(config: SzorkConfig) {

    /** Points the save directory and media cache, which are shared by everything in the process, at this config's. */
    def useStorage(): Unit = {
      StepPersistence.useSaveDirectory(Paths.get(config.savesDirectory))
      MediaCache.configure(config.cache)
    }

    def validate(): SzorkResult[Unit] = {
      val errors = scala.collection.mutable.ListBuffer[String]()

      // Validate ports
      if (config.port < 1 || config.port > 65535) {
        errors += s"Invalid port: ${config.port}"
      }
      if (config.wsPort < 1 || config.wsPort > 65535) {
        errors += s"Invalid WebSocket port: ${config.wsPort}"
      }

      // An image provider that was chosen but cannot be used (its credentials are missing)
      if (config.imageGenerationEnabled && config.imageProvider != ImageProvider.None)
        SceneImages.config(config).left.foreach(reason => errors += s"Scene images unavailable: $reason")

      // Validate LLM configuration
      if (config.llmConfig.isEmpty) {
        errors += "No LLM provider configured. Set one of: OPENAI_API_KEY, ANTHROPIC_API_KEY, or LLAMA_BASE_URL (or SZORK_LLM_PROVIDER)."
      }

      if (errors.isEmpty) {
        Right(())
      } else {
        Left(ConfigurationError(errors.mkString("\n")))
      }
    }

    def logConfiguration(logger: Logger): Unit = {
      logger.info("=== Szork Configuration ===")
      logger.info(s"Server: http://${config.host}:${config.port}, WebSocket ws://${config.host}:${config.wsPort}")
      logger.info(s"LLM Provider: ${config.llmConfig.map(_.provider).getOrElse("Not configured")}")
      val window = config.dmContextWindow.fold("whole conversation") { w =>
        w.maxMessages.fold(s"${w.maxTokens.getOrElse(0)} tokens")(m => s"$m messages")
      }
      logger.info(s"DM context window: $window")
      logger.info(s"DM input guardrail: ${if (config.inputGuardrailEnabled) "Enabled" else "Disabled"}")
      def onOff(on: Boolean) = if (on) "Enabled" else "Disabled"
      logger.info(
        s"DM subagents: NPC dialogue ${onOff(config.subagents.npcDialogue)}, hints ${onOff(config.subagents.hints)}" +
          s" (model: ${config.subagents.model.getOrElse("the Dungeon Master's")})")
      logger.info(s"Auto-save: ${if (config.autoSaveEnabled) "Enabled" else "Disabled"}")
      logger.info(s"Cache: ${if (config.cache.enabled) s"Enabled (${config.cache.directory})" else "Disabled"}")
      logger.info(s"Saves Directory: ${config.savesDirectory}")
      logger.info(s"Session TTL: ${config.sessionTtlMinutes} minutes")
      logger.info("========================")
    }
  }
}
