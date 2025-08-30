package org.llm4s.szork

import org.llm4s.llmconnect.model.StreamedChunk
import org.slf4j.LoggerFactory

/**
 * Detects whether streaming chunks are user-facing text or tool calls.
 * This is critical for ensuring users only see narrative text, not internal
 * agent operations like inventory management or scene parsing.
 */
class StreamingResponseDetector {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)
  
  sealed trait ResponseType
  case object UserText extends ResponseType
  case object ToolCall extends ResponseType
  case object Unknown extends ResponseType
  
  private var detectedType: ResponseType = Unknown
  private var chunkCount: Int = 0
  private val earlyDetectionBuffer = new StringBuilder()
  
  /**
   * Process a streaming chunk and determine if it should be shown to the user.
   * 
   * @param chunk The streaming chunk from the LLM
   * @return A tuple of (ResponseType, Option[text to stream to user])
   */
  def processChunk(chunk: StreamedChunk): (ResponseType, Option[String]) = {
    chunkCount += 1
    
    // Early detection based on chunk structure
    if (detectedType == Unknown) {
      // Check if this chunk helps us determine the type
      if (chunk.content.isDefined && chunk.toolCall.isEmpty) {
        // Detected text content - this will be user-facing
        logger.debug(s"Detected UserText response at chunk $chunkCount")
        detectedType = UserText
        
        // Return any buffered content plus current chunk
        val buffered = earlyDetectionBuffer.toString
        earlyDetectionBuffer.clear()
        val textToReturn = buffered + chunk.content.get
        
        return (UserText, if (textToReturn.nonEmpty) Some(textToReturn) else None)
        
      } else if (chunk.toolCall.isDefined) {
        // Detected tool call - don't stream this to user
        logger.debug(s"Detected ToolCall response at chunk $chunkCount: ${chunk.toolCall.get.name}")
        detectedType = ToolCall
        earlyDetectionBuffer.clear() // Clear any buffered content
        return (ToolCall, None)
        
      } else if (chunk.content.isDefined) {
        // We have content but aren't sure yet - buffer it
        logger.debug(s"Buffering chunk $chunkCount while detecting type")
        earlyDetectionBuffer.append(chunk.content.get)
        
        // If we've buffered several chunks and still don't know, assume it's text
        if (chunkCount >= 3) {
          logger.debug(s"Assuming UserText after $chunkCount chunks")
          detectedType = UserText
          val buffered = earlyDetectionBuffer.toString
          earlyDetectionBuffer.clear()
          return (UserText, if (buffered.nonEmpty) Some(buffered) else None)
        }
        
        return (Unknown, None)
      }
    }
    
    // Once type is detected, handle accordingly
    detectedType match {
      case UserText =>
        // Stream text content to user
        (UserText, chunk.content)
        
      case ToolCall =>
        // Never stream tool calls to user
        (ToolCall, None)
        
      case Unknown =>
        // Shouldn't happen after detection, but handle gracefully
        logger.warn(s"Still Unknown after $chunkCount chunks")
        (Unknown, None)
    }
  }
  
  /**
   * Reset the detector for a new response.
   * Called when starting to process a new agent response.
   */
  def reset(): Unit = {
    detectedType = Unknown
    chunkCount = 0
    earlyDetectionBuffer.clear()
    logger.debug("StreamingResponseDetector reset")
  }
  
  /**
   * Get the current detected type
   */
  def getDetectedType: ResponseType = detectedType
  
  /**
   * Check if the response type has been determined
   */
  def isDetected: Boolean = detectedType != Unknown
}