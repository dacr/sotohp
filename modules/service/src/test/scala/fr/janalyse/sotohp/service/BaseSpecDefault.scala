package fr.janalyse.sotohp.service

import com.typesafe.config.ConfigFactory
import zio.config.typesafe.TypesafeConfigProvider
import zio.test.{TestEnvironment, ZIOSpecDefault, testEnvironment}
import zio.{Runtime, ZLayer}

abstract class BaseSpecDefault extends ZIOSpecDefault {
  val configProvider = Runtime.setConfigProvider(TypesafeConfigProvider.fromTypesafeConfig(com.typesafe.config.ConfigFactory.load()))
}
