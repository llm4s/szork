package org.llm4s.szork

import org.llm4s.szork.api.AgentTracing
import org.llm4s.szork.config.{ConfigReader, EnvLoader}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class EnvLoaderSpec extends AnyFunSuite with Matchers {

  private final case class MapReader(values: Map[String, String]) extends ConfigReader {
    def get(key: String): Option[String] = values.get(key)
  }

  test("a ConfigReader's getOrElse falls back only when the key is missing") {
    val reader = MapReader(Map("A" -> "1"))
    reader.getOrElse("A", "x") shouldBe "1"
    reader.getOrElse("B", "x") shouldBe "x"
  }

  test("EnvLoader reads the process environment, and entries lists it") {
    val (key, value) = sys.env.head
    EnvLoader.get(key) shouldBe Some(value)
    EnvLoader.get("SZORK_SURELY_NOT_SET_ANYWHERE") shouldBe None
    EnvLoader.getOrElse("SZORK_SURELY_NOT_SET_ANYWHERE", "dflt") shouldBe "dflt"
    EnvLoader.entries.get(key) shouldBe Some(value)
  }

  test("tracing settings come from the given variables without touching system properties") {
    AgentTracing.create(Map.empty) shouldBe None
    AgentTracing.create(Map("TRACING_MODE" -> "none")) shouldBe None
    AgentTracing.create(Map("TRACING_MODE" -> "console")) should not be empty
    sys.props.get("TRACING_MODE") shouldBe None
  }
}
