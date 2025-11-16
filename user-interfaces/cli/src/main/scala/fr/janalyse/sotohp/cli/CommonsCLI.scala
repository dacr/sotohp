package fr.janalyse.sotohp.cli

import zio.*
import zio.Runtime.removeDefaultLoggers
import zio.config.typesafe.TypesafeConfigProvider
import zio.logging.{LogFormat, consoleLogger}
//import zio.logging.backend.SLF4J

abstract class CommonsCLI extends ZIOAppDefault {
  val configProvider = TypesafeConfigProvider.fromTypesafeConfig(com.typesafe.config.ConfigFactory.load())
  val configProviderLayer = Runtime.setConfigProvider(configProvider)

  //val loggingLayer = removeDefaultLoggers >>> SLF4J.slf4j(format = LogFormat.colored)
  //val loggingLayer = zio.logging.slf4j.bridge.Slf4jBridge.initialize
  val loggingLayer = Runtime.removeDefaultLoggers >>> consoleLogger()

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = {
    loggingLayer ++ configProviderLayer
  }
}
