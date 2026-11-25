package org.llm4s.szork.api

import org.llm4s.config.ConfigReader

/** The Dungeon Master's subagents (llm4s delegation, see `game.DmSubagents`). Part of [[SzorkConfig]]: SZORK_NPC_AGENT
  * and SZORK_HINT_AGENT turn the NPC dialogue and hint subagents on or off (on unless `false`, `off`, `0` or `no`), and
  * SZORK_SUBAGENT_MODEL names a model of the Dungeon Master's provider for them (unset: the Dungeon Master's model).
  */
case class SubagentConfig(
  npcDialogue: Boolean = true,
  hints: Boolean = true,
  model: Option[String] = None
)

object SubagentConfig {

  /** The subagent settings in `reader`; an empty value is ignored. */
  def load(reader: ConfigReader): SubagentConfig = {
    def setting(key: String): Option[String] = reader.get(key).map(_.trim).filter(_.nonEmpty)
    SubagentConfig(
      npcDialogue = SzorkConfig.flag(reader, "SZORK_NPC_AGENT"),
      hints = SzorkConfig.flag(reader, "SZORK_HINT_AGENT"),
      model = setting("SZORK_SUBAGENT_MODEL")
    )
  }
}
