package fr.janalyse.sotohp.core

import fr.janalyse.sotohp.model.*
import zio.*

/** Contextual log key/values, attached to an effect with `@@` so that every log emitted inside it carries them.
  *
  * Messages stay constant (easy to grep), the context goes into the key/values :
  * {{{
  *   ZIO.logInfo("Faces detected") @@ LogContext.kv("facesCount" -> faces.size)
  *   logic @@ LogContext.original(original)
  * }}}
  *
  * Key naming convention : camelCase, prefixed by the entity they describe (originalId, faceId, personId, bagId,
  * portfolioName, ...), metrics as plain nouns (count, facesCount, referenceSize, width, height, elapsedSeconds, ...).
  */
object LogContext {

  /** Identifies an original photo : identifier, file name, full path, timestamps and store. */
  def original(original: Original): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    ZIOAspect.annotated(
      "originalId"            -> original.id.asString,
      "originalFileName"      -> original.mediaPath.fileName,
      "originalPath"          -> original.absoluteMediaPath.toString,
      "originalTimestamp"     -> original.timestamp.toString,
      "originalShootDateTime" -> original.cameraShootDateTime.map(_.offsetDateTime.toString).getOrElse("none"),
      "storeId"               -> original.store.id.asString
    )

  /** Use only when the original itself is not at hand. */
  def originalId(originalId: OriginalId): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    ZIOAspect.annotated("originalId" -> originalId.asString)

  /** Identifies a media : its original context, plus the media access key, the media timestamp and its bag. */
  def media(media: Media): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    original(media.original) @@
      ZIOAspect.annotated(
        Seq(
          "mediaAccessKey" -> MediaAccessKey(media.timestamp, media.original.id).asString,
          "mediaTimestamp" -> media.timestamp.toString
        ) ++ media.bag.toSeq.flatMap(bag => Seq("bagId" -> bag.id.asString, "bagName" -> bag.name.text))*
      )

  def face(faceId: FaceId): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    ZIOAspect.annotated("faceId" -> faceId.asString)

  def person(personId: PersonId): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    ZIOAspect.annotated("personId" -> personId.asString)

  /** Ad-hoc key/values (counts, sizes, durations, ...). */
  def kv(pairs: (String, Any)*): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    ZIOAspect.annotated(pairs.map((key, value) => key -> String.valueOf(value))*)
}
