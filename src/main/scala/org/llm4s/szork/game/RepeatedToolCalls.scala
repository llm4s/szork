package org.llm4s.szork.game

import org.llm4s.agent.graph.middleware.{AgentMiddleware, BoundaryResult, MiddlewareId, ModelContext, ToolCallRequest}
import org.llm4s.agent.graph.tool.{ToolContext, ToolOutcome}
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap

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
