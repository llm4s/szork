package org.llm4s.szork.api

import java.util.concurrent.{ConcurrentLinkedQueue, Executor}
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext

/** Runs tasks one at a time, in submission order, on `underlying`. Each game session has one, so everything that
  * touches a session's engine (turns, saves) is serialised without holding a thread per session.
  */
final class SerialExecutor(underlying: Executor, onFailure: Throwable => Unit) extends ExecutionContext {
  private val queue = new ConcurrentLinkedQueue[Runnable]()
  private val scheduled = new AtomicBoolean(false)

  override def execute(task: Runnable): Unit = {
    queue.add(task)
    schedule()
  }

  override def reportFailure(cause: Throwable): Unit = onFailure(cause)

  private def schedule(): Unit =
    if (scheduled.compareAndSet(false, true)) underlying.execute(() => drain())

  private def drain(): Unit =
    try {
      var task = queue.poll()
      while (task != null) {
        try task.run()
        catch { case e: Throwable => reportFailure(e) }
        task = queue.poll()
      }
    } finally {
      scheduled.set(false)
      // A task added after the last poll but before the flag was cleared must still run
      if (!queue.isEmpty) schedule()
    }
}
