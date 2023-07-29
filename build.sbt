organization := "fr.janalyse"
name         := "sotohp"
homepage     := Some(new URL("https://github.com/dacr/sotohp"))

licenses += "Apache 2" -> url(s"https://www.apache.org/licenses/LICENSE-2.0.txt")

scmInfo := Some(
  ScmInfo(
    url(s"https://github.com/dacr/sotohp.git"),
    s"git@github.com:dacr/sotohp.git"
  )
)

val versions = new {
  val zio      = "2.0.15"
//  val zionio     = "2.0.1"
//  val zioconfig  = "4.0.0-RC16"
  val ziojson  = "0.6.0"
//  val ziologging = "2.1.13"
  val ziolmdb  = "1.2.0"
//  val tapir      = "1.5.0"
  val metadata = "2.18.0"
  val uuidgen  = "4.2.0"
}

//val sharedSettings = Seq(
//  scalaVersion := "3.3.0",
//  Test / fork  := true,
//  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
//  scalacOptions ++= Seq("-deprecation"), // "-Xfatal-warnings",
//  excludeDependencies += "org.scala-lang.modules" % "scala-collection-compat_2.13",
//  libraryDependencies ++= Seq(
//    "dev.zio" %% "zio"            % versions.zio,
//    "dev.zio" %% "zio-test"       % versions.zio % Test,
//    "dev.zio" %% "zio-test-sbt"   % versions.zio % Test,
//    "dev.zio" %% "zio-test-junit" % versions.zio % Test
//  ),
//  Test / javaOptions                             := Seq( // -- Required for LMDB with recent JVM
//    "--add-opens",
//    "java.base/java.nio=ALL-UNNAMED",
//    "--add-opens",
//    "java.base/sun.nio.ch=ALL-UNNAMED"
//  )
//)

val sharedSettings = Seq(
  scalaVersion := "3.3.0",
  scalacOptions ++= Seq("-deprecation"), // "-Xfatal-warnings"
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio-test"     % versions.zio % Test,
    "dev.zio" %% "zio-test-sbt" % versions.zio % Test
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
)

lazy val moduleModel =
  project
    .in(file("modules/model"))
    .settings(sharedSettings)

lazy val moduleCore =
  project
    .in(file("modules/core"))
    .dependsOn(moduleModel)
    .settings(
      sharedSettings,
      libraryDependencies ++= Seq(
        "dev.zio"           %% "zio-streams"         % versions.zio,
        "dev.zio"           %% "zio-json"            % versions.ziojson,
//        "dev.zio"           %% "zio-config"          % versions.zioconfig,
//        "dev.zio"           %% "zio-config-typesafe" % versions.zioconfig,
//        "dev.zio"           %% "zio-config-magnolia" % versions.zioconfig,
        "com.drewnoakes"     % "metadata-extractor"  % versions.metadata,
        "com.fasterxml.uuid" % "java-uuid-generator" % versions.uuidgen,
        "fr.janalyse"       %% "zio-lmdb"            % versions.ziolmdb
      )
    )

lazy val userInterfacesCLI =
  project
    .in(file("user-interfaces/cli"))
    .settings(sharedSettings)
    .dependsOn(moduleCore)
    .settings(
      sharedSettings,
      libraryDependencies ++= Seq(
        "dev.zio" %% "zio" % versions.zio
      )
    )

//
//lazy val webapi =
//  project
//    .in("modules" / "webapi")
//    .dependsOn(core)
//    .enablePlugins(JavaServerAppPackaging)
//    .settings(
//      sharedSettings,
//      Universal / packageName := "sotohp",
//      Universal / javaOptions := Seq( // -- Required for LMDB with recent JVM
//        "--add-opens",
//        "java.base/java.nio=ALL-UNNAMED",
//        "--add-opens",
//        "java.base/sun.nio.ch=ALL-UNNAMED"
//      ),
//      libraryDependencies ++= Seq(
//        "dev.zio"                     %% "zio-logging"             % versions.ziologging,
//        "dev.zio"                     %% "zio-logging-slf4j"       % versions.ziologging,
//        "com.softwaremill.sttp.tapir" %% "tapir-zio"               % versions.tapir,
//        "com.softwaremill.sttp.tapir" %% "tapir-zio-http-server"   % versions.tapir,
//        "com.softwaremill.sttp.tapir" %% "tapir-json-zio"          % versions.tapir,
//        "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % versions.tapir,
//        "ch.qos.logback"               % "logback-classic"         % versions.logback
//      )
//    )
