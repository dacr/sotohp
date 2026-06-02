package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.json.{*, given}
import io.scalaland.chimney.*
import io.scalaland.chimney.dsl.*
import sttp.tapir.Schema

case class ApiFaceCreate(
  originalId: OriginalId,
  box: ApiBoundingBox
)

object ApiFaceCreate {
  given JsonValueCodec[ApiFaceCreate] = JsonCodecMaker.make

  given apiFaceSchema: Schema[ApiFaceCreate] = Schema.derived[ApiFaceCreate].name(Schema.SName("FaceCreate"))
}
