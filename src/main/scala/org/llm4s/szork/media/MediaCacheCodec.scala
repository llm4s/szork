package org.llm4s.szork.media

import ujson._
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets

case class ImageEntry(
  key: String,
  artStyle: String,
  provider: String,
  description: String,
  path: String,
  generatedAt: Long)
case class MusicEntry(key: String, mood: String, provider: String, description: String, path: String, generatedAt: Long)
case class LocationEntries(images: List[ImageEntry], music: List[MusicEntry])
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
          },
          "music" -> entries.music.map { e =>
            Obj(
              "key" -> e.key,
              "mood" -> e.mood,
              "provider" -> e.provider,
              "description" -> e.description,
              "path" -> e.path,
              "generatedAt" -> e.generatedAt.toDouble
            )
          }
        )
      })
    )

  private def readTs(v: Value): Long = v match {
    case Str(s) => s.toLongOption.getOrElse(0L)
    case Num(n) => n.toLong
    case _ => 0L
  }

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
          generatedAt = readTs(j("generatedAt"))
        )
      }
      val mus = v.obj.get("music").map(_.arr.toList).getOrElse(Nil).map { j =>
        MusicEntry(
          key = j("key").str,
          mood = j("mood").str,
          provider = j("provider").str,
          description = j("description").str,
          path = j("path").str,
          generatedAt = readTs(j("generatedAt"))
        )
      }
      loc -> LocationEntries(imgs, mus)
    }.toMap
    MediaIndex(parsed)
  }

  def load(path: Path): MediaIndex = {
    if (!Files.exists(path)) return MediaIndex(Map.empty)
    val jsonString = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    try fromJson(ujson.read(jsonString))
    catch { case _: Throwable => MediaIndex(Map.empty) }
  }

  def save(path: Path, index: MediaIndex): Unit = {
    val json = toJson(index)
    Files.write(path, ujson.write(json, indent = 2).getBytes(StandardCharsets.UTF_8))
  }
}
