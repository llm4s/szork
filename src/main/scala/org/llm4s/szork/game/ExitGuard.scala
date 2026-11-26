package org.llm4s.szork.game

import org.llm4s.agent.graph.middleware.{AgentMiddleware, MiddlewareId, ModelContext, ModelRequest, ModelResponse}
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{AssistantMessage, UserMessage}
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/** The rule that a scene never moves the player through an exit that is not open.
  *
  * There is no move tool: a `fullScene` answer for another location is the move. The answer matches the output schema
  * whichever location it names, so llm4s's own check accepts a walk through a locked door; this is the game's check.
  * [[ExitGuardMiddleware]] applies it to every answer as the model gives it; `GameEngine` checks once more before it
  * records a move, and fails a turn that still breaks it.
  */
object ExitGuard {

  /** A move the world does not allow: `from` has `exits` leading to `to`, and none of them is open. */
  final case class BlockedMove(from: String, to: String, exits: List[ExitRecord])

  /** How the corrective request to the model starts. */
  val CorrectionLead: String = "The game did not accept that answer"

  /** The `actionTaken` of the answer that replaces a move the model would not take back. */
  val ShutAction: String = "blocked"

  /** Why a scene at `destination` cannot be recorded in `world`, if it cannot.
    *
    * A move is refused only when the player's location has exits to `destination` and none is open. It is allowed when
    * the player is nowhere yet (the opening scene), stays where they are (a look around), or goes somewhere no exit of
    * the location leads (a place the Dungeon Master invented, or a move the story makes).
    */
  def blockedMove(world: WorldSnapshot, destination: String): Option[BlockedMove] =
    for {
      from <- world.currentLocation
      if from != destination
      here <- world.locations.find(_.id == from)
      ways = here.exits.filter(_.targetId == destination)
      if ways.nonEmpty && !ways.exists(_.state.passable)
    } yield BlockedMove(from, destination, ways)

  /** [[blockedMove]] for a model's `answer`: the move it would make if the engine used it. Only an answer the engine
    * would read as a scene moves the player ([[ResponseInterpreter]]). The answer's JSON is found as llm4s finds it for
    * the output schema (a code fence or prose round it stripped, `LLMClient.extractJson`), so the move checked is the
    * one the turn would end with.
    */
  def blockedMoveIn(world: WorldSnapshot, answer: String): Option[BlockedMove] =
    ResponseInterpreter
      .interpret(LLMClient.extractJson(answer))
      .data
      .collect { case scene: GameScene => scene }
      .flatMap(scene => blockedMove(world, scene.locationId))

  /** What the model is told about an answer that made `move`, asking it to answer again. With `canOpen`, the request
    * offers `set_exit_state`, and the model may open the way with it; otherwise it can only say what is in the way.
    */
  def correction(move: BlockedMove, canOpen: Boolean = true): String = {
    val ways = move.exits.map { e =>
      val note = if (e.state == ExitState.Hidden) " and the player has not discovered it" else ""
      s"- the ${e.direction} exit to ${e.targetId} is ${e.state.wire}$note${e.obstacle.fold("")(o => s" ($o)")}"
    }
    val stayPut =
      s"give a simple response with locationId ${move.from} that tells the player what is in the way (never reveal an exit that is hidden)."
    val answerAgain =
      if (canOpen)
        s"""Answer the player's command again, in one of two ways:
          |1. If what the player did in this command legitimately opens the way (they simply open a closed door that has no obstacle, or they have and use what the obstacle needs), first call set_exit_state to set that exit to "open", and only then give the fullScene for ${move.to}.
          |2. Otherwise $stayPut""".stripMargin
      else s"Answer the player's command again: no more tools can be called this turn, so $stayPut"
    s"""$CorrectionLead: its fullScene moves the player from ${move.from} to ${move.to}, but that way is not open. In the current game state:
      |${ways.mkString("\n")}
      |The player is still at ${move.from}. $answerAgain
      |Do not give a fullScene for ${move.to} while no exit to it is open.""".stripMargin
  }

  /** What the player is told when a move is refused without the model's help: what is in the way, but nothing of an
    * exit they have not discovered.
    */
  def shutNarration(move: BlockedMove): String =
    move.exits.find(_.state != ExitState.Hidden) match {
      case Some(e) =>
        s"You cannot go ${e.direction} yet: the way is ${e.state.wire}${e.obstacle.fold("")(o => s" ($o)")}."
      case None => "You cannot go that way."
    }

  /** A Dungeon Master answer ([[DungeonMasterOutput.schema]]) that keeps the player at `move.from` and tells them
    * [[shutNarration]].
    */
  def shutAnswer(move: BlockedMove): String =
    ujson.write(
      ujson.Obj(
        "narrationText" -> shutNarration(move),
        "responseType" -> "simple",
        "locationId" -> move.from,
        "locationName" -> ujson.Null,
        "imageDescription" -> ujson.Null,
        "imageUpdate" -> ujson.Null,
        "musicDescription" -> ujson.Null,
        "musicMood" -> ujson.Null,
        "exits" -> ujson.Null,
        "items" -> ujson.Null,
        "npcs" -> ujson.Null,
        "actionTaken" -> ShutAction
      ))
}

/** Holds the Dungeon Master's answers to [[ExitGuard]]'s rule, as an llm4s model-call wrapper.
  *
  * Each model call's answer (a completion without tool calls) is checked against this game's [[WorldState]] as it is at
  * that moment, so an exit a tool call opened earlier in the turn is open. An answer that would move the player through
  * an exit that is not open is not returned to the agent: the model is called once more with that answer and
  * [[ExitGuard.correction]] added to the request, as llm4s itself does for an answer that fails the output schema.
  * Neither is stored in the thread. The second response is used if it is anything else - a tool call (the model opens
  * the exit, and its next answer is checked in turn), a simple response, a scene the world allows. If it makes a
  * refused move again, its content is replaced with [[ExitGuard.shutAnswer]], so the thread, the player and the world
  * state agree that the player has not moved.
  *
  * Both calls are counted in the turn's usage, and both are announced to a streaming turn's subscribers: the refused
  * narration streams before the one that is used, as with llm4s's own correction. The corrective call is part of the
  * model call it corrects: it is offered the same tools, and [[ToolRoundsMiddleware]], which wraps this, does not count
  * it as another round.
  */
final class ExitGuardMiddleware(world: WorldState) extends AgentMiddleware {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)

  val id: MiddlewareId = MiddlewareId(ExitGuardMiddleware.Id)

  override def wrapModelCall(request: ModelRequest, context: ModelContext)(
    next: ModelRequest => Result[ModelResponse]
  ): Result[ModelResponse] =
    next(request).flatMap { first =>
      refused(first) match {
        case None => Right(first)
        case Some(move) =>
          logger.warn(s"Answer moves the player from ${move.from} to ${move.to}, which is not open: asking again")
          val canOpen = request.tools.get(ExitGuardMiddleware.OpeningTool).isDefined
          val corrective = request.withMessages(
            request.messages :+ AssistantMessage(first.completion.message.content) :+
              UserMessage(ExitGuard.correction(move, canOpen)))
          next(corrective).map { second =>
            refused(second).fold(second) { again =>
              logger.warn(s"The corrected answer moves the player to ${again.to} again: the move is refused")
              val answer = ExitGuard.shutAnswer(again)
              val completion = second.completion
              second.withCompletion(completion.withMessage(completion.message.withContent(answer)))
            }
          }
      }
    }

  /** The refused move `response` would make, when it is an answer. */
  private def refused(response: ModelResponse): Option[ExitGuard.BlockedMove] = {
    val message = response.completion.message
    if (message.toolCalls.nonEmpty) None else ExitGuard.blockedMoveIn(world.snapshot, message.content)
  }
}

object ExitGuardMiddleware {

  /** The middleware's id in the Dungeon Master's stack. */
  val Id: String = "exit-guard"

  /** The tool that opens an exit, which the correction offers when the request does. */
  val OpeningTool: String = "set_exit_state"
}
