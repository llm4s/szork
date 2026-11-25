package org.llm4s.szork.media

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.Base64
import javax.imageio.{IIOImage, ImageIO, ImageWriteParam}
import javax.imageio.stream.MemoryCacheImageOutputStream
import scala.util.{Try, Using}

/** Scene pictures as they are kept and sent: small JPEGs.
  *
  * Image models answer with a PNG of a megabyte and a half. [[shrink]] re-encodes a picture as a JPEG no larger than
  * the size the game shows it at when enlarged: a fraction of the bytes to store, send and cache.
  */
object PictureFiles {
  // Decoding needs no display; without this a server on a desktop machine starts a windowing toolkit
  System.setProperty("java.awt.headless", "true")

  private val JpegQuality = 0.85f

  /** `base64` (an image in any format the JVM reads) as a Base64 JPEG no more than `maxSide` pixels on its longer side.
    * A picture that is already that small is only re-encoded; one that cannot be read is returned as it is.
    */
  def shrink(base64: String, maxSide: Int): String =
    Try {
      val source = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder.decode(base64)))
      val scale = math.min(1.0, maxSide.toDouble / math.max(source.getWidth, source.getHeight))
      val width = math.max(1, math.round(source.getWidth * scale).toInt)
      val height = math.max(1, math.round(source.getHeight * scale).toInt)
      // JPEG has no transparency: the picture is drawn onto an opaque canvas
      val target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
      val graphics = target.createGraphics()
      try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        // Scaled by drawing it: the hints above apply to this, not to Image.getScaledInstance
        graphics.drawImage(source, 0, 0, width, height, null)
      } finally graphics.dispose()
      Base64.getEncoder.encodeToString(jpeg(target))
    }.getOrElse(base64)

  private def jpeg(image: BufferedImage): Array[Byte] = {
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    try {
      val settings = writer.getDefaultWriteParam
      settings.setCompressionMode(ImageWriteParam.MODE_EXPLICIT)
      settings.setCompressionQuality(JpegQuality)
      val bytes = new ByteArrayOutputStream()
      Using.resource(new MemoryCacheImageOutputStream(bytes)) { out =>
        writer.setOutput(out)
        writer.write(null, new IIOImage(image, null, null), settings)
      }
      bytes.toByteArray
    } finally writer.dispose()
  }

  /** The file extension for an image file's `bytes`: `jpg` or `png` (the default). */
  def extension(bytes: Array[Byte]): String = if (mediaType(bytes) == "image/jpeg") "jpg" else "png"

  /** The media type of an image file from its first bytes: JPEG or PNG (the default). */
  def mediaType(bytes: Array[Byte]): String =
    if (bytes.length > 2 && (bytes(0) & 0xff) == 0xff && (bytes(1) & 0xff) == 0xd8) "image/jpeg" else "image/png"
}
