ThisBuild / pomIncludeRepository   := { _ => false }
ThisBuild / publishMavenStyle      := true
ThisBuild / Test / publishArtifact := false
ThisBuild / releaseCrossBuild      := true
ThisBuild / versionScheme          := Some("semver-spec")

// -----------------------------------------------------------------------------
// Central Portal configuration
ThisBuild / sonatypeCredentialHost := Sonatype.sonatypeCentralHost
//ThisBuild / sonatypeProfileName := "fr.janalyse"

//ThisBuild / sonatypeRepository := "https://central.sonatype.com/api/v1/publisher"

ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else sonatypePublishToBundle.value
}

ThisBuild / credentials ++= (for {
  username <- sys.env.get("SONATYPE_USERNAME")
  password <- sys.env.get("SONATYPE_PASSWORD")
} yield Credentials("Sonatype Nexus Repository Manager", "central.sonatype.com", username, password))

// -----------------------------------------------------------------------------
ThisBuild / releasePublishArtifactsAction := PgpKeys.publishSigned.value

// -----------------------------------------------------------------------------
ThisBuild / releaseTagComment        := s"Releasing ${(ThisBuild / version).value}"
ThisBuild / releaseCommitMessage     := s"Setting version to ${(ThisBuild / version).value}"
ThisBuild / releaseNextCommitMessage := s"[ci skip] Setting version to ${(ThisBuild / version).value}"

// -----------------------------------------------------------------------------
addCommandAlias("sonaRelease", "+publishSigned; sonatypeBundleUpload; sonatypeBundleRelease")

import ReleaseTransformations.*
ThisBuild / releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  publishArtifacts,
  //releaseStepCommand("publishSigned"),
  releaseStepCommand("sonaRelease"),
  setNextVersion,
  commitNextVersion,
  pushChanges
)
