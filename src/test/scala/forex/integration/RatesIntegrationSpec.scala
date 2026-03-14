package forex.integration

import cats.effect.IO
import forex.domain.Currency
import forex.http.rates.Protocol
import forex.programs.RatesProgram
import forex.services.RatesServices
import io.circe.parser.decode
import org.http4s._
import org.http4s.implicits._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import Protocol._

/** Integration tests: full stack from HTTP routes through program to service (dummy backend). No real server or
  * One-Frame; exercises the full request/response pipeline.
  */
class RatesIntegrationSpec extends AnyFreeSpec with Matchers {

  "Full stack GET /rates" - {
    "returns 200 and rate from dummy service" in {
      val ratesService = RatesServices.dummy[IO]
      val program      = RatesProgram[IO](ratesService)
      val routes       = new forex.http.rates.RatesHttpRoutes[IO](program).routes
      val req          = Request[IO](Method.GET, uri"/rates?from=USD&to=JPY")
      val resp         = routes.orNotFound(req).unsafeRunSync()
      resp.status shouldEqual Status.Ok
      val body   = resp.as[String].unsafeRunSync()
      val parsed = decode[GetApiResponse](body)
      parsed shouldBe a[Right[_, _]]
      parsed.foreach { r =>
        r.from shouldEqual Currency.USD
        r.to shouldEqual Currency.JPY
        r.price.value shouldEqual BigDecimal(100)
      }
    }
    "same_currency returns 400" in {
      val ratesService = RatesServices.dummy[IO]
      val program      = RatesProgram[IO](ratesService)
      val routes       = new forex.http.rates.RatesHttpRoutes[IO](program).routes
      val req          = Request[IO](Method.GET, uri"/rates?from=EUR&to=EUR")
      val resp         = routes.orNotFound(req).unsafeRunSync()
      resp.status shouldEqual Status.BadRequest
      val body   = resp.as[String].unsafeRunSync()
      val parsed = decode[ApiError](body)
      parsed.foreach(_.code shouldEqual Some("same_currency"))
    }
  }
}
