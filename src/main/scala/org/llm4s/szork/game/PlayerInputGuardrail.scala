package org.llm4s.szork.game

import org.llm4s.agent.guardrails.InputGuardrail
import org.llm4s.agent.guardrails.builtin.{InjectionCategory, InjectionPattern, PromptInjectionDetector}
import org.llm4s.types.Result

/** The Dungeon Master's input guardrail: llm4s's `PromptInjectionDetector`, with patterns chosen for a text adventure.
  *
  * It runs on each player command before the model sees it (llm4s's `GuardrailMiddleware`), so a refused command costs
  * no model call. It is deterministic: regular expressions matched against llm4s's normalised (case- and accent-folded)
  * copy of the command, at llm4s's default (high) sensitivity.
  *
  * It starts from llm4s's defaults and drops, by name (`PromptInjectionDetector.defaults.without`), the ones that match
  * ordinary adventure commands; Szork adds its own for talking to the Dungeon Master out of the game. Dropped:
  *   - jailbreak, code injection and data exfiltration patterns: players talk about weapons, bombs and stealing
  *     secrets, and a command only ever reaches the model, never a shell, database or page
  *   - `dan_jailbreak` (a character may be called Dan), `roleplay_evil` ("pretend to be an evil cultist"),
  *     `no_restrictions` ("remove filters"), `what_is_prompt` ("what are the secret rules"), and `reveal_prompt`,
  *     `tell_me_prompt`, `repeat_instructions` ("show the hidden instructions to the guard")
  *   - `forget_instructions` ("forget training"), `disregard` ("disregard the rules of the guild"), `developer_mode`
  *     ("put the drone in debug mode", "switch the terminal to admin mode"), `no_guardrails_mode` ("a bridge with no
  *     guardrails") and `you_are_now` ("tell the slave you are now a free man who can leave")
  *
  * Szork's own patterns catch the out-of-game forms of these: addressed to the Dungeon Master ("your instructions",
  * "your rules", "the system prompt"), or naming the conversation ("previous instructions").
  */
object PlayerInputGuardrail {

  /** What the Dungeon Master answers to a refused command. */
  val Refusal: String = "The dungeon does not understand you."

  /** The llm4s default patterns that match ordinary adventure commands. */
  val DroppedLlm4sPatterns: Seq[String] = Seq(
    "forget_instructions",
    "disregard",
    "dan_jailbreak",
    "roleplay_evil",
    "no_restrictions",
    "no_guardrails_mode",
    "you_are_now",
    "developer_mode",
    "reveal_prompt",
    "tell_me_prompt",
    "what_is_prompt",
    "repeat_instructions",
    "hypothetical_scenario",
    "creative_writing",
    "how_to_dangerous",
    "opposite_day",
    "base64_encoded",
    "sql_injection",
    "shell_injection",
    "script_tags",
    "extract_data",
    "send_to_url"
  )

  import InjectionCategory.*

  // Whose instructions: the Dungeon Master's, or the conversation's ("your rules", "all previous instructions")
  private val Theirs = """(all\s+)?(of\s+)?(your|(the\s+)?(previous|prior|above|earlier|preceding))\s+"""

  /** Szork's patterns for addressing the Dungeon Master out of the game (lower-case: matched against folded input). */
  private val SzorkPatterns: Seq[InjectionPattern] = Seq(
    InjectionPattern(
      "system_prompt",
      """\bsystem\s+prompts?\b|\byour\s+system\s+(messages?|instructions?)\b""".r,
      SystemPromptExtraction,
      3),
    InjectionPattern(
      "forget_your_instructions",
      ("""\b(forget|disregard)\s+""" + Theirs + """(instructions?|rules|guidelines|directives|programming|training|prompts?)\b""").r,
      InstructionOverride,
      3
    ),
    InjectionPattern(
      "reveal_your_instructions",
      ("""\b(reveal|show|display|output|print|repeat|recite|dump|leak|tell\s+me|what\s+(is|are|were))\b.{0,20}""" +
        """\byour\s+(initial\s+|original\s+|hidden\s+|secret\s+)?""" +
        """(prompt|instructions|programming|guidelines|directives|configuration)\b""").r,
      SystemPromptExtraction,
      3
    ),
    // A command that opens by redefining the Dungeon Master ("You are now...", "From now on you will...")
    InjectionPattern("redefine_dm", """^\W*(you\s+are\s+now|from\s+now\s+on,?\s+you)\b""".r, RoleManipulation, 3),
    InjectionPattern(
      "become_unrestricted",
      ("""\b(you\s+are\s+now|act\s+as|act\s+like|behave\s+like|respond\s+as|pretend\s+to\s+be)\s+(an?\s+)?""" +
        """(ai|chatbot|assistant|language\s+model|llm|chatgpt|unrestricted|uncensored|unfiltered|jailbroken)\b""").r,
      RoleManipulation,
      3
    ),
    InjectionPattern(
      "drop_your_rules",
      ("""\b(without|no|remove|ignore|bypass|disable|forget)\s+(any\s+|all\s+)?(of\s+)?your\s+""" +
        """(restrictions?|limitations?|filters?|guardrails?|rules|guidelines|safety)\b""").r,
      RoleManipulation,
      3
    ),
    // The model's own modes: an adventure's terminals and drones have admin and debug modes
    InjectionPattern(
      "assistant_mode",
      """\b(developer|jailbreak|jailbroken|dan|unrestricted|uncensored|god)\s+mode\b""".r,
      RoleManipulation,
      3),
    InjectionPattern(
      "leave_the_game",
      ("""\b(stop|quit)\s+(being|acting\s+as|playing)\s+(the\s+)?(dungeon\s+master|game\s+master|narrator|dm)\b|""" +
        """\bbreak\s+character\b""").r,
      RoleManipulation,
      3
    )
  )

  /** The detector: llm4s's defaults without [[DroppedLlm4sPatterns]], and Szork's own patterns. Left only if a dropped
    * name is not one of llm4s's patterns.
    */
  val detector: Result[PromptInjectionDetector] =
    PromptInjectionDetector.defaults
      .without(DroppedLlm4sPatterns*)
      .map(kept => new PromptInjectionDetector(kept.patterns ++ SzorkPatterns, kept.sensitivity, kept.onFail))

  /** A new guardrail that refuses a command matching any of the detector's patterns. */
  def apply(): InputGuardrail =
    detector.fold(error => throw new IllegalStateException(s"Szork's injection patterns: ${error.message}"), identity)

  /** The input guardrails a Dungeon Master uses by default. */
  def default: Seq[InputGuardrail] = Seq(apply())
}
