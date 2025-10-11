ThisBuild / pomIncludeRepository   := { _ => false }
ThisBuild / publishMavenStyle      := true
ThisBuild / Test / publishArtifact := false
ThisBuild / releaseCrossBuild      := true
ThisBuild / versionScheme          := Some("semver-spec")

// -----------------------------------------------------------------------------
ThisBuild / sonatypeCredentialHost := Sonatype.sonatypeCentralHost

ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}

ThisBuild / credentials ++= (for {
  username <- sys.env.get("SONATYPE_USERNAME")
  password <- sys.env.get("SONATYPE_PASSWORD")
} yield Credentials("Sonatype Nexus Repository Manager", "central.sonatype.com", username, password))

// -----------------------------------------------------------------------------
ThisBuild / releasePublishArtifactsAction := PgpKeys.publishSigned.value

ThisBuild / releaseTagComment        := s"Releasing ${(ThisBuild / version).value}"
ThisBuild / releaseCommitMessage     := s"Setting version to ${(ThisBuild / version).value}"
ThisBuild / releaseNextCommitMessage := s"[ci skip] Setting version to ${(ThisBuild / version).value}"

// -----------------------------------------------------------------------------
import ReleaseTransformations.*
addCommandAlias("sonaRelease", "sonatypeBundleUpload; sonatypeBundleRelease")

ThisBuild / releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  publishArtifacts,
  releaseStepCommand("sonaRelease"),
  setNextVersion,
  commitNextVersion,
  pushChanges
)

// -----------------------------------------------------------------------------
// Common POM metadata for all modules (required by Central Portal validation)
ThisBuild / homepage := Some(url("https://github.com/dacr/sotohp"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/dacr/sotohp"),
    "scm:git:git@github.com:dacr/sotohp.git",
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
