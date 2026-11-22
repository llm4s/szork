package org.llm4s.szork.game

import org.llm4s.toolapi._
import upickle.default._
import org.slf4j.{Logger, LoggerFactory}

/** A player's inventory. Each game has its own; tool calls and saves may touch it from different threads, so every
  * operation is atomic.
  */
final class PlayerInventory(initial: List[String] = Nil) {
  private var items: Vector[String] = initial.distinct.toVector

  def list: List[String] = synchronized(items.toList)

  /** Adds `item`; false if it is already held. */
  def add(item: String): Boolean = synchronized {
    if (items.contains(item)) false
    else { items = items :+ item; true }
  }

  /** Removes `item`; false if it is not held. */
  def remove(item: String): Boolean = synchronized {
    if (!items.contains(item)) false
    else { items = items.filterNot(_ == item); true }
  }

  /** Replaces the contents, e.g. when a saved game is restored. */
  def replaceWith(newItems: List[String]): Unit = synchronized { items = newItems.distinct.toVector }
}

/** The tools the Dungeon Master agent can call. They are built per game, over that game's [[PlayerInventory]]. */
object GameTools {
  private implicit val logger: Logger = LoggerFactory.getLogger(getClass.getSimpleName)

  // Unwrap a tool built via buildSafe(); a Left here is a static definition error.
  private def orThrow[T, R](result: org.llm4s.types.Result[ToolFunction[T, R]]): ToolFunction[T, R] =
    result match {
      case Right(tool) => tool
      case Left(error) => throw new IllegalStateException(s"Failed to build tool: ${error.message}")
    }

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

  implicit val inventoryListResultRW: ReadWriter[InventoryListResult] = macroRW
  implicit val inventoryModifyResultRW: ReadWriter[InventoryModifyResult] = macroRW

  private val listInventorySchema: ObjectSchema[Map[String, Any]] = Schema
    .`object`[Map[String, Any]]("Simple returns the list of items in your inventory")

  private def itemSchema(description: String): ObjectSchema[Map[String, Any]] = Schema
    .`object`[Map[String, Any]](description)
    .withProperty(Schema.property("item", Schema.string("The name of the item (REQUIRED)")))

  /** The inventory tools, reading and changing `inventory`. */
  def forInventory(inventory: PlayerInventory): Seq[ToolFunction[?, ?]] = {
    val listInventoryTool = orThrow(
      ToolBuilder[Map[String, Any], InventoryListResult](
        "list_inventory",
        "List all items currently in the player's inventory",
        listInventorySchema
      ).withHandler { _ =>
        logger.info("Listing player inventory")
        val items = inventory.list
        val message =
          if (items.isEmpty) "Your inventory is empty" else s"You have ${items.size} item(s) in your inventory"
        Right(InventoryListResult(items, items.size, message))
      }.buildSafe())

    val addInventoryItemTool = orThrow(
      ToolBuilder[Map[String, Any], InventoryModifyResult](
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
      }.buildSafe())

    val removeInventoryItemTool = orThrow(
      ToolBuilder[Map[String, Any], InventoryModifyResult](
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
      }.buildSafe())

    Seq(listInventoryTool, addInventoryItemTool, removeInventoryItemTool)
  }
}
