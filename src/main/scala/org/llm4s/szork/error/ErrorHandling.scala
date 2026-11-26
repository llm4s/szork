package org.llm4s.szork.error

import org.slf4j.Logger

/** Utilities for consistent error handling across the Szork application */
object ErrorHandling {

  /** Type alias for cleaner signatures */
  type SzorkResult[T] = Either[SzorkError, T]

  object Logging {

    /** Log an error if present, pass through the result unchanged */
    def logError[T](operation: String)(result: SzorkResult[T])(using logger: Logger): SzorkResult[T] = {
      result.left.foreach { error =>
        error.cause match {
          case Some(throwable) => logger.error(s"$operation failed: ${error.message}", throwable)
          case None => logger.error(s"$operation failed: ${error.message}")
        }
      }
      result
    }
  }
}
