package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.{Bag, Keyword, Location, Media, MediaAccessKey, MediaDescription, Orientation, Original, ShootDateTime, Starred}
import fr.janalyse.sotohp.service.json.{*, given}
import io.scalaland.chimney.*
import io.scalaland.chimney.dsl.*
import sttp.tapir.Schema

case class ApiMedia(
  accessKey: MediaAccessKey,
  original: ApiOriginal,
  bag: Option[ApiBag],
  description: Option[MediaDescription],       // user-authored
  autoDescription: Option[MediaDescription],   // model-generated image-to-text caption (read-only)
  starred: Starred,
  keywords: Set[Keyword],
  orientation: Option[Orientation],         // override original's orientation
  shootDateTime: Option[ShootDateTime],     // override original's cameraShotDateTime
  userDefinedLocation: Option[ApiLocation], // replace the original's location (user-defined or deducted location)
  deductedLocation: Option[ApiLocation],    // location deducted from near-by (time, space) localized photos
  location: Option[ApiLocation],            // effective location (userDefinedLocation orElse original.location orElse deductedLocation)
  userDefinedPlace: Option[ApiPlace],       // replace the deducted place (user-defined or corrected textual place)
  deductedPlace: Option[ApiPlace],          // textual place (town/region/country) reverse-geocoded from the effective location
  place: Option[ApiPlace]                   // effective place (userDefinedPlace orElse deductedPlace)
)

object ApiMedia {
  given JsonValueCodec[ApiMedia]         = JsonCodecMaker.make
  given apiMediaSchema: Schema[ApiMedia] = Schema.derived[ApiMedia].name(Schema.SName("Media"))
}
