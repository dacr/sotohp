package fr.janalyse.sotohp.api.security

import fr.janalyse.sotohp.api.AuthConfig
import fr.janalyse.sotohp.api.protocol.{ApiIssue, ApiSecurityError}
import sttp.model.StatusCode
import sttp.tapir.{EndpointInput, EndpointOutput, Schema, auth, oneOf, oneOfVariant}
import sttp.tapir.json.zio.jsonBody
import zio.*
import zio.json.*

object SecureEndpoints {

  type SecureApiEnv = SecurityService

  private def securityErrorToApi(error: SecurityError): ApiSecurityError = error match {
    case TokenMissing(msg)  => ApiSecurityError(msg)
    case TokenInvalid(msg)  => ApiSecurityError(msg)
    case TokenExpired(msg)  => ApiSecurityError(msg)
    case JwksError(msg)     => ApiSecurityError(msg)
    case Unauthorized(msg)  => ApiSecurityError(msg)
    case PendingUser(msg)   => ApiSecurityError(msg)
  }

  val securityErrorOutput: EndpointOutput.OneOf[ApiSecurityError, ApiSecurityError] =
    oneOf[ApiSecurityError](
      oneOfVariant(StatusCode.Unauthorized, jsonBody[ApiSecurityError].description("Authentication failed")),
      oneOfVariant(StatusCode.Forbidden, jsonBody[ApiSecurityError].description("Insufficient permissions"))
    )

  val bearerAuth: EndpointInput[String] =
    auth.bearer[String]()

  def securityLogic(authConfig: AuthConfig)(token: String): ZIO[SecurityService, ApiSecurityError, UserContext] =
    if (!authConfig.enabled) {
      ZIO.succeed(UserContext("anonymous", Set("admin"), Set.empty))
    } else {
      if (token.isEmpty) ZIO.fail(ApiSecurityError("Bearer token required"))
      else SecurityService.validateToken(token).mapError(securityErrorToApi)
    }

  def requireAdmin(user: UserContext): IO[ApiSecurityError, UserContext] =
    if (user.isAdmin) ZIO.succeed(user)
    else ZIO.fail(ApiSecurityError("Admin role required"))

  def requireNotPending(user: UserContext): IO[ApiSecurityError, UserContext] =
    if (user.isPending) ZIO.fail(ApiSecurityError("Account pending validation"))
    else ZIO.succeed(user)

  def requireWriteAccess(user: UserContext): IO[ApiSecurityError, UserContext] =
    if (user.isAdmin) ZIO.succeed(user)
    else if (user.isReader) ZIO.fail(ApiSecurityError("Write access denied for reader role"))
    else ZIO.succeed(user)

  def requireReadAccess(user: UserContext): IO[ApiSecurityError, UserContext] =
    if (user.isAdmin || user.isReader) ZIO.succeed(user)
    else ZIO.fail(ApiSecurityError("Read access required"))
}
