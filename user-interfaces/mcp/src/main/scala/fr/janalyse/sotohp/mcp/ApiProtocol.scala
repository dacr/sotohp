package fr.janalyse.sotohp.mcp

import zio.json.*
import fr.janalyse.sotohp.model.*
import java.util.UUID
import java.time.OffsetDateTime

// --- Local Types to avoid heavy dependencies ---
opaque type PersonId = String
object PersonId {
  def apply(id: String): PersonId = id
  extension (id: PersonId) def asString: String = id
}

// --- Codecs for Model Types ---

object ApiCodecs {
  implicit val uuidDecoder: JsonDecoder[UUID] = JsonDecoder.string.map(UUID.fromString)
  implicit val offsetDateTimeDecoder: JsonDecoder[OffsetDateTime] = JsonDecoder.string.map(OffsetDateTime.parse)
  
  // Opaque types decoders - mirroring what was likely in service.json
  implicit val mediaAccessKeyDecoder: JsonDecoder[MediaAccessKey] = JsonDecoder.string.map(MediaAccessKey.apply)
  implicit val eventIdDecoder: JsonDecoder[EventId] = uuidDecoder.map(EventId.apply)
  implicit val personIdDecoder: JsonDecoder[PersonId] = JsonDecoder.string.map(PersonId.apply)
  implicit val originalIdDecoder: JsonDecoder[OriginalId] = uuidDecoder.map(OriginalId.apply)
  
  // Add other necessary decoders as simple strings if strict typing isn't crucial for MCP output text
  implicit val eventNameDecoder: JsonDecoder[EventName] = JsonDecoder.string.map(EventName.apply)
  implicit val firstNameDecoder: JsonDecoder[FirstName] = JsonDecoder.string.map(FirstName.apply)
  implicit val lastNameDecoder: JsonDecoder[LastName] = JsonDecoder.string.map(LastName.apply)
  implicit val keywordDecoder: JsonDecoder[Keyword] = JsonDecoder.string.map(Keyword.apply)
  implicit val mediaDescriptionDecoder: JsonDecoder[MediaDescription] = JsonDecoder.string.map(MediaDescription.apply)
}

import ApiCodecs._

// --- DTOs ---

case class ApiMedia(
  accessKey: MediaAccessKey,
  description: Option[MediaDescription],
  shootDateTime: Option[ShootDateTime],
  timestamp: Option[OffsetDateTime],
  keywords: Set[Keyword] = Set.empty
)

object ApiMedia {
  implicit val shootDateTimeDecoder: JsonDecoder[ShootDateTime] = offsetDateTimeDecoder.map(ShootDateTime.apply)
  implicit val decoder: JsonDecoder[ApiMedia] = DeriveJsonDecoder.gen[ApiMedia]
}

case class ApiEvent(
  id: EventId,
  name: EventName,
  timestamp: Option[OffsetDateTime] // ApiEvent has timestamp: Option[ShootDateTime]
)

object ApiEvent {
  implicit val shootDateTimeDecoder: JsonDecoder[ShootDateTime] = offsetDateTimeDecoder.map(ShootDateTime.apply)
  implicit val decoder: JsonDecoder[ApiEvent] = DeriveJsonDecoder.gen[ApiEvent]
}

case class ApiPerson(
  id: PersonId,
  firstName: FirstName,
  lastName: LastName
)

object ApiPerson {
  implicit val decoder: JsonDecoder[ApiPerson] = DeriveJsonDecoder.gen[ApiPerson]
}
