package org.llm4s.szork

import org.llm4s.toolapi._
import upickle.default._
import org.slf4j.LoggerFactory
import scala.collection.mutable

object GameTools {
  private val logger = LoggerFactory.getLogger(getClass.getSimpleName)
  
  // Mutable inventory storage (in a real app, this would be persisted)
  private val playerInventory = mutable.ListBuffer[String]()
  
  // Define result types
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
  
  // Provide implicit reader/writers
  implicit val inventoryListResultRW: ReadWriter[InventoryListResult] = macroRW
  implicit val inventoryModifyResultRW: ReadWriter[InventoryModifyResult] = macroRW
  
  /**
   * Tool to list the player's current inventory
   */
  val listInventorySchema: ObjectSchema[Map[String, Any]] = Schema
    .`object`[Map[String, Any]]("List inventory parameters")
  
  def listInventoryHandler(params: SafeParameterExtractor): Either[String, InventoryListResult] = {
    val _ = params // No parameters needed for list operation
    logger.info("Listing player inventory")
    
    val result = if (playerInventory.isEmpty) {
      InventoryListResult(
        inventory = List.empty,
        count = 0,
        message = "Your inventory is empty"
      )
    } else {
      InventoryListResult(
        inventory = playerInventory.toList,
        count = playerInventory.size,
        message = s"You have ${playerInventory.size} item(s) in your inventory"
      )
    }
    
    Right(result)
  }
  
  val listInventoryTool = ToolBuilder[Map[String, Any], InventoryListResult](
    "list_inventory",
    "List all items currently in the player's inventory",
    listInventorySchema
  ).withHandler(listInventoryHandler).build()
  
  /**
   * Tool to add an item to the player's inventory
   */
  val addInventorySchema: ObjectSchema[Map[String, Any]] = Schema
    .`object`[Map[String, Any]]("Add inventory item parameters")
    .withProperty(
      Schema.property(
        "item",
        Schema.string("The name of the item to add to inventory")
      )
    )
  
  def addInventoryHandler(params: SafeParameterExtractor): Either[String, InventoryModifyResult] = {
    for {
      item <- params.getString("item")
    } yield {
      logger.info(s"Adding item to inventory: $item")
      
      if (playerInventory.contains(item)) {
        InventoryModifyResult(
          success = false,
          message = s"You already have '$item' in your inventory",
          item = item,
          inventory = playerInventory.toList
        )
      } else {
        playerInventory += item
        InventoryModifyResult(
          success = true,
          message = s"Added '$item' to your inventory",
          item = item,
          inventory = playerInventory.toList
        )
      }
    }
  }
  
  val addInventoryItemTool = ToolBuilder[Map[String, Any], InventoryModifyResult](
    "add_inventory_item",
    "Add a new item to the player's inventory",
    addInventorySchema
  ).withHandler(addInventoryHandler).build()
  
  /**
   * Tool to remove an item from the player's inventory
   */
  val removeInventorySchema: ObjectSchema[Map[String, Any]] = Schema
    .`object`[Map[String, Any]]("Remove inventory item parameters")
    .withProperty(
      Schema.property(
        "item",
        Schema.string("The name of the item to remove from inventory")
      )
    )
  
  def removeInventoryHandler(params: SafeParameterExtractor): Either[String, InventoryModifyResult] = {
    for {
      item <- params.getString("item")
    } yield {
      logger.info(s"Removing item from inventory: $item")
      
      if (playerInventory.contains(item)) {
        playerInventory -= item
        InventoryModifyResult(
          success = true,
          message = s"Removed '$item' from your inventory",
          item = item,
          inventory = playerInventory.toList
        )
      } else {
        InventoryModifyResult(
          success = false,
          message = s"You don't have '$item' in your inventory",
          item = item,
          inventory = playerInventory.toList
        )
      }
    }
  }
  
  val removeInventoryItemTool = ToolBuilder[Map[String, Any], InventoryModifyResult](
    "remove_inventory_item",
    "Remove an item from the player's inventory",
    removeInventorySchema
  ).withHandler(removeInventoryHandler).build()
  
  /**
   * Get all game tools for the ToolRegistry
   */
  def allTools: Seq[ToolFunction[_, _]] = Seq(
    listInventoryTool,
    addInventoryItemTool,
    removeInventoryItemTool
  )
  
  /**
   * Clear the inventory (useful for game restart)
   */
  def clearInventory(): Unit = {
    logger.info("Clearing player inventory")
    playerInventory.clear()
  }
  
  /**
   * Get current inventory state (for persistence)
   */
  def getInventory: List[String] = playerInventory.toList
  
  /**
   * Set inventory state (for loading saved games)
   */
  def setInventory(items: List[String]): Unit = {
    logger.info(s"Setting inventory to: ${items.mkString(", ")}")
    playerInventory.clear()
    playerInventory ++= items
  }
}