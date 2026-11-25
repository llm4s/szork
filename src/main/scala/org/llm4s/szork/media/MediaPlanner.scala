package org.llm4s.szork.media

/** Builds image prompts in the game's art style. */
object MediaPlanner {
  def styledImagePrompt(artStyle: Option[String], baseDescription: String, artStyleDescription: String): String =
    artStyle match {
      case Some("pixel") =>
        // The style comes first and is said again last: given a long, atmospheric description, an image model
        // otherwise paints it realistically and ignores the style
        s"Pixel art. A 16-bit SNES-era adventure game background drawn in large, clearly visible square pixels, with a limited palette of flat colours, hard pixel edges and dithering for shading. No photorealism, no painterly brushwork, no smooth gradients, no soft glow or blur. The scene: $baseDescription Draw the whole scene strictly as chunky retro pixel art on a visible pixel grid, like a screenshot from a 1990s point-and-click adventure."
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
