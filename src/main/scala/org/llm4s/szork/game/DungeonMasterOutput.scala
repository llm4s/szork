package org.llm4s.szork.game

import org.llm4s.schema.{PartialJson, SchemaOf, description}
import org.llm4s.toolapi.SchemaDefinition
import upickle.default.ReadWriter

import scala.util.Try

/** The structured answer the Dungeon Master gives every turn ([[DungeonMasterOutput.Answer]]), its llm4s output schema,
  * and the one definition of the values an answer is held to: exit directions, music moods and narration lengths.
  *
  * The schema is derived from the type (llm4s-schema-derivation's `SchemaOf`), so what the model is asked for and what
  * the game reads cannot drift apart. The agent is built with `withOutput(schema)` and
  * `withOutputCheck(GameResponseValidator.problems)`: llm4s states the schema in the system prompt on every model call,
  * checks each final answer against it, reads it as an [[Answer]], runs the game's check, and asks the model to correct
  * an answer that fails any of them once; a second invalid answer fails the turn. A turn is either a `fullScene` (the
  * player is somewhere new, or looked around) or a `simple` response; fields a simple response has no use for are null.
  * `narrationText` comes first so it can be streamed to the player while the rest is written.
  *
  * The two kinds are one object rather than a JSON Schema union (`anyOf`): llm4s requires an output schema's root to be
  * an object, and the fields each kind must give are checked by [[GameResponseValidator.problems]], in the same retry.
  *
  * A `@description` must be one string literal: `SchemaOf` reads only a literal, and silently describes a field by its
  * name when its description is built (concatenated or interpolated). So the narration limits are written out in the
  * narration's description, and `DungeonMasterOutputSpec` checks they are [[MaxSceneNarrationChars]] and
  * [[MaxSimpleNarrationChars]], and that every field has its description.
  */
object DungeonMasterOutput {

  /** What kind of answer it is. The case names are the values written. */
  @description("fullScene for a new location or a fresh look around; simple for anything else")
  enum ResponseType derives ReadWriter, SchemaOf {
    case fullScene, simple
  }

  /** The directions an exit may take. The case names are the values written. */
  @description("Direction of the exit")
  enum Direction derives ReadWriter, SchemaOf {
    case north, south, east, west, up, down, in, out, left, right, forward, back
  }

  /** The moods a scene's background music may have; `MusicGeneration.MusicMoods` has settings for each. The case names
    * are the values written.
    */
  @description("fullScene: the mood of the scene's background music; simple: null")
  enum MusicMood derives ReadWriter, SchemaOf {
    case entrance, exploration, combat, victory, dungeon, forest, town, mystery, castle, underwater, temple, boss,
      stealth, treasure, danger, peaceful
  }

  /** The directions an exit may take, as written. */
  val Directions: Seq[String] = Direction.values.toSeq.map(_.toString)

  /** The moods a scene's background music may have, as written. */
  val MusicMoods: Seq[String] = MusicMood.values.toSeq.map(_.toString)

  /** The mood played for a scene whose mood is not one of [[MusicMoods]]. */
  val DefaultMusicMood: String = MusicMood.exploration.toString

  /** Longest narration for a full scene, in characters: a guideline, so a longer one is used, with a warning. */
  val MaxSceneNarrationChars: Int = 750

  /** Longest narration for a simple response, in characters: a guideline, as for a scene. */
  val MaxSimpleNarrationChars: Int = 450

  /** An exit as the Dungeon Master gives it. */
  @description("An exit from the location")
  final case class AnswerExit(
    direction: Direction,
    @description("Id of the location the exit leads to, lower_snake_case")
    locationId: String,
    @description("What lies that way, or null")
    description: Option[String]
  ) derives ReadWriter,
        SchemaOf

  // scalafmt: { maxColumn = 400 }
  /** The Dungeon Master's answer to one turn, as it is written: the output schema is derived from this type. */
  @description("The Dungeon Master's response to the player")
  final case class Answer(
    @description("What the player reads and hears: vivid, flowing second-person prose in whole sentences (never a list of fragments), mentioning every exit for a scene by its own direction (at most 750 characters for a fullScene, 450 for a simple response)")
    narrationText: String,
    responseType: ResponseType,
    @description("The player's location id, lower_snake_case")
    locationId: String,
    @description("fullScene: the location's name; simple: null")
    locationName: Option[String],
    @description("fullScene: 2-3 sentence visual description; simple: null")
    imageDescription: Option[String],
    @description("Normally null: a place keeps the picture it was first shown with. Only when what the player sees here has visibly and lastingly changed since then (a wall brought down, a fire lit, a door broken open, a character gone for good): a 2-3 sentence visual description of how the place looks now, for a new picture. Either response type may give it")
    imageUpdate: Option[String],
    @description("fullScene: atmosphere for background music; simple: null")
    musicDescription: Option[String],
    musicMood: Option[MusicMood],
    @description("fullScene: every exit; simple: null")
    exits: Option[List[AnswerExit]],
    @description("fullScene: the items here, each with its state if it has one; simple: the items here now if the action changed them or their state (taken, used up, opened, filled, revealed), otherwise null")
    items: Option[List[String]],
    @description("fullScene: the characters here; simple: the characters here now if the action changed who is here (someone appeared or left), otherwise null")
    npcs: Option[List[String]],
    @description("simple: the action, e.g. examine, take, talk; fullScene: null")
    actionTaken: Option[String]
  ) derives ReadWriter,
        SchemaOf
  // scalafmt: { maxColumn = 120 }

  /** The output schema the Dungeon Master agent is built with. */
  val schema: SchemaDefinition[Answer] = SchemaOf[Answer].definition

  /** The answer `json` holds, or why it holds none: for an answer as llm4s stores it in the thread (its JSON alone). */
  def read(json: ujson.Readable): Either[String, Answer] =
    Try(upickle.default.read[Answer](json)).toEither.left.map(e => s"not a Dungeon Master answer: ${e.getMessage}")

  /** Where a JSON object with a property may start: a brace, then a quote. */
  private val ObjectStart = """\{\s*"""".r

  /** The most places in a reply an answer is looked for. */
  private val MaxStarts = 16

  /** The answer in a model's reply as the model wrote it, before llm4s has checked it: the first JSON object in it that
    * reads as an answer, so a code fence or prose round the JSON - braces of its own included - is passed over, as
    * llm4s passes over it. A reply cut short within a string does not read as an answer.
    */
  def answerIn(reply: String): Either[String, Answer] =
    ObjectStart
      .findAllMatchIn(reply)
      .take(MaxStarts)
      .flatMap(start => PartialJson.parse(reply.substring(start.start), partialStrings = false))
      .map(read(_))
      .find(_.isRight)
      .getOrElse(Left("the reply holds no Dungeon Master answer"))

  /** The JSON object in `text`, a reply being written, as far as it has been written: from the first brace that opens
    * an object with a property, read with llm4s's `PartialJson`, which closes what is still open and leaves out an
    * escape not yet complete (and a string still being written unless `partialStrings`).
    */
  def jsonIn(text: String, partialStrings: Boolean): Option[ujson.Value] =
    ObjectStart
      .findFirstMatchIn(text)
      .flatMap(start => PartialJson.parse(text.substring(start.start), partialStrings))
      .filter(_.objOpt.isDefined)
}
