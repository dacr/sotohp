package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.json.{given,*}
import zio.json.JsonCodec
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{given,*}

case class DaoDimension(
  width: Width,
  height: Height
) derives LMDBCodecJson
