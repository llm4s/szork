package org.llm4s.szork

import org.llm4s.config.ConfigReader
import org.llm4s.szork.api.ImageProvider
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ImageProviderCredsSpec extends AnyFunSuite with Matchers {

  /** A deterministic in-memory ConfigReader backed by a Map. */
  private final case class FakeReader(values: Map[String, String]) extends ConfigReader {
    override def get(key: String): Option[String] = values.get(key)
  }

  private val emptyReader = FakeReader(Map.empty)

  test("huggingFaceKey returns the first non-empty key in priority order") {
    val reader = FakeReader(
      Map(
        "HUGGINGFACE_API_KEY" -> "primary",
        "HF_API_KEY" -> "secondary",
        "HUGGINGFACE_TOKEN" -> "tertiary"
      ))
    ImageProvider.huggingFaceKey(reader) shouldBe Some("primary")
  }

  test("huggingFaceKey skips an empty HUGGINGFACE_API_KEY and falls back to HF_API_KEY") {
    val reader = FakeReader(
      Map(
        "HUGGINGFACE_API_KEY" -> "",
        "HF_API_KEY" -> "secondary",
        "HUGGINGFACE_TOKEN" -> "tertiary"
      ))
    ImageProvider.huggingFaceKey(reader) shouldBe Some("secondary")
  }

  test("huggingFaceKey falls back to HUGGINGFACE_TOKEN when the first two are empty/absent") {
    val reader = FakeReader(
      Map(
        "HUGGINGFACE_API_KEY" -> "",
        "HUGGINGFACE_TOKEN" -> "tertiary"
      ))
    ImageProvider.huggingFaceKey(reader) shouldBe Some("tertiary")
  }

  test("huggingFaceKey returns None when all keys are absent") {
    ImageProvider.huggingFaceKey(emptyReader) shouldBe None
  }

  test("huggingFaceKey returns None when all keys are empty strings") {
    val reader = FakeReader(
      Map(
        "HUGGINGFACE_API_KEY" -> "",
        "HF_API_KEY" -> "",
        "HUGGINGFACE_TOKEN" -> ""
      ))
    ImageProvider.huggingFaceKey(reader) shouldBe None
  }

  test("imageCredsAvailable is true for HuggingFace providers when any HF key is present") {
    for (provider <- Seq(ImageProvider.HuggingFace, ImageProvider.HuggingFaceSDXL))
      ImageProvider.imageCredsAvailable(provider, FakeReader(Map("HF_API_KEY" -> "k"))) shouldBe true
  }

  test("imageCredsAvailable is false for HuggingFace providers when all HF keys are absent") {
    for (provider <- Seq(ImageProvider.HuggingFace, ImageProvider.HuggingFaceSDXL))
      ImageProvider.imageCredsAvailable(provider, emptyReader) shouldBe false
  }

  test("imageCredsAvailable is false for HuggingFace providers when all HF keys are empty strings") {
    val reader = FakeReader(
      Map(
        "HUGGINGFACE_API_KEY" -> "",
        "HF_API_KEY" -> "",
        "HUGGINGFACE_TOKEN" -> ""
      ))
    for (provider <- Seq(ImageProvider.HuggingFace, ImageProvider.HuggingFaceSDXL))
      ImageProvider.imageCredsAvailable(provider, reader) shouldBe false
  }

  test("imageCredsAvailable for OpenAI DALL-E providers is true iff OPENAI_API_KEY is non-empty") {
    for (provider <- Seq(ImageProvider.OpenAIDalle2, ImageProvider.OpenAIDalle3)) {
      ImageProvider.imageCredsAvailable(provider, FakeReader(Map("OPENAI_API_KEY" -> "k"))) shouldBe true
      ImageProvider.imageCredsAvailable(provider, FakeReader(Map("OPENAI_API_KEY" -> ""))) shouldBe false
      ImageProvider.imageCredsAvailable(provider, emptyReader) shouldBe false
    }
  }

  test("imageCredsAvailable for LocalStableDiffusion is always true") {
    ImageProvider.imageCredsAvailable(ImageProvider.LocalStableDiffusion, emptyReader) shouldBe true
  }

  test("imageCredsAvailable for None is always false") {
    ImageProvider.imageCredsAvailable(ImageProvider.None, FakeReader(Map("OPENAI_API_KEY" -> "k"))) shouldBe false
  }
}
