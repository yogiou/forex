package forex.http.rates

import cats.effect.IO
import forex.domain.{ Currency, Price, Rate, Timestamp }
import forex.programs.RatesProgram
import forex.http.rates.Protocol.{ ApiError, GetApiResponse }
import forex.programs.rates.errors.{ Error => ProgramError }
import io.circe.parser.decode
import org.http4s._
import org.http4s.implicits._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class RatesHttpRoutesSpec extends AnyFreeSpec with Matchers {

  def runRequest(routes: HttpRoutes[IO], req: Request[IO]): Response[IO] =
    routes.orNotFound(req).unsafeRunSync()

  "RatesHttpRoutes" - {
    "GET /rates?from=USD&to=JPY returns 200 and rate body when program returns Right" in {
      val pair                      = Rate.Pair(Currency.USD, Currency.JPY)
      val rate                      = Rate(pair, Price(BigDecimal(123.45)), Timestamp.now)
      val program: RatesProgram[IO] = _ => IO.pure(Right(rate))
      val routes                    = new RatesHttpRoutes[IO](program).routes
      val req                       = Request[IO](Method.GET, uri"/rates?from=USD&to=JPY")
      val resp                      = runRequest(routes, req)
      resp.status shouldEqual Status.Ok
      val body    = resp.as[String].unsafeRunSync()
      val decoded = decode[GetApiResponse](body)
      decoded shouldBe a[Right[_, _]]
      decoded.foreach { r =>
        r.from shouldEqual Currency.USD
        r.to shouldEqual Currency.JPY
        r.price.value shouldEqual BigDecimal(123.45)
      }
    }
    "GET /rates without from/to returns 400 with missing_params" in {
      val program: RatesProgram[IO] = _ => IO.pure(Left(ProgramError.RateLookupFailed("unused")))
      val routes                    = new RatesHttpRoutes[IO](program).routes
      val req                       = Request[IO](Method.GET, uri"/rates")
      val resp                      = runRequest(routes, req)
      resp.status shouldEqual Status.BadRequest
      val body    = resp.as[String].unsafeRunSync()
      val decoded = decode[ApiError](body)
      decoded shouldBe a[Right[_, _]]
      decoded.foreach { e =>
        e.code shouldEqual Some("missing_params")
        e.message should include("from")
        e.message should include("to")
      }
    }
    "GET /rates?from=USD&to=USD returns 400 with same_currency" in {
      val program: RatesProgram[IO] = _ => IO.pure(Left(ProgramError.RateLookupFailed("unused")))
      val routes                    = new RatesHttpRoutes[IO](program).routes
      val req                       = Request[IO](Method.GET, uri"/rates?from=USD&to=USD")
      val resp                      = runRequest(routes, req)
      resp.status shouldEqual Status.BadRequest
      val body    = resp.as[String].unsafeRunSync()
      val decoded = decode[ApiError](body)
      decoded.foreach { e =>
        e.code shouldEqual Some("same_currency")
        e.message should include("different")
      }
    }
    "GET /rates?from=USD&to=JPY returns 502 and rate_lookup_failed when program returns Left" in {
      val program: RatesProgram[IO] = _ => IO.pure(Left(ProgramError.RateLookupFailed("One-Frame down")))
      val routes                    = new RatesHttpRoutes[IO](program).routes
      val req                       = Request[IO](Method.GET, uri"/rates?from=USD&to=JPY")
      val resp                      = runRequest(routes, req)
      resp.status shouldEqual Status.BadGateway
      val body    = resp.as[String].unsafeRunSync()
      val decoded = decode[ApiError](body)
      decoded.foreach { e =>
        e.code shouldEqual Some("rate_lookup_failed")
        e.message should include("One-Frame down")
      }
    }
    "GET /rates?from=USD&to=JPY returns 429 and rate_limit_exceeded when One-Frame daily limit exceeded" in {
      val program: RatesProgram[IO] =
        _ => IO.pure(Left(ProgramError.RateLimitExceeded("One-Frame 429: daily limit exceeded")))
      val routes = new RatesHttpRoutes[IO](program).routes
      val req    = Request[IO](Method.GET, uri"/rates?from=USD&to=JPY")
      val resp   = runRequest(routes, req)
      resp.status shouldEqual Status.TooManyRequests
      val body    = resp.as[String].unsafeRunSync()
      val decoded = decode[ApiError](body)
      decoded.foreach { e =>
        e.code shouldEqual Some("rate_limit_exceeded")
        e.message should include("429")
      }
    }
    "GET /rates?from=XXX&to=JPY returns 400 for invalid currency" in {
      val program: RatesProgram[IO] = _ => IO.pure(Left(ProgramError.RateLookupFailed("unused")))
      val routes                    = new RatesHttpRoutes[IO](program).routes
      val req                       = Request[IO](Method.GET, uri"/rates?from=XXX&to=JPY")
      val resp                      = runRequest(routes, req)
      resp.status shouldEqual Status.BadRequest
    }
    "GET /rates?from=USD&to=JPY returns 503 and service_unavailable when cache is not ready" in {
      val program: RatesProgram[IO] =
        _ => IO.pure(Left(ProgramError.ServiceUnavailable("Rates not yet available, please retry shortly")))
      val routes = new RatesHttpRoutes[IO](program).routes
      val req    = Request[IO](Method.GET, uri"/rates?from=USD&to=JPY")
      val resp   = runRequest(routes, req)
      resp.status shouldEqual Status.ServiceUnavailable
      val body    = resp.as[String].unsafeRunSync()
      val decoded = decode[ApiError](body)
      decoded shouldBe a[Right[_, _]]
      decoded.foreach { e =>
        e.code shouldEqual Some("service_unavailable")
        e.message should include("retry")
      }
    }
    "GET /rates?from=XXX&to=JPY returns 400 with invalid_currency code and descriptive message" in {
      val program: RatesProgram[IO] = _ => IO.pure(Left(ProgramError.RateLookupFailed("unused")))
      val routes                    = new RatesHttpRoutes[IO](program).routes
      val req                       = Request[IO](Method.GET, uri"/rates?from=XXX&to=JPY")
      val resp                      = runRequest(routes, req)
      resp.status shouldEqual Status.BadRequest
      val body    = resp.as[String].unsafeRunSync()
      val decoded = decode[ApiError](body)
      decoded shouldBe a[Right[_, _]]
      decoded.foreach { e =>
        e.code shouldEqual Some("invalid_currency")
        e.message.nonEmpty shouldBe true
        e.message should include("currency")
      }
    }
  }
}
