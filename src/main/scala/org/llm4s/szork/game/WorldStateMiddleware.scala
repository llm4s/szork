package org.llm4s.szork.game

import org.llm4s.agent.graph.middleware.{AgentMiddleware, MiddlewareId, ModelContext, PromptSection}
import org.llm4s.types.Result

/** Puts the game's current state into every model call of the Dungeon Master, as an llm4s prompt section: the player's
  * location (its exits with their current states, the facts recorded about it, the characters met there), what the
  * player carries and the active quests.
  *
  * llm4s collects every middleware's `promptSections` before each model call and adds them to the system prompt of that
  * request only: a section is never stored in the thread, so it does not accumulate in the history or in saves, and the
  * context window (which prunes only the conversation after the system prompt) never drops it. The section is rendered
  * afresh for each call from this game's [[WorldState]] and [[PlayerInventory]], so a call made after a tool changed
  * them in the same turn sees the change, and the model rarely needs the read tools.
  *
  * The section is not `cacheable`: llm4s places it after the static system prompt and the cacheable sections (the
  * subagents and the output format), so the prefix a provider caches - the tools and those - stays the same from call
  * to call. Only what follows it, the conversation, is cached no further than this section is unchanged.
  */
final class WorldStateMiddleware(world: WorldState, inventory: PlayerInventory) extends AgentMiddleware {
  val id: MiddlewareId = MiddlewareId(WorldStateMiddleware.Id)

  override def promptSections(context: ModelContext): Result[Vector[PromptSection]] =
    Right(
      Vector(
        PromptSection(WorldStateMiddleware.SectionId, WorldStateMiddleware.render(world.snapshot, inventory.list))))
}

object WorldStateMiddleware {

  /** The middleware's id in the Dungeon Master's stack. */
  val Id: String = "world-state"

  /** The id of its prompt section. */
  val SectionId: String = "current-game-state"

  /** The section's name, by which the system prompt refers to it. */
  val Title: String = "CURRENT GAME STATE"

  /** The heading the section starts with (it appears nowhere else in the prompt). */
  val Heading: String = s"=== $Title ==="

  /** The most characters the section holds (about 1500 tokens), whatever the size of the world: the agent refuses a
    * request whose sections are over its `maxPromptSectionTokens`, so the section bounds itself.
    */
  val MaxChars: Int = 6000

  // How much of each list the section shows; the rest is left to the read tools
  private val MaxExits = 12
  private val MaxFlags = 16
  private val MaxItems = 30
  private val MaxQuests = 8
  private val MaxNotes = 2

  /** The section's text for `world` and the carried `items`. */
  def render(world: WorldSnapshot, items: List[String]): String = {
    val here = world.location(None)
    val location = here match {
      case None =>
        world.currentLocation match {
          case Some(id) => List(s"Location: $id (nothing recorded about it yet)")
          case None => List("Location: unknown yet (the opening scene sets it)")
        }
      case Some(loc) =>
        val exits =
          if (loc.exits.isEmpty) List("Exits: none recorded yet (use the adventure outline)")
          else
            "Exits:" :: capped(loc.exits, MaxExits, "exits", "get_location_state").map {
              case Right(e) =>
                val undiscovered = if (e.state == ExitState.Hidden) " - not yet discovered, do not mention it" else ""
                s"- ${e.direction} -> ${e.targetId}: ${e.state.wire}${e.obstacle.fold("")(o => s" ($o)")}$undiscovered"
              case Left(more) => s"- $more"
            }
        val flags =
          if (loc.flags.isEmpty) List("Facts recorded here: none")
          else
            "Facts recorded here:" :: capped(loc.flags.toList.sorted, MaxFlags, "facts", "get_location_state").map {
              case Right((flag, value)) => s"- $flag = $value"
              case Left(more) => s"- $more"
            }
        val met = world.npcs.filter(n => n.met && n.lastSeenAt.contains(loc.id))
        val people =
          if (met.isEmpty) List("Characters met here: none")
          else "Characters met here:" :: met.map(n => s"- ${n.name}${notes(n.notes)}")
        (s"Location: ${loc.id} (${loc.name})" :: exits) ++ flags ++ people
    }
    val carried =
      if (items.isEmpty) List("Inventory: empty")
      else
        List(
          "Inventory: " + capped(items, MaxItems, "items", "list_inventory")
            .map(_.fold(identity, identity))
            .mkString(", "))
    val active = world.quests.filter(_.status == QuestStatus.Active)
    val quests =
      if (active.isEmpty) List("Active quests: none")
      else
        "Active quests:" :: capped(active, MaxQuests, "quests", "get_world_summary").map {
          case Right(q) => s"- ${q.id}: ${q.description}${notes(q.notes)}"
          case Left(more) => s"- $more"
        }
    val text = (List(
      Heading,
      "The game's state as of this model call, including every change your tool calls have made this turn. Trust " +
        "it over older turns of the conversation."
    ) ++ location ++ carried ++ quests).mkString("\n")
    if (text.length <= MaxChars) text
    else text.take(MaxChars - 80) + "\n... (cut short; the read tools give the rest)"
  }

  private def notes(all: List[String]): String =
    if (all.isEmpty) "" else all.takeRight(MaxNotes).mkString(" (", "; ", ")")

  /** `all` up to `max`, then (as a Left) how many more there are and which tool lists them. */
  private def capped[A](all: List[A], max: Int, what: String, tool: String): List[Either[String, A]] =
    if (all.size <= max) all.map(Right(_))
    else all.take(max).map(Right(_)) :+ Left(s"... and ${all.size - max} more $what ($tool lists them)")
}
