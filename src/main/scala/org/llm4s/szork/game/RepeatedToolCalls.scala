package org.llm4s.szork.game

import org.llm4s.agent.graph.middleware.{
  AgentMiddleware,
  BoundaryResult,
  MiddlewareId,
  ModelContext,
  ModelRequest,
  ModelResponse,
  ToolCallRequest
}
import org.llm4s.agent.graph.tool.{ToolContext, ToolOutcome, ToolSet}
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Stops the Dungeon Master making the same tool call twice in one turn when nothing has changed in between (llm4s
  * `wrapToolCall`).
  *
  * A model sometimes records the same thing over and over - the same character met, the same quest still active - until
  * the turn runs out of steps and the player gets an error. A call with the same tool and arguments as one made since
  * the game last changed is not run, and is answered with [[RepeatedToolCallMiddleware.Repeated]] (a change) or
  * [[RepeatedToolCallMiddleware.Reread]] (a read, whose answer is already in the conversation). A call that may change
  * the game - any tool not in `readOnly` - and succeeds starts afresh: after it, reading a location again, or setting
  * an exit back to how it was, runs. The calls a turn has made are forgotten when the next turn starts (`beforeAgent`).
  *
  * One instance belongs to one game, whose turns run one at a time.
  *
  * @param readOnly
  *   The tools that only read the game ([[GameTools.ReadOnly]])
  */
final class RepeatedToolCallMiddleware(readOnly: Set[String] = GameTools.ReadOnly) extends AgentMiddleware {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)
  // The calls made since the game last changed (one turn's calls run one at a time)
  private val made = ConcurrentHashMap.newKeySet[String]()

  val id: MiddlewareId = MiddlewareId(RepeatedToolCallMiddleware.Id)

  override def beforeAgent(input: String, context: ModelContext): Result[BoundaryResult] = {
    made.clear()
    Right(BoundaryResult(input))
  }

  override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome = {
    val tool = request.call.name
    val key = RepeatedToolCallMiddleware.key(tool, request.call.arguments)
    if (!made.add(key)) {
      logger.warn(s"The Dungeon Master repeated $tool with the same arguments: not run again")
      ToolOutcome.Error(if (readOnly(tool)) RepeatedToolCallMiddleware.Reread else RepeatedToolCallMiddleware.Repeated)
    } else {
      val outcome = next()
      outcome match {
        // a change: what was read or done before it may be different now
        case _: ToolOutcome.Success if !readOnly(tool) =>
          made.clear()
          made.add(key)
        case _ => ()
      }
      outcome
    }
  }
}

object RepeatedToolCallMiddleware {

  /** The middleware's id in the Dungeon Master's stack. */
  val Id: String = "repeated-tool-calls"

  /** What the model is told when it repeats a call that changes the game. */
  val Repeated: String =
    "You already made this exact call this turn and it took effect; it was not run again. " +
      "Do not call tools again for what is already recorded: give the player your answer now."

  /** What the model is told when it repeats a call that reads the game, with nothing changed since. */
  val Reread: String =
    "You already made this exact call this turn and nothing has changed since; its result is above and it was not " +
      "run again. Give the player your answer now."

  /** A call's identity: its tool and its arguments, whatever order their fields were written in. */
  private[game] def key(tool: String, arguments: ujson.Value): String =
    s"$tool ${ujson.write(arguments, sortKeys = true)}"
}

/** Limits how many rounds of tool calls a turn may make before the Dungeon Master has to answer (llm4s `wrapModelCall`
  * and `wrapToolCall`).
  *
  * A model sometimes does what the player asked in its first step and then keeps looking things up - the location, the
  * inventory, the world summary, other rooms - each call a little different, so [[RepeatedToolCallMiddleware]] lets it
  * through, until the turn runs out of steps and the player gets an error (and every step is sent the whole prompt
  * again). It counts the model calls that reach it: each step of the turn, and llm4s's request to correct an answer
  * that does not match the output schema. The tool calls of the first [[ToolRoundsMiddleware.MaxRounds]] run. After
  * that a model call is offered no tools, so the model can only answer the player; a tool call it makes all the same is
  * not run and is answered with [[ToolRoundsMiddleware.Spent]].
  *
  * It must wrap the [[ExitGuardMiddleware]] (be added before it): the guard's request to correct a refused move is part
  * of the model call it corrects, not another round, so it is offered the tools that call was.
  *
  * One instance belongs to one game, whose turns run one at a time.
  */
final class ToolRoundsMiddleware(maxRounds: Int = ToolRoundsMiddleware.MaxRounds) extends AgentMiddleware {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)
  // The model calls this turn has made
  private val modelCalls = new AtomicInteger()

  val id: MiddlewareId = MiddlewareId(ToolRoundsMiddleware.Id)

  override def beforeAgent(input: String, context: ModelContext): Result[BoundaryResult] = {
    modelCalls.set(0)
    Right(BoundaryResult(input))
  }

  override def wrapModelCall(request: ModelRequest, context: ModelContext)(
    next: ModelRequest => Result[ModelResponse]
  ): Result[ModelResponse] =
    // Past its rounds the model is offered no tools: all it can do is answer
    if (modelCalls.incrementAndGet() <= maxRounds) next(request)
    else {
      logger.warn(s"The Dungeon Master has made $maxRounds rounds of tool calls this turn: it must answer now")
      next(request.withTools(ToolSet.empty))
    }

  override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
    if (modelCalls.get <= maxRounds) next()
    else {
      logger.warn(s"The Dungeon Master is still calling tools after $maxRounds rounds: ${request.call.name} not run")
      ToolOutcome.Error(ToolRoundsMiddleware.Spent)
    }
}

object ToolRoundsMiddleware {

  /** The middleware's id in the Dungeon Master's stack. */
  val Id: String = "tool-rounds"

  /** The model calls of a turn whose tool calls run. The longest turns the game needs - a door unlocked and walked
    * through, a character spoken with and a quest advanced - make their tool calls in one or two.
    */
  val MaxRounds: Int = 3

  /** What the model is told when it calls a tool after the turn's rounds are spent. */
  val Spent: String =
    "This turn has used its tool calls: this call was not run. What you have already done this turn took effect, " +
      "and the current game state has everything else. Call no more tools: give the player your answer now."
}
