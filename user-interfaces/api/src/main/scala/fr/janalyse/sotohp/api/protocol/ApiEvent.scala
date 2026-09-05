package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import sttp.tapir.{Codec, Schema}
import sttp.tapir.Codec.PlainCodec

// A single flat shape for every kind of change, broadcast to connected clients over SSE so they
// can invalidate/refetch whatever they're showing instead of requiring a full page reload.
// Deliberately not a typed hierarchy per entity (KISS) — clients only need "something about
// entity `entity` (optionally `id`) changed", not a strongly typed payload per case.
enum ApiEventAction derives CanEqual {
  case created, updated, deleted
}

object ApiEventAction {
  given JsonValueCodec[ApiEventAction] = JsonCodecMaker.make
  given Schema[ApiEventAction]         = Schema.derivedEnumeration[ApiEventAction].defaultStringBased
}

case class ApiEvent(
  entity: String, // "owner" | "store" | "person" | "portfolio" | "bag" | "media" | "face" | "sync"
  id: Option[String],
  action: ApiEventAction
)

object ApiEvent {
  given JsonValueCodec[ApiEvent] = JsonCodecMaker.make
  given apiEventSchema: Schema[ApiEvent] = Schema.derived[ApiEvent].name(Schema.SName("Event"))
}
