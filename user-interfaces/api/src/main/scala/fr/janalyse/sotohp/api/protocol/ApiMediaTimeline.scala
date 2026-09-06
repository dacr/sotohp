package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.MediaAccessKey
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema

import java.time.Instant

case class ApiMediaTimelineAnchor(
  offset: Long,
  accessKey: MediaAccessKey,
  timestamp: Instant
)

object ApiMediaTimelineAnchor {
  given JsonValueCodec[ApiMediaTimelineAnchor]                       = JsonCodecMaker.make
  given apiMediaTimelineAnchorSchema: Schema[ApiMediaTimelineAnchor] = Schema.derived[ApiMediaTimelineAnchor].name(Schema.SName("MediaTimelineAnchor"))
}

case class ApiMediaTimeline(
  total: Long,
  step: Int,
  anchors: List[ApiMediaTimelineAnchor]
)

object ApiMediaTimeline {
  import ApiMediaTimelineAnchor.given

  given JsonValueCodec[ApiMediaTimeline]                 = JsonCodecMaker.make
  given apiMediaTimelineSchema: Schema[ApiMediaTimeline] = Schema.derived[ApiMediaTimeline].name(Schema.SName("MediaTimeline"))
}
