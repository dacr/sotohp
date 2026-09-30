package fr.janalyse.sotohp.processor.config

import fr.janalyse.sotohp.core.ConfigInvalid
import zio.*
import zio.config.*
import zio.config.magnolia.*

/** Configuration for the image-to-text captioner, which asks a local model server to describe
  * each photo - Ollama, or any OpenAI-compatible server (LocalAI, vLLM, llama.cpp's llama-server).
  *
  * Disabled by default: it needs a server running with a vision model available
  * (`ollama pull qwen2.5vl:3b`). When disabled every caption request is a fast no-op.
  *
  * @param enabled        turn the captioner on
  * @param api            wire protocol : "ollama" (native `/api/generate`) or "openai"
  *                       (`/v1/chat/completions`, which Ollama also serves)
  * @param baseUrl        server base URL (no trailing slash)
  * @param model          vision model name, e.g. "qwen2.5vl:3b" (multilingual), "llava", "gemma3:4b"
  * @param prompt         instruction sent alongside the image
  * @param timeoutSeconds per-request timeout (a cold model load can be slow)
  * @param maxImageSize   longest edge, in px, the photo is downscaled to before sending
  *                       (0 = send the normalized rendition unchanged)
  */
case class CaptionerConfig(
  enabled: Boolean,
  api: String,
  baseUrl: String,
  model: String,
  prompt: String,
  timeoutSeconds: Int,
  maxImageSize: Int
)

object CaptionerConfig {
  val ollamaApi = "ollama"
  val openaiApi = "openai"

  private val derivedConfig =
    deriveConfig[CaptionerConfig]
      .mapKey(toKebabCase)
      .nested("sotohp", "processors", "captioner")

  val config =
    ZIO
      .config(derivedConfig)
      .mapError(err => ConfigInvalid("Couldn't build CaptionerConfig", err))
}
