package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.szork.api.SzorkConfig
import org.llm4s.szork.playtest.{Player, Playtest, PlaytestConfig}

/** A playtest against a real LLM: the configured model designs an adventure, a second conversation with it plays 20
  * turns, text only, and the game is held to [[org.llm4s.szork.playtest.PlaytestChecks]].
  *
  * Skipped unless RUN_INTEGRATION=true and an LLM is configured. Run it with: `RUN_INTEGRATION=true sbt "testOnly
  * *AdventureIntegrationSpec"`. The same playtest runs from the command line, with a report, as `sbt "runMain
  * org.llm4s.szork.playtest.Playtest"`.
  */
class AdventureIntegrationSpec extends AnyFunSuite with Matchers {

  test("a designed adventure, played by an LLM, keeps the game's rules") {
    if (!sys.env.getOrElse("RUN_INTEGRATION", "false").toLowerCase().contains("true"))
      cancel("Integration test skipped (set RUN_INTEGRATION=true to enable)")
    val model = SzorkConfig.instance.llmConfig
      .map(c => s"${c.provider}/${c.model.getOrElse("default")}")
      .getOrElse(cancel("No LLM provider configured (set OPENAI_API_KEY, ANTHROPIC_API_KEY, or LLAMA_BASE_URL)"))
    val llm = SzorkConfig.getLLMClient().fold(e => fail(s"LLM client: $e"), identity)

    val config = PlaytestConfig("underground realm: caverns, ancient ruins, subterranean rivers, glowing fungi", 20)
    val settings = SzorkConfig.instance
    val report = Playtest
      .run(config, llm, Player.model(llm, settings.reasoningEffort), model, log = println, settings = settings)
      .fold(e => fail(s"The playtest could not run: ${e.message}"), identity)

    println(report.summary)
    withClue(report.summary + "\n") {
      report.findings.filter(_.hard) shouldBe Nil
      report.turns should not be empty
      // The player got somewhere: a game stuck in its first room is not one the rules alone would catch
      report.turns.flatMap(_.after.currentLocation).distinct.size should be > 1
    }
  }
}
