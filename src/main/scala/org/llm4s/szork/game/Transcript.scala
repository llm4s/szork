package org.llm4s.szork.game

import org.llm4s.llmconnect.model.{AssistantMessage, Message, UserMessage}

/** The transcript shown to the player: their commands and the Dungeon Master's narration, in order.
  *
  * It is derived from the Dungeon Master's conversation (the agent thread's history), so the game keeps one record of
  * what was said. A turn is a user message (the command) and, if it was answered, its final assistant message (the
  * structured answer, shown as its narration). Left out:
  *   - the hidden first turn, [[GameConstants.Prompts.INIT_ADVENTURE]] (its narration, the opening scene, is shown)
  *   - tool calls and their results
  *   - commands an input guardrail refused, and turns that failed: neither is stored in the thread
  *   - llm4s's request to correct an invalid answer, which is never stored either
  */
object Transcript {

  /** One line of the transcript, said by the player ("user") or the Dungeon Master ("assistant"). `pictured` is the
    * location whose picture the line was shown with: an answer is pictured when it arrives somewhere, and when the
    * Dungeon Master says the place now looks different (the rule the server draws pictures by).
    */
  final case class Entry(role: String, content: String, pictured: Option[String] = None)

  def of(messages: Seq[Message]): List[Entry] = {
    // A turn's command (None for the hidden first turn) and its final answer, if it has one
    final case class Turn(command: Option[String], answer: Option[String])

    val turns = messages.zipWithIndex.foldLeft(Vector.empty[Turn]) {
      case (turns, (user: UserMessage, index)) =>
        val hidden = index == 0 && user.content == GameConstants.Prompts.INIT_ADVENTURE
        turns :+ Turn(Option.unless(hidden)(user.content), None)
      case (turns, (assistant: AssistantMessage, _)) if !assistant.hasToolCalls && turns.nonEmpty =>
        turns.updated(turns.size - 1, turns.last.copy(answer = Some(assistant.content)))
      case (turns, _) => turns
    }

    // Each turn's lines, and where its answer left the player (to tell an arrival from an answer given in place)
    turns
      .foldLeft((List.empty[Entry], Option.empty[String])) { case ((entries, location), turn) =>
        val answered = turn.answer.map(ResponseInterpreter.interpret)
        val now = answered.flatMap(_.data).map(_.locationId).orElse(location)
        val line = answered.map { answer =>
          val pictured = answer.data.collect {
            case scene: GameScene if !location.contains(scene.locationId) => scene.locationId
            case data if answer.imageUpdate.isDefined => data.locationId
          }
          Entry("assistant", answer.narration, pictured)
        }
        (entries ++ turn.command.map(Entry("user", _)) ++ line, now)
      }
      ._1
  }
}
