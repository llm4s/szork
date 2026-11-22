package org.llm4s.szork.stubs

import org.llm4s.llmconnect.model.{Conversation, UserMessage}
import org.llm4s.testkit.{Reply, ScriptedLLMClient}

/** A scripted Dungeon Master built on the llm4s testkit's `ScriptedLLMClient`: no network, and every request recorded.
  *
  * It plays a two-room cavern for as long as a test likes, choosing each answer from the player's latest command, and a
  * test puts its own rules in front of it:
  *
  * {{{
  * val llm = ScriptedDm { case ScriptedDm.Command("test") => Reply.text(answer) }
  * // ... play, then assert on llm.calls / llm.lastCall
  * }}}
  */
object ScriptedDm {

  /** Matches a conversation that ends with the player's command, that is a turn's first call (later calls of the turn
    * end with tool results or llm4s's correction).
    */
  object Command {
    def unapply(conversation: Conversation): Option[String] =
      conversation.messages.lastOption.collect { case u: UserMessage => u.content }
  }

  /** The text of the latest user message, or "" when there is none. */
  def lastUserText(conversation: Conversation): String =
    conversation.messages.reverseIterator.collectFirst { case u: UserMessage => u.content }.getOrElse("")

  /** The cavern: "look" (or the opening) shows the entrance, "go" moves to the hall, anything else is examined. */
  val cavern: PartialFunction[Conversation, Reply] = { case conversation =>
    val command = lastUserText(conversation).toLowerCase
    Reply.text(
      if (command.startsWith("look") || command.contains("start adventure") || command.isEmpty)
        DmAnswers.scene("You are at the cavern entrance.", "cavern_entrance", "Cavern Entrance", Seq("north" -> "hall"))
      else if (command.startsWith("go"))
        DmAnswers.scene(
          "You walk north into a small hall.",
          "hall",
          "Small Hall",
          Seq("south" -> "cavern_entrance"),
          mood = "mystery")
      else DmAnswers.simple("You examine it closely.", "cavern_entrance", "examine"))
  }

  /** On "take <item>", first calls `add_inventory_item` with that item; the cavern then answers the turn. */
  val takesItems: PartialFunction[Conversation, Reply] = { case Command(s"take $item") =>
    Reply.toolCall("add_inventory_item", ujson.Obj("item" -> item).render())
  }

  /** A client answering with `rules` where they apply and the cavern otherwise. It reports an 8000-token context window
    * with 1000 reserved for the answer, so the default context-window cap is small enough to matter.
    */
  def apply(rules: PartialFunction[Conversation, Reply] = PartialFunction.empty): ScriptedLLMClient =
    ScriptedLLMClient.respondingTo(rules.orElse(cavern)).withContextWindow(8000).withReserveCompletion(1000)
}
