package forex.http
package rates

import cats.data.Validated.{ Invalid, Valid }
import cats.effect.Sync
import cats.syntax.flatMap._
import forex.programs.RatesProgram
import forex.programs.rates.{ errors => programErrors, Protocol => RatesProgramProtocol }
import org.http4s.{ HttpRoutes, Response, Status }
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

class RatesHttpRoutes[F[_]: Sync](rates: RatesProgram[F]) extends Http4sDsl[F] {

  import Converters._, Protocol._
  import QueryParams.{ FromValidating, ToValidating }

  private[http] val prefixPath = "/rates"

  private def toApiError(e: programErrors.Error): (Status, ApiError) = e match {
    case programErrors.Error.RateLookupFailed(msg) =>
      (Status.BadGateway, ApiError(message = msg, code = Some("rate_lookup_failed")))
    case programErrors.Error.RateLimitExceeded(msg) =>
      (Status.TooManyRequests, ApiError(message = msg, code = Some("rate_limit_exceeded")))
    case programErrors.Error.ServiceUnavailable(msg) =>
      (Status.ServiceUnavailable, ApiError(message = msg, code = Some("service_unavailable")))
  }

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root :? FromValidating(fromV) +& ToValidating(toV) =>
      (fromV, toV) match {
        case (Invalid(errors), _) =>
          BadRequest(ApiError(errors.head.sanitized, Some("invalid_currency")))
        case (_, Invalid(errors)) =>
          BadRequest(ApiError(errors.head.sanitized, Some("invalid_currency")))
        case (Valid(from), Valid(to)) if from == to =>
          BadRequest(ApiError("from and to must be different currencies", Some("same_currency")))
        case (Valid(from), Valid(to)) =>
          rates.get(RatesProgramProtocol.GetRatesRequest(from, to)).flatMap {
            case Right(rate) => Ok(rate.asGetApiResponse)
            case Left(e)     =>
              val (status, body) = toApiError(e)
              Sync[F].pure(Response(status).withEntity(body))
          }
      }
    case GET -> Root =>
      BadRequest(ApiError("Missing required query parameters: from and to", Some("missing_params")))
  }

  val routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )
}
