package org.llm4s.szork

import org.scalatest.funsuite.AnyFunSuite
import org.llm4s.szork.game.IdGenerator

class IdGeneratorTest extends AnyFunSuite {

  test("game and session ids are a prefix and eight lowercase hex characters") {
    assert(IdGenerator.gameId().matches("game-[a-f0-9]{8}"))
    assert(IdGenerator.sessionId().matches("sess-[a-f0-9]{8}"))
  }

  test("generated ids are unique") {
    val ids = (1 to 100).map(_ => IdGenerator.gameId()).toSet
    assert(ids.size == 100, "All generated IDs should be unique")
  }

  test("isValidGameId accepts generated game ids only") {
    assert(IdGenerator.isValidGameId(IdGenerator.gameId()))
    assert(!IdGenerator.isValidGameId(IdGenerator.sessionId()))
    assert(!IdGenerator.isValidGameId("game-short"))
    assert(!IdGenerator.isValidGameId("game-UPPERCAS"))
    assert(!IdGenerator.isValidGameId(null))
  }
}
