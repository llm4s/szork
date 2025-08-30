package org.llm4s.szork

import org.llm4s.config.EnvLoader
import org.slf4j.Logger

/**
 * Image generation provider options
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

/**
 * Configuration for the Szork game server
 */
case class SzorkConfig(
  // Server configuration
  port: Int = 8090,
  host: String = "0.0.0.0",
  
  // Image generation configuration
  imageGenerationEnabled: Boolean = true,
  imageProvider: ImageProvider = ImageProvider.None,
  
  // LLM configuration
  llmConfig: Option[LLMConfig] = None,
  
  // Game configuration
  autoSaveEnabled: Boolean = true,
  cacheEnabled: Boolean = true,
  cacheDirectory: String = "szork-cache",
  savesDirectory: String = "szork-saves"
)

case class LLMConfig(
  provider: String,
  apiKey: String,
  model: Option[String] = None,
  baseUrl: Option[String] = None
)

object SzorkConfig {
  lazy val instance: SzorkConfig = load()
  
  private def load(): SzorkConfig = {
    // Load port
    val port = EnvLoader.get("SZORK_PORT")
      .flatMap(p => scala.util.Try(p.toInt).toOption)
      .getOrElse(8090)
    
    // Load host
    val host = EnvLoader.get("SZORK_HOST").getOrElse("0.0.0.0")
    
    // Load image generation settings
    val imageGenerationEnabled = EnvLoader.get("SZORK_IMAGE_GENERATION_ENABLED")
      .map(_.toLowerCase != "false")
      .getOrElse(true)
    
    val imageProviderStr = EnvLoader.get("SZORK_IMAGE_PROVIDER")
    println(s"DEBUG: SZORK_IMAGE_PROVIDER from env: $imageProviderStr")
    val imageProvider = imageProviderStr
      .map(ImageProvider.fromString)
      .getOrElse(ImageProvider.None)
    println(s"DEBUG: Parsed image provider: $imageProvider")
    
    // Load LLM configuration
    val llmConfig = loadLLMConfig()
    
    // Load game settings
    val autoSaveEnabled = EnvLoader.get("SZORK_AUTO_SAVE")
      .map(_.toLowerCase != "false")
      .getOrElse(true)
    
    val cacheEnabled = EnvLoader.get("SZORK_CACHE_ENABLED")
      .map(_.toLowerCase != "false")
      .getOrElse(true)
    
    val cacheDirectory = EnvLoader.get("SZORK_CACHE_DIR").getOrElse("szork-cache")
    val savesDirectory = EnvLoader.get("SZORK_SAVES_DIR").getOrElse("szork-saves")
    
    SzorkConfig(
      port = port,
      host = host,
      imageGenerationEnabled = imageGenerationEnabled,
      imageProvider = imageProvider,
      llmConfig = llmConfig,
      autoSaveEnabled = autoSaveEnabled,
      cacheEnabled = cacheEnabled,
      cacheDirectory = cacheDirectory,
      savesDirectory = savesDirectory
    )
  }
  
  private def loadLLMConfig(): Option[LLMConfig] = {
    // Try to detect LLM provider from environment
    val openAIKey = EnvLoader.get("OPENAI_API_KEY")
    val anthropicKey = EnvLoader.get("ANTHROPIC_API_KEY")
    val llamaUrl = EnvLoader.get("LLAMA_BASE_URL")
    
    // Check for explicit provider setting
    val explicitProvider = EnvLoader.get("SZORK_LLM_PROVIDER").map(_.toLowerCase)
    
    explicitProvider match {
      case Some("openai") =>
        openAIKey.map(key => LLMConfig(
          provider = "openai",
          apiKey = key,
          model = EnvLoader.get("SZORK_LLM_MODEL").orElse(Some("gpt-4")),
          baseUrl = EnvLoader.get("OPENAI_BASE_URL")
        ))
      
      case Some("anthropic") =>
        anthropicKey.map(key => LLMConfig(
          provider = "anthropic",
          apiKey = key,
          model = EnvLoader.get("SZORK_LLM_MODEL").orElse(Some("claude-3-opus-20240229")),
          baseUrl = EnvLoader.get("ANTHROPIC_BASE_URL")
        ))
      
      case Some("llama") | Some("local") =>
        Some(LLMConfig(
          provider = "llama",
          apiKey = "not-required",
          model = EnvLoader.get("SZORK_LLM_MODEL").orElse(Some("llama2")),
          baseUrl = llamaUrl.orElse(Some("http://localhost:11434"))
        ))
      
      case _ =>
        // Auto-detect based on available keys
        if (openAIKey.isDefined) {
          openAIKey.map(key => LLMConfig(
            provider = "openai",
            apiKey = key,
            model = EnvLoader.get("SZORK_LLM_MODEL").orElse(Some("gpt-4")),
            baseUrl = EnvLoader.get("OPENAI_BASE_URL")
          ))
        } else if (anthropicKey.isDefined) {
          anthropicKey.map(key => LLMConfig(
            provider = "anthropic",
            apiKey = key,
            model = EnvLoader.get("SZORK_LLM_MODEL").orElse(Some("claude-3-opus-20240229")),
            baseUrl = EnvLoader.get("ANTHROPIC_BASE_URL")
          ))
        } else if (llamaUrl.isDefined) {
          Some(LLMConfig(
            provider = "llama",
            apiKey = "not-required",
            model = EnvLoader.get("SZORK_LLM_MODEL").orElse(Some("llama2")),
            baseUrl = llamaUrl
          ))
        } else {
          None
        }
    }
  }
  
  implicit class ConfigOps(config: SzorkConfig) {
    def validate(): Either[String, Unit] = {
      val errors = scala.collection.mutable.ListBuffer[String]()
      
      // Validate port
      if (config.port < 1 || config.port > 65535) {
        errors += s"Invalid port: ${config.port}"
      }
      
      // Validate image provider configuration
      if (config.imageGenerationEnabled && config.imageProvider != ImageProvider.None) {
        config.imageProvider match {
          case ImageProvider.HuggingFace | ImageProvider.HuggingFaceSDXL =>
            if (EnvLoader.get("HUGGINGFACE_API_KEY").isEmpty && 
                EnvLoader.get("HF_API_KEY").isEmpty && 
                EnvLoader.get("HUGGINGFACE_TOKEN").isEmpty) {
              errors += "HuggingFace image provider selected but no API key found"
            }
          
          case ImageProvider.OpenAIDalle2 | ImageProvider.OpenAIDalle3 =>
            if (EnvLoader.get("OPENAI_API_KEY").isEmpty) {
              errors += "OpenAI DALL-E provider selected but OPENAI_API_KEY not found"
            }
          
          case _ => // No validation needed
        }
      }
      
      // Validate LLM configuration
      if (config.llmConfig.isEmpty) {
        errors += "No LLM provider configured. Please set OPENAI_API_KEY, ANTHROPIC_API_KEY, or LLAMA_BASE_URL"
      }
      
      if (errors.isEmpty) {
        Right(())
      } else {
        Left(errors.mkString("\n"))
      }
    }
    
    def logConfiguration(logger: Logger): Unit = {
      logger.info("=== Szork Configuration ===")
      logger.info(s"Server: ${config.host}:${config.port}")
      logger.info(s"Image Generation: ${if (config.imageGenerationEnabled) s"Enabled (${ImageProvider.toString(config.imageProvider)})" else "Disabled"}")
      logger.info(s"LLM Provider: ${config.llmConfig.map(_.provider).getOrElse("Not configured")}")
      logger.info(s"Auto-save: ${if (config.autoSaveEnabled) "Enabled" else "Disabled"}")
      logger.info(s"Cache: ${if (config.cacheEnabled) s"Enabled (${config.cacheDirectory})" else "Disabled"}")
      logger.info(s"Saves Directory: ${config.savesDirectory}")
      logger.info("========================")
    }
  }
}