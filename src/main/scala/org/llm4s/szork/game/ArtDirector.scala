package org.llm4s.szork.game

import org.llm4s.agent.Agent
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{CompletionOptions, ReasoningEffort}
import org.llm4s.szork.error._
import org.llm4s.szork.error.ErrorHandling._
import org.llm4s.toolapi.{ObjectSchema, Schema}
import org.llm4s.trace.Tracing
import org.slf4j.LoggerFactory
import upickle.default.ReadWriter

/** One location's picture, as the art director briefs it. */
final case class LocationArt(id: String, image: String) derives ReadWriter

/** The art director's answer: a brief for each location. */
final case class ArtDirection(locations: List[LocationArt]) derives ReadWriter

/** Writes the brief each location of an adventure is pictured from: a third llm4s agent, after the designer and before
  * the Dungeon Master.
  *
  * A location's outline description is written for a player. It says where the doors lead and leaves the reader to work
  * out that they are standing indoors, so an image model given it draws the wrong thing: a gatehouse described by the
  * rain on its roof and the ledger on its table comes out as a table in the rain. The art director reads the whole
  * outline once and writes, for each location, what a picture of it should show - inside or outside, where the viewer
  * stands, the few things that matter, the light - and nothing a picture cannot show.
  *
  * Briefs are kept in the outline ([[LocationOutline.image]]) and so in the adventure library; the art style is not
  * part of a brief ([[org.llm4s.szork.media.MediaPlanner]] adds it), so one brief serves every style.
  */
object ArtDirector {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)

  val AgentId = "szork-art-director"

  // The briefs for a whole adventure are a few thousand tokens; this leaves room for a model that reasons first
  private val MaxOutputTokens = 8000
  // One call, and one more if llm4s has to ask for a corrected answer
  private val MaxModelCalls = 2

  private val location: ObjectSchema[LocationArt] = Schema
    .`object`[LocationArt]("The picture of one location")
    .withProperty(Schema.property("id", Schema.string("The location's id, exactly as given")))
    .withProperty(Schema.property(
      "image",
      Schema.string(
        "The brief for an image generator: one paragraph of 40-80 words. Begin with 'Interior of' or 'Exterior " +
          "of' and where the viewer stands; then the three to five things that matter and where they are in the " +
          "frame; then the light, weather and mood")
    ))

  val schema: ObjectSchema[ArtDirection] = Schema
    .`object`[ArtDirection]("Picture briefs for an adventure's locations")
    .withProperty(Schema.property("locations", Schema.array("One brief for every location given", location)))

  val SystemPrompt: String =
    """You are the art director of an illustrated text adventure. For each location you are given, you brief the
      |artist who will paint the single picture the player sees on arriving there.
      |
      |A brief describes one picture, from one place, of what is in front of the viewer:
      |- The artist sees one brief at a time and nothing else of the adventure, so every brief names its world in its
      |  opening words - "Interior of a hydroponics deck aboard a derelict space station", not "Interior of a garden
      |  corridor" - and gives what things are made of where that says which world it is: steel deck plates, riveted
      |  bulkheads, moulded panels. A brief that could be drawn as a castle, a ship or a space station is not finished.
      |- Say first whether it is an interior or an exterior, and where the viewer stands. Decide from the whole
      |  description: a room with a table, a ledger and a mirror is an interior even if the description opens with
      |  the rain on its roof. Weather outside an interior is seen through a window or a doorway, or left out.
      |- Name the three to five things that make the place what it is, and where each is in the frame. Keep the objects
      |  the description names; a player will look for them. An object means what it is: a mirror reflects the room.
      |- Give the light, the weather if it shows, and the mood.
      |- A way out is shown only as what is seen of it - "a carved oak door, ajar, in the far wall" - never as a
      |  direction. Do not use compass points, and do not show what lies beyond a door unless the description does.
      |- Leave out what a picture cannot show: sounds, smells, history, what the player did or will do.
      |- No people, unless the description has someone who is always there. Never the player.
      |- No lettering, captions, signs to be read, borders or maps.
      |- Do not name an art style or medium: the artist is told the style separately.
      |
      |Give one brief for every location, with its id exactly as given.""".stripMargin

  private def request(outline: AdventureOutline): String = {
    val locations = outline.keyLocations.map { place =>
      val things = if (place.interactables.isEmpty) "" else s"\n  things here: ${place.interactables.mkString(", ")}"
      s"- id: ${place.id}\n  name: ${place.name}\n  description: ${place.description}$things"
    }
    s"""The adventure: ${outline.title}. ${outline.mainQuest}
      |
      |Its locations:
      |${locations.mkString("\n")}""".stripMargin
  }

  /** `outline` with a picture brief on each location the art director briefed (a location it left out keeps what it
    * had), written by the art director agent on `client`.
    */
  def direct(outline: AdventureOutline, tracing: Option[Tracing] = None, reasoning: Option[ReasoningEffort] = None)(
    using client: LLMClient): SzorkResult[AdventureOutline] = {
    val builder = Agent
      .builder(AgentId, client)
      .withSystemPrompt(SystemPrompt)
      .withMaxSteps(MaxModelCalls)
      .withCompletionOptions(CompletionOptions().withMaxTokens(MaxOutputTokens).withReasoning(reasoning))
      .withOutput(schema)
    val direction = for {
      agent <- tracing.fold(builder)(builder.withTracing).build()
      result <- agent.run(request(outline))
      _ = logger.info(s"Art director finished ${result.status.productPrefix}: ${result.runUsage}")
      _ = agent.forget(result.threadId)
      direction <- result.decodeOutput[ArtDirection]
    } yield direction
    direction match {
      case Right(art) =>
        val briefs = art.locations.map(l => l.id -> l.image.trim).filter(_._2.nonEmpty).toMap
        logger.info(s"Art direction for '${outline.title}': ${briefs.size} of ${outline.keyLocations.size} locations")
        Right(withBriefs(outline, briefs))
      case Left(error) =>
        logger.error(s"Art direction for '${outline.title}' failed: ${error.message}")
        Left(AIError(s"The art director could not brief the pictures: ${error.message}", llmError = Some(error)))
    }
  }

  /** `outline` with `briefs` (by location id) as its locations' picture briefs. */
  def withBriefs(outline: AdventureOutline, briefs: Map[String, String]): AdventureOutline =
    outline.copy(keyLocations = outline.keyLocations.map(place =>
      briefs.get(place.id).fold(place)(brief => place.copy(image = Some(brief)))))

  /** Whether every location of `outline` has a picture brief. */
  def isDirected(outline: AdventureOutline): Boolean =
    outline.keyLocations.forall(_.image.exists(_.trim.nonEmpty))
}
