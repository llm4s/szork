package org.llm4s.szork.media

import java.nio.file.{Files, Path, StandardCopyOption}

object AtomicFiles {

  /** Writes `bytes` to `path` via a temporary file in the same directory and an atomic rename. */
  def write(path: Path, bytes: Array[Byte]): Unit = {
    val tmp = Files.createTempFile(path.getParent, s".${path.getFileName}", ".tmp")
    try {
      Files.write(tmp, bytes)
      Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally Files.deleteIfExists(tmp)
  }
}
