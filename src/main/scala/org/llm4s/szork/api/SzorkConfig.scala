package org.llm4s.szork.api

import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._
import org.llm4s.config.{ConfigReader, EnvLoader}
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

  /** Resolve the HuggingFace API key from the supported env keys, in priority order. */
  def huggingFaceKey(reader: ConfigReader = EnvLoader): Option[String] =
    reader
      .get("HUGGINGFACE_API_KEY")
      .filter(_.nonEmpty)
      .orElse(reader.get("HF_API_KEY").filter(_.nonEmpty))
      .orElse(reader.get("HUGGINGFACE_TOKEN").filter(_.nonEmpty))

  /** Whether the credentials required by the given image provider are available. */
  def imageCredsAvailable(provider: ImageProvider, reader: ConfigReader = EnvLoader): Boolean =
    provider match {
      case HuggingFace | HuggingFaceSDXL => huggingFaceKey(reader).exists(_.nonEmpty)
      case OpenAIDalle2 | OpenAIDalle3 => reader.get("OPENAI_API_KEY").exists(_.nonEmpty)
      case LocalStableDiffusion => true
      case None => false
    }
}

/** Configuration for the Szork game server
  */
case class SzorkConfig(
  // Server configuration
  port: Int = 8090,
  host: String = "0.0.0.0",

  // Image generation configuration
  imageGenerationEnabled: Boolean = true,
  imageProvider: ImageProvider = ImageProvider.None,

  // Audio/speech/media feature flags
  ttsEnabled: Boolean = true,
  sttEnabled: Boolean = true,
  musicEnabled: Boolean = true,

  // LLM configuration
  llmConfig: Option[LLMConfig] = None,

  // Game configuration
  autoSaveEnabled: Boolean = true,
  cacheEnabled: Boolean = true,
  cacheDirectory: String = "szork-cache",
  savesDirectory: String = "szork-saves"
)

/** The LLM provider szork uses: `provider` names a section of `llm4s.providers` in application.conf (`openai`,
  * `anthropic` or `ollama`), which holds the model, base URL and API key binding.
  */
case class LLMConfig(provider: String)

object SzorkConfig {
  lazy val instance: SzorkConfig = load()

  /** Get an LLM client for the selected `llm4s.providers.<name>` section of application.conf */
  def getLLMClient(): Either[String, org.llm4s.llmconnect.LLMClient] =
    instance.llmConfig match {
      case Some(config) =>
        EnvLoader.exportToSystemProperties()
        for {
          providerConfig <- org.llm4s.config.Llm4sConfig.provider(config.provider).left.map(_.message)
          registry <- org.llm4s.config.Llm4sConfig.modelRegistryService().left.map(_.message)
          client <- {
            given org.llm4s.model.ModelRegistryService = registry
            org.llm4s.llmconnect.LLMConnect.getClient(providerConfig).left.map(_.message)
          }
        } yield client
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
    val host = EnvLoader.get("SZORK_HOST").getOrElse("0.0.0.0")

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
      imageGenerationEnabled = imageGenerationEnabled,
      imageProvider = imageProvider,
      ttsEnabled = ttsEnabled,
      sttEnabled = sttEnabled,
      musicEnabled = musicEnabled,
      llmConfig = llmConfig,
      autoSaveEnabled = autoSaveEnabled,
      cacheEnabled = cacheEnabled,
      cacheDirectory = cacheDirectory,
      savesDirectory = savesDirectory
    )
  }

  /** Choose the provider section: SZORK_LLM_PROVIDER if set, else the first provider whose credentials are present. */
  private[api] def loadLLMConfig(reader: ConfigReader = EnvLoader): Option[LLMConfig] = {
    def present(key: String) = reader.get(key).exists(_.nonEmpty)
    reader.get("SZORK_LLM_PROVIDER").map(_.trim.toLowerCase).filter(_.nonEmpty) match {
      case Some("openai") => Some(LLMConfig("openai")).filter(_ => present("OPENAI_API_KEY"))
      case Some("anthropic") => Some(LLMConfig("anthropic")).filter(_ => present("ANTHROPIC_API_KEY"))
      case Some("ollama" | "llama" | "local") => Some(LLMConfig("ollama"))
      case Some(_) => None
      case None =>
        if (present("OPENAI_API_KEY")) Some(LLMConfig("openai"))
        else if (present("ANTHROPIC_API_KEY")) Some(LLMConfig("anthropic"))
        else if (present("LLAMA_BASE_URL")) Some(LLMConfig("ollama"))
        else None
    }
  }

  implicit class ConfigOps(config: SzorkConfig) {
    def validate(): SzorkResult[Unit] = {
      val errors = scala.collection.mutable.ListBuffer[String]()

      // Validate port
      if (config.port < 1 || config.port > 65535) {
        errors += s"Invalid port: ${config.port}"
      }

      // Validate image provider configuration
      if (config.imageGenerationEnabled && config.imageProvider != ImageProvider.None
        && !ImageProvider.imageCredsAvailable(config.imageProvider)) {
        config.imageProvider match {
          case ImageProvider.HuggingFace | ImageProvider.HuggingFaceSDXL =>
            errors += "HuggingFace image provider selected but no API key found"
          case ImageProvider.OpenAIDalle2 | ImageProvider.OpenAIDalle3 =>
            errors += "OpenAI DALL-E provider selected but OPENAI_API_KEY not found"
          case _ => ()
        }
      }

      // Validate LLM configuration
      if (config.llmConfig.isEmpty) {
        errors += "No LLM provider configured. Set one of: OPENAI_API_KEY, ANTHROPIC_API_KEY, or LLAMA_BASE_URL, or SZORK_LLM_PROVIDER=openai|anthropic|ollama (with its key)."
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
