package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.szork.game.{GameTools, PlayerInventory}

/** Covers the player's inventory and its tools: an item is matched trimmed and ignoring case, as names are elsewhere.
  */
class PlayerInventorySpec extends AnyFunSuite with Matchers {

  test("an item is one item whatever its case or spacing, and keeps the spelling it was first added with") {
    val inventory = new PlayerInventory(List("Lantern", "lantern ", " "))
    inventory.list shouldBe List("Lantern")
    inventory.add("Brass Key") shouldBe true
    inventory.add(" brass key") shouldBe false
    inventory.list shouldBe List("Lantern", "Brass Key")
    inventory.remove("BRASS KEY") shouldBe true
    inventory.remove("brass key") shouldBe false
    inventory.list shouldBe List("Lantern")
  }

  test("the tools add and remove items whatever their case") {
    val inventory = new PlayerInventory()
    val tools = GameTools.forInventory(inventory).toOption.get.map(t => t.name -> t).toMap
    def call(tool: String, item: String) = tools(tool).execute(ujson.Obj("item" -> item)).toOption.get

    call("add_inventory_item", "Brass Key")("success").bool shouldBe true
    call("add_inventory_item", "brass key")("success").bool shouldBe false
    val removed = call("remove_inventory_item", "BRASS KEY ")
    removed("success").bool shouldBe true
    removed("inventory").arr shouldBe empty
    call("remove_inventory_item", "brass key")("success").bool shouldBe false
    inventory.list shouldBe Nil
  }
}
