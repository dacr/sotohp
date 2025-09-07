package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.*
import zio.json.JsonCodec
import fr.janalyse.sotohp.service.json.{given,*}


case class ApiDimension(
  width: Width,
  height: Height
) derives JsonCodec
