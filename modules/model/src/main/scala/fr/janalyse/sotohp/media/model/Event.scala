package fr.janalyse.sotohp.media.model

import java.nio.file.Path
import java.util.UUID
import scala.annotation.targetName

opaque type EventId = UUID
object EventId {
  def apply(id: UUID): EventId = id
}

opaque type EventName = String
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

opaque type EventMediaDirectory = Path
object EventMediaDirectory {
  def apply(path: Path): EventMediaDirectory = path
  extension (eventMediaDirectory: EventMediaDirectory) {
    def path: Path = eventMediaDirectory
  }
}

case class Event(
  id: EventId,
  ownerId: OwnerId,
  mediaDirectory: EventMediaDirectory, // Unicity on (ownerId/mediaDirectory)
  name: EventName,
  description: Option[EventDescription],
  keywords: Set[Keyword]
)
