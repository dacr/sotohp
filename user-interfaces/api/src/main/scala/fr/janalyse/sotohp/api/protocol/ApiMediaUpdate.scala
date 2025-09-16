package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.{Event, Keyword, Location, Media, MediaAccessKey, MediaDescription, Orientation, Original, ShootDateTime, Starred}
import fr.janalyse.sotohp.service.json.{*, given}
import io.scalaland.chimney.*
import io.scalaland.chimney.dsl.*
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.encodedName
import zio.json.*

case class ApiMediaUpdate(
  description: Option[MediaDescription],
  starred: Starred,
  keywords: Set[Keyword],
  // orientation: Option[Orientation],         // override original's orientation
  shootDateTime: Option[ShootDateTime], // override original's cameraShotDateTime
  userDefinedLocation: Option[ApiLocation] // replace the original's location (user-defined or deducted location)
)

object ApiMediaUpdate {
  given JsonCodec[ApiMediaUpdate]              = DeriveJsonCodec.gen
  given apiMediaSchema: Schema[ApiMediaUpdate] = Schema.derived[ApiMediaUpdate].name(Schema.SName("Media"))

  given transformer: Transformer[Media, ApiMediaUpdate] =
    Transformer
      .define[Media, ApiMediaUpdate]
      .buildTransformer

}
