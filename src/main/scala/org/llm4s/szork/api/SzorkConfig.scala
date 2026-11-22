package org.llm4s.szork.api

import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._
import org.llm4s.agent.ContextWindowConfig
import org.llm4s.config.{ConfigReader, EnvLoader}
import org.llm4s.szork.game.GameEngine
import org.llm4s.szork.media.SceneImages
import org.llm4s.security.Secret
import org.slf4j.Logger

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

/** Configuration for the Szork game server
  */
case class SzorkConfig(
  // Server configuration
  port: Int = 8090,
  host: String = "127.0.0.1",

  // Image generation configuration
  imageGenerationEnabled: Boolean = true,
  imageProvider: ImageProvider = ImageProvider.None,

  // Audio/speech/media feature flags
  ttsEnabled: Boolean = true,
  sttEnabled: Boolean = true,
  musicEnabled: Boolean = true,

  // LLM configuration
  llmConfig: Option[LLMConfig] = None,
  // How much of the game's conversation each Dungeon Master model call is sent (None: all of it)
  dmContextWindow: Option[ContextWindowConfig] = Some(GameEngine.DefaultContextWindow),

  // Game configuration
  autoSaveEnabled: Boolean = true,
  cacheEnabled: Boolean = true,
  cacheDirectory: String = "szork-cache",
  savesDirectory: String = "szork-saves",

  // Browser origins allowed to open the WebSocket
  allowedOrigins: Set[String] = Set("http://localhost:3090", "http://127.0.0.1:3090")
)

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
  lazy val instance: SzorkConfig = load()

  /** Get an LLM client using the current configuration */
  def getLLMClient(): Either[String, org.llm4s.llmconnect.LLMClient] =
    instance.llmConfig match {
      case Some(config) =>
        try
          org.llm4s.model.ModelRegistryService.default().left.map(_.message).flatMap { registry =>
            given org.llm4s.model.ModelRegistryService = registry
            org.llm4s.llmconnect.LLMConnect.getClient(config.toProviderConfig).left.map(_.message)
          }
        catch {
          case e: Exception => Left(s"Failed to create LLM client: ${e.getMessage}")
        }
      case None =>
        Left("No LLM configuration found")
    }

  private def load(): SzorkConfig = {
    // Load port
    val port = EnvLoader
      .get("SZORK_PORT")
      .flatMap(p => scala.util.Try(p.toInt).toOption)
      .getOrElse(8090)

    // Load host
    val host = EnvLoader.get("SZORK_HOST").getOrElse("127.0.0.1")
    // Browser origins allowed to open the WebSocket (comma-separated); defaults to the dev frontend and this server
    val allowedOrigins = EnvLoader
      .get("SZORK_ALLOWED_ORIGINS")
      .map(_.split(",").map(_.trim).filter(_.nonEmpty).toSet)
      .getOrElse(Set(3090, port).flatMap(p => Set(s"http://localhost:$p", s"http://127.0.0.1:$p")))

    // Load image generation settings
    val imageGenerationEnabled = EnvLoader
      .get("SZORK_IMAGE_GENERATION_ENABLED")
      .map(_.toLowerCase != "false")
      .getOrElse(true)

    val imageProviderStr = EnvLoader.get("SZORK_IMAGE_PROVIDER")
    val imageProvider = imageProviderStr
      .map(ImageProvider.fromString)
      .getOrElse(ImageProvider.None)

    // Load LLM configuration
    val llmConfig = loadLLMConfig()
    val dmContextWindow = loadDmContextWindow()

    // Media feature flags
    val ttsEnabled = EnvLoader.get("SZORK_TTS_ENABLED").map(_.toLowerCase != "false").getOrElse(true)
    val sttEnabled = EnvLoader.get("SZORK_STT_ENABLED").map(_.toLowerCase != "false").getOrElse(true)
    val musicEnabled = EnvLoader.get("SZORK_MUSIC_ENABLED").map(_.toLowerCase != "false").getOrElse(true)

    // Load game settings
    val autoSaveEnabled = EnvLoader
      .get("SZORK_AUTO_SAVE")
      .map(_.toLowerCase != "false")
      .getOrElse(true)

    val cacheEnabled = EnvLoader
      .get("SZORK_CACHE_ENABLED")
      .map(_.toLowerCase != "false")
      .getOrElse(true)

    val cacheDirectory = EnvLoader.get("SZORK_CACHE_DIR").getOrElse("szork-cache")
    val savesDirectory = EnvLoader.get("SZORK_SAVES_DIR").getOrElse("szork-saves")

    SzorkConfig(
      port = port,
      host = host,
      allowedOrigins = allowedOrigins,
      imageGenerationEnabled = imageGenerationEnabled,
      imageProvider = imageProvider,
      ttsEnabled = ttsEnabled,
      sttEnabled = sttEnabled,
      musicEnabled = musicEnabled,
      llmConfig = llmConfig,
      dmContextWindow = dmContextWindow,
      autoSaveEnabled = autoSaveEnabled,
      cacheEnabled = cacheEnabled,
      cacheDirectory = cacheDirectory,
      savesDirectory = savesDirectory
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

  /** The LLM to use, from SZORK_LLM_PROVIDER or else whichever provider's settings are present. */
  private[szork] def loadLLMConfig(env: ConfigReader = EnvLoader): Option[LLMConfig] = {
    // Try to detect LLM provider from environment
    val openAIKey = env.get("OPENAI_API_KEY")
    val anthropicKey = env.get("ANTHROPIC_API_KEY")
    val llamaUrl = env.get("LLAMA_BASE_URL")

    // Check for explicit provider setting
    val explicitProvider = env.get("SZORK_LLM_PROVIDER").map(_.toLowerCase)

    explicitProvider match {
      case Some("openai") =>
        openAIKey.map(key =>
          LLMConfig(
            provider = "openai",
            apiKey = key,
            model = env.get("SZORK_LLM_MODEL").orElse(Some(LLMConfig.DefaultOpenAIModel)),
            baseUrl = env.get("OPENAI_BASE_URL")
          ))

      case Some("anthropic") =>
        anthropicKey.map(key =>
          LLMConfig(
            provider = "anthropic",
            apiKey = key,
            model = env.get("SZORK_LLM_MODEL").orElse(Some(LLMConfig.DefaultAnthropicModel)),
            baseUrl = env.get("ANTHROPIC_BASE_URL")
          ))

      case Some("llama") | Some("local") =>
        Some(
          LLMConfig(
            provider = "llama",
            apiKey = "not-required",
            model = env.get("SZORK_LLM_MODEL").orElse(Some(LLMConfig.DefaultLlamaModel)),
            baseUrl = llamaUrl // unset: LLMConfig.toProviderConfig's default, which ends in /v1
          ))

      case _ =>
        // Auto-detect based on available keys
        if (openAIKey.isDefined) {
          openAIKey.map(key =>
            LLMConfig(
              provider = "openai",
              apiKey = key,
              model = env.get("SZORK_LLM_MODEL").orElse(Some(LLMConfig.DefaultOpenAIModel)),
              baseUrl = env.get("OPENAI_BASE_URL")
            ))
        } else if (anthropicKey.isDefined) {
          anthropicKey.map(key =>
            LLMConfig(
              provider = "anthropic",
              apiKey = key,
              model = env.get("SZORK_LLM_MODEL").orElse(Some(LLMConfig.DefaultAnthropicModel)),
              baseUrl = env.get("ANTHROPIC_BASE_URL")
            ))
        } else if (llamaUrl.isDefined) {
          Some(
            LLMConfig(
              provider = "llama",
              apiKey = "not-required",
              model = env.get("SZORK_LLM_MODEL").orElse(Some(LLMConfig.DefaultLlamaModel)),
              baseUrl = llamaUrl
            ))
        } else {
          None
        }
    }
  }

  implicit class ConfigOps(config: SzorkConfig) {
    def validate(): SzorkResult[Unit] = {
      val errors = scala.collection.mutable.ListBuffer[String]()

      // Validate port
      if (config.port < 1 || config.port > 65535) {
        errors += s"Invalid port: ${config.port}"
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
      logger.info(s"Server: ${config.host}:${config.port}")
      logger.info(s"Image Generation: ${
          if (config.imageGenerationEnabled) s"Enabled (${ImageProvider.toString(config.imageProvider)})"
          else "Disabled"
        }")
      logger.info(s"LLM Provider: ${config.llmConfig.map(_.provider).getOrElse("Not configured")}")
      val window = config.dmContextWindow.fold("whole conversation") { w =>
        w.maxMessages.fold(s"${w.maxTokens.getOrElse(0)} tokens")(m => s"$m messages")
      }
      logger.info(s"DM context window: $window")
      logger.info(s"TTS: ${if (config.ttsEnabled) "Enabled" else "Disabled"}")
      logger.info(s"STT: ${if (config.sttEnabled) "Enabled" else "Disabled"}")
      logger.info(s"Music: ${if (config.musicEnabled) "Enabled" else "Disabled"}")
      logger.info(s"Auto-save: ${if (config.autoSaveEnabled) "Enabled" else "Disabled"}")
      logger.info(s"Cache: ${if (config.cacheEnabled) s"Enabled (${config.cacheDirectory})" else "Disabled"}")
      logger.info(s"Saves Directory: ${config.savesDirectory}")
      logger.info("========================")
    }
  }
}
