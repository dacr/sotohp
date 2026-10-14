package fr.janalyse.sotohp.cli

import zio.*
import zio.config.typesafe.TypesafeConfigProvider
import zio.logging.{ConsoleLoggerConfig, consoleLogger}

abstract class CommonsCLI extends ZIOAppDefault {
  val configProvider      = TypesafeConfigProvider.fromTypesafeConfig(com.typesafe.config.ConfigFactory.load())
  val configProviderLayer = Runtime.setConfigProvider(configProvider)

  // The logger configuration is explicitly read through the typesafe provider : the bootstrap layers are built
  // concurrently, so relying on configProviderLayer would silently fall back to the default format (no annotations)
  val loggingLayer =
    Runtime.removeDefaultLoggers >>>
      ZLayer.fromZIO(ZIO.withConfigProvider(configProvider)(ConsoleLoggerConfig.load())).flatMap(env => consoleLogger(env.get))

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = {
    loggingLayer ++ configProviderLayer
  }
}
