package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite

class IdGeneratorTest extends AnyFunSuite {
  
  test("generate should create IDs with correct format") {
    val gameId = IdGenerator.gameId()
    assert(gameId.startsWith("game-"))
    assert(gameId.length == 13) // "game-" (5) + 8 chars
    
    val sessionId = IdGenerator.sessionId()
    assert(sessionId.startsWith("sess-"))
    assert(sessionId.length == 13) // "sess-" (5) + 8 chars
    
    val userId = IdGenerator.userId()
    assert(userId.startsWith("user-"))
    assert(userId.length == 13) // "user-" (5) + 8 chars
  }
  
  test("generated UUIDs should be unique") {
    val ids = (1 to 100).map(_ => IdGenerator.gameId()).toSet
    assert(ids.size == 100, "All generated IDs should be unique")
  }
  
  test("getPrefix should extract prefix correctly") {
    assert(IdGenerator.getPrefix("game-a3b2c1d4") == Some("game"))
    assert(IdGenerator.getPrefix("sess-f5e6d7c8") == Some("sess"))
    assert(IdGenerator.getPrefix("invalid") == Some("invalid"))
    assert(IdGenerator.getPrefix("") == Some(""))
  }
  
  test("getUuidPart should extract UUID portion correctly") {
    assert(IdGenerator.getUuidPart("game-a3b2c1d4") == Some("a3b2c1d4"))
    assert(IdGenerator.getUuidPart("sess-f5e6d7c8") == Some("f5e6d7c8"))
    assert(IdGenerator.getUuidPart("invalid") == None)
  }
  
  test("isValid should validate ID format") {
    assert(IdGenerator.isValid("game-a3b2c1d4"))
    assert(IdGenerator.isValid("sess-f5e6d7c8"))
    assert(IdGenerator.isValid("user-9a8b7c6d"))
    
    // Invalid formats
    assert(!IdGenerator.isValid("game-short"))      // UUID too short
    assert(!IdGenerator.isValid("game-toolong123")) // UUID too long
    assert(!IdGenerator.isValid("game-UPPERCASE"))  // UUID has uppercase
    assert(!IdGenerator.isValid("game-xyz12345"))   // UUID has non-hex chars
    assert(!IdGenerator.isValid("game_a3b2c1d4"))   // Wrong separator
    assert(!IdGenerator.isValid("a3b2c1d4"))        // Missing prefix
  }
  
  test("isValid should check expected prefix when provided") {
    assert(IdGenerator.isValid("game-a3b2c1d4", Some("game")))
    assert(!IdGenerator.isValid("game-a3b2c1d4", Some("sess")))
    assert(!IdGenerator.isValid("sess-f5e6d7c8", Some("game")))
  }
  
  test("generated IDs should pass validation") {
    val gameId = IdGenerator.gameId()
    assert(IdGenerator.isValid(gameId))
    assert(IdGenerator.isValid(gameId, Some("game")))
    
    val sessionId = IdGenerator.sessionId()
    assert(IdGenerator.isValid(sessionId))
    assert(IdGenerator.isValid(sessionId, Some("sess")))
  }
}