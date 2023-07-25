package fr.janalyse.sotohp.core

import java.nio.file.Path

object HashOps {

  def sha1(that: String): String = {
    import java.math.BigInteger
    import java.security.MessageDigest
    val content      = if (that == null) "" else that     // TODO - probably discutable, migrate to an effect
    val md           = MessageDigest.getInstance("SHA-1") // TODO - can fail => potential border side effect !
    val digest       = md.digest(content.getBytes)
    val bigInt       = new BigInteger(1, digest)
    val hashedString = bigInt.toString(16)
    hashedString
  }

  def fileDigest(path: Path, algo: String = "SHA-256"): String = {
    import java.math.BigInteger
    import java.security.{MessageDigest, DigestInputStream}
    import java.io.FileInputStream
    val buffer = new Array[Byte](8192)
    val md5    = MessageDigest.getInstance(algo)
    val dis    = new DigestInputStream(new FileInputStream(path.toFile), md5)
    try { while (dis.read(buffer) != -1) {} }
    finally { dis.close() }
    md5.digest.map("%02x".format(_)).mkString
  }
}
