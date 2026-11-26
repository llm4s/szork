package org.llm4s.szork

import org.llm4s.szork.config.ConfigReader
import org.llm4s.szork.api.SpeechConfig
import org.llm4s.szork.error.{ConfigurationError, ValidationError}
import org.llm4s.szork.media.{SpeechToText, TextToSpeech}
import org.llm4s.speech.AudioInput
import org.llm4s.speech.stt.{STTOptions, Transcription, SpeechToText => SttClient}
import org.llm4s.types.Result
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

  /** A transcriber that records the options each recording came with. */
  private class RecordingTranscriber extends SttClient {
    var asked: List[STTOptions] = Nil
    val name = "recording"
    val supportedFormats: List[String] = List("audio/webm")
    def transcribe(input: AudioInput, options: STTOptions): Result[Transcription] = {
      asked = asked :+ options
      Right(Transcription(" go north ", options.language))
    }
  }

  test("the transcriber is told the language, so a short recording is not heard as another one") {
    val english = new RecordingTranscriber
    new SpeechToText(english, SpeechConfig().sttLanguage).transcribeBytes(Array[Byte](1, 2, 3)) shouldBe Right(
      "go north")
    english.asked.map(_.language) shouldBe List(Some("en"))

    val detecting = new RecordingTranscriber
    new SpeechToText(detecting, None).transcribeBytes(Array[Byte](1, 2, 3))
    detecting.asked.map(_.language) shouldBe List(None)
  }

  test("SZORK_STT_LANGUAGE is a language tag or auto; anything else is a problem that stops the server") {
    SpeechConfig.load(reader()).sttLanguage shouldBe Some("en")
    SpeechConfig.load(reader("SZORK_STT_LANGUAGE" -> " fr-FR ")).sttLanguage shouldBe Some("fr-FR")
    SpeechConfig.load(reader("SZORK_STT_LANGUAGE" -> "Auto")).sttLanguage shouldBe None
    SpeechConfig.languageProblem(reader()) shouldBe None
    SpeechConfig.languageProblem(reader("SZORK_STT_LANGUAGE" -> "fr-FR")) shouldBe None
    SpeechConfig.languageProblem(reader("SZORK_STT_LANGUAGE" -> "auto")) shouldBe None
    SpeechConfig.languageProblem(reader("SZORK_STT_LANGUAGE" -> "english")).get should include("SZORK_STT_LANGUAGE")
  }
}
