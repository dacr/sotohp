package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.media.model.*
import zio.json.JsonCodec
import zio.lmdb.json.LMDBCodecJson

case class DaoDimension(
  width: Width,
  height: Height
) derives LMDBCodecJson
