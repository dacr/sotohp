package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.model.{BoundingBox, FaceId}
import fr.janalyse.sotohp.service.json.{*, given}
import io.scalaland.chimney.*
import io.scalaland.chimney.dsl.*
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.encodedName
import zio.json.*

case class ApiFaceCreate(
  originalId: OriginalId,
  box: ApiBoundingBox
)

object ApiFaceCreate {
  given JsonCodec[ApiFaceCreate] = DeriveJsonCodec.gen

  given apiFaceSchema: Schema[ApiFaceCreate] = Schema.derived[ApiFaceCreate].name(Schema.SName("FaceCreate"))
}
