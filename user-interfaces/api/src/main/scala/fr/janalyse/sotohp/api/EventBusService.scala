package fr.janalyse.sotohp.api

import fr.janalyse.sotohp.api.protocol.ApiEvent
import zio.*
import zio.stream.ZStream

// In-memory pub/sub used to tell every connected client about mutations as they happen, so the
// UI can stay live (SSE) instead of requiring manual reloads/polling. No persistence, no
// cross-process fan-out — a single dropped/missed event just means a client refetches slightly
// later than instantly (all its consumers re-fetch idempotent GETs, they don't apply the event
// itself), so a bounded Hub with a sliding strategy is the right amount of machinery (KISS).
trait EventBusService {
  def publish(event: ApiEvent): UIO[Unit]
  def events: ZStream[Any, Nothing, ApiEvent]
}

object EventBusService {

  def publish(event: ApiEvent): URIO[EventBusService, Unit] =
    ZIO.serviceWithZIO[EventBusService](_.publish(event))

  def events: ZStream[EventBusService, Nothing, ApiEvent] =
    ZStream.serviceWithStream[EventBusService](_.events)

  val live: ZLayer[Any, Nothing, EventBusService] =
    ZLayer.scoped {
      for {
        hub <- Hub.sliding[ApiEvent](1024)
      } yield new EventBusService {
        def publish(event: ApiEvent): UIO[Unit]     = hub.publish(event).unit
        def events: ZStream[Any, Nothing, ApiEvent] = ZStream.fromHub(hub)
      }
    }
}
