package org.llm4s.szork

object ResponseInterpreter {
  // Helper to extract just narrationText from JSON when full parsing fails
  def extractNarrationTextFromJson(response: String): Option[String] = {
    try {
      if (response.trim.startsWith("{") && response.contains("narrationText")) {
        val json = ujson.read(response)
        json.obj.get("narrationText").map(_.str)
      } else None
    } catch {
      case _: Exception =>
        val pattern = """"narrationText"\s*:\s*"([^"]+(?:\\.[^"]+)*)"""".r
        pattern.findFirstMatchIn(response).map(_.group(1).replace("\\\"", "\"").replace("\\n", "\n"))
    }
  }

  def parseAndValidate(response: String): Either[List[String], GameResponseData] =
    GameResponseParser.parseAndValidate(response)
}

