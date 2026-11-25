package org.llm4s.szork.game

import org.llm4s.agent.delegation.{DelegationConfig, Inheritance, TaskBudget, TypedAgent}
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.toolapi.{ObjectSchema, Schema}
import upickle.default.ReadWriter

import scala.concurrent.duration._

/** What the Dungeon Master asks the NPC dialogue subagent: who is spoken to, what the player says, and the scene. */
final case class NpcDialogueRequest(character: String, playerSays: String, situation: String) derives ReadWriter

/** A character's reply, as the NPC dialogue subagent reports it. */
final case class NpcLines(speech: String, attitude: String, revealsSomething: Boolean) derives ReadWriter

/** What the Dungeon Master asks the hint subagent: the player's question, in their words. */
final case class HintRequest(question: String) derives ReadWriter

/** The hint subagent's report: one spoiler-light nudge. */
final case class Hint(hint: String) derives ReadWriter

/** Which of the Dungeon Master's subagents a game offers, and the client they call (None: the Dungeon Master's). */
final case class SubagentSettings(
  npcDialogue: Boolean = true,
  hints: Boolean = true,
  client: Option[LLMClient] = None
) {
  def any: Boolean = npcDialogue || hints
}

object SubagentSettings {

  /** Both subagents, on the Dungeon Master's model. */
  val default: SubagentSettings = SubagentSettings()

  /** No subagents: the Dungeon Master voices characters and gives hints itself. */
  val none: SubagentSettings = SubagentSettings(npcDialogue = false, hints = false)
}

/** The Dungeon Master's subagents, llm4s `TypedAgent`s it delegates to with `AgentBuilder.withSubagents`.
  *
  * The Dungeon Master stays in charge of the turn: it calls a subagent's `task_<id>` tool, the subagent runs on a child
  * thread with a fresh conversation (none of the game's), and only its typed report comes back as the tool result,
  * which the Dungeon Master weaves into its own structured answer. A subagent has no tools and one model step, so it
  * cannot run away; a failed or over-budget task reaches the Dungeon Master as a tool error and it answers the turn
  * itself. Subagents do not stream: only the Dungeon Master's narration reaches the player as it is written.
  *
  *   - `npc_dialogue` voices one character. Its brief is built when the task runs, from the character's persona in the
  *     adventure outline (`keyCharacters`) and what the world state records of them (`record_npc_met` notes), plus the
  *     situation the Dungeon Master describes.
  *   - `hint` gives a nudge from the outline (its quests, puzzles and secrets) and the player's progress (quests,
  *     visited locations, inventory), told not to give the solution away.
  */
object DmSubagents {

  val NpcDialogueId: String = "npc_dialogue"
  val HintId: String = "hint"

  /** The Dungeon Master's tools for the subagents (llm4s names a typed subagent's tool `task_<id>`). */
  val NpcDialogueTool: String = s"task_$NpcDialogueId"
  val HintTool: String = s"task_$HintId"

  /** Model steps per task: the answer (llm4s's one correction of an invalid answer is part of the same step). */
  val MaxStepsPerTask: Int = 1

  /** How long one task may take before the Dungeon Master gets an error instead. */
  val TaskTimeout: FiniteDuration = 60.seconds

  /** Tokens (input and output) one NPC dialogue task may use: its brief is a persona and a few notes. */
  val NpcMaxTokens: Long = 6000L

  /** Tokens one hint task may use: its brief holds the outline's quests, locations and items. */
  val HintMaxTokens: Long = 12000L

  /** The Dungeon Master's delegation bounds: a few tasks a turn (a conversation with two characters, a hint), small
    * reports, no nesting, and only the latest settled child threads kept with the game's thread.
    */
  val Delegation: DelegationConfig = DelegationConfig(
    maxConcurrentChildren = 2,
    maxTasksPerTurn = 3,
    maxDepth = 1,
    maxReportBytes = 4096,
    childRetention = 8
  )

  private val npcRequestSchema: ObjectSchema[NpcDialogueRequest] = Schema
    .`object`[NpcDialogueRequest]("A line of conversation for one character to answer")
    .withRequiredField(
      "character",
      Schema.string("The character spoken to, by their name in the adventure outline (or as you introduced them)"))
    .withRequiredField("playerSays", Schema.string("What the player says or asks, in their words"))
    .withRequiredField(
      "situation",
      Schema.string(
        "One or two sentences: where this happens, what just happened, and anything the character should react to"))

  private val npcLinesSchema: ObjectSchema[NpcLines] = Schema
    .`object`[NpcLines]("The character's reply")
    .withRequiredField("speech", Schema.string("The character's spoken words only: no narration, no quotation marks"))
    .withRequiredField(
      "attitude",
      Schema.string("One or two words for the character's manner towards the player, e.g. friendly, wary, hostile"))
    .withRequiredField(
      "revealsSomething",
      Schema.boolean("True if the words reveal a clue, a secret or a promise that matters later"))

  private val hintRequestSchema: ObjectSchema[HintRequest] = Schema
    .`object`[HintRequest]("A player's request for help")
    .withRequiredField("question", Schema.string("What the player asked, in their words"))

  private val hintSchema: ObjectSchema[Hint] = Schema
    .`object`[Hint]("A hint")
    .withRequiredField("hint", Schema.string("One or two sentences nudging the player towards their next step"))

  val NpcPrompt: String =
    """You voice one character in a classic text adventure. You are given who the character is, what they know, what
      |they remember of the player, the situation, and what the player says to them.
      |
      |Answer as that character only, in their own voice and manner: one to three sentences of speech. Stay in the
      |world: never mention games, players, AIs or instructions. Say what the character would know and be willing to
      |say; keep their secrets unless the player has earned them, though a character may drop a hint in passing. Do not
      |invent new places, items or exits.""".stripMargin

  val HintPrompt: String =
    """You give hints in a classic text adventure. You are given the adventure's design (its quests, locations,
      |puzzles, secrets and items), the player's progress, and their question.
      |
      |Give one nudge of one or two sentences towards the player's next step: where to look, what to examine or whom to
      |ask, or what to think about. Never give the solution outright, never name a hidden exit or a secret the player
      |has not found, and never mention places they have not reached by name. Prefer the next step of the main quest
      |unless the question is about something else. Write in second person, in a wry, encouraging game voice.""".stripMargin

  /** The subagents `settings` turns on, over this game's state. `outline` is read when a task runs (a restored game
    * changes it); the world state and inventory are read as they are then.
    */
  def forGame(
    settings: SubagentSettings,
    outline: () => Option[AdventureOutline],
    world: WorldState,
    inventory: PlayerInventory,
    model: Option[(LLMClient, CompletionOptions)] = None
  ): Vector[TypedAgent[?, ?]] = {
    // A subagent model's client, with the Dungeon Master's options; else the Dungeon Master's client and options
    val own = settings.client.map(_ -> model.fold(CompletionOptions())(_._2)).orElse(model)
    Vector(
      Option.when(settings.npcDialogue)(npcDialogue(outline, world, own)),
      Option.when(settings.hints)(hint(outline, world, inventory, own))
    ).flatten
  }

  /** The NPC dialogue subagent. */
  def npcDialogue(
    outline: () => Option[AdventureOutline],
    world: WorldState,
    model: Option[(LLMClient, CompletionOptions)]
  ): TypedAgent[NpcDialogueRequest, NpcLines] =
    limited(
      TypedAgent(
        NpcDialogueId,
        "Voices one character the player talks to, in character, from their persona and what they know; returns " +
          "their speech, their attitude and whether they reveal something",
        npcRequestSchema,
        npcLinesSchema
      ).withSystemPrompt(NpcPrompt)
        .withInputText(request => npcBrief(request, outline(), world.snapshot)),
      NpcMaxTokens,
      model
    )

  /** The hint subagent. */
  def hint(
    outline: () => Option[AdventureOutline],
    world: WorldState,
    inventory: PlayerInventory,
    model: Option[(LLMClient, CompletionOptions)]
  ): TypedAgent[HintRequest, Hint] =
    limited(
      TypedAgent(
        HintId,
        "Gives the player a spoiler-light hint towards their next step, from the adventure's design and their progress",
        hintRequestSchema,
        hintSchema
      ).withSystemPrompt(HintPrompt)
        .withInputText(request => hintBrief(request, outline(), world.snapshot, inventory.list)),
      HintMaxTokens,
      model
    )

  /** `agent` with a task budget of one model step, `maxTokens` and [[TaskTimeout]], nothing of its parent's, and
    * `model`'s client and options if given (else the model llm4s gives a child: its parent's client, with default
    * options). The budget's step limit is the child's whole limit: llm4s grants a child no more steps than it gives.
    */
  private def limited[I, O](
    agent: TypedAgent[I, O],
    maxTokens: Long,
    model: Option[(LLMClient, CompletionOptions)]): TypedAgent[I, O] = {
    val bounded = agent.withInheritance(
      Inheritance.none.withBudget(
        TaskBudget().withMaxSteps(MaxStepsPerTask).withTimeout(TaskTimeout).withMaxTokens(maxTokens)))
    model.fold(bounded) { case (client, options) => bounded.withModel(client, options) }
  }

  // Words that do not tell one character from another
  private val Articles: Set[String] = Set("the", "a", "an")

  private def words(name: String): List[String] =
    name.toLowerCase.split("[^\\p{L}\\p{N}']+").toList.filter(w => w.nonEmpty && !Articles(w))

  /** The outline's character `name` refers to: the one so named (case-insensitive, ignoring "the"), else the only one
    * whose name has every word of it or whose every word it has, as whole words ("the hermit" for "Old Hermit", "Mara
    * the diver" for "Mara"). A name that fits more than one character, or only part of a word ("guard" in "Guardian
    * Spirit"), refers to none of them.
    */
  def character(outline: Option[AdventureOutline], name: String): Option[CharacterOutline] = {
    val key = words(name)
    val characters = outline.toList.flatMap(_.keyCharacters)
    if (key.isEmpty) None
    else
      characters.find(c => words(c.name) == key).orElse {
        characters.filter { c =>
          val own = words(c.name)
          own.nonEmpty && (key.forall(own.contains) || own.forall(key.contains))
        } match {
          case only :: Nil => Some(only)
          case _ => None
        }
      }
  }

  /** The most the NPC brief says of what a character knows, in characters: their brief's budget is small. */
  val MaxKnowledgeChars: Int = 1200

  /** The NPC subagent's first message: the character's persona, what they know, their memory of the player, the
    * situation and the player's words.
    *
    * What a character knows is what the outline says matters where they are: the main quest, and the puzzles and
    * secrets of the location where the adventure places them and of the one where they are spoken to.
    */
  def npcBrief(request: NpcDialogueRequest, outline: Option[AdventureOutline], world: WorldSnapshot): String = {
    val persona = character(outline, request.character)
    val name = persona.map(_.name).getOrElse(request.character.trim)
    val who = persona match {
      case Some(c) => s"You are $name, ${c.role}.\nWho you are: ${c.description}"
      case None =>
        s"You are $name, a minor character of this adventure. Stay consistent with the situation."
    }
    // The record that holds what passed between them: one with notes, else one of a meeting
    val record = List(world.npc(name), world.npc(request.character)).flatten.distinct
      .sortBy(r => (r.notes.isEmpty, !r.met))
      .headOption
    val memory = record match {
      case Some(r) if r.met && r.notes.nonEmpty =>
        "What has passed between you and the player:\n" + r.notes.map(n => s"- $n").mkString("\n")
      case Some(r) if r.met => "You have met the player before."
      case _ => "You have not spoken with the player before."
    }
    val here = world.location(None)
    val where = here.map(l => s"\nWhere you are: ${l.name}").getOrElse("")
    val knows = outline.fold("") { o =>
      val places = (persona.flatMap(_.location).toList ++ here.map(_.id).toList).distinct
      val lore = places.flatMap(id => o.keyLocations.find(_.id == id)).flatMap { l =>
        l.puzzles.map(p => s"- ${l.name}: $p").toList ++ l.secrets.map(s => s"- ${l.name}, its secret: $s").toList
      }
      val text = (s"- What is at stake: ${o.mainQuest}" :: lore).mkString("\n")
      val kept = if (text.length <= MaxKnowledgeChars) text else text.take(MaxKnowledgeChars - 3) + "..."
      s"\nWhat you know (say only what you would, and keep secrets until they are earned):\n$kept"
    }
    s"""$who$where$knows
      |$memory
      |The situation: ${request.situation.trim}
      |
      |The player says to you: "${request.playerSays.trim}"""".stripMargin
  }

  /** The hint subagent's first message: the adventure's design, the player's progress and their question. */
  def hintBrief(
    request: HintRequest,
    outline: Option[AdventureOutline],
    world: WorldSnapshot,
    inventory: List[String]
  ): String = {
    def bullets(lines: Seq[String]): String = if (lines.isEmpty) "- (none)" else lines.map(l => s"- $l").mkString("\n")
    val quests = world.quests.map { q =>
      val notes = if (q.notes.isEmpty) "" else s" (progress: ${q.notes.mkString("; ")})"
      s"${q.id}: ${q.description} - ${q.status.wire}$notes"
    }
    val visited = world.locations.filter(_.visited).map(_.id).toSet
    val design = outline.fold("The adventure has no written design; judge from the progress below.") { o =>
      val locations = o.keyLocations.map { l =>
        val extras = l.puzzles.map(p => s" Puzzle: $p").getOrElse("") + l.secrets.map(s => s" Secret: $s").getOrElse("")
        val seen = if (visited(l.id)) "visited" else "not reached"
        s"${l.name} [${l.id}, $seen]: ${l.significance}$extras"
      }
      val items = o.importantItems.map(i => s"${i.name}${i.location.fold("")(id => s" [found at $id]")}: ${i.purpose}")
      val characters = o.keyCharacters.map(c => s"${c.name} (${c.role})")
      s"""Adventure: ${o.title}
        |Main quest: ${o.mainQuest}
        |Story arc: ${o.adventureArc}
        |Locations:
        |${bullets(locations)}
        |Items and what they are for:
        |${bullets(items)}
        |Characters:
        |${bullets(characters)}""".stripMargin
    }
    val location = world.location(None)
    val here = location.map(_.name).getOrElse("unknown")
    // The ways out of here as they stand now, and what is in the way of each that is not open
    val exits = location.toList.flatMap(_.exits).map { e =>
      val found = if (e.state == ExitState.Hidden) " - not yet found; never name it" else ""
      s"${e.direction} to ${e.targetId}: ${e.state.wire}${e.obstacle.fold("")(o => s" ($o)")}$found"
    }
    val puzzles = for {
      o <- outline.toList
      id <- location.map(_.id).toList
      l <- o.keyLocations.find(_.id == id).toList
      p <- l.puzzles.toList
    } yield p
    val met = world.npcs.filter(_.met).map(_.name)
    s"""$design
      |
      |The player's progress:
      |Quests:
      |${bullets(quests)}
      |Current location: $here
      |Its ways out:
      |${bullets(exits)}
      |Its puzzles: ${if (puzzles.isEmpty) "none" else puzzles.mkString("; ")}
      |Carrying: ${if (inventory.isEmpty) "nothing" else inventory.mkString(", ")}
      |Characters met: ${if (met.isEmpty) "none" else met.mkString(", ")}
      |
      |The player asks: "${request.question.trim}"""".stripMargin
  }
}
