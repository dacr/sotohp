package fr.janalyse.sotohp.media.model

case class Event(
  id: EventId,
  attachment: Option[(store: Store, eventMediaDirectory: EventMediaDirectory)], // for event based on a relative directory path within a given store
  name: EventName,
  description: Option[EventDescription],
  keywords: Set[Keyword]
)
