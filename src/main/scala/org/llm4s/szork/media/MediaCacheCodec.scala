package org.llm4s.szork.media

import ujson.*
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets

case class ImageEntry(
  key: String,
  artStyle: String,
  provider: String,
  description: String,
  path: String,
  generatedAt: Long)
case class LocationEntries(images: List[ImageEntry])
case class MediaIndex(locations: Map[String, LocationEntries])

object MediaCacheCodec {
  private def toJson(index: MediaIndex): Value =
    Obj(
      "locations" -> Obj.from(index.locations.map { case (loc, entries) =>
        loc -> Obj(
          "images" -> entries.images.map { e =>
            Obj(
              "key" -> e.key,
              "artStyle" -> e.artStyle,
              "provider" -> e.provider,
              "description" -> e.description,
              "path" -> e.path,
              "generatedAt" -> e.generatedAt.toDouble
            )
          }
        )
      })
    )

  private def fromJson(json: Value): MediaIndex = {
    val locs = json.obj.get("locations").map(_.obj).getOrElse(Map.empty[String, Value])
    val parsed = locs.map { case (loc, v) =>
      val imgs = v.obj.get("images").map(_.arr.toList).getOrElse(Nil).map { j =>
        ImageEntry(
          key = j("key").str,
          artStyle = j("artStyle").str,
          provider = j("provider").str,
          description = j("description").str,
          path = j("path").str,
          generatedAt = j("generatedAt").num.toLong
        )
      }
      loc -> LocationEntries(imgs)
    }.toMap
    MediaIndex(parsed)
  }

  /** The index at `path`: empty if there is none, or None if it exists but cannot be read. */
  def load(path: Path): Option[MediaIndex] =
    if (!Files.exists(path)) Some(MediaIndex(Map.empty))
    else
      try Some(fromJson(ujson.read(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))))
      catch { case _: Exception => None }

  /** Writes the index atomically: readers see the old index or the new one, never a partial file. */
  def save(path: Path, index: MediaIndex): Unit =
    AtomicFiles.write(path, ujson.write(toJson(index), indent = 2).getBytes(StandardCharsets.UTF_8))
}
