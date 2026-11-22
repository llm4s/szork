package org.llm4s.szork.game

import org.llm4s.agent.guardrails.{GuardrailAction, InputGuardrail}
import org.llm4s.agent.guardrails.builtin.{
  InjectionCategory,
  InjectionPattern,
  InjectionSensitivity,
  PromptInjectionDetector
}

/** The Dungeon Master's input guardrail: llm4s's `PromptInjectionDetector`, with patterns chosen for a text adventure.
  *
  * It runs on each player command before the model sees it (llm4s's `GuardrailMiddleware`), so a refused command costs
  * no model call. It is deterministic: regular expressions matched against llm4s's normalised (case- and accent-folded)
  * copy of the command.
  *
  * llm4s's default patterns are written for assistants, and several match ordinary adventure commands, so only the ones
  * that do not are kept; Szork adds its own for talking to the Dungeon Master out of the game. Left out:
  *   - jailbreak, code injection and data exfiltration patterns: players talk about weapons, bombs and stealing
  *     secrets, and a command only ever reaches the model, never a shell, database or page
  *   - `dan_jailbreak` (a character may be called Dan), `roleplay_evil` ("pretend to be an evil cultist"),
  *     `no_restrictions` ("remove filters"), `what_is_prompt` ("ask the guard what are the rules"), and
  *     `reveal_prompt`, `tell_me_prompt`, `repeat_instructions` ("show the instructions to the guard"); Szork's own
  *     patterns catch the out-of-game forms of these, addressed to the Dungeon Master ("your instructions", "the system
  *     prompt").
  */
object PlayerInputGuardrail {

  /** What the Dungeon Master answers to a refused command. */
  val Refusal: String = "The dungeon does not understand you."

  /** The llm4s patterns kept for player commands. */
  private val KeptLlm4sPatterns: Set[String] = Set(
    "ignore_instructions",
    "forget_instructions",
    "disregard",
    "new_instructions",
    "override_system",
    "no_guardrails_mode",
    "you_are_now",
    "developer_mode",
    "beginning_text"
  )

  import InjectionCategory._

  /** Szork's patterns for addressing the Dungeon Master out of the game (lower-case: matched against folded input). */
  private val SzorkPatterns: Seq[InjectionPattern] = Seq(
    InjectionPattern("system_prompt", """\bsystem\s+(prompt|message|instructions?)\b""".r, SystemPromptExtraction, 3),
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
    InjectionPattern(
      "leave_the_game",
      ("""\b(stop|quit)\s+(being|acting\s+as|playing)\s+(the\s+)?(dungeon\s+master|game\s+master|narrator|dm)\b|""" +
        """\bbreak\s+character\b""").r,
      RoleManipulation,
      3
    )
  )

  /** Every pattern the guardrail matches. */
  val patterns: Seq[InjectionPattern] =
    InjectionPattern.default.filter(p => KeptLlm4sPatterns.contains(p.name)) ++ SzorkPatterns

  /** A new guardrail that refuses a command matching any of [[patterns]]. */
  def apply(): InputGuardrail =
    PromptInjectionDetector(patterns, InjectionSensitivity.High, GuardrailAction.Block)

  /** The input guardrails a Dungeon Master uses by default. */
  def default: Seq[InputGuardrail] = Seq(apply())
}
