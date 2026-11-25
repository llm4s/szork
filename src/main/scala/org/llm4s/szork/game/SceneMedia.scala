package org.llm4s.szork.game

import org.llm4s.szork.adventures.AdventureLibrary
import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._
import org.llm4s.szork.media.{MediaCache, MediaPlanner, MediaRef}
import org.llm4s.szork.spi.{ImageClient, MusicClient, TTSClient}
import org.slf4j.LoggerFactory

/** A game's narration audio, scene images and background music, from whichever clients the server has.
  *
  * A place is drawn once and keeps its picture ([[image]]); music is one track per mood, shared by every game
  * ([[music]]). A missing client or a failed generation yields `None`: the game plays on without that medium. Nothing
  * here reads the game's mutable state (the adventure and outline are looked up when a picture is asked for), so images
  * and music can be generated while the next turn is in progress.
  *
  * @param library
  *   the adventure library, which keeps the pictures of a library adventure's locations and the mood tracks (None:
  *   every picture and track is made for this game alone)
  * @param adventure
  *   the id of the library adventure this game plays, if it plays one
  * @param outlineLocation
  *   the adventure outline's location with an id, if the outline has it
  */
private[game] final class SceneMedia(
  sessionId: String,
  artStyle: Option[String],
  artStyleDescription: String,
  ttsClient: Option[TTSClient],
  narrationVoice: String,
  imageClient: Option[ImageClient],
  musicClient: Option[MusicClient],
  library: Option[AdventureLibrary] = None,
  adventure: () => Option[String] = () => None,
  outlineLocation: String => Option[LocationOutline] = _ => None) {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)
  private val style = AdventureLibrary.styleOf(artStyle)

  /** `text` spoken in the narration voice, Base64-encoded. */
  def narrate(text: String): Option[String] =
    ttsClient.filter(_ => text.nonEmpty).flatMap { client =>
      val start = System.currentTimeMillis()
      client.synthesizeToBase64(text, narrationVoice) match {
        case Right(audio) =>
          logger.info(s"[$sessionId] Audio generated in ${System.currentTimeMillis() - start}ms (${text.length} chars)")
          Some(audio)
        case Left(error) =>
          logger.error(s"[$sessionId] Failed to generate audio: ${error.message}")
          None
      }
    }

  // The library adventure's location a scene is at: the library keeps its picture
  private def libraryPlace(locationId: String): Option[(AdventureLibrary, String, LocationOutline)] =
    for {
      lib <- library
      adventureId <- adventure()
      place <- outlineLocation(locationId)
    } yield (lib, adventureId, place)

  private def draw(description: String, gameId: Option[String], locationId: Option[String]): SzorkResult[String] =
    imageClient match {
      case None => Left(ConfigurationError("No image provider is configured"))
      case Some(client) =>
        val prompt = MediaPlanner.styledImagePrompt(artStyle, description, artStyleDescription)
        logger.info(s"[$sessionId] Generating image for ${locationId.getOrElse("a location")}: ${prompt.take(100)}...")
        client.generateScene(prompt, style, gameId, locationId)
    }

  /** The picture of `scene`'s location.
    *
    * A place is drawn once: the first picture made of it is the one shown on every later visit. For a location of a
    * library adventure that picture is the library's, drawn from the outline and shared by every game of the adventure;
    * for any other it is drawn from the scene's own description and kept for this game. With `update` - the Dungeon
    * Master saying the place now looks different, and how - a new picture is drawn for this game, and is the one this
    * game shows from then on.
    */
  def image(scene: GameScene, gameId: Option[String], update: Option[String] = None): Option[MediaRef] = {
    val location = scene.locationId
    val picture: SzorkResult[MediaRef] = update match {
      case Some(look) => draw(look, gameId, Some(location)).map(MediaRef.Inline(_))
      case None =>
        gameId.flatMap(MediaCache.latestImage(_, location, style)) match {
          case Some(current) => Right(MediaRef.Inline(current))
          case None =>
            libraryPlace(location) match {
              case Some((lib, adventureId, place)) =>
                lib.imageOr(adventureId, location, style)(draw(SceneMedia.outlineImage(place), None, None))
              case None => draw(scene.imageDescription, gameId, Some(location)).map(MediaRef.Inline(_))
            }
        }
    }
    picture.left.foreach(error => logger.error(s"[$sessionId] No image for $location: ${error.message}"))
    picture.toOption
  }

  /** Whether [[image]] can be expected to produce a picture: there is an image client, or the library has one. */
  def canPicture(scene: GameScene, update: Option[String]): Boolean =
    imageClient.isDefined || (update.isEmpty && libraryPlace(scene.locationId).exists { case (lib, adventureId, _) =>
      lib.image(adventureId, scene.locationId, style).isDefined
    })

  private def moodOf(scene: GameScene): String =
    DungeonMasterOutput.MusicMoods
      .find(_.equalsIgnoreCase(scene.musicMood.trim))
      .getOrElse(DungeonMasterOutput.DefaultMusicMood)

  /** Background music for `scene` and its mood.
    *
    * With a library, music is one track per mood: made the first time any game needs the mood, then the library's.
    * Without one, a track is made for this location from the scene's description.
    */
  def music(scene: GameScene, gameId: Option[String]): Option[(MediaRef, String)] = {
    val client = musicClient.filter(_.isAvailable)
    val track: Option[SzorkResult[(MediaRef, String)]] = library match {
      case Some(lib) =>
        val mood = moodOf(scene)
        client match {
          case Some(c) =>
            Some(
              lib
                .musicOr(mood) {
                  logger.info(s"[$sessionId] Generating the $mood track")
                  c.generate(mood, "", None, None)
                }
                .map(_ -> mood))
          case None => lib.music(mood).map(stored => Right(stored -> mood))
        }
      case None =>
        client.map { c =>
          logger.info(s"[$sessionId] Generating music for ${scene.locationId}: mood=${scene.musicMood}")
          c.generate(scene.musicMood, scene.musicDescription, gameId, Some(scene.locationId))
            .map(MediaRef.Inline(_) -> scene.musicMood)
        }
    }
    track.flatMap {
      case Right(music) => Some(music)
      case Left(error) =>
        logger.warn(s"[$sessionId] Music generation failed: ${error.message}")
        None
    }
  }

  /** Whether [[music]] can be expected to produce a track: there is a music client, or the library has the mood's. */
  def canPlay(scene: GameScene): Boolean =
    musicClient.exists(_.isAvailable) || library.exists(_.music(moodOf(scene)).isDefined)
}

object SceneMedia {

  /** What a picture of an outline's location is drawn from: the [[ArtDirector]]'s brief, or for a location that has
    * none the description the designer wrote for the player (which draws worse: it is not written to be drawn). The art
    * style is added by [[MediaPlanner.styledImagePrompt]].
    */
  def outlineImage(place: LocationOutline): String =
    place.image.map(_.trim).filter(_.nonEmpty).getOrElse(s"${place.name}. ${place.description}")
}
