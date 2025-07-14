package fr.janalyse.sotohp.media.model

import java.util.UUID
import scala.annotation.targetName

opaque type EventId          = UUID
object EventId {
  def apply(id: UUID): EventId = id
}

opaque type EventName        = String
object EventName {
  def apply(name: String): EventName = name
  extension (eventName: EventName) {
    def text: String = eventName
  }
}

opaque type EventDescription = String
object EventDescription {
  def apply(description: String): EventDescription = description
  extension (eventDescription: EventDescription) {
    def text: String = eventDescription
  }
}

case class Event(
  id: EventId,
  name: EventName,
  description: Option[EventDescription],
  keywords: Set[Keyword]
)
