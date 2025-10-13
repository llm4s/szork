package org.llm4s.szork.game

/** Constants used throughout the Szork game engine.
  * Centralizes magic strings and default values for better maintainability.
  */
object GameConstants {

  // Initial game prompts
  object Prompts {
    val INIT_ADVENTURE = "Start adventure"
  }

  // Music moods
  object MusicMoods {
    val EXPLORATION = "exploration"
    val COMBAT = "combat"
    val MYSTERY = "mystery"
    val PEACEFUL = "peaceful"
    val TENSE = "tense"
  }

  // Response types
  object ResponseTypes {
    val FULL_SCENE = "fullScene"
    val SIMPLE = "simple"
  }

  // Default settings
  object Defaults {
    val DEFAULT_MUSIC_MOOD = MusicMoods.EXPLORATION
    val DEFAULT_TTS_VOICE = "nova"
  }
}
