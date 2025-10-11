ThisBuild / name         := "sotohp"
ThisBuild / organization := "fr.janalyse"
ThisBuild / description  := "Photos management made simple"

ThisBuild / licenses += "Apache 2" -> url(s"https://www.apache.org/licenses/LICENSE-2.0.txt")

ThisBuild / scalaVersion := "3.7.3"

publishArtifact := false // no artifact for "root" project

val versions = new {
  val zio        = "2.1.21"
//  val zionio     = "2.0.1"
  val zioconfig  = "4.0.5"
  val ziojson    = "0.7.44"
  val ziologging = "2.5.1"
  val ziolmdb    = "2.1.4"
  val uuidgen    = "5.1.1"
  val elastic4s  = "8.18.1"
  val metadata   = "2.19.0"
  val ulid       = "2025.1.14"
  // val javafx     = "21"
  val djl        = "0.34.0"
  val chimney    = "1.8.2"
  val tapir      = "1.11.49"
  val logback    = "1.5.19"
}

lazy val deepJavaLearningLibs = Seq(
  "ai.djl"             % "api"                  % versions.djl,
  "ai.djl"             % "basicdataset"         % versions.djl,
  "ai.djl"             % "model-zoo"            % versions.djl,
  "ai.djl.huggingface" % "tokenizers"           % versions.djl,
  "ai.djl.mxnet"       % "mxnet-engine"         % versions.djl,
  "ai.djl.mxnet"       % "mxnet-model-zoo"      % versions.djl,
  "ai.djl.pytorch"     % "pytorch-engine"       % versions.djl,
  "ai.djl.pytorch"     % "pytorch-model-zoo"    % versions.djl,
  "ai.djl.tensorflow"  % "tensorflow-engine"    % versions.djl,
  "ai.djl.tensorflow"  % "tensorflow-model-zoo" % versions.djl,
  "ai.djl.onnxruntime" % "onnxruntime-engine"   % versions.djl,
  "net.java.dev.jna"   % "jna"                  % "5.17.0"
)

lazy val lmdbJavaOptions = Seq(
  "--add-opens",
  "java.base/java.nio=ALL-UNNAMED",
  "--add-opens",
  "java.base/sun.nio.ch=ALL-UNNAMED"
)

lazy val osName = System.getProperty("os.name") match {
  case n if n.startsWith("Linux")   => "linux"
  case n if n.startsWith("Mac")     => "mac"
  case n if n.startsWith("Windows") => "win"
  case _                            => throw new Exception("Unknown platform!")
}

val sharedSettings = Seq(
  releasePublishArtifactsAction := PgpKeys.publishSigned.value, // MUST BE SET HERE TO TRIGGER THIS REQUIREMENT
  scalacOptions ++= Seq("-deprecation"), // "-Xfatal-warnings"
  Test / fork                   := true,
  Test / baseDirectory          := (ThisBuild / baseDirectory).value,
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio-test"     % versions.zio % Test,
    "dev.zio" %% "zio-test-sbt" % versions.zio % Test
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
)

// ---------------------------------------------------------------------------------------------------------------------

lazy val moduleModel =
  project
    .in(file("modules/model"))
    .settings(
      sharedSettings,
      name := "sotohp-model",
      libraryDependencies ++= Seq(
        "org.wvlet.airframe" %% "airframe-ulid" % versions.ulid
      )
    )

lazy val moduleImaging =
  project
    .in(file("modules/imaging"))
    .settings(
      sharedSettings,
      name := "sotohp-imaging",
      libraryDependencies ++= Seq(
      )
    )

lazy val moduleCore =
  project
    .in(file("modules/core"))
    .dependsOn(moduleModel)
    .settings(
      sharedSettings,
      name := "sotohp-core",
      libraryDependencies ++= Seq(
        "dev.zio"           %% "zio-streams"         % versions.zio,
        "dev.zio"           %% "zio-json"            % versions.ziojson,
        "dev.zio"           %% "zio-config"          % versions.zioconfig,
        "dev.zio"           %% "zio-config-typesafe" % versions.zioconfig,
        "dev.zio"           %% "zio-config-magnolia" % versions.zioconfig,
        "com.drewnoakes"     % "metadata-extractor"  % versions.metadata,
        "com.fasterxml.uuid" % "java-uuid-generator" % versions.uuidgen,
        "fr.janalyse"       %% "zio-lmdb"            % versions.ziolmdb
      )
    )

lazy val moduleProcessor =
  project
    .in(file("modules/processor"))
    .dependsOn(moduleCore, moduleImaging)
    .settings(
      sharedSettings,
      name := "sotohp-processor",
      fork := true,
      javaOptions ++= lmdbJavaOptions,
      libraryDependencies ++= Seq(
        // "net.coobird"        % "thumbnailator"   % "0.4.20",    // https://github.com/coobird/thumbnailator
        // "org.apache.commons" % "commons-imaging" % "1.0.0-alpha6" // https://commons.apache.org/proper/commons-imaging/
      ),
      libraryDependencies ++= deepJavaLearningLibs
    )

lazy val moduleSearch =
  project
    .in(file("modules/search"))
    .dependsOn(moduleCore, moduleProcessor)
    .settings(
      sharedSettings,
      name := "sotohp-search",
      libraryDependencies ++= Seq(
        "nl.gn0s1s" %% "elastic4s-effect-zio"    % versions.elastic4s,
        "nl.gn0s1s" %% "elastic4s-client-esjava" % versions.elastic4s,
        "nl.gn0s1s" %% "elastic4s-json-zio"      % versions.elastic4s
      )
    )

lazy val moduleService =
  project
    .in(file("modules/service"))
    .dependsOn(moduleCore, moduleSearch, moduleProcessor)
    .settings(
      sharedSettings,
      name := "sotohp-service",
      javaOptions ++= lmdbJavaOptions,
      libraryDependencies ++= Seq(
        "io.scalaland" %% "chimney" % versions.chimney
      )
    )

lazy val cli =
  project
    .in(file("user-interfaces/cli"))
    .dependsOn(moduleService)
    .settings(
      sharedSettings,
      name := "sotohp-cli",
      fork := true,
      javaOptions ++= lmdbJavaOptions,
      libraryDependencies ++= Seq(
        "dev.zio"             %% "zio"                 % versions.zio,
        "dev.zio"             %% "zio-config"          % versions.zioconfig,
        "dev.zio"             %% "zio-config-typesafe" % versions.zioconfig,
        "dev.zio"             %% "zio-config-magnolia" % versions.zioconfig,
        "dev.zio"             %% "zio-logging"         % versions.ziologging,
        "dev.zio"             %% "zio-logging-slf4j2"  % versions.ziologging,
        "ch.qos.logback"       % "logback-classic"     % versions.logback,
        "com.github.haifengl" %% "smile-scala"         % "4.3.0" // Temporary for quick&dirty evaluation of the DBSCAN clustering algo
      )
      // dependency conflict between smile and elastic4s with jackson-databind
      // dependencyOverrides += "com.fasterxml.jackson.dataformat" % "jackson-dataformat-xml" % "2.14.3" // temporary downgraded
    )

lazy val gui =
  project
    .in(file("user-interfaces/gui"))
    .dependsOn(moduleService)
    .settings(
      sharedSettings,
      name := "sotohp-gui",
      // See default.nix to setup the right environment
      // no longer needed // javaOptions ++= lmdbJavaOptions ++ Seq("--module-path", "/etc/jfx21/modules_libs/", "--add-modules", "javafx.controls"),
      // no longer needed // javaOptions ++= lmdbJavaOptions ++ Seq("--module-path", sys.env.getOrElse("OPENJFX_LIBRARY_PATH", ""), "--add-modules", "javafx.controls"),
      fork := true,
      javaOptions ++= lmdbJavaOptions,
      libraryDependencies ++= Seq(
        // "org.openjfx"  % "javafx-graphics" % versions.javafx classifier osName,
        // "org.openjfx"  % "javafx-controls" % versions.javafx classifier osName,
        "org.scalafx" %% "scalafx" % "21.0.0-R32"
      )
    )

lazy val api =
  project
    .in(file("user-interfaces/api"))
    .dependsOn(moduleService)
    .enablePlugins(JavaServerAppPackaging)
    .settings(
      sharedSettings,
      name := "sotohp-api",
      Universal / packageName := "sotohp",
      fork                    := true,
      javaOptions ++= lmdbJavaOptions,
      libraryDependencies ++= Seq(
        "dev.zio"                     %% "zio-logging"             % versions.ziologging,
        "dev.zio"                     %% "zio-logging-slf4j2"      % versions.ziologging,
        "ch.qos.logback"               % "logback-classic"         % versions.logback,
        "com.softwaremill.sttp.tapir" %% "tapir-zio"               % versions.tapir,
        "com.softwaremill.sttp.tapir" %% "tapir-zio-http-server"   % versions.tapir,
        "com.softwaremill.sttp.tapir" %% "tapir-json-zio"          % versions.tapir,
        "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % versions.tapir
      )
    )

ThisBuild / homepage   := Some(url("https://github.com/dacr/sotohp"))
ThisBuild / scmInfo    := Some(
  ScmInfo(
    url(s"https://github.com/dacr/sotohp.git"),
    s"scm:git:git@github.com:dacr/sotohp.git",
    Some("scm:git:https://github.com/dacr/sotohp.git")
  )
)
ThisBuild / developers := List(
  Developer(
    id = "dacr",
    name = "David Crosson",
    email = "crosson.david@gmail.com",
    url = url("https://github.com/dacr")
  )
)
