package org.llm4s.szork.persistence

import org.llm4s.szork.api.{GameTheme, ArtStyle}
import org.llm4s.szork.game.{GameScene, AdventureGenerator, Exit, WorldSnapshot}
import ujson.*

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
      "inventory" -> state.inventory,
      "createdAt" -> ts(state.createdAt),
      "lastSaved" -> ts(state.lastSaved),
      "lastPlayed" -> ts(state.lastPlayed),
      "totalPlayTime" -> ts(state.totalPlayTime),
      "adventureTitle" -> state.adventureTitle.map(Str(_)).getOrElse(Null),
      "adventureId" -> state.adventureId.map(Str(_)).getOrElse(Null),
      "adventureOutline" -> state.adventureOutline.map(AdventureGenerator.outlineToJson).getOrElse(Null),
      "agentMessages" -> state.agentMessages.map(MessageCodec.toJson),
      "worldState" -> upickle.default.writeJs(state.worldState)
    )

  private def readTs(v: Value): Long = v.num.toLong

  def fromJson(json: Value): GameState = {
    val adventureOutline = json.obj.get("adventureOutline").flatMap {
      case Null => None; case v => Some(AdventureGenerator.outlineFromJson(v))
    }
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
      inventory = json("inventory").arr.map(_.str).toList,
      createdAt = readTs(json("createdAt")),
      lastSaved = readTs(json("lastSaved")),
      lastPlayed = readTs(json("lastPlayed")),
      totalPlayTime = readTs(json("totalPlayTime")),
      adventureTitle =
        json.obj.get("adventureTitle").flatMap { case Null => None; case s => Some(s.str) }.filter(_.nonEmpty),
      adventureId = json.obj.get("adventureId").flatMap(_.strOpt),
      adventureOutline = adventureOutline,
      agentMessages = json("agentMessages").arr.map(MessageCodec.fromJson).toList,
      worldState = upickle.default.read[WorldSnapshot](json("worldState"))
    )
  }
}
