package org.llm4s.szork.game

import org.llm4s.agent.{Agent, AgentResult, AgentStatus}
import org.llm4s.agent.graph.{RunConfig, StreamEvent, ThreadId}
import org.llm4s.llmconnect.model.Message
import org.llm4s.types.Result
import org.llm4s.szork.spi.Clock
import org.slf4j.LoggerFactory

/** One game's conversation with the Dungeon Master: the llm4s thread it runs on, its history and its usage.
  *
  * The thread keeps the whole conversation while the game is live. After a restore, or a turn that did not complete,
  * there is no thread, and the next turn starts a new one seeded with [[history]], the conversation as of the last
  * completed turn.
  *
  * Used from one thread at a time (a session's `SerialExecutor`); [[history]] and [[lastTurnUsage]] may be read from
  * others.
  *
  * @param sessionId
  *   The game session, for the log
  * @param initialAgents
  *   The Dungeon Master agents, or why they could not be built (every turn then fails with that error)
  * @param clock
  *   What a turn's time is measured with
  */
private[game] final class DmConversation(
  sessionId: String,
  initialAgents: Result[DmConversation.Agents],
  clock: Clock) {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)

  private var agents: Result[DmConversation.Agents] = initialAgents
  // The thread holding the conversation, once a turn has run on it
  private var threadId: Option[ThreadId] = None
  @volatile private var lastUsage: Option[GameEngine.TurnUsage] = None
  @volatile private var messages: Vector[Message] = Vector.empty
  // Index into `messages` where the last turn's messages begin
  private var lastTurnStart: Int = 0

  /** The conversation as of the last completed turn: the thread's full history, without the system prompt (the agent
    * sends that on every model call).
    */
  def history: Vector[Message] = messages

  /** The messages the last completed turn added: its command, tool calls and results, and answer. */
  def lastTurnMessages: Vector[Message] = messages.drop(lastTurnStart)

  /** The model calls, tokens and time the last completed turn took. */
  def lastTurnUsage: Option[GameEngine.TurnUsage] = lastUsage

  /** Replaces the agents (a restored game has another system prompt); the next turn runs on them. */
  def rebuild(rebuilt: Result[DmConversation.Agents]): Unit = {
    forgetThread()
    agents = rebuilt
  }

  /** Replaces the conversation: forgets the current thread, and seeds the next turn's new thread with `seed`. There is
    * no last turn until one completes.
    */
  def startOver(seed: Vector[Message]): Unit = {
    forgetThread()
    messages = seed
    lastTurnStart = seed.length
    lastUsage = None
  }

  /** Takes back the last completed turn: [[history]] is as it was before it, and the next turn starts a new thread
    * seeded with that. For a turn the game found it cannot use.
    */
  def discardLastTurn(): Unit = startOver(messages.take(lastTurnStart))

  /** Seeds the next turn with restored `saved` messages.
    *
    * llm4s refuses to seed a thread with an invalid conversation - for example one saved mid-turn, ending in a tool
    * call that has no result - so such a history is cut back to its longest valid prefix.
    */
  def restore(saved: Vector[Message]): Unit = {
    val valid = Iterator
      .iterate(saved)(_.dropRight(1))
      .takeWhile(_.nonEmpty)
      .find(prefix => Message.validateConversation(prefix.toList).isRight)
      .getOrElse(Vector.empty)
    if (valid.length < saved.length)
      logger.warn(
        s"[$sessionId] Dropped ${saved.length - valid.length} trailing messages to restore a valid conversation")
    startOver(valid)
  }

  /** Removes the current thread from the runtime; the next turn starts a new one seeded with `history`. */
  private def forgetThread(): Unit = {
    for {
      id <- threadId
      dm <- agents.toOption
    } dm.blocking.forget(id).left.foreach { error =>
      logger.warn(s"[$sessionId] Could not forget agent thread ${id.value}: ${error.message}")
    }
    threadId = None
  }

  /** Runs one turn of the conversation, streaming events to `listener` when given.
    *
    * Continues the current thread, or starts a new one seeded with `history`. A completed turn updates `history` from
    * the thread. A turn that did not complete - it failed, or ended at a step or output limit, or was suspended - is an
    * error: it leaves `history` as it was and drops the thread (llm4s keeps such a thread for recovery or to carry on,
    * which the game does not use), so the player can simply try the command again. A turn an input guardrail blocked is
    * returned with its `Blocked` status and leaves `history` as it was; its thread stored nothing of it.
    */
  def runTurn(query: String, listener: Option[StreamEvent => Unit]): Result[AgentResult] =
    agents.flatMap { dm =>
      val (turnThread, seed) = threadId match {
        case Some(id) => (id, Nil)
        case None => (ThreadId(s"szork-${sessionId}-${java.util.UUID.randomUUID()}"), messages)
      }
      val started = clock.now()
      val outcome = listener match {
        case None => dm.blocking.run(turnThread, query, RunConfig(), seed)
        case Some(l) => dm.streaming.stream(turnThread, query, RunConfig(), seed)(l).flatMap(_.await())
      }
      outcome match {
        case Right(result) =>
          val usage = GameEngine.TurnUsage(result.runUsage, clock.now() - started)
          result.status match {
            case AgentStatus.Completed(_) =>
              threadId = Some(result.threadId)
              lastTurnStart = messages.length
              messages = result.messages.toVector
              lastUsage = Some(usage)
              logger.info(s"[$sessionId] Turn usage: $usage")
              logger.debug(s"[$sessionId] Turn completed: ${result.messages.length} messages, usage=${result.usage}")
              Right(result)
            case AgentStatus.Blocked(guardrail, reason) =>
              // The reason names the patterns that matched, never the command
              logger.warn(s"[$sessionId] Command refused by guardrail $guardrail: $reason")
              // A thread this turn opened holds no conversation yet; the next turn opens another, seeded with history
              if (threadId.isEmpty) {
                threadId = Some(result.threadId)
                forgetThread()
              }
              Right(result)
            case status =>
              // Ended at a step or output limit, or suspended (the game configures no approvals or breakpoints): the
              // thread is mid-turn, so the last good history is kept and the thread dropped
              logger.warn(s"[$sessionId] Turn did not complete (${status.productPrefix}); usage: $usage")
              threadId = Some(turnThread)
              forgetThread()
              Left(GameEngine.TurnNotCompleted(status match {
                case AgentStatus.Truncated(_) => "the answer was cut off at the model's output limit"
                case AgentStatus.Suspended(_) => "the turn was suspended"
                case other => s"the turn ended ${other.productPrefix}"
              }))
          }
        case Left(error) =>
          logger.warn(s"[$sessionId] Turn failed on thread ${turnThread.value}: ${error.message}")
          error match {
            case invalid: org.llm4s.agent.graph.GraphError.OutputInvalid =>
              invalid.errors.foreach(problem => logger.warn(s"[$sessionId]   the answer's problem: $problem"))
            case _ => ()
          }
          threadId = Some(turnThread)
          forgetThread()
          Left(error)
      }
    }
}

private[game] object DmConversation {

  /** The Dungeon Master, compiled twice from the same settings: without and with streaming. */
  final case class Agents(blocking: Agent, streaming: Agent)

  /** `result` if the turn was answered; a turn a guardrail blocked is an error. */
  def answered(result: AgentResult): Result[AgentResult] = result.status match {
    case AgentStatus.Blocked(guardrail, _) => Left(GameEngine.TurnNotCompleted(s"guardrail $guardrail refused it"))
    case _ => Right(result)
  }
}
