package org.llm4s.szork.media

import org.slf4j.LoggerFactory
import org.llm4s.config.EnvLoader
import org.llm4s.imagegeneration
import org.llm4s.imagegeneration._
import org.llm4s.szork.api.{SzorkConfig, ImageProvider}
import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._

class ImageGeneration {
  private implicit val logger = LoggerFactory.getLogger(getClass.getSimpleName)
  private val config = SzorkConfig.instance

  // Configure image generation provider based on configuration
  private val (imageClientOpt, providerName) =
    if (!config.imageGenerationEnabled) {
      logger.info("Image generation is disabled")
      (None, "Disabled")
    } else {
      config.imageProvider match {
        case ImageProvider.None =>
          logger.info("Image generation provider set to None")
          (None, "None")

        case ImageProvider.HuggingFace | ImageProvider.HuggingFaceSDXL =>
          val hfKey = EnvLoader
            .get("HUGGINGFACE_API_KEY")
            .orElse(EnvLoader.get("HF_API_KEY"))
            .orElse(EnvLoader.get("HUGGINGFACE_TOKEN"))
            .getOrElse(
              throw new IllegalStateException(
                s"Image provider set to ${ImageProvider.toString(config.imageProvider)} but no HuggingFace API key found. " +
                  "Please set HUGGINGFACE_API_KEY, HF_API_KEY, or HUGGINGFACE_TOKEN"
              )
            )
          val model = config.imageProvider match {
            case ImageProvider.HuggingFaceSDXL => "stabilityai/stable-diffusion-xl-base-1.0"
            case _ => "runwayml/stable-diffusion-v1-5"
          }
          logger.info(s"Using HuggingFace for image generation with model: $model")
          (Some(imagegeneration.ImageGeneration.huggingFaceClient(hfKey, model)), s"HuggingFace ($model)")

        case ImageProvider.OpenAIDalle3 =>
          val openAIKey = EnvLoader
            .get("OPENAI_API_KEY")
            .getOrElse(
              throw new IllegalStateException("Image provider set to OpenAI DALL-E 3 but OPENAI_API_KEY not found")
            )
          logger.info("Using OpenAI DALL-E 3 for image generation")
          (Some(imagegeneration.ImageGeneration.openAIClient(openAIKey, "dall-e-3")), "OpenAI DALL-E 3")

        case ImageProvider.OpenAIDalle2 =>
          val openAIKey = EnvLoader
            .get("OPENAI_API_KEY")
            .getOrElse(
              throw new IllegalStateException("Image provider set to OpenAI DALL-E 2 but OPENAI_API_KEY not found")
            )
          logger.info("Using OpenAI DALL-E 2 for image generation")
          (Some(imagegeneration.ImageGeneration.openAIClient(openAIKey, "dall-e-2")), "OpenAI DALL-E 2")

        case ImageProvider.LocalStableDiffusion =>
          val baseUrl = EnvLoader
            .get("STABLE_DIFFUSION_URL")
            .orElse(EnvLoader.get("SD_URL"))
            .getOrElse("http://localhost:7860")
          val apiKey = EnvLoader
            .get("STABLE_DIFFUSION_API_KEY")
            .orElse(EnvLoader.get("SD_API_KEY"))
          logger.info(s"Using Local Stable Diffusion for image generation at: $baseUrl")
          (
            Some(imagegeneration.ImageGeneration.stableDiffusionClient(baseUrl, apiKey)),
            s"Local Stable Diffusion ($baseUrl)")
      }
    }

  def generateScene(prompt: String, style: String = ""): SzorkResult[String] =
    generateSceneWithCache(prompt, style, None, None)

  def generateSceneWithCache(
    prompt: String,
    style: String = "",
    gameId: Option[String] = None,
    locationId: Option[String] = None): SzorkResult[String] =
    // Check if image generation is enabled
    imageClientOpt match {
      case None =>
        logger.debug(s"Image generation disabled, returning empty image")
        return Right("") // Return empty string when disabled
      case Some(imageClient) =>
        // Check cache first if gameId and locationId are provided
        (gameId, locationId) match {
          case (Some(gId), Some(lId)) =>
            MediaCache.getCachedImage(gId, lId, prompt, style, providerName) match {
              case Some(cachedImage) =>
                logger.info(s"Using cached image for game=$gId, location=$lId (0ms - from cache)")
                return Right(cachedImage)
              case None =>
                logger.info(s"No cached image found for game=$gId, location=$lId - generating new image")
            }
          case _ =>
            logger.info(s"No cache info provided - generating image directly")
        }

        val imageStartTime = System.currentTimeMillis()
        logger.info(s"Starting image generation using $providerName for prompt: ${prompt.take(100)}...")

        // Use prompt as-is if no additional style specified
        val fullPrompt = if (style.isEmpty) {
          prompt
        } else {
          s"$prompt, $style"
        }

        // Configure options for image generation
        val options = ImageGenerationOptions(
          size = ImageSize.Square512, // 512x512 for good quality with reasonable file size
          format = ImageFormat.PNG
        )

        // Use LLM4S image generation
        imageClient.generateImage(fullPrompt, options) match {
          case Right(generatedImage) =>
            val base64Image = generatedImage.data
            val imageGenerationTime = System.currentTimeMillis() - imageStartTime
            logger.info(s"Image generation completed in ${imageGenerationTime}ms (${base64Image.length} bytes base64)")

            // Cache the generated image if gameId and locationId are provided
            (gameId, locationId) match {
              case (Some(gId), Some(lId)) =>
                MediaCache.cacheImage(gId, lId, prompt, style, base64Image, providerName)
                logger.info(s"Cached generated image for game=$gId, location=$lId")
              case _ =>
                logger.debug("No cache info provided - skipping image caching")
            }

            Right(base64Image)

          case Left(error) =>
            val imageGenerationTime = System.currentTimeMillis() - imageStartTime
            val errorMessage = s"Image generation error: ${error.message}"
            logger.error(s"$errorMessage (failed after ${imageGenerationTime}ms)")
            val szorkError = ImageGenerationError(errorMessage, retryable = true)
            Left(szorkError)
        }
    }

}

object ImageGeneration {
  def apply(): ImageGeneration = new ImageGeneration()

  // Style presets for consistent art direction
  val STYLE_FANTASY = "fantasy art, digital painting, concept art"
  val STYLE_DUNGEON = "dark fantasy, dungeon, atmospheric, ominous"
  val STYLE_FOREST = "enchanted forest, mystical, natural lighting"
  val STYLE_TOWN = "medieval town, bustling, warm colors"
  val STYLE_COMBAT = "dynamic action scene, dramatic lighting"
}
