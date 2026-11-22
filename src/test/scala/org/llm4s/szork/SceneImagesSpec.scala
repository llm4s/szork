package org.llm4s.szork

import java.nio.file.{Files, Path}
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import org.llm4s.config.ConfigReader
import org.llm4s.error.LLMError
import org.llm4s.imagegeneration.{
  GeneratedImage,
  HuggingFaceConfig,
  ImageGenerationClient,
  ImageGenerationOptions,
  ImageRateLimitError,
  InstrumentedImageGenerationClient,
  InvalidPromptError,
  OpenAIConfig,
  StableDiffusionConfig
}
import org.llm4s.media.MediaType
import org.llm4s.szork.api.{Capabilities, ImageProvider, SzorkConfig}
import org.llm4s.szork.error.ImageGenerationError
import org.llm4s.szork.game.IdGenerator
import org.llm4s.szork.media.{MediaCache, SceneImageClient, SceneImages}
import org.llm4s.trace.{NoOpTracing, TraceEvent}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._

class SceneImagesSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private final case class FakeReader(values: Map[String, String]) extends ConfigReader {
    override def get(key: String): Option[String] = values.get(key)
  }

  /** An llm4s image client that answers with `answer` and records the prompts and options it was sent. */
  private final class FakeLlm4sImageClient(answer: String => Either[LLMError, GeneratedImage])
      extends ImageGenerationClient {
    val calls = new AtomicInteger()
    val sent = ListBuffer.empty[(String, ImageGenerationOptions)]
    override def generateImage(prompt: String, options: ImageGenerationOptions): Either[LLMError, GeneratedImage] = {
      calls.incrementAndGet()
      sent += ((prompt, options))
      answer(prompt)
    }
    override def generateImages(
      prompt: String,
      count: Int,
      options: ImageGenerationOptions): Either[LLMError, Seq[GeneratedImage]] =
      generateImage(prompt, options).map(Seq(_))
  }

  private def b64(s: String) = Base64.getEncoder.encodeToString(s.getBytes)
  private def image(prompt: String) =
    Right(
      GeneratedImage(
        data = b64(s"png:$prompt"),
        format = MediaType.Png,
        size = SceneImages.Options.size,
        prompt = prompt))

  private val root: Path = Files.createTempDirectory("szork-scene-images-test")
  override def beforeAll(): Unit = MediaCache.useCacheDirectory(root)
  override def afterAll(): Unit =
    Files.walk(root).iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)

  private def providerConfig(provider: ImageProvider, env: (String, String)*) =
    SceneImages.providerConfig(provider, FakeReader(env.toMap))

  // --- configuration and capability ---

  test("DALL-E providers use OPENAI_API_KEY and their model") {
    providerConfig(ImageProvider.OpenAIDalle3, "OPENAI_API_KEY" -> "sk") match {
      case Right(c: OpenAIConfig) => (c.apiKey.reveal, c.model) shouldBe ("sk", "dall-e-3")
      case other => fail(s"unexpected $other")
    }
    providerConfig(ImageProvider.OpenAIDalle2, "OPENAI_API_KEY" -> "sk").map(_.model) shouldBe Right("dall-e-2")
  }

  test("DALL-E without an OpenAI key (absent or empty) is unavailable, with the reason") {
    providerConfig(ImageProvider.OpenAIDalle3).left.map(_.contains("OPENAI_API_KEY")) shouldBe Left(true)
    providerConfig(ImageProvider.OpenAIDalle2, "OPENAI_API_KEY" -> " ").isLeft shouldBe true
  }

  test("HuggingFace takes the first non-empty of its key variables, and the model for the provider") {
    providerConfig(
      ImageProvider.HuggingFace,
      "HUGGINGFACE_API_KEY" -> "",
      "HF_API_KEY" -> "second",
      "HUGGINGFACE_TOKEN" -> "third") match {
      case Right(c: HuggingFaceConfig) =>
        (c.apiKey.reveal, c.model) shouldBe ("second", "runwayml/stable-diffusion-v1-5")
      case other => fail(s"unexpected $other")
    }
    providerConfig(ImageProvider.HuggingFaceSDXL, "HUGGINGFACE_TOKEN" -> "t").map(_.model) shouldBe
      Right("stabilityai/stable-diffusion-xl-base-1.0")
    providerConfig(ImageProvider.HuggingFace, "HUGGINGFACE_API_KEY" -> "", "HF_API_KEY" -> "").isLeft shouldBe true
  }

  test("local Stable Diffusion needs no key and reads its URL and optional key") {
    providerConfig(ImageProvider.LocalStableDiffusion) shouldBe Right(StableDiffusionConfig())
    providerConfig(ImageProvider.LocalStableDiffusion, "SD_URL" -> "http://gpu:7860", "SD_API_KEY" -> "k") match {
      case Right(c: StableDiffusionConfig) =>
        (c.baseUrl, c.apiKey.map(_.reveal)) shouldBe ("http://gpu:7860", Some("k"))
      case other => fail(s"unexpected $other")
    }
  }

  test("no provider, or images switched off, means no image configuration and no image capability") {
    val env = FakeReader(Map("OPENAI_API_KEY" -> "sk"))
    val none = SzorkConfig(imageProvider = ImageProvider.None)
    val off = SzorkConfig(imageGenerationEnabled = false, imageProvider = ImageProvider.OpenAIDalle3)
    val on = SzorkConfig(imageProvider = ImageProvider.OpenAIDalle3)
    SceneImages.config(none, env).isLeft shouldBe true
    SceneImages.config(off, env).isLeft shouldBe true
    Capabilities.from(none, env).image shouldBe false
    Capabilities.from(off, env).image shouldBe false
    Capabilities.from(on, env).image shouldBe true
    Capabilities.from(on, FakeReader(Map.empty)).image shouldBe false
    SceneImages.fromConfig(off, tracing = None, env) shouldBe None
    SceneImages.fromConfig(on, tracing = None, env) should not be empty
  }

  // --- the llm4s client and its tracing ---

  test("the llm4s client reports each image call to the Dungeon Master's tracing") {
    val events = ListBuffer.empty[TraceEvent]
    val tracing = new NoOpTracing {
      override def traceEvent(event: TraceEvent): Either[LLMError, Unit] = { events += event; Right(()) }
    }
    // Nothing listens on port 1, so the call fails at once, and is still traced
    val config = StableDiffusionConfig(baseUrl = "http://127.0.0.1:1")
    val client = SceneImages.llm4sClient(config, Some(tracing)).fold(fail(_), identity)
    client shouldBe a[InstrumentedImageGenerationClient]
    client.generateImage("a cave", SceneImages.Options).isLeft shouldBe true
    events.collect { case e: TraceEvent.ImageGenerationCompleted => (e.provider, e.success) }.toList shouldBe
      List(("stable-diffusion", false))
  }

  // --- the adapter, with the media cache in front ---

  test("generates through the llm4s client with Szork's options and returns the image data") {
    val llm4s = new FakeLlm4sImageClient(image)
    val client = new SceneImageClient(llm4s, "test/model")
    client.generateScene("a cave, pixel art", "pixel", None, None) shouldBe Right(b64("png:a cave, pixel art"))
    llm4s.sent.toList shouldBe List(("a cave, pixel art", SceneImages.Options))
  }

  test("a game's scene is generated once, then served from the media cache") {
    val llm4s = new FakeLlm4sImageClient(image)
    val client = new SceneImageClient(llm4s, "test/model")
    val game = IdGenerator.gameId()
    val first = client.generateScene("a hall", "pixel", Some(game), Some("hall"))
    val second = client.generateScene("a hall", "pixel", Some(game), Some("hall"))
    first shouldBe Right(b64("png:a hall"))
    second shouldBe first
    llm4s.calls.get shouldBe 1
    MediaCache.getCachedImage(game, "hall", "a hall", "pixel", "test/model") shouldBe Some(b64("png:a hall"))
  }

  test("another provider or model does not reuse a cached image") {
    val game = IdGenerator.gameId()
    val first = new FakeLlm4sImageClient(image)
    val second = new FakeLlm4sImageClient(image)
    new SceneImageClient(first, "a/one").generateScene("a hall", "pixel", Some(game), Some("hall"))
    new SceneImageClient(second, "b/two").generateScene("a hall", "pixel", Some(game), Some("hall"))
    second.calls.get shouldBe 1
  }

  test("without a game and location nothing is cached") {
    val llm4s = new FakeLlm4sImageClient(image)
    val client = new SceneImageClient(llm4s, "test/model")
    client.generateScene("a hall", "pixel", None, Some("hall"))
    client.generateScene("a hall", "pixel", None, Some("hall"))
    llm4s.calls.get shouldBe 2
  }

  test("llm4s errors become image errors, retryable only when llm4s says so, and are not cached") {
    val game = IdGenerator.gameId()
    val rateLimited = new SceneImageClient(new FakeLlm4sImageClient(_ => Left(ImageRateLimitError("slow down"))), "t/m")
    val refused = new SceneImageClient(new FakeLlm4sImageClient(_ => Left(InvalidPromptError("no"))), "t/m")
    rateLimited.generateScene("a hall", "pixel", Some(game), Some("hall")) match {
      case Left(e: ImageGenerationError) =>
        e.retryable shouldBe true
        e.message should include("slow down")
      case other => fail(s"unexpected $other")
    }
    refused.generateScene("a hall", "pixel", Some(game), Some("hall")) match {
      case Left(e: ImageGenerationError) => e.retryable shouldBe false
      case other => fail(s"unexpected $other")
    }
    MediaCache.getCachedImage(game, "hall", "a hall", "pixel", "t/m") shouldBe None
  }
}
