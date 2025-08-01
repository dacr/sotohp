package fr.janalyse.sotohp.model

case class EventAttachment(
  store: Store,
  eventMediaDirectory: EventMediaDirectory
)

case class Event(
  id: EventId,
  attachment: Option[EventAttachment], // for event based on a relative directory path within a given store
  name: EventName,
  description: Option[EventDescription],
  keywords: Set[Keyword]
)
