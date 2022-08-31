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
  val zio        = "2.0.0"
  val zionio     = "2.0.0"
  val zioconfig  = "3.0.1"
  val ziojson    = "0.3.0-RC10"
  val ziologging = "2.0.1"
  val ziolmdb    = "0.0.2"
  val tapir      = "1.0.5"
  val logback    = "1.2.11"
  val metadata   = "2.18.0"
  val uuidgen    = "4.0.1"
}

val sharedSettings = Seq(
  scalaVersion := "3.1.3",
  Test / fork  := true,
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  scalacOptions ++= Seq("-deprecation"), // "-Xfatal-warnings",
  excludeDependencies += "org.scala-lang.modules" % "scala-collection-compat_2.13",
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio"            % versions.zio,
    "dev.zio" %% "zio-test"       % versions.zio % Test,
    "dev.zio" %% "zio-test-sbt"   % versions.zio % Test,
    "dev.zio" %% "zio-test-junit" % versions.zio % Test
  ),
  Test / javaOptions                             := Seq( // -- Required for LMDB with recent JVM
    "--add-opens",
    "java.base/java.nio=ALL-UNNAMED",
    "--add-opens",
    "java.base/sun.nio.ch=ALL-UNNAMED"
  )
)

lazy val core =
  project
    .in(file("core"))
    .settings(
      sharedSettings,
      libraryDependencies ++= Seq(
        "dev.zio"           %% "zio-streams"         % versions.zio,
        "dev.zio"           %% "zio-nio"             % versions.zionio,
        "dev.zio"           %% "zio-config"          % versions.zioconfig,
        "dev.zio"           %% "zio-config-typesafe" % versions.zioconfig,
        "dev.zio"           %% "zio-config-magnolia" % versions.zioconfig,
        "com.drewnoakes"     % "metadata-extractor"  % versions.metadata,
        "com.fasterxml.uuid" % "java-uuid-generator" % versions.uuidgen,
        "fr.janalyse"       %% "zio-lmdb"            % versions.ziolmdb
      )
    )

lazy val webapi =
  project
    .in(file("webapi"))
    .dependsOn(core)
    .enablePlugins(JavaServerAppPackaging)
    .settings(
      sharedSettings,
      Universal / packageName := "sotohp",
      Universal / javaOptions := Seq( // -- Required for LMDB with recent JVM
        "--add-opens",
        "java.base/java.nio=ALL-UNNAMED",
        "--add-opens",
        "java.base/sun.nio.ch=ALL-UNNAMED"
      ),
      libraryDependencies ++= Seq(
        "dev.zio"                     %% "zio-logging"             % versions.ziologging,
        "dev.zio"                     %% "zio-logging-slf4j"       % versions.ziologging,
        "com.softwaremill.sttp.tapir" %% "tapir-zio"               % versions.tapir,
        "com.softwaremill.sttp.tapir" %% "tapir-zio-http-server"   % versions.tapir,
        "com.softwaremill.sttp.tapir" %% "tapir-json-zio"          % versions.tapir,
        "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % versions.tapir,
        "ch.qos.logback"               % "logback-classic"         % versions.logback
      )
    )
