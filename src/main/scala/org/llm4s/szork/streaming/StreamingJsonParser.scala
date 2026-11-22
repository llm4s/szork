package org.llm4s.szork.streaming

/** Extracts the `narrationText` string from JSON as it streams in, so the player sees narration while the rest of the
  * structured answer is still being written.
  *
  * Each chunk is appended; [[processChunk]] returns the narration decoded since the last call. Escape sequences are
  * decoded only once complete, so a chunk that ends inside one never emits a partial character.
  */
class StreamingJsonParser {
  private val accumulated = new StringBuilder()
  private var emitted = 0
  private val FieldStart = """"narrationText"\s*:\s*"""".r

  /** Appends `chunk`; returns the narration text that became available, if any. */
  def processChunk(chunk: String): Option[String] = {
    accumulated.append(chunk)
    val narration = decodedNarration
    if (narration.length > emitted) {
      val fresh = narration.substring(emitted)
      emitted = narration.length
      Some(fresh)
    } else None
  }

  /** The narration decoded so far (complete once the closing quote has arrived). */
  def narration: String = decodedNarration

  private def decodedNarration: String =
    FieldStart.findFirstMatchIn(accumulated) match {
      case None => ""
      case Some(m) =>
        val out = new StringBuilder()
        var i = m.end
        var done = false
        while (!done && i < accumulated.length)
          accumulated.charAt(i) match {
            case '"' => done = true
            case '\\' if i + 1 < accumulated.length =>
              accumulated.charAt(i + 1) match {
                case 'n' => out.append('\n'); i += 2
                case 't' => out.append('\t'); i += 2
                case 'r' => out.append('\r'); i += 2
                case 'b' => out.append('\b'); i += 2
                case 'f' => out.append('\f'); i += 2
                case 'u' if i + 6 <= accumulated.length =>
                  scala.util.Try(Integer.parseInt(accumulated.substring(i + 2, i + 6), 16)) match {
                    case scala.util.Success(code) => out.append(code.toChar); i += 6
                    case _ => done = true
                  }
                case 'u' => done = true // the \\uXXXX escape has not fully arrived
                case other => out.append(other); i += 2 // \\" \\\\ \\/
              }
            case '\\' => done = true // the escape has not fully arrived
            case c => out.append(c); i += 1
          }
        out.toString
    }
}
