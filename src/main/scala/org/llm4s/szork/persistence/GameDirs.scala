package org.llm4s.szork.persistence

import java.nio.file.Path
import org.llm4s.szork.game.IdGenerator

/** Resolves per-game directories. Game ids come from clients, so every directory a game id reaches goes through here:
  * the id must have the generated format, and the result must be a direct child of `root`.
  */
object GameDirs {

  /** The directory for `gameId` under `root`; throws IllegalArgumentException for an invalid id. */
  def resolve(root: Path, gameId: String): Path = {
    require(IdGenerator.isValidGameId(gameId), s"Invalid game id: '${gameId.take(40)}'")
    val base = root.toAbsolutePath.normalize()
    val dir = base.resolve(gameId).normalize()
    require(dir.getParent == base, s"Invalid game id: '${gameId.take(40)}'")
    dir
  }
}
