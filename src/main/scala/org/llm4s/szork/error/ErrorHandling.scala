package org.llm4s.szork.error

import org.llm4s.error.LLMError
import org.slf4j.Logger
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}

/** Utilities for consistent error handling across the Szork application */
object ErrorHandling {

  /** Type alias for cleaner signatures */
  type SzorkResult[T] = Either[SzorkError, T]

  /** Convert various error types to SzorkError */
  object Converters {
    def fromString(message: String): SzorkError =
      GameStateError(message)

    def fromThrowable(t: Throwable): SzorkError =
      GameStateError(Option(t.getMessage).getOrElse("Unknown error"), Some(t))

    def fromLLMError(error: LLMError): SzorkError =
      AIError(error.message, retryable = true, llmError = Some(error))

    def fromValidationIssues(issues: List[String]): SzorkError =
      ValidationError(issues)

    /** Convert Either[String, T] to SzorkResult[T] */
    def fromStringEither[T](either: Either[String, T]): SzorkResult[T] =
      either.left.map(fromString)

    /** Convert Either[List[String], T] to SzorkResult[T] */
    def fromValidationEither[T](either: Either[List[String], T]): SzorkResult[T] =
      either.left.map(fromValidationIssues)

    /** Convert Either[LLMError, T] to SzorkResult[T] */
    def fromLLMResult[T](either: Either[LLMError, T]): SzorkResult[T] =
      either.left.map(fromLLMError)

    /** Convert Try[T] to SzorkResult[T] */
    def fromTry[T](tried: Try[T]): SzorkResult[T] =
      tried match {
        case Success(value) => Right(value)
        case Failure(exception) => Left(fromThrowable(exception))
      }

    /** Safely execute a block that might throw */
    def catching[T](operation: => T): SzorkResult[T] =
      fromTry(Try(operation))
  }

  /** Logging utilities */
  object Logging {
    /** Log an error if present, pass through the result unchanged */
    def logError[T](operation: String)(result: SzorkResult[T])(implicit logger: Logger): SzorkResult[T] = {
      result.left.foreach { error =>
        error.cause match {
          case Some(throwable) =>
            logger.error(s"$operation failed: ${error.message}", throwable)
          case None =>
            logger.error(s"$operation failed: ${error.message}")
        }
      }
      result
    }

    /** Log a warning if error, pass through the result unchanged */
    def logWarning[T](operation: String)(result: SzorkResult[T])(implicit logger: Logger): SzorkResult[T] = {
      result.left.foreach { error =>
        logger.warn(s"$operation: ${error.message}")
      }
      result
    }

    /** Log error and recover with default value */
    def logAndRecover[T](operation: String, default: T)(result: SzorkResult[T])(implicit logger: Logger): T = {
      result match {
        case Right(value) => value
        case Left(error) =>
          logger.error(s"$operation failed, using default: ${error.message}")
          default
      }
    }

    /** Log error and convert to Option */
    def logAndOption[T](operation: String)(result: SzorkResult[T])(implicit logger: Logger): Option[T] = {
      result match {
        case Right(value) => Some(value)
        case Left(error) =>
          logger.error(s"$operation failed: ${error.message}")
          None
      }
    }
  }

  /** Retry logic for operations that might fail transiently */
  object Retry {
    /** Retry an operation with exponential backoff */
    def withRetry[T](
      maxAttempts: Int = 3,
      initialBackoff: FiniteDuration = 1.second,
      maxBackoff: FiniteDuration = 30.seconds,
      backoffMultiplier: Double = 2.0
    )(operation: => SzorkResult[T])(implicit logger: Logger): SzorkResult[T] = {

      @annotation.tailrec
      def attempt(remainingAttempts: Int, currentBackoff: FiniteDuration): SzorkResult[T] = {
        operation match {
          case success @ Right(_) =>
            if (remainingAttempts < maxAttempts) {
              logger.info(s"Operation succeeded after ${maxAttempts - remainingAttempts} retry(s)")
            }
            success

          case Left(error) if error.retryable && remainingAttempts > 1 =>
            logger.warn(s"Retryable error (${error.message}), waiting ${currentBackoff.toSeconds}s before retry...")
            Thread.sleep(currentBackoff.toMillis)
            val nextBackoff = FiniteDuration(
              math.min(
                (currentBackoff.toMillis * backoffMultiplier).toLong,
                maxBackoff.toMillis
              ),
              MILLISECONDS
            )
            attempt(remainingAttempts - 1, nextBackoff)

          case failure =>
            if (remainingAttempts < maxAttempts) {
              logger.error(s"Operation failed after ${maxAttempts - remainingAttempts} retry(s)")
            }
            failure
        }
      }

      attempt(maxAttempts, initialBackoff)
    }

    /** Retry an async operation */
    def withRetryAsync[T](
      maxAttempts: Int = 3,
      initialBackoff: FiniteDuration = 1.second,
      maxBackoff: FiniteDuration = 30.seconds,
      backoffMultiplier: Double = 2.0
    )(operation: => Future[T])(implicit ec: ExecutionContext, logger: Logger): Future[T] = {

      def attempt(remainingAttempts: Int, currentBackoff: FiniteDuration): Future[T] = {
        operation.recoverWith {
          case error if remainingAttempts > 1 =>
            logger.warn(s"Async operation failed (${error.getMessage}), retrying in ${currentBackoff.toSeconds}s...")
            Future {
              Thread.sleep(currentBackoff.toMillis)
            }.flatMap { _ =>
              val nextBackoff = FiniteDuration(
                math.min(
                  (currentBackoff.toMillis * backoffMultiplier).toLong,
                  maxBackoff.toMillis
                ),
                MILLISECONDS
              )
              attempt(remainingAttempts - 1, nextBackoff)
            }
          case error =>
            logger.error(s"Async operation failed after retries: ${error.getMessage}")
            Future.failed(error)
        }
      }

      attempt(maxAttempts, initialBackoff)
    }
  }

  /** Transformation utilities for working with errors */
  object Transform {
    /** Chain multiple operations, short-circuiting on first error */
    def sequence[T](results: List[SzorkResult[T]]): SzorkResult[List[T]] = {
      results.foldRight[SzorkResult[List[T]]](Right(Nil)) { (result, acc) =>
        for {
          list <- acc
          value <- result
        } yield value :: list
      }
    }

    /** Collect all successful results, ignoring errors */
    def collectSuccesses[T](results: List[SzorkResult[T]]): List[T] = {
      results.collect { case Right(value) => value }
    }

    /** Collect all errors, ignoring successes */
    def collectErrors[T](results: List[SzorkResult[T]]): List[SzorkError] = {
      results.collect { case Left(error) => error }
    }

    /** Transform error type while preserving success */
    def mapError[T](result: SzorkResult[T])(f: SzorkError => SzorkError): SzorkResult[T] = {
      result.left.map(f)
    }

    /** Add context to an error */
    def withContext[T](context: String)(result: SzorkResult[T]): SzorkResult[T] = {
      mapError(result) {
        case error: GameStateError =>
          error.copy(message = s"$context: ${error.message}")
        case error: AIError =>
          error.copy(message = s"$context: ${error.message}")
        case error =>
          GameStateError(s"$context: ${error.message}", error.cause)
      }
    }
  }

  /** Recovery strategies for handling errors gracefully */
  object Recovery {
    /** Try primary operation, fall back to secondary on failure */
    def withFallback[T](
      primary: => SzorkResult[T],
      fallback: => SzorkResult[T]
    )(implicit logger: Logger): SzorkResult[T] = {
      primary match {
        case success @ Right(_) => success
        case Left(primaryError) =>
          logger.warn(s"Primary operation failed (${primaryError.message}), trying fallback...")
          fallback match {
            case success @ Right(_) => success
            case Left(fallbackError) =>
              logger.error(s"Fallback also failed: ${fallbackError.message}")
              // Return the primary error as it's likely more informative
              Left(primaryError)
          }
      }
    }

    /** Use default value on error */
    def withDefault[T](result: SzorkResult[T], default: => T)(implicit logger: Logger): T = {
      result match {
        case Right(value) => value
        case Left(error) =>
          logger.warn(s"Using default value due to error: ${error.message}")
          default
      }
    }

    /** Convert error to Option, logging the error */
    def toOption[T](result: SzorkResult[T])(implicit logger: Logger): Option[T] = {
      result match {
        case Right(value) => Some(value)
        case Left(error) =>
          logger.debug(s"Converting error to None: ${error.message}")
          None
      }
    }

    /** Recover from specific error types */
    def recover[T](result: SzorkResult[T])(pf: PartialFunction[SzorkError, T]): SzorkResult[T] = {
      result match {
        case success @ Right(_) => success
        case Left(error) if pf.isDefinedAt(error) => Right(pf(error))
        case failure => failure
      }
    }

    /** Recover with a new operation for specific error types */
    def recoverWith[T](result: SzorkResult[T])(pf: PartialFunction[SzorkError, SzorkResult[T]]): SzorkResult[T] = {
      result match {
        case success @ Right(_) => success
        case Left(error) if pf.isDefinedAt(error) => pf(error)
        case failure => failure
      }
    }
  }

  /** Validation utilities */
  object Validation {
    /** Validate a condition, creating an error if false */
    def require(condition: Boolean, errorMessage: => String): SzorkResult[Unit] = {
      if (condition) Right(())
      else Left(ValidationError(List(errorMessage)))
    }

    /** Validate multiple conditions */
    def requireAll(validations: (Boolean, String)*): SzorkResult[Unit] = {
      val errors = validations.filterNot(_._1).map(_._2).toList
      if (errors.isEmpty) Right(())
      else Left(ValidationError(errors))
    }

    /** Validate and transform a value */
    def validate[T, U](value: T)(validation: T => Either[String, U]): SzorkResult[U] = {
      validation(value).left.map(msg => ValidationError(List(msg)))
    }
  }
}