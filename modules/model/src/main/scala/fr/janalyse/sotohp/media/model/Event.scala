package fr.janalyse.sotohp.media.model

opaque type EventName = String
opaque type EventDescription = String

object EventName {
  def apply(name: String): EventName = name
}

case class Event(
  name: EventName,
  description: Option[EventDescription],
  keywords: Set[Keyword]
)
