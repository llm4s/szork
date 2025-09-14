# Error Handling Standardization Plan for Szork

## Current State Analysis

### Error Types Currently in Use
1. **`Either[String, T]`** - Used in 30+ locations for simple error messages
2. **`Either[List[String], T]`** - Used for validation with multiple issues
3. **`Either[LLMError, T]`** - Used for LLM operations (from LLM4S library)
4. **`Result[T]`** - Type alias for `Either[LLMError, T]` in LLM4S
5. **Try/catch blocks** - Some places still use exceptions directly

### Problems Identified
- **Inconsistent error information**: Some errors are just strings, others have structure
- **Loss of context**: String errors don't preserve stack traces or error categories
- **Difficult recovery**: Can't determine if an error is retryable without parsing strings
- **Code duplication**: Similar error handling logic repeated across files
- **Mixed abstractions**: Both functional (Either) and imperative (exceptions) error handling

## Proposed Solution

### Phase 1: Define Error Hierarchy

Create a comprehensive error type system that captures all failure modes:

```scala
// File: src/main/scala/org/llm4s/szork/error/SzorkError.scala

package org.llm4s.szork.error

import org.llm4s.error.LLMError

sealed trait SzorkError {
  def message: String
  def cause: Option[Throwable] = None
  def retryable: Boolean = false
  def userMessage: String = message // Safe message to show users
}

// Category: LLM and AI-related errors
case class AIError(
  message: String,
  override val cause: Option[Throwable] = None,
  llmError: Option[LLMError] = None,
  override val retryable: Boolean = true
) extends SzorkError {
  override def userMessage: String = "The AI is having trouble understanding. Please try again."
}

// Category: Media generation errors
sealed trait MediaError extends SzorkError
case class ImageGenerationError(
  message: String,
  override val cause: Option[Throwable] = None,
  override val retryable: Boolean = true
) extends MediaError {
  override def userMessage: String = "Unable to generate image at this time."
}

case class AudioGenerationError(
  message: String,
  override val cause: Option[Throwable] = None,
  override val retryable: Boolean = true
) extends MediaError {
  override def userMessage: String = "Unable to generate audio at this time."
}

case class MusicGenerationError(
  message: String,
  override val cause: Option[Throwable] = None,
  override val retryable: Boolean = true
) extends MediaError {
  override def userMessage: String = "Unable to generate background music."
}

// Category: Game logic errors
case class GameStateError(
  message: String,
  override val cause: Option[Throwable] = None
) extends SzorkError {
  override def userMessage: String = "There was an issue with the game state."
}

// Category: Validation errors
case class ValidationError(
  issues: List[String]
) extends SzorkError {
  override def message: String = s"Validation failed: ${issues.mkString(", ")}"
  override def userMessage: String = "Your input couldn't be processed. Please check and try again."
}

// Category: Persistence errors
case class PersistenceError(
  message: String,
  operation: String, // "save", "load", "delete"
  override val cause: Option[Throwable] = None,
  override val retryable: Boolean = true
) extends SzorkError {
  override def userMessage: String = operation match {
    case "save" => "Unable to save game. Please try again."
    case "load" => "Unable to load game. Please check the game ID."
    case "delete" => "Unable to delete game."
    case _ => "Storage operation failed."
  }
}

// Category: Network/External service errors
case class NetworkError(
  message: String,
  service: String, // "openai", "anthropic", "replicate", etc.
  override val cause: Option[Throwable] = None,
  override val retryable: Boolean = true
) extends SzorkError {
  override def userMessage: String = "Connection issue. Please check your internet and try again."
}

// Category: Configuration errors
case class ConfigurationError(
  message: String,
  missingConfig: Option[String] = None
) extends SzorkError {
  override def retryable: Boolean = false
  override def userMessage: String = "The game is not properly configured. Please contact support."
}

// Category: WebSocket errors
case class WebSocketError(
  message: String,
  override val cause: Option[Throwable] = None
) extends SzorkError {
  override def userMessage: String = "Connection lost. Please refresh the page."
}
```

### Phase 2: Create Error Handling Utilities

```scala
// File: src/main/scala/org/llm4s/szork/error/ErrorHandling.scala

package org.llm4s.szork.error

import org.slf4j.Logger
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}

object ErrorHandling {

  // Type alias for cleaner signatures
  type SzorkResult[T] = Either[SzorkError, T]

  // Convert various error types to SzorkError
  object Converters {
    def fromString(message: String): SzorkError =
      GameStateError(message)

    def fromThrowable(t: Throwable): SzorkError =
      GameStateError(t.getMessage, Some(t))

    def fromLLMError(error: LLMError): SzorkError =
      AIError(error.message, retryable = true, llmError = Some(error))

    def fromValidationIssues(issues: List[String]): SzorkError =
      ValidationError(issues)
  }

  // Logging utilities
  object Logging {
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

    def logAndRecover[T](operation: String, default: T)(result: SzorkResult[T])(implicit logger: Logger): T = {
      result match {
        case Right(value) => value
        case Left(error) =>
          logger.error(s"$operation failed, using default: ${error.message}")
          default
      }
    }
  }

  // Retry logic
  object Retry {
    def withRetry[T](
      maxAttempts: Int = 3,
      backoff: FiniteDuration = 1.second,
      backoffMultiplier: Double = 2.0
    )(operation: => SzorkResult[T])(implicit logger: Logger): SzorkResult[T] = {

      @annotation.tailrec
      def attempt(remainingAttempts: Int, currentBackoff: FiniteDuration): SzorkResult[T] = {
        operation match {
          case success @ Right(_) =>
            success

          case Left(error) if error.retryable && remainingAttempts > 1 =>
            logger.warn(s"Operation failed (${error.message}), retrying in $currentBackoff...")
            Thread.sleep(currentBackoff.toMillis)
            attempt(
              remainingAttempts - 1,
              FiniteDuration((currentBackoff.toMillis * backoffMultiplier).toLong, MILLISECONDS)
            )

          case failure =>
            failure
        }
      }

      attempt(maxAttempts, backoff)
    }

    def withRetryAsync[T](
      maxAttempts: Int = 3,
      backoff: FiniteDuration = 1.second
    )(operation: => Future[T])(implicit ec: ExecutionContext, logger: Logger): Future[T] = {
      def attempt(remainingAttempts: Int): Future[T] = {
        operation.recoverWith {
          case error if remainingAttempts > 1 =>
            logger.warn(s"Async operation failed (${error.getMessage}), retrying...")
            Thread.sleep(backoff.toMillis)
            attempt(remainingAttempts - 1)
          case error =>
            Future.failed(error)
        }
      }
      attempt(maxAttempts)
    }
  }

  // Transformation utilities
  object Transform {
    // Convert old Either[String, T] to new error system
    def fromStringEither[T](either: Either[String, T]): SzorkResult[T] =
      either.left.map(Converters.fromString)

    // Convert old Either[List[String], T] to new error system
    def fromValidationEither[T](either: Either[List[String], T]): SzorkResult[T] =
      either.left.map(Converters.fromValidationIssues)

    // Convert Try to new error system
    def fromTry[T](tried: Try[T]): SzorkResult[T] =
      tried match {
        case Success(value) => Right(value)
        case Failure(exception) => Left(Converters.fromThrowable(exception))
      }

    // Chain operations with error accumulation
    def sequence[T](results: List[SzorkResult[T]]): SzorkResult[List[T]] = {
      results.foldRight[SzorkResult[List[T]]](Right(Nil)) { (result, acc) =>
        for {
          list <- acc
          value <- result
        } yield value :: list
      }
    }
  }

  // Recovery strategies
  object Recovery {
    def withFallback[T](primary: => SzorkResult[T], fallback: => SzorkResult[T])(implicit logger: Logger): SzorkResult[T] = {
      primary match {
        case success @ Right(_) => success
        case Left(error) =>
          logger.warn(s"Primary operation failed (${error.message}), trying fallback...")
          fallback
      }
    }

    def withDefault[T](result: SzorkResult[T], default: T)(implicit logger: Logger): T = {
      result match {
        case Right(value) => value
        case Left(error) =>
          logger.warn(s"Operation failed (${error.message}), using default value")
          default
      }
    }
  }
}
```

### Phase 3: Refactoring Strategy

#### Step 1: Add new error types alongside existing code
1. Create the error hierarchy in `src/main/scala/org/llm4s/szork/error/`
2. Create the error handling utilities
3. Add implicit conversions for gradual migration

#### Step 2: Refactor by component priority
**High Priority** (Core game flow):
1. `GameEngine.scala` - Central game logic
2. `TypedWebSocketServer.scala` - User-facing errors
3. `StreamingAgent.scala` - LLM interaction

**Medium Priority** (Supporting services):
4. `ImageGeneration.scala` - Media errors
5. `MusicGeneration.scala` - Media errors
6. `TextToSpeech.scala` - Media errors
7. `GamePersistence.scala` - Persistence errors

**Low Priority** (Utilities):
8. `GameResponseParser.scala` - Validation errors
9. `AdventureGenerator.scala` - Generation errors
10. Other utility classes

#### Step 3: Migration approach for each file

Example refactoring for `GameEngine.scala`:

```scala
// BEFORE:
def processCommand(command: String, generateAudio: Boolean = true): Either[LLMError, GameResponse] = {
  // ... code ...
  agent.run(currentState) match {
    case Right(newState) =>
      // ... success case ...
    case Left(error) =>
      logger.error(s"[$sessionId] Error processing command: $error")
      Left(error)
  }
}

// AFTER:
import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._

def processCommand(command: String, generateAudio: Boolean = true): SzorkResult[GameResponse] = {
  // ... code ...
  val result = Transform.fromTry(Try(agent.run(currentState)))
    .flatMap(Transform.fromLLMError)

  Logging.logError(s"[$sessionId] Process command")(result)
    .flatMap { newState =>
      // ... success case ...
    }
}
```

### Phase 4: Add Retry and Recovery

Identify operations that should be retryable:
1. **Network calls** - API requests to OpenAI, Anthropic, etc.
2. **Media generation** - Image, audio, music generation
3. **File I/O** - Save/load operations
4. **WebSocket reconnection** - Connection recovery

Example implementation:
```scala
// In ImageGeneration.scala
def generateScene(prompt: String, style: String = ""): SzorkResult[String] = {
  Retry.withRetry(maxAttempts = 3, backoff = 2.seconds) {
    generateSceneInternal(prompt, style)
      .left.map(msg => ImageGenerationError(msg, retryable = true))
  }
}
```

### Phase 5: Testing Strategy

1. **Unit tests for error types**:
```scala
class ErrorHandlingSpec extends AnyFlatSpec with Matchers {
  "ErrorHandling.Retry" should "retry retryable errors" in {
    var attempts = 0
    val result = Retry.withRetry(maxAttempts = 3) {
      attempts += 1
      if (attempts < 3) Left(NetworkError("Failed", "test", retryable = true))
      else Right("Success")
    }
    result shouldBe Right("Success")
    attempts shouldBe 3
  }
}
```

2. **Integration tests for error propagation**
3. **Property-based tests for error transformations**

## Implementation Timeline

### Week 1: Foundation
- [ ] Create error hierarchy (2 hours)
- [ ] Create error handling utilities (3 hours)
- [ ] Write comprehensive tests (3 hours)
- [ ] Document usage patterns (1 hour)

### Week 2: Core Components
- [ ] Refactor GameEngine (4 hours)
- [ ] Refactor TypedWebSocketServer (3 hours)
- [ ] Refactor StreamingAgent (2 hours)
- [ ] Test integration (2 hours)

### Week 3: Supporting Services
- [ ] Refactor media generation classes (4 hours)
- [ ] Refactor persistence classes (2 hours)
- [ ] Add retry logic where appropriate (3 hours)
- [ ] Performance testing (2 hours)

### Week 4: Cleanup and Polish
- [ ] Refactor remaining utilities (3 hours)
- [ ] Remove old error handling code (1 hour)
- [ ] Update documentation (2 hours)
- [ ] Final testing and bug fixes (3 hours)

## Success Metrics

1. **Consistency**: 100% of error handling uses the new system
2. **Reliability**: 50% reduction in unhandled errors
3. **Maintainability**: 30% less error handling code overall
4. **User Experience**: Clear, actionable error messages
5. **Developer Experience**: Easy to add new error types and handlers

## Migration Checklist

For each file being refactored:
- [ ] Identify all error patterns in the file
- [ ] Map old errors to new error types
- [ ] Replace error creation with appropriate error type
- [ ] Add retry logic where beneficial
- [ ] Add proper logging with context
- [ ] Update tests to use new error types
- [ ] Document any special error handling

## Benefits of This Approach

1. **Type Safety**: Compiler ensures all errors are handled
2. **Consistency**: Single pattern throughout codebase
3. **Maintainability**: Easy to add new error types
4. **Debuggability**: Errors carry full context and stack traces
5. **User-Friendly**: Separate technical and user messages
6. **Resilience**: Built-in retry and recovery mechanisms
7. **Testability**: Easy to test error scenarios

## Notes

- Keep the old error handling during migration for backwards compatibility
- Use feature flags to gradually roll out new error handling
- Monitor error rates during migration to ensure no regressions
- Consider adding error metrics/monitoring in future phase