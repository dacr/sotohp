package fr.janalyse.sotohp.core

import java.nio.file.Path
import java.security.NoSuchAlgorithmException

object HashOperations {

  sealed trait HashingError extends Exception {
    def message: String
  }

  case class HashingNoSuchAlgorithm(message: String, cause: Throwable)  extends Exception(message, cause) with HashingError
  case class HashingInvalidInput(message: String)                       extends Exception(message) with HashingError
  case class HashingNoSuchFile(message: String, cause: Throwable)       extends Exception(message, cause) with HashingError
  case class HashingInputOutputError(message: String, cause: Throwable) extends Exception(message, cause) with HashingError
  case class HashingInternalError(message: String, cause: Throwable)    extends Exception(message, cause) with HashingError

  def sha1(that: String): Either[HashingError, String] = {
    import java.math.BigInteger
    import java.security.MessageDigest
    that match {
      case null    => Left(HashingInvalidInput("null input"))
      case ""      => Left(HashingInvalidInput("empty input"))
      case content =>
        try {
          val mdAlgo       = MessageDigest.getInstance("SHA-1")
          val digest       = mdAlgo.digest(content.getBytes)
          val bigInt       = new BigInteger(1, digest)
          val hashedString = bigInt.toString(16)
          Right(hashedString)
        } catch {
          case e: NoSuchAlgorithmException => Left(HashingNoSuchAlgorithm(e.getMessage, e))
          case e: Exception                => Left(HashingInternalError(e.getMessage, e))

        }
    }
  }

  def fileDigest(path: Path, algo: String = "SHA-256"): Either[HashingError, String] = {
    import java.io.FileInputStream
    import java.security.{DigestInputStream, MessageDigest}
    val buffer = new Array[Byte](8192)
    try {
      val mdAlgo = MessageDigest.getInstance(algo)
      val fis    = new FileInputStream(path.toFile)
      val dis    = new DigestInputStream(fis, mdAlgo)
      try {
        while (dis.read(buffer) != -1) {}
      } finally {
        try { dis.close() }
        finally { fis.close() }
      }
      Right(mdAlgo.digest.map("%02x".format(_)).mkString)
    } catch {
      case e: NoSuchAlgorithmException      => Left(HashingNoSuchAlgorithm(e.getMessage, e))
      case e: java.io.FileNotFoundException => Left(HashingNoSuchFile(e.getMessage, e))
      case e: java.io.IOException           => Left(HashingInputOutputError(e.getMessage, e))
      case e: Exception                     => Left(HashingInternalError(e.getMessage, e))
    }
  }
}
