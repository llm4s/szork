package org.llm4s.szork.game

import org.llm4s.szork.media.MediaPlanner
import org.llm4s.szork.spi.{ImageClient, MusicClient, TTSClient}
import org.slf4j.LoggerFactory

/** A game's narration audio, scene images and background music, from whichever clients the server has.
  *
  * A missing client or a failed generation yields `None`: the game plays on without that medium. Nothing here reads the
  * game's mutable state, so images and music can be generated while the next turn is in progress.
  */
private[game] final class SceneMedia(
  sessionId: String,
  artStyle: Option[String],
  artStyleDescription: String,
  ttsClient: Option[TTSClient],
  narrationVoice: String,
  imageClient: Option[ImageClient],
  musicClient: Option[MusicClient]) {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)

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

  /** The image for `scene` (generated, or from the cache), Base64-encoded. */
  def image(scene: GameScene, gameId: Option[String]): Option[String] =
    imageClient.flatMap { client =>
      val styledPrompt = MediaPlanner.styledImagePrompt(artStyle, scene.imageDescription, artStyleDescription)
      logger.info(s"[$sessionId] Generating scene image for ${scene.locationId}: ${styledPrompt.take(100)}...")
      client.generateScene(styledPrompt, artStyle.getOrElse("fantasy"), gameId, Some(scene.locationId)) match {
        case Right(image) => Some(image)
        case Left(error) =>
          logger.error(s"[$sessionId] Failed to generate image: ${error.message}")
          None
      }
    }

  /** Background music for `scene` (generated, or from the cache) and its mood. */
  def music(scene: GameScene, gameId: Option[String]): Option[(String, String)] =
    musicClient.filter(_.isAvailable).flatMap { client =>
      logger.info(s"[$sessionId] Generating music for ${scene.locationId}: mood=${scene.musicMood}")
      client.generate(scene.musicMood, scene.musicDescription, gameId, Some(scene.locationId)) match {
        case Right(music) => Some((music, scene.musicMood))
        case Left(error) =>
          logger.warn(s"[$sessionId] Music generation failed: ${error.message}")
          None
      }
    }
}
