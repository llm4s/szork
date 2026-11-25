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

/** Stops the Dungeon Master making the same tool call twice in one turn (llm4s `wrapToolCall`).
  *
  * A model sometimes records the same thing over and over - the same character met, the same quest still active - until
  * the turn runs out of steps and the player gets an error. The first call with a given tool and arguments runs; a
  * second in the same turn does not, and is answered with [[RepeatedToolCallMiddleware.Repeated]], which tells the
  * model the call already took effect and to answer the player. The calls a turn has made are forgotten when the next
  * turn starts (`beforeAgent`).
  *
  * One instance belongs to one game, whose turns run one at a time.
  */
final class RepeatedToolCallMiddleware extends AgentMiddleware {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)
  private val made = ConcurrentHashMap.newKeySet[String]()

  val id: MiddlewareId = MiddlewareId(RepeatedToolCallMiddleware.Id)

  override def beforeAgent(input: String, context: ModelContext): Result[BoundaryResult] = {
    made.clear()
    Right(BoundaryResult(input))
  }

  override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
    if (made.add(RepeatedToolCallMiddleware.key(request.call.name, request.call.arguments))) next()
    else {
      logger.warn(s"The Dungeon Master repeated ${request.call.name} with the same arguments: not run again")
      ToolOutcome.Error(RepeatedToolCallMiddleware.Repeated)
    }
}

object RepeatedToolCallMiddleware {

  /** The middleware's id in the Dungeon Master's stack. */
  val Id: String = "repeated-tool-calls"

  /** What the model is told when it repeats a call. */
  val Repeated: String =
    "You already made this exact call this turn and it took effect; it was not run again. " +
      "Do not call tools again for what is already recorded: give the player your answer now."

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
  * again). The tool calls of a turn's first [[ToolRoundsMiddleware.MaxRounds]] model calls run. After that a model call
  * is offered no tools, so the model can only answer the player; a tool call it makes all the same is not run and is
  * answered with [[ToolRoundsMiddleware.Spent]].
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
