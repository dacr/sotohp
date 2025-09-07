package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.{EventAttachment, EventDescription, EventId, EventName, Keyword}
import zio.json.JsonCodec
import fr.janalyse.sotohp.service.json.{given,*}

case class ApiEvent(
  id: EventId,
  name: EventName,
  description: Option[EventDescription],
  keywords: Set[Keyword]
) derives JsonCodec
