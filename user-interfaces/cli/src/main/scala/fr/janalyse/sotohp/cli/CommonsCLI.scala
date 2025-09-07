package fr.janalyse.sotohp.cli

import zio.*
import zio.Runtime.removeDefaultLoggers
import zio.config.typesafe.TypesafeConfigProvider
import zio.logging.LogFormat
import zio.logging.backend.SLF4J

abstract class CommonsCLI extends ZIOAppDefault {
  val configProvider = TypesafeConfigProvider.fromTypesafeConfig(com.typesafe.config.ConfigFactory.load())
  val configProviderLayer = Runtime.setConfigProvider(configProvider)

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = {
    val loggingLayer = removeDefaultLoggers >>> SLF4J.slf4j(format = LogFormat.colored)
    loggingLayer ++ configProviderLayer
  }
}
