package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.{EventId, LatitudeDecimalDegrees, LongitudeDecimalDegrees, MediaAccessKey, ShootDateTime, Starred}
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema

case class ApiMediaLocation(
  accessKey: MediaAccessKey,
  latitude: LatitudeDecimalDegrees,
  longitude: LongitudeDecimalDegrees,
  shootDateTime: Option[ShootDateTime],
  starred: Starred,
  eventId: Option[EventId]
)

object ApiMediaLocation {
  given JsonValueCodec[ApiMediaLocation]                 = JsonCodecMaker.make
  given apiMediaLocationSchema: Schema[ApiMediaLocation] = Schema.derived[ApiMediaLocation].name(Schema.SName("MediaLocation"))
}
