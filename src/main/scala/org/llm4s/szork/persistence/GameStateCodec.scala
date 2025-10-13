package org.llm4s.szork.persistence

import org.llm4s.szork.api.{GameTheme, ArtStyle}
import org.llm4s.szork.game.{GameScene, AdventureGenerator, Exit}
import ujson._

object GameStateCodec {
  private def ts(n: Long): Num = Num(n.toDouble)

  def toJson(state: GameState): Value =
    Obj(
      "gameId" -> state.gameId,
      "theme" -> state.theme.map(t => Obj("id" -> t.id, "name" -> t.name, "prompt" -> t.prompt)).getOrElse(Null),
      "artStyle" -> state.artStyle.map(a => Obj("id" -> a.id, "name" -> a.name)).getOrElse(Null),
      "currentScene" -> state.currentScene
        .map { scene =>
          Obj(
            "locationId" -> scene.locationId,
            "locationName" -> scene.locationName,
            "narrationText" -> scene.narrationText,
            "imageDescription" -> scene.imageDescription,
            "musicDescription" -> scene.musicDescription,
            "musicMood" -> scene.musicMood,
            "exits" -> scene.exits.map { e =>
              val o = Obj("direction" -> e.direction, "locationId" -> e.locationId)
              e.description.foreach(d => o("description") = d)
              o
            },
            "items" -> scene.items,
            "npcs" -> scene.npcs
          )
        }
        .getOrElse(Null),
      "visitedLocations" -> state.visitedLocations.toList,
      "conversationHistory" -> state.conversationHistory.map(e =>
        Obj("role" -> e.role, "content" -> e.content, "timestamp" -> ts(e.timestamp))),
      "inventory" -> state.inventory,
      "createdAt" -> ts(state.createdAt),
      "lastSaved" -> ts(state.lastSaved),
      "lastPlayed" -> ts(state.lastPlayed),
      "totalPlayTime" -> ts(state.totalPlayTime),
      "adventureTitle" -> state.adventureTitle.map(Str(_)).getOrElse(Null),
      "adventureOutline" -> state.adventureOutline.map(AdventureGenerator.outlineToJson).getOrElse(Null),
      "agentMessages" -> state.agentMessages,
      "mediaCache" -> Obj.from(state.mediaCache.map { case (loc, m) =>
        loc -> Obj(
          "locationId" -> m.locationId,
          "imagePrompt" -> m.imagePrompt.map(Str(_)).getOrElse(Null),
          "imageCacheId" -> m.imageCacheId.map(Str(_)).getOrElse(Null),
          "musicPrompt" -> m.musicPrompt.map(Str(_)).getOrElse(Null),
          "musicCacheId" -> m.musicCacheId.map(Str(_)).getOrElse(Null),
          "generatedAt" -> ts(m.generatedAt)
        )
      }),
      "systemPrompt" -> state.systemPrompt.map(Str(_)).getOrElse(Null)
    )

  private def readTs(v: Value): Long = v match {
    case Str(s) => s.toLong
    case Num(n) => n.toLong
    case _ => 0L
  }

  def fromJson(json: Value): GameState =
    GameState(
      gameId = json("gameId").str,
      theme = json("theme") match {
        case Null => None
        case t => Some(GameTheme(t("id").str, t("name").str, t("prompt").str))
      },
      artStyle = json("artStyle") match {
        case Null => None
        case a => Some(ArtStyle(a("id").str, a("name").str))
      },
      currentScene = json("currentScene") match {
        case Null => None
        case s =>
          Some(
            GameScene(
              locationId = s("locationId").str,
              locationName = s("locationName").str,
              narrationText = s("narrationText").str,
              imageDescription = s("imageDescription").str,
              musicDescription = s("musicDescription").str,
              musicMood = s("musicMood").str,
              exits = s("exits").arr
                .map(e =>
                  Exit(
                    direction = e("direction").str,
                    locationId = e("locationId").str,
                    description = e.obj.get("description").map(_.str)
                  ))
                .toList,
              items = s("items").arr.map(_.str).toList,
              npcs = s("npcs").arr.map(_.str).toList
            ))
      },
      visitedLocations = json("visitedLocations").arr.map(_.str).toSet,
      conversationHistory = json("conversationHistory").arr
        .map(e => ConversationEntry(e("role").str, e("content").str, readTs(e("timestamp"))))
        .toList,
      inventory = json.obj.get("inventory").map(_.arr.map(_.str).toList).getOrElse(Nil),
      createdAt = readTs(json("createdAt")),
      lastSaved = readTs(json("lastSaved")),
      lastPlayed = json.obj.get("lastPlayed").map(readTs).getOrElse(readTs(json("lastSaved"))),
      totalPlayTime = json.obj.get("totalPlayTime").map(readTs).getOrElse(0L),
      adventureTitle =
        json.obj.get("adventureTitle").flatMap { case Null => None; case s => Some(s.str) }.filter(_.nonEmpty),
      adventureOutline = json.obj.get("adventureOutline").flatMap {
        case Null => None; case v => Some(GamePersistence.parseAdventureOutlineFromJson(v))
      },
      agentMessages = json.obj.get("agentMessages").map(_.arr.toList).getOrElse(Nil),
      mediaCache = json.obj
        .get("mediaCache")
        .map { co =>
          co.obj.map { case (loc, entry) =>
            loc -> MediaCacheEntry(
              locationId = entry("locationId").str,
              imagePrompt = entry.obj.get("imagePrompt").flatMap { case Null => None; case s => Some(s.str) },
              imageCacheId = entry.obj.get("imageCacheId").flatMap { case Null => None; case s => Some(s.str) },
              musicPrompt = entry.obj.get("musicPrompt").flatMap { case Null => None; case s => Some(s.str) },
              musicCacheId = entry.obj.get("musicCacheId").flatMap { case Null => None; case s => Some(s.str) },
              generatedAt = readTs(entry("generatedAt"))
            )
          }.toMap
        }
        .getOrElse(Map.empty),
      systemPrompt = json.obj.get("systemPrompt").flatMap { case Null => None; case s => Some(s.str) }
    )

  def metadataFromJson(json: Value): GameMetadata = {
    def ts(v: Value): Long = v match {
      case Str(s) => s.toLong
      case Num(n) => n.toLong
      case _ => 0L
    }
    val themeName: String = json("theme") match {
      case Null => "Unknown"
      case t => t("name").str
    }

    val artStyleName: String = json("artStyle") match {
      case Null => "Unknown"
      case a => a("name").str
    }

    val titleFromJson: Option[String] =
      json.obj.get("adventureTitle").flatMap { case Null => None; case s => Some(s.str) }.filter(_.nonEmpty)

    // createdAt may be missing in legacy saves; fall back to lastSaved
    val created: Long = json.obj
      .get("createdAt")
      .map(ts)
      .filter(_ > 0L)
      .getOrElse(ts(json("lastSaved")))

    GameMetadata(
      gameId = json("gameId").str,
      theme = themeName,
      artStyle = artStyleName,
      adventureTitle = titleFromJson.getOrElse(themeName),
      createdAt = created,
      lastSaved = ts(json("lastSaved")),
      lastPlayed = json.obj.get("lastPlayed").map(ts).getOrElse(ts(json("lastSaved"))),
      totalPlayTime = json.obj.get("totalPlayTime").map(ts).getOrElse(0L)
    )
  }
}
