package org.llm4s.szork

import org.slf4j.LoggerFactory
import ujson._

/**
 * Parses streaming response that has narration text followed by JSON.
 * Format:
 *   Narration text here...
 *   <<<JSON>>>
 *   {"responseType": "fullScene", ...} (without narrationText field)
 */
class StreamingTextParser {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)
  
  private val accumulated = new StringBuilder()
  private var jsonStarted = false
  private var narrationComplete = false
  private var lastNarrationPosition = 0
  private var capturedNarrationText: String = ""
  
  /**
   * Process a new chunk and extract narration text if available.
   * 
   * @param chunk The new chunk
   * @return Option containing any newly available narration text
   */
  def processChunk(chunk: String): Option[String] = {
    accumulated.append(chunk)
    val fullText = accumulated.toString
    
    if (!narrationComplete) {
      // Look for the JSON marker
      val jsonMarkerIndex = fullText.indexOf("<<<JSON>>>")
      
      if (jsonMarkerIndex >= 0) {
        // Found the marker - everything before it is narration
        narrationComplete = true
        jsonStarted = true
        
        val narration = fullText.substring(0, jsonMarkerIndex).trim
        capturedNarrationText = narration
        
        if (narration.length > lastNarrationPosition) {
          val newText = narration.substring(lastNarrationPosition)
          lastNarrationPosition = narration.length
          logger.debug(s"Extracted final narration chunk: ${newText.take(50)}...")
          return Some(newText)
        }
      } else {
        // No marker yet - stream what we have so far, but keep some buffer
        // in case "<<<JSON>>>" is split across chunks
        val safeLength = Math.max(0, fullText.length - 15) // Keep last 15 chars as buffer
        
        if (safeLength > lastNarrationPosition) {
          val newText = fullText.substring(lastNarrationPosition, safeLength)
          lastNarrationPosition = safeLength
          
          if (newText.nonEmpty) {
            logger.debug(s"Streaming narration chunk (${newText.length} chars): ${newText.take(50)}...")
            return Some(newText)
          }
        }
      }
    }
    
    None
  }
  
  /**
   * Get the JSON portion of the response (after <<<JSON>>> marker).
   * This method adds the narrationText field back into the JSON.
   * 
   * @return The JSON string with narrationText field added
   */
  def getJson(): Option[String] = {
    if (jsonStarted) {
      val fullText = accumulated.toString
      val jsonMarkerIndex = fullText.indexOf("<<<JSON>>>")
      if (jsonMarkerIndex >= 0) {
        val jsonStart = jsonMarkerIndex + "<<<JSON>>>".length
        val jsonStr = fullText.substring(jsonStart).trim
        
        if (jsonStr.nonEmpty) {
          try {
            // Parse the JSON and add the narrationText field
            val json = ujson.read(jsonStr)
            json("narrationText") = capturedNarrationText
            return Some(json.toString())
          } catch {
            case _: Exception =>
              // If parsing fails, return the original JSON
              return Some(jsonStr)
          }
        }
      }
    }
    None
  }
  
  /**
   * Get the complete narration text.
   */
  def getNarration(): Option[String] = {
    if (capturedNarrationText.nonEmpty) {
      Some(capturedNarrationText)
    } else {
      // Fallback: extract from accumulated text if JSON marker hasn't been found yet
      val fullText = accumulated.toString
      val jsonMarkerIndex = fullText.indexOf("<<<JSON>>>")
      
      if (jsonMarkerIndex >= 0) {
        Some(fullText.substring(0, jsonMarkerIndex).trim)
      } else if (fullText.nonEmpty) {
        // No JSON marker found yet, return what we have
        Some(fullText.trim)
      } else {
        None
      }
    }
  }
  
  /**
   * Reset the parser for a new response.
   */
  def reset(): Unit = {
    accumulated.clear()
    jsonStarted = false
    narrationComplete = false
    lastNarrationPosition = 0
    capturedNarrationText = ""
    logger.debug("StreamingTextParser reset")
  }
}