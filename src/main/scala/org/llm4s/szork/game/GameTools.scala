package org.llm4s.szork.game

import org.llm4s.toolapi.*
import org.llm4s.types.Result
import upickle.default.*
import org.slf4j.{Logger, LoggerFactory}

/** A player's inventory. Each game has its own; tool calls and saves may touch it from different threads, so every
  * operation is atomic.
  *
  * Items are matched as the rest of the game matches names: trimmed and ignoring case, so "Brass Key" and "brass key"
  * are one item. An item keeps the spelling it was first added with.
  */
final class PlayerInventory(initial: List[String] = Nil) {
  private var items: Vector[String] = PlayerInventory.distinct(initial)

  def list: List[String] = synchronized(items.toList)

  /** Adds `item`; false if it is already held, in any spelling. */
  def add(item: String): Boolean = synchronized {
    val name = item.trim
    if (name.isEmpty || items.exists(PlayerInventory.same(_, name))) false
    else { items = items :+ name; true }
  }

  /** Removes `item`, in any spelling; false if it is not held. */
  def remove(item: String): Boolean = synchronized {
    if (!items.exists(PlayerInventory.same(_, item))) false
    else { items = items.filterNot(PlayerInventory.same(_, item)); true }
  }

  /** Replaces the contents, e.g. when a saved game is restored or a failed turn is undone. */
  def replaceWith(newItems: List[String]): Unit = synchronized { items = PlayerInventory.distinct(newItems) }
}

object PlayerInventory {

  /** How an item is matched: trimmed, ignoring case. */
  private[game] def key(item: String): String = item.trim.toLowerCase

  private def same(a: String, b: String): Boolean = key(a) == key(b)

  private def distinct(items: List[String]): Vector[String] =
    items.map(_.trim).filter(_.nonEmpty).distinctBy(key).toVector
}

/** The tools the Dungeon Master agent can call. They are built per game, over that game's [[PlayerInventory]] and
  * [[WorldState]].
  */
object GameTools {
  private val logger: Logger = LoggerFactory.getLogger(getClass.getSimpleName)

  /** The tools that only read the game: calling one changes nothing. */
  val ReadOnly: Set[String] = Set("list_inventory", "get_location_state", "get_world_summary")

  /** The built `tools`, or the first one's definition error. */
  private def all(tools: Result[ToolFunction[?]]*): Result[Seq[ToolFunction[?]]] =
    tools.foldLeft[Result[Vector[ToolFunction[?]]]](Right(Vector.empty))((acc, tool) =>
      acc.flatMap(built => tool.map(built :+ _)))

  case class InventoryListResult(
    inventory: List[String],
    count: Int,
    message: String
  )

  case class InventoryModifyResult(
    success: Boolean,
    message: String,
    item: String,
    inventory: List[String]
  )

  given inventoryListResultRW: ReadWriter[InventoryListResult] = macroRW
  given inventoryModifyResultRW: ReadWriter[InventoryModifyResult] = macroRW

  private val listInventorySchema: ObjectSchema[Map[String, Any]] = Schema
    .`object`[Map[String, Any]]("Simple returns the list of items in your inventory")

  private def itemSchema(description: String): ObjectSchema[Map[String, Any]] = Schema
    .`object`[Map[String, Any]](description)
    .withProperty(Schema.property("item", Schema.string("The name of the item (REQUIRED)")))

  /** The inventory tools, reading and changing `inventory`. */
  def forInventory(inventory: PlayerInventory): Result[Seq[ToolFunction[?]]] = {
    val listInventoryTool =
      ToolBuilder[InventoryListResult](
        "list_inventory",
        "List all items currently in the player's inventory. The current game state in the prompt already lists " +
          "them; use this only when it says the inventory was cut short.",
        listInventorySchema
      ).withHandler { _ =>
        logger.info("Listing player inventory")
        val items = inventory.list
        val message =
          if (items.isEmpty) "Your inventory is empty" else s"You have ${items.size} item(s) in your inventory"
        Right(InventoryListResult(items, items.size, message))
      }.build()

    val addInventoryItemTool =
      ToolBuilder[InventoryModifyResult](
        "add_inventory_item",
        "Add a new item to the player's inventory",
        itemSchema("Add inventory item parameters")
      ).withHandler { params =>
        params.getString("item").map { item =>
          logger.info(s"Adding item to inventory: $item")
          val added = inventory.add(item)
          val message = if (added) s"Added '$item' to your inventory" else s"You already have '$item' in your inventory"
          InventoryModifyResult(added, message, item, inventory.list)
        }
      }.build()

    val removeInventoryItemTool =
      ToolBuilder[InventoryModifyResult](
        "remove_inventory_item",
        "Remove an item from the player's inventory",
        itemSchema("Remove inventory item parameters")
      ).withHandler { params =>
        params.getString("item").map { item =>
          logger.info(s"Removing item from inventory: $item")
          val removed = inventory.remove(item)
          val message =
            if (removed) s"Removed '$item' from your inventory" else s"You don't have '$item' in your inventory"
          InventoryModifyResult(removed, message, item, inventory.list)
        }
      }.build()

    all(listInventoryTool, addInventoryItemTool, removeInventoryItemTool)
  }

  // --- World state ---

  case class ExitView(direction: String, leadsTo: String, state: String, passable: Boolean, obstacle: Option[String])

  case class LocationView(
    found: Boolean,
    locationId: String,
    name: String,
    playerIsHere: Boolean,
    visited: Boolean,
    exits: List[ExitView],
    flags: Map[String, String],
    charactersMetHere: List[String],
    message: String
  )

  case class WorldChangeResult(success: Boolean, message: String)

  case class NpcView(name: String, role: Option[String], lastSeenAt: Option[String], notes: List[String])

  case class QuestView(id: String, description: String, status: String, notes: List[String])

  case class WorldSummary(
    currentLocation: Option[String],
    visitedLocations: List[String],
    impassableExits: List[String],
    locationFlags: List[String],
    charactersMet: List[NpcView],
    quests: List[QuestView]
  )

  given exitViewRW: ReadWriter[ExitView] = macroRW
  given locationViewRW: ReadWriter[LocationView] = macroRW
  given worldChangeResultRW: ReadWriter[WorldChangeResult] = macroRW
  given npcViewRW: ReadWriter[NpcView] = macroRW
  given questViewRW: ReadWriter[QuestView] = macroRW
  given worldSummaryRW: ReadWriter[WorldSummary] = macroRW

  /** An optional string parameter (llm4s's `getOptionalString`: absent or null is None), and blank is None too. */
  private def optionalString(params: SafeParameterExtractor, name: String): Either[ToolParameterError, Option[String]] =
    params.getOptionalString(name).map(_.map(_.trim).filter(_.nonEmpty))

  /** An optional string, nullable so that a strict tool schema (where every property is required) can leave it out.
    */
  private def optionalText(description: String) = Schema.nullable(Schema.string(description))

  private val locationIdProperty =
    optionalText("Id (or name) of the location; null for the player's current location")

  /** A location's state as a tool reports it. */
  def locationView(world: WorldSnapshot, location: LocationRecord): LocationView =
    LocationView(
      found = true,
      locationId = location.id,
      name = location.name,
      playerIsHere = world.currentLocation.contains(location.id),
      visited = location.visited,
      exits = location.exits.map(e => ExitView(e.direction, e.targetId, e.state.wire, e.state.passable, e.obstacle)),
      flags = location.flags,
      charactersMetHere = world.npcs.filter(n => n.met && n.lastSeenAt.contains(location.id)).map(_.name),
      message = s"${location.name}: ${location.exits.size} recorded exit(s)"
    )

  /** A summary of the whole world as a tool reports it. */
  def worldSummary(world: WorldSnapshot): WorldSummary =
    WorldSummary(
      currentLocation = world.currentLocation,
      visitedLocations = world.locations.filter(_.visited).map(_.id),
      impassableExits =
        for {
          loc <- world.locations
          exit <- loc.exits if !exit.state.passable
        } yield s"${loc.id} ${exit.direction} -> ${exit.targetId}: ${exit.state.wire}" +
          exit.obstacle.fold("")(o => s" ($o)"),
      locationFlags = for {
        loc <- world.locations
        (flag, value) <- loc.flags.toList
      } yield s"${loc.id}: $flag = $value",
      charactersMet = world.npcs.filter(_.met).map(n => NpcView(n.name, n.role, n.lastSeenAt, n.notes)),
      quests = world.quests.map(q => QuestView(q.id, q.description, q.status.wire, q.notes))
    )

  private def changeResult(outcome: Either[String, String]): WorldChangeResult = outcome match {
    case Right(message) => WorldChangeResult(success = true, message)
    case Left(message) => WorldChangeResult(success = false, message)
  }

  /** The world-state tools, reading and changing `world`. A scene answer moves the player (the engine records it in the
    * world state), so there is no tool for movement.
    */
  def forWorld(world: WorldState): Result[Seq[ToolFunction[?]]] = {
    val getLocationStateTool =
      ToolBuilder[LocationView](
        "get_location_state",
        "Get a location's current state: its exits (direction, destination, state open/closed/locked/sealed/" +
          "blocked/hidden, obstacle), the facts recorded about it and the characters met there. The player's " +
          "current location is already in the current game state in the prompt; use this for other locations.",
        Schema
          .`object`[Map[String, Any]]("Get location state parameters")
          .withOptionalField("location_id", locationIdProperty)
      ).withHandler { params =>
        optionalString(params, "location_id").map { query =>
          val snapshot = world.snapshot
          snapshot.location(query) match {
            case Some(location) => locationView(snapshot, location)
            case None =>
              val id = query.getOrElse("")
              LocationView(
                found = false,
                locationId = id,
                name = id,
                playerIsHere = false,
                visited = false,
                exits = Nil,
                flags = Map.empty,
                charactersMetHere = Nil,
                message =
                  s"No record of location '$id'. Known locations: ${snapshot.locations.map(_.id).mkString(", ")}"
              )
          }
        }
      }.build()

    val setExitStateTool =
      ToolBuilder[WorldChangeResult](
        "set_exit_state",
        "Change an exit's state when the player changes it: unlocks, opens, closes or locks a door, clears or " +
          "unseals a passage, or discovers a hidden way. The way back from the other side changes with it. The " +
          "player moves only through an exit that is open: open it here before a scene that takes them through it.",
        Schema
          .`object`[Map[String, Any]]("Set exit state parameters")
          .withRequiredField(
            "direction",
            Schema.string("Direction of the exit from the location").withEnum(DungeonMasterOutput.Directions))
          .withRequiredField("state", Schema.string("The exit's new state").withEnum(ExitState.names))
          .withOptionalField("location_id", locationIdProperty)
          .withOptionalField(
            "target_id",
            optionalText("Id of the location the exit leads to; needed only for an exit not yet recorded"))
          .withOptionalField("obstacle", optionalText("What blocks the way, when it is not open"))
      ).withHandler { params =>
        for {
          direction <- params.getString("direction")
          stateName <- params.getString("state")
          state <- ExitState
            .parse(stateName)
            .toRight(
              ToolHandlerError.failed(s"Unknown state '$stateName'; use one of ${ExitState.names.mkString(", ")}"))
          location <- optionalString(params, "location_id")
          target <- optionalString(params, "target_id")
          obstacle <- optionalString(params, "obstacle")
        } yield {
          logger.info(s"Setting exit $direction at ${location.getOrElse("the current location")} to ${state.wire}")
          changeResult(world.setExitState(location, direction, state, target, obstacle))
        }
      }.build()

    val setLocationFlagTool =
      ToolBuilder[WorldChangeResult](
        "set_location_flag",
        "Record a lasting fact about a location that later turns must respect: a lever pulled, a chest emptied, a " +
          "fire lit, a guard bribed. An empty value clears the fact.",
        Schema
          .`object`[Map[String, Any]]("Set location flag parameters")
          .withRequiredField("flag", Schema.string("Short name of the fact, e.g. lever_pulled"))
          .withRequiredField("value", Schema.string("Its value, e.g. true or 'emptied by the player'; empty to clear"))
          .withOptionalField("location_id", locationIdProperty)
      ).withHandler { params =>
        for {
          flag <- params.getString("flag")
          value <- params.getString("value")
          location <- optionalString(params, "location_id")
        } yield {
          logger.info(s"Setting location flag $flag = $value")
          changeResult(world.setFlag(location, flag, value))
        }
      }.build()

    val recordNpcMetTool =
      ToolBuilder[WorldChangeResult](
        "record_npc_met",
        "Record that the player met or talked with a character, with a short note of anything that matters later " +
          "(what they asked for, promised or revealed).",
        Schema
          .`object`[Map[String, Any]]("Record NPC met parameters")
          .withRequiredField("name", Schema.string("The character's name"))
          .withOptionalField("location_id", locationIdProperty)
          .withOptionalField("note", optionalText("A short note on what happened"))
      ).withHandler { params =>
        for {
          name <- params.getString("name")
          location <- optionalString(params, "location_id")
          note <- optionalString(params, "note")
        } yield {
          logger.info(s"Recording NPC met: $name")
          WorldChangeResult(success = true, world.recordNpcMet(name, location, note))
        }
      }.build()

    val updateQuestProgressTool =
      ToolBuilder[WorldChangeResult](
        "update_quest_progress",
        "Record progress on a quest when the player starts, advances, completes or fails it. The main quest's id " +
          "is 'main' and side quests are side-1, side-2, ... (a quest's description also finds it; a new one is added).",
        Schema
          .`object`[Map[String, Any]]("Update quest progress parameters")
          .withRequiredField("quest", Schema.string("The quest's id or description"))
          .withRequiredField("status", Schema.string("The quest's status").withEnum(QuestStatus.names))
          .withOptionalField("note", optionalText("What the player achieved"))
      ).withHandler { params =>
        for {
          quest <- params.getString("quest")
          statusName <- params.getString("status")
          status <- QuestStatus
            .parse(statusName)
            .toRight(
              ToolHandlerError.failed(s"Unknown status '$statusName'; use one of ${QuestStatus.names.mkString(", ")}"))
          note <- optionalString(params, "note")
        } yield {
          logger.info(s"Updating quest $quest to ${status.wire}")
          WorldChangeResult(success = true, world.updateQuest(quest, status, note))
        }
      }.build()

    val getWorldSummaryTool =
      ToolBuilder[WorldSummary](
        "get_world_summary",
        "Get a summary of the whole world: where the player is and has been, every exit that is not open, the " +
          "facts recorded about locations, the characters met and every quest's status. Use it when the player asks " +
          "about their progress or goals, or when earlier events are no longer in the conversation.",
        Schema.`object`[Map[String, Any]]("No parameters")
      ).withHandler(_ => Right(worldSummary(world.snapshot))).build()

    all(
      getLocationStateTool,
      setExitStateTool,
      setLocationFlagTool,
      recordNpcMetTool,
      updateQuestProgressTool,
      getWorldSummaryTool)
  }
}
