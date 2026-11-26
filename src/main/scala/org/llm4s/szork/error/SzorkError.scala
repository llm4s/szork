package org.llm4s.szork.error

import org.llm4s.error.{LLMError => LLM4SError}

/** Base trait for all Szork application errors. Provides consistent error handling across the application.
  *
  * `message` is for the server log: it may carry a provider's own text or a file path. `userMessage` is what the player
  * is shown, and says what they can do about it.
  */
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
  llmError: Option[LLM4SError] = None,
  override val retryable: Boolean = true
) extends SzorkError {
  override def userMessage: String = llmError.fold(AIError.CouldNotAnswer)(AIError.reason)
}

object AIError {
  val CouldNotAnswer = "The Dungeon Master could not answer. Send the command again; the server log has the details."

  /** What to tell the player about a turn that failed with `error`. llm4s reports a failed model call as the graph node
    * that failed, with the provider's error as its cause, so the cause's type decides. The texts are the ones the
    * README's Troubleshooting section explains.
    */
  def reason(error: LLM4SError): String = {
    import org.llm4s.agent.graph.GraphError
    import org.llm4s.error.{
      AuthenticationError,
      InvalidInputError,
      NetworkError => LLMNetworkError,
      NotFoundError => LLMNotFoundError,
      RateLimitError,
      TimeoutError,
      ValidationError => LLMValidationError
    }
    error match {
      case GraphError.NodeFailed(_, _, cause) => reason(cause)
      case GraphError.ToolFailed(_, _, cause) => reason(cause)
      // Szork's own: its text names how the turn ended, and nothing of the provider's
      case unfinished: org.llm4s.szork.game.GameEngine.TurnNotCompleted =>
        s"${unfinished.message}. The game is unchanged; send the command again."
      case _: AuthenticationError =>
        "The AI service rejected the server's API key. Check the key in the server's .env file."
      case _: RateLimitError => "The AI service is busy or rate-limiting requests. Wait a moment and try again."
      case _: TimeoutError | _: LLMNetworkError =>
        "The AI service could not be reached. Check the connection and try again."
      case _: InvalidInputError | _: LLMNotFoundError | _: LLMValidationError =>
        "The AI service rejected the request; the configured model may not be supported. See the server log."
      case _ => CouldNotAnswer
    }
  }
}

// Category: Media generation errors
sealed trait MediaError extends SzorkError

case class ImageGenerationError(
  message: String,
  override val cause: Option[Throwable] = None,
  override val retryable: Boolean = true
) extends MediaError {
  override def userMessage: String = "The picture could not be drawn; the server log has the details."
}

case class AudioGenerationError(
  message: String,
  override val cause: Option[Throwable] = None,
  override val retryable: Boolean = true
) extends MediaError {
  override def userMessage: String = "The narration could not be spoken; the server log has the details."
}

case class MusicGenerationError(
  message: String,
  override val cause: Option[Throwable] = None,
  override val retryable: Boolean = true
) extends MediaError {
  override def userMessage: String = "The music could not be made; the server log has the details."
}

// Category: Validation errors
/** Input that could not be used; each issue is written for the player. */
case class ValidationError(
  issues: List[String]
) extends SzorkError {
  override def message: String = s"Validation failed: ${issues.mkString(", ")}"
  override def userMessage: String = issues.mkString(" ")
}

// Category: Parsing errors
case class ParseError(
  message: String,
  override val cause: Option[Throwable] = None,
  input: Option[String] = None
) extends SzorkError {
  override def userMessage: String = "The Dungeon Master's answer could not be read. Send the command again."
}

// Category: Persistence errors
case class PersistenceError(
  message: String,
  override val cause: Option[Throwable] = None,
  override val retryable: Boolean = true,
  operation: String = "unknown" // "save", "load", "delete"
) extends SzorkError {
  override def userMessage: String = operation match {
    case "save" => "The game could not be saved; the server log has the details."
    case "load" => "The saved game could not be read; the server log has the details."
    case "delete" => "The saved game could not be deleted; the server log has the details."
    case _ => "The save directory could not be used; the server log has the details."
  }
}

// Category: Network/External service errors
case class NetworkError(
  message: String,
  service: String, // "openai", "anthropic", "replicate", etc.
  override val cause: Option[Throwable] = None,
  override val retryable: Boolean = true
) extends SzorkError {
  override def userMessage: String =
    s"The $service service could not be reached or refused the request. Check the connection and the server log."
}

// Category: Configuration errors
case class ConfigurationError(
  message: String
) extends SzorkError {
  override def userMessage: String =
    s"The server is not set up for this: $message. See the README's configuration table."
}

// Category: Not found errors
/** Something asked for by id is not there; the message names only the id, so the player is shown it. */
case class NotFoundError(
  message: String
) extends SzorkError {
  override def userMessage: String = message
}

// Category: Cache errors
case class CacheError(
  message: String,
  override val cause: Option[Throwable] = None
) extends SzorkError {
  override def userMessage: String = "A media file could not be stored; the server log has the details."
}

// Category: adventure generation errors
/** An adventure outline could not be generated; `reason` is what the player is told, which says what went wrong as far
  * as it is known (a bad key and a malformed design call for different things).
  */
case class AdventureGenerationError(
  message: String,
  reason: String,
  override val retryable: Boolean = true
) extends SzorkError {
  override def userMessage: String = reason
}
