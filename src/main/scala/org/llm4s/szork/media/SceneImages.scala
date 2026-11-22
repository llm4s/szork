package org.llm4s.szork.media

import org.llm4s.config.{ConfigReader, EnvLoader}
import org.llm4s.error.LLMError
import org.llm4s.imagegeneration.{
  HuggingFaceConfig,
  ImageGeneration,
  ImageGenerationClient,
  ImageGenerationConfig,
  ImageGenerationOptions,
  ImageSize,
  OpenAIConfig,
  StableDiffusionConfig
}
import org.llm4s.media.MediaType
import org.llm4s.metrics.MetricsCollector
import org.llm4s.security.Secret
import org.llm4s.szork.api.{ImageProvider, SzorkConfig}
import org.llm4s.szork.error.ImageGenerationError
import org.llm4s.szork.error.ErrorHandling.SzorkResult
import org.llm4s.szork.spi.ImageClient
import org.llm4s.trace.{NoOpTracing, Tracing}
import org.slf4j.LoggerFactory

/** Scene images through the llm4s image client.
  *
  * The server builds one llm4s [[ImageGenerationClient]] from the [[ImageGenerationConfig]] that `SZORK_IMAGE_PROVIDER`
  * selects, instrumented with the Dungeon Master's `Tracing` so image calls are traced alongside its turns, and wraps
  * it in a [[SceneImageClient]], which puts the [[MediaCache]] in front of it.
  */
object SceneImages {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)

  /** What Szork asks for: 512x512 PNG, good quality at a reasonable size. */
  val Options: ImageGenerationOptions = ImageGenerationOptions(size = ImageSize.Square512, format = MediaType.Png)

  /** The llm4s configuration for the server's image provider, or why scene images are unavailable: switched off, no
    * provider chosen, or the provider's credentials missing.
    */
  def config(szork: SzorkConfig, env: ConfigReader = EnvLoader): Either[String, ImageGenerationConfig] =
    if (!szork.imageGenerationEnabled) Left("disabled by SZORK_IMAGE_GENERATION_ENABLED")
    else providerConfig(szork.imageProvider, env)

  /** The llm4s configuration for `provider`, with its credentials and settings from `env`. */
  def providerConfig(provider: ImageProvider, env: ConfigReader = EnvLoader): Either[String, ImageGenerationConfig] = {
    def first(keys: String*): Option[String] =
      keys.iterator.flatMap(key => env.get(key).map(_.trim).filter(_.nonEmpty)).nextOption()
    def openAI(model: String) =
      first("OPENAI_API_KEY")
        .map(key => OpenAIConfig(apiKey = Secret(key), model = model))
        .toRight(s"${ImageProvider.toString(provider)} needs OPENAI_API_KEY")
    def huggingFace(model: String) =
      first("HUGGINGFACE_API_KEY", "HF_API_KEY", "HUGGINGFACE_TOKEN")
        .map(key => HuggingFaceConfig(apiKey = Secret(key), model = model))
        .toRight(s"${ImageProvider.toString(provider)} needs HUGGINGFACE_API_KEY (or HF_API_KEY or HUGGINGFACE_TOKEN)")

    provider match {
      case ImageProvider.None => Left("no provider set (SZORK_IMAGE_PROVIDER)")
      case ImageProvider.OpenAIDalle3 => openAI("dall-e-3")
      case ImageProvider.OpenAIDalle2 => openAI("dall-e-2")
      case ImageProvider.HuggingFace => huggingFace("runwayml/stable-diffusion-v1-5")
      case ImageProvider.HuggingFaceSDXL => huggingFace("stabilityai/stable-diffusion-xl-base-1.0")
      case ImageProvider.LocalStableDiffusion =>
        Right(
          StableDiffusionConfig(
            baseUrl = first("STABLE_DIFFUSION_URL", "SD_URL").getOrElse("http://localhost:7860"),
            apiKey = first("STABLE_DIFFUSION_API_KEY", "SD_API_KEY").map(Secret(_))
          ))
    }
  }

  /** The llm4s client for `config`, reporting each call (model, size, duration, cost) to `tracing` if there is one. */
  def llm4sClient(config: ImageGenerationConfig, tracing: Option[Tracing]): Either[String, ImageGenerationClient] =
    ImageGeneration.client(config, MetricsCollector.noop, tracing.getOrElse(new NoOpTracing)).left.map(_.message)

  /** The server's scene image client, or None when scene images are unavailable (see [[config]]). */
  def fromConfig(szork: SzorkConfig, tracing: Option[Tracing], env: ConfigReader = EnvLoader): Option[ImageClient] =
    config(szork, env).toOption.flatMap { c =>
      llm4sClient(c, tracing) match {
        case Right(client) => Some(new SceneImageClient(client, cacheTag(c)))
        case Left(error) =>
          logger.warn(s"Scene images unavailable: the llm4s image client could not be created: $error")
          None
      }
    }

  /** Names the provider and model in cache keys, so changing either one generates new images. */
  def cacheTag(config: ImageGenerationConfig): String = s"${config.provider}/${config.model}"
}

/** Scene images from an llm4s image client, cached per game and location.
  *
  * @param client
  *   the llm4s image client (instrumented by [[SceneImages.llm4sClient]])
  * @param cacheTag
  *   the provider and model, part of each cache key
  */
final class SceneImageClient(client: ImageGenerationClient, cacheTag: String) extends ImageClient {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)

  /** The image for `prompt`, which already carries the art style; `style` (the art style id) only keys the cache. With
    * a game and location, the image is looked up in, and stored to, the [[MediaCache]].
    */
  override def generateScene(
    prompt: String,
    style: String,
    gameId: Option[String],
    locationId: Option[String]): SzorkResult[String] = {
    val place = gameId.zip(locationId)
    place.flatMap { case (game, location) =>
      MediaCache.getCachedImage(game, location, prompt, style, cacheTag)
    } match {
      case Some(cached) =>
        logger.info(s"Using cached image for ${place.mkString}")
        Right(cached)
      case None =>
        val start = System.currentTimeMillis()
        client.generateImage(prompt, SceneImages.Options) match {
          case Right(image) =>
            logger.info(
              s"Image generated in ${System.currentTimeMillis() - start}ms (${image.data.length} chars base64)")
            place.foreach { case (game, location) =>
              MediaCache.cacheImage(game, location, prompt, style, image.data, cacheTag)
            }
            Right(image.data)
          case Left(error) =>
            logger.error(s"Image generation failed after ${System.currentTimeMillis() - start}ms: ${error.message}")
            Left(
              ImageGenerationError(
                s"Image generation error: ${error.message}",
                retryable = LLMError.isRecoverable(error)))
        }
    }
  }
}
