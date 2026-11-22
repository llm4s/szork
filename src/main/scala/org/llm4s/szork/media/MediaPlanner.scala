package org.llm4s.szork.media

/** Builds image prompts in the game's art style. */
object MediaPlanner {
  def styledImagePrompt(artStyle: Option[String], baseDescription: String, artStyleDescription: String): String =
    artStyle match {
      case Some("pixel") =>
        s"Classic retro pixel art game scene: $baseDescription. Create in detailed 16-bit pixel art style like SNES-era adventure games, with blocky pixels, dithering patterns, limited color palette, tile-based environments, and nostalgic retro gaming aesthetic. Show clear pixelated details and structured grid-based composition."
      case Some("illustration") =>
        s"Professional pencil sketch: $baseDescription. Create as a detailed graphite pencil drawing with realistic shading, cross-hatching techniques, varied line weights, textured surfaces, and fine detail work. Like an artist's sketchbook illustration with visible pencil strokes, subtle gradients, and hand-drawn quality."
      case Some("painting") =>
        s"Fantasy concept art painting: $baseDescription. Create as a fully rendered atmospheric scene with realistic lighting, rich textures, environmental depth, dramatic composition, and painterly details. Like a fantasy book cover or game concept art with visible brushwork, color depth, and artistic atmosphere."
      case Some("comic") =>
        s"Dynamic comic book panel: $baseDescription. Create in comic book art style with bold black outlines, cel shading, dramatic angles, expressive details, and vibrant colors. Like a graphic novel illustration with clear line art, dynamic composition, and stylized comic book aesthetic."
      case _ =>
        s"$baseDescription, rendered in $artStyleDescription"
    }
}
