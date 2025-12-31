package org.llm4s.szork.error

import org.llm4s.error.{LLMError => LLM4SError}

/** Base trait for all Szork application errors. Provides consistent error handling across the application.
  */
sealed trait SzorkError {
  def message: String
  def cause: Option[Throwable] = None
  def retryable: Boolean = false
  def userMessage: String = message // Safe message to show users

  /** Convert to a simple string for backwards compatibility */
  def toSimpleString: String = message
}

// Category: LLM and AI-related errors
case class AIError(
  message: String,
  override val cause: Option[Throwable] = None,
  llmError: Option[LLM4SError] = None,
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

// Category: Parsing errors
case class ParseError(
  message: String,
  override val cause: Option[Throwable] = None,
  input: Option[String] = None
) extends SzorkError {
  override def userMessage: String = "Unable to understand the response format."
}

// Category: Persistence errors
case class PersistenceError(
  message: String,
  override val cause: Option[Throwable] = None,
  override val retryable: Boolean = true,
  operation: String = "unknown" // "save", "load", "delete"
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
  connectionId: Option[String] = None,
  override val cause: Option[Throwable] = None
) extends SzorkError {
  override def userMessage: String = "Connection lost. Please refresh the page."
}

// Category: Not found errors
case class NotFoundError(
  message: String
) extends SzorkError {
  override def retryable: Boolean = false
  override def userMessage: String = "The requested item was not found."
}

// Category: Cache errors
case class CacheError(
  message: String,
  override val cause: Option[Throwable] = None
) extends SzorkError {
  override def retryable: Boolean = false
  override def userMessage: String = "Cache operation failed."
}

// Category: LLM errors (alias for AIError for compatibility)
case class LLMError(
  message: String,
  override val cause: Option[Throwable] = None,
  override val retryable: Boolean = true
) extends SzorkError {
  override def userMessage: String = "The AI service is temporarily unavailable."
}

// Category: Session errors
case class SessionError(
  message: String,
  sessionId: Option[String] = None
) extends SzorkError {
  override def userMessage: String = "Your session has expired. Please start a new game."
}

// Companion object with factory methods
object SzorkError {

  /** Create an error from a simple string message */
  def fromString(message: String): SzorkError =
    GameStateError(message)

  /** Create an error from a throwable */
  def fromThrowable(throwable: Throwable): SzorkError =
    GameStateError(throwable.getMessage, Some(throwable))

  /** Create an error from an LLM error */
  def fromLLMError(error: LLM4SError): SzorkError =
    AIError(error.message, retryable = true, llmError = Some(error))

  /** Create a validation error from a list of issues */
  def validation(issues: List[String]): SzorkError =
    ValidationError(issues)

  /** Create a validation error from a single issue */
  def validation(issue: String): SzorkError =
    ValidationError(List(issue))
}
