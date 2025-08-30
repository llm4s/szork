package org.llm4s.szork

import org.slf4j.LoggerFactory

/**
 * Progressively parses streaming JSON to extract narrationText as it arrives.
 * Handles incomplete JSON by attempting to close quotes and braces.
 */
class StreamingJsonParser {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)
  
  private val accumulatedJson = new StringBuilder()
  private var lastExtractedPosition = 0
  private var inNarrationText = false
  private var narrationTextStart = -1
  private var bracketDepth = 0
  private var inQuotes = false
  private var escapeNext = false
  
  /**
   * Process a new chunk of JSON and attempt to extract any new narration text.
   * 
   * @param chunk The new JSON chunk
   * @return Option containing any newly available narration text
   */
  def processChunk(chunk: String): Option[String] = {
    accumulatedJson.append(chunk)
    val fullJson = accumulatedJson.toString
    
    // Only process if we have enough JSON to potentially contain narrationText
    if (fullJson.contains("\"narrationText\"")) {
      extractNarrationText(fullJson)
    } else {
      None
    }
  }
  
  /**
   * Extract narrationText from potentially incomplete JSON.
   * Returns only the newly extracted portion since last call.
   */
  private def extractNarrationText(json: String): Option[String] = {
    // Look for "narrationText": " pattern
    val narrationPattern = """"narrationText"\s*:\s*"""".r
    
    narrationPattern.findFirstMatchIn(json) match {
      case Some(m) =>
        val startPos = m.end
        
        // Check if we're past where we've already extracted
        if (startPos < lastExtractedPosition) {
          // We've already processed past this point
          return None
        }
        
        // Find the end of the narrationText value
        var pos = startPos
        var inString = true
        var escaped = false
        val textBuilder = new StringBuilder()
        
        while (pos < json.length && inString) {
          val char = json.charAt(pos)
          
          if (escaped) {
            // Handle escaped characters
            char match {
              case 'n' => textBuilder.append('\n')
              case 't' => textBuilder.append('\t')
              case 'r' => textBuilder.append('\r')
              case '"' => textBuilder.append('"')
              case '\\' => textBuilder.append('\\')
              case _ => textBuilder.append(char)
            }
            escaped = false
          } else if (char == '\\') {
            escaped = true
          } else if (char == '"') {
            // End of string (unless it's escaped, which we already handled)
            inString = false
          } else {
            textBuilder.append(char)
          }
          
          pos += 1
        }
        
        // Extract the new portion of text
        val fullText = textBuilder.toString
        
        // Calculate how much of this text is new
        val alreadyExtracted = if (lastExtractedPosition > startPos) {
          Math.min(fullText.length, lastExtractedPosition - startPos)
        } else {
          0
        }
        
        if (alreadyExtracted < fullText.length) {
          val newText = fullText.substring(alreadyExtracted)
          lastExtractedPosition = startPos + fullText.length
          
          if (newText.nonEmpty) {
            logger.debug(s"Extracted new narration text (${newText.length} chars): ${newText.take(50)}...")
            return Some(newText)
          }
        }
        
        None
        
      case None =>
        // No narrationText field found yet
        None
    }
  }
  
  /**
   * Attempt to extract narrationText by adding closing quotes/braces if needed.
   * Called when streaming is complete but we might have incomplete JSON.
   */
  def finalizeExtraction(): Option[String] = {
    val json = accumulatedJson.toString
    
    // First try normal extraction
    extractNarrationText(json).orElse {
      // If that fails, try adding closing quote and braces
      val withClosing = json + "\"}"
      extractNarrationText(withClosing)
    }
  }
  
  /**
   * Reset the parser for a new response.
   */
  def reset(): Unit = {
    accumulatedJson.clear()
    lastExtractedPosition = 0
    inNarrationText = false
    narrationTextStart = -1
    bracketDepth = 0
    inQuotes = false
    escapeNext = false
    logger.debug("StreamingJsonParser reset")
  }
  
  /**
   * Get the full accumulated JSON so far (for debugging).
   */
  def getAccumulatedJson: String = accumulatedJson.toString
}