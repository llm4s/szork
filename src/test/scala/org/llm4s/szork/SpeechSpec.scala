package org.llm4s.szork

import org.llm4s.config.ConfigReader
import org.llm4s.szork.api.SpeechConfig
import org.llm4s.szork.error.{ConfigurationError, ValidationError}
import org.llm4s.szork.media.{SpeechToText, TextToSpeech}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The speech clients: built through llm4s-speech's provider selector, with no network. */
class SpeechSpec extends AnyFunSuite with Matchers {

  private def reader(values: (String, String)*): ConfigReader = new ConfigReader {
    def get(key: String): Option[String] = values.toMap.get(key)
  }

  test("speech needs OPENAI_API_KEY, and is built through llm4s-speech's selector when it is there") {
    SpeechToText.create(SpeechConfig(), reader()).left.toOption.get shouldBe a[ConfigurationError]
    TextToSpeech.create(SpeechConfig(), reader()).left.toOption.get shouldBe a[ConfigurationError]
    SpeechToText.create(SpeechConfig(), reader("OPENAI_API_KEY" -> "sk-test")).isRight shouldBe true
    TextToSpeech.create(SpeechConfig(), reader("OPENAI_API_KEY" -> "sk-test")).isRight shouldBe true
  }

  test("a recording whose file name would not make a plain temporary file is refused, not thrown at") {
    val stt = SpeechToText.create(SpeechConfig(), reader("OPENAI_API_KEY" -> "sk-test")).toOption.get
    Seq("clip.we/bm", "clip.a/../../etc", "clip.we\\bm", "clip.").foreach { name =>
      withClue(name)(stt.transcribeBytes(Array[Byte](1, 2, 3), name).left.toOption.get shouldBe a[ValidationError])
    }
    stt.transcribeBytes(Array.emptyByteArray, "clip.webm").left.toOption.get shouldBe a[ValidationError]
  }
}
