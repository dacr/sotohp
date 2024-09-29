ThisBuild / pomIncludeRepository := { _ => false }

ThisBuild / releaseCrossBuild := true
ThisBuild / releasePublishArtifactsAction := PgpKeys.publishSigned.value
ThisBuild / publishMavenStyle    := true
ThisBuild / Test / publishArtifact := false
ThisBuild / Compile / packageBin / publishArtifact := true
ThisBuild / Compile / packageDoc / publishArtifact := false
ThisBuild / Compile / packageSrc / publishArtifact := true
ThisBuild / publishTo := Some(if (isSnapshot.value) Opts.resolver.sonatypeOssSnapshots.head else Opts.resolver.sonatypeStaging)

Global / PgpKeys.useGpg := true      // workaround with pgp and sbt 1.2.x
ThisBuild / pgpSecretRing := pgpPublicRing.value // workaround with pgp and sbt 1.2.x

ThisBuild / pomExtra in Global := {
  <developers>
    <developer>
      <id>dacr</id>
      <name>David Crosson</name>
      <url>https://github.com/dacr</url>
    </developer>
  </developers>
}

ThisBuild / releaseTagComment := s"Releasing ${(ThisBuild / version).value}"
ThisBuild / releaseCommitMessage := s"Setting version to ${(ThisBuild / version).value}"
ThisBuild / releaseNextCommitMessage := s"[ci skip] Setting version to ${(ThisBuild / version).value}"

import ReleaseTransformations.*
ThisBuild / releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  //runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  publishArtifacts,
  setNextVersion,
  commitNextVersion,
  releaseStepCommand("sonatypeReleaseAll"),
  pushChanges
)
