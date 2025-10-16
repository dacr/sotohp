package fr.janalyse.sotohp.model

import java.net.URL

case class EventAttachment(
  store: Store,
  eventMediaDirectory: EventMediaDirectory
)

case class Event(
  id: EventId,
  attachment: Option[EventAttachment], // for event based on a relative directory path within a given store
  name: EventName,
  description: Option[EventDescription],
  location: Option[Location],          // reference location for this event
  timestamp: Option[ShootDateTime],    // reference date time for this event,
  originalId: Option[OriginalId],      // reference/chosen original, which will be shown as the event cover
  publishedOn: Option[URL],            // URL where this event album has been published
  keywords: Set[Keyword]
)
