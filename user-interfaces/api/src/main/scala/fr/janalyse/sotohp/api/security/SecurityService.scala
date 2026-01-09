package fr.janalyse.sotohp.api.security

import fr.janalyse.sotohp.api.{ApiConfig, AuthConfig}
import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtZIOJson}
import sttp.client4.*
import zio.*
import zio.json.*

import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory
import java.util.Base64

case class UserContext(
  subject: String,
  roles: Set[String],
  groups: Set[String]
) {
  def isAdmin: Boolean   = roles.contains("admin")
  def isReader: Boolean  = roles.contains("reader")
  def isPending: Boolean = groups.contains("pending")
}

object UserContext {
  given JsonDecoder[UserContext] = DeriveJsonDecoder.gen[UserContext]
}

sealed trait SecurityError
case class TokenMissing(message: String = "Bearer token required")          extends SecurityError
case class TokenInvalid(message: String)                                    extends SecurityError
case class TokenExpired(message: String = "Token has expired")              extends SecurityError
case class JwksError(message: String)                                       extends SecurityError
case class Unauthorized(message: String = "Insufficient permissions")       extends SecurityError
case class PendingUser(message: String = "Account pending validation")      extends SecurityError

case class JwksKey(
  kty: String,
  kid: Option[String],
  use: Option[String],
  n: Option[String],
  e: Option[String],
  alg: Option[String],
  x5c: Option[List[String]]
)

object JwksKey {
  given JsonDecoder[JwksKey] = DeriveJsonDecoder.gen[JwksKey]
}

case class Jwks(keys: List[JwksKey])

object Jwks {
  given JsonDecoder[Jwks] = DeriveJsonDecoder.gen[Jwks]
}

case class JwtPayload(
  sub: Option[String],
  iss: Option[String],
  aud: Option[String],
  exp: Option[Long],
  iat: Option[Long],
  realm_access: Option[RealmAccess],
  groups: Option[List[String]]
)

object JwtPayload {
  given JsonDecoder[JwtPayload] = DeriveJsonDecoder.gen[JwtPayload]
}

case class RealmAccess(roles: List[String])

object RealmAccess {
  given JsonDecoder[RealmAccess] = DeriveJsonDecoder.gen[RealmAccess]
}

trait SecurityService {
  def validateToken(token: String): IO[SecurityError, UserContext]
  def refreshJwks: IO[SecurityError, Unit]
}

object SecurityService {

  def validateToken(token: String): ZIO[SecurityService, SecurityError, UserContext] =
    ZIO.serviceWithZIO[SecurityService](_.validateToken(token))

  def refreshJwks: ZIO[SecurityService, SecurityError, Unit] =
    ZIO.serviceWithZIO[SecurityService](_.refreshJwks)

  val live: ZLayer[AuthConfig, Nothing, SecurityService] =
    ZLayer.scoped {
      for {
        config   <- ZIO.service[AuthConfig]
        jwksRef  <- Ref.make[Map[String, PublicKey]](Map.empty)
        backend  <- ZIO.succeed(DefaultSyncBackend())
        service   = LiveSecurityService(config, jwksRef, backend)
        _        <- service.startJwksRefresh.forkScoped
      } yield service
    }

  val disabled: ZLayer[Any, Nothing, SecurityService] =
    ZLayer.succeed(DisabledSecurityService())
}

private class LiveSecurityService(
  config: AuthConfig,
  jwksRef: Ref[Map[String, PublicKey]],
  backend: SyncBackend
) extends SecurityService {

  private val supportedAlgorithms = Seq(JwtAlgorithm.RS256, JwtAlgorithm.RS384, JwtAlgorithm.RS512)

  override def validateToken(token: String): IO[SecurityError, UserContext] =
    for {
      keys       <- jwksRef.get
      _          <- ZIO.when(keys.isEmpty)(refreshJwks)
      keys       <- jwksRef.get
      claim      <- decodeAndValidate(token, keys)
      payload    <- parsePayload(claim)
      _          <- validateIssuer(payload)
      _          <- validateAudience(payload)
      userCtx    <- extractUserContext(payload)
    } yield userCtx

  override def refreshJwks: IO[SecurityError, Unit] =
    for {
      response <- ZIO.attempt {
                    basicRequest
                      .get(uri"${config.jwksUrl}")
                      .response(asStringAlways)
                      .send(backend)
                  }.mapError(e => JwksError(s"Failed to fetch JWKS: ${e.getMessage}"))
      jwks     <- ZIO.fromEither(response.body.fromJson[Jwks])
                    .mapError(e => JwksError(s"Failed to parse JWKS: $e"))
      keys     <- ZIO.foreach(jwks.keys)(parseKey).map(_.flatten)
      keyMap    = keys.collect { case (Some(kid), key) => kid -> key }.toMap
      _        <- jwksRef.set(keyMap)
    } yield ()

  def startJwksRefresh: ZIO[Any, Nothing, Unit] =
    (refreshJwks.ignore *> ZIO.sleep(config.jwksRefreshIntervalSeconds.seconds)).forever.forkDaemon.unit

  private def decodeAndValidate(token: String, keys: Map[String, PublicKey]): IO[SecurityError, JwtClaim] = {
    val cleanToken = token.stripPrefix("Bearer ").stripPrefix("bearer ").trim

    ZIO.fromTry {
      keys.values.toList match {
        case Nil      => scala.util.Failure(new Exception("No public keys available"))
        case keysList =>
          keysList.view.map { key =>
            JwtZIOJson.decode(cleanToken, key, supportedAlgorithms)
          }.find(_.isSuccess).getOrElse(
            scala.util.Failure(new Exception("Token signature verification failed"))
          )
      }
    }.mapError {
      case e if e.getMessage.contains("expired") => TokenExpired()
      case e                                     => TokenInvalid(e.getMessage)
    }
  }

  private def parsePayload(claim: JwtClaim): IO[SecurityError, JwtPayload] =
    ZIO.fromEither(claim.content.fromJson[JwtPayload])
      .mapError(e => TokenInvalid(s"Failed to parse token payload: $e"))

  private def validateIssuer(payload: JwtPayload): IO[SecurityError, Unit] =
    ZIO.unless(payload.iss.contains(config.issuer))(
      ZIO.fail(TokenInvalid(s"Invalid issuer: expected ${config.issuer}, got ${payload.iss}"))
    ).unit

  private def validateAudience(payload: JwtPayload): IO[SecurityError, Unit] =
    config.audience match {
      case Some(expectedAud) =>
        ZIO.unless(payload.aud.contains(expectedAud))(
          ZIO.fail(TokenInvalid(s"Invalid audience: expected $expectedAud, got ${payload.aud}"))
        ).unit
      case None => ZIO.unit
    }

  private def extractUserContext(payload: JwtPayload): IO[SecurityError, UserContext] = {
    val subject = payload.sub.getOrElse("unknown")
    val roles   = payload.realm_access.map(_.roles.toSet).getOrElse(Set.empty)
    val groups  = payload.groups.map(_.toSet).getOrElse(Set.empty)
    ZIO.succeed(UserContext(subject, roles, groups))
  }

  private def parseKey(jwksKey: JwksKey): UIO[Option[(Option[String], PublicKey)]] =
    ZIO.succeed {
      jwksKey.x5c.flatMap(_.headOption).flatMap { cert =>
        try {
          val decoded     = Base64.getDecoder.decode(cert)
          val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
          val certificate = certFactory.generateCertificate(new java.io.ByteArrayInputStream(decoded))
          Some((jwksKey.kid, certificate.getPublicKey))
        } catch {
          case _: Exception => None
        }
      }.orElse {
        (for {
          n <- jwksKey.n
          e <- jwksKey.e
        } yield {
          scala.util.Try {
            val modulus  = Base64.getUrlDecoder.decode(n)
            val exponent = Base64.getUrlDecoder.decode(e)
            val spec     = new java.security.spec.RSAPublicKeySpec(
              new java.math.BigInteger(1, modulus),
              new java.math.BigInteger(1, exponent)
            )
            val factory = KeyFactory.getInstance("RSA")
            (jwksKey.kid, factory.generatePublic(spec))
          }.toOption
        }).flatten
      }
    }
}

private class DisabledSecurityService extends SecurityService {
  override def validateToken(token: String): IO[SecurityError, UserContext] =
    ZIO.succeed(UserContext("anonymous", Set("admin"), Set.empty))

  override def refreshJwks: IO[SecurityError, Unit] = ZIO.unit
}
