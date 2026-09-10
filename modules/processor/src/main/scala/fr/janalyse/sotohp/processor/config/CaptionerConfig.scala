package fr.janalyse.sotohp.processor.config

import fr.janalyse.sotohp.core.ConfigInvalid
import zio.*
import zio.config.*
import zio.config.magnolia.*

/** Configuration for the image-to-text captioner, which asks a local Ollama server to
  * describe each photo.
  *
  * Disabled by default: it needs an Ollama server running with a vision model pulled
  * (`ollama pull qwen2.5vl:3b`). When disabled every caption request is a fast no-op.
  *
  * @param enabled        turn the captioner on
  * @param baseUrl        Ollama server base URL (no trailing slash)
  * @param model          vision model name, e.g. "qwen2.5vl:3b" (multilingual), "llava", "gemma3:4b"
  * @param prompt         instruction sent alongside the image
  * @param timeoutSeconds per-request timeout (a cold model load can be slow)
  * @param maxImageSize   longest edge, in px, the photo is downscaled to before sending
  *                       (0 = send the normalized rendition unchanged)
  */
case class CaptionerConfig(
  enabled: Boolean,
  baseUrl: String,
  model: String,
  prompt: String,
  timeoutSeconds: Int,
  maxImageSize: Int
)

object CaptionerConfig {
  private val derivedConfig =
    deriveConfig[CaptionerConfig]
      .mapKey(toKebabCase)
      .nested("sotohp", "processors", "captioner")

  val config =
    ZIO
      .config(derivedConfig)
      .mapError(err => ConfigInvalid("Couldn't build CaptionerConfig", err))
}
