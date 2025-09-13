package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.{LLM}
import org.llm4s.llmconnect.model.{SystemMessage, UserMessage, Conversation}
import org.llm4s.config.EnvLoader

/**
 * Integration test: runs an underground realm adventure with an LLM player.
 * Focuses on text-only playthrough (no images, no audio, no music).
 */
class AdventureIntegrationSpec extends AnyFunSuite with Matchers {

  private def hasLLM: Boolean = {
    val cfg = SzorkConfig.instance.llmConfig
    cfg.isDefined
  }

  test("LLM-driven underground realm adventure (text-only)") {
    if (!sys.env.getOrElse("RUN_INTEGRATION", "false").toLowerCase().contains("true"))
      cancel("Integration test skipped (set RUN_INTEGRATION=true to enable)")
    if (!hasLLM) cancel("No LLM provider configured (set OPENAI_API_KEY, ANTHROPIC_API_KEY, or LLAMA_BASE_URL)")

    // Initialize game engine with underground realm theme; disable audio in all steps
    val llmClient = LLM.client(EnvLoader)
    val engine = GameEngine.create(
      llmClient = llmClient,
      sessionId = IdGenerator.sessionId(),
      theme = Some("underground realm adventure: caverns, ancient ruins, subterranean rivers, bioluminescent fungi"),
      artStyle = None,
      adventureOutline = None
    )

    val init = engine.initialize()
    init.isRight shouldBe true
    val opening = init.fold(_ => "", identity)
    println("[Adventure] Opening scene:\n" + opening + "\n")

    // LLM player loop (max 20 steps)
    val player = LLM.client(EnvLoader)
    var transcript = Vector[(String, String)]() // (command, response)
    var lastNarration = opening

    def nextCommand(narration: String, history: Vector[(String, String)]): String = {
      val historyText = history.takeRight(8).map { case (c, r) => s"Player: $c\nDM: $r" }.mkString("\n\n")
      val prompt = s"""
        You are playtesting a classic Infocom-style text adventure.
        Produce ONE next player command only (lowercase, terse: e.g., "look", "examine lantern", "take key", "open door", "go north").
        Avoid repeating impossible actions. If blocked, try an alternative or explore.
        Prefer short canonical verbs (north/south/east/west, up/down, in/out, look, examine, take, drop, use, inventory).
        Output must be a single command with no extra words.

        Recent transcript:
        %s

        Latest narration:
        %s
      """.stripMargin.format(historyText, narration)

      val convo = Conversation(Seq(
        SystemMessage("You respond only with the next player command, nothing else."),
        UserMessage(prompt)
      ))

      player.complete(convo) match {
        case Right(resp) =>
          val cmd = resp.message.content.trim.split("\n").headOption.getOrElse("look").trim
          // sanitize to one short line
          cmd.take(60).toLowerCase
        case Left(_) => "look"
      }
    }

    var steps = 0
    while (steps < 20) {
      val command = nextCommand(lastNarration, transcript)
      println(s"[Player] $command")

      val result = engine.processCommand(command, generateAudio = false)
      result.isRight shouldBe true
      val responseText = result.fold(_ => "", _.text)
      println("[DM] " + responseText + "\n")

      transcript = transcript :+ (command -> responseText)
      lastNarration = responseText

      // basic stop conditions
      val lower = responseText.toLowerCase
      if (lower.contains("you have won") || lower.contains("game over") || lower.contains("the end")) {
        println("[Adventure] End condition reached; stopping early.")
        steps = 20
      } else {
        steps += 1
      }
    }

    // Ask the LLM to rate the interaction
    val rubric =
      """
        Evaluate the following transcript of a text-adventure play session.
        Criteria: consistency of world state, adherence to fair-play (no bypassing obstacles), clarity and terseness of style, engagement/pacing, and puzzle coherence.
        Return STRICT JSON with fields: {
          "score": number (1-10),
          "summary": string,
          "issues": [string]
        }
      """.stripMargin

    val transcriptText = transcript.map { case (c, r) => s"Player: $c\nDM: $r" }.mkString("\n\n")
    val ratingConvo = Conversation(Seq(
      SystemMessage("You are a critical but fair game reviewer. Output only JSON."),
      UserMessage(rubric + "\n\nTranscript:\n" + transcriptText)
    ))

    val ratingResp = player.complete(ratingConvo)
    ratingResp.isRight shouldBe true
    val ratingText = ratingResp.fold(_ => "{}", _.message.content)
    println("[Review] " + ratingText)

    // Non-strict check: ensure we got a non-empty response from the reviewer
    ratingText.trim.length should be > 2
  }
}
