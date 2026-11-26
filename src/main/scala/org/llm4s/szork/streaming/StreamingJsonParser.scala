package org.llm4s.szork.streaming

import org.llm4s.szork.game.DungeonMasterOutput

/** Extracts the `narrationText` string from a Dungeon Master answer as it streams in, so the player sees narration
  * while the rest of the structured answer is still being written.
  *
  * Each chunk is appended; [[processChunk]] returns the narration that became available since the last call. The text
  * so far is read with llm4s's `PartialJson` (from its first brace, as [[DungeonMasterOutput.jsonIn]] reads it), which
  * leaves out an escape sequence that has not fully arrived, so a chunk that ends inside one never emits a partial
  * character; text that is not valid JSON ends what is read. Once a field after the narration has started, the
  * narration is whole and later chunks are not read: the schema puts `narrationText` first, so the text is re-read only
  * while the narration is being written.
  */
class StreamingJsonParser {
  private val accumulated = new StringBuilder()
  private var decoded = ""
  private var complete = false

  /** Appends `chunk`; returns the narration text that became available, if any. */
  def processChunk(chunk: String): Option[String] =
    if (complete) None
    else {
      accumulated.append(chunk)
      val emitted = decoded.length
      DungeonMasterOutput.jsonIn(accumulated.toString, partialStrings = true).flatMap(_.objOpt).foreach { fields =>
        fields.get(StreamingJsonParser.Field).flatMap(_.strOpt).foreach(decoded = _)
        complete = fields.keys.dropWhile(_ != StreamingJsonParser.Field).size > 1
      }
      Option.when(decoded.length > emitted)(decoded.substring(emitted))
    }

  /** The narration decoded so far (complete once a later field, or the end of the answer, has arrived). */
  def narration: String = decoded
}

object StreamingJsonParser {

  /** The field streamed to the player. */
  private val Field = "narrationText"
}
