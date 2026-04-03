package forex.services.rates.interpreters

import cats.data.Kleisli
import cats.effect.IO
import forex.domain.{ Currency, Rate }
import forex.services.rates.errors.Error
import io.circe.Json
import org.http4s._
import org.http4s.circe._
import org.http4s.client.Client
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class OneFrameLiveSpec extends AnyFreeSpec with Matchers {

  private val token = "test-token"

  private def mockClient(f: Request[IO] => IO[Response[IO]]): Client[IO] =
    Client.fromHttpApp(Kleisli(f))

  private def staticClient(resp: Response[IO]): Client[IO] =
    mockClient(_ => IO.pure(resp))

  private def rateJson(from: String, to: String, price: BigDecimal, ts: String): Json =
    Json.obj(
      "from"       -> Json.fromString(from),
      "to"         -> Json.fromString(to),
      "bid"        -> Json.fromBigDecimal(price),
      "ask"        -> Json.fromBigDecimal(price),
      "price"      -> Json.fromBigDecimal(price),
      "time_stamp" -> Json.fromString(ts)
    )

  "OneFrameLive" - {
    "getAll returns parsed rates for a successful multi-pair response" in {
      val json   = Json.arr(rateJson("USD", "JPY", 0.71, "2024-01-01T00:00:00.000Z"))
      val client = staticClient(Response[IO](Status.Ok).withEntity(json))
      val service = new OneFrameLive[IO](client, "http://localhost:8080", token)
      val pair    = Rate.Pair(Currency.USD, Currency.JPY)
      val result  = service.getAll(List(pair)).unsafeRunSync()
      result shouldBe a[Right[_, _]]
      result.foreach { rates =>
        rates.size shouldEqual 1
        rates(pair).price.value shouldEqual BigDecimal(0.71)
      }
    }

    "getAll handles timestamps without timezone offset (assumes UTC)" in {
      val json   = Json.arr(rateJson("USD", "JPY", 0.71, "2024-01-01T00:00:00.000"))
      val client = staticClient(Response[IO](Status.Ok).withEntity(json))
      val service = new OneFrameLive[IO](client, "http://localhost:8080", token)
      val pair    = Rate.Pair(Currency.USD, Currency.JPY)
      val result  = service.getAll(List(pair)).unsafeRunSync()
      result shouldBe a[Right[_, _]]
    }

    "getAll returns OneFrameRateLimitExceeded on 429 response" in {
      val client  = staticClient(Response[IO](Status.TooManyRequests).withEntity("rate limit exceeded"))
      val service = new OneFrameLive[IO](client, "http://localhost:8080", token)
      val pair    = Rate.Pair(Currency.USD, Currency.JPY)
      val result  = service.getAll(List(pair)).unsafeRunSync()
      result shouldBe a[Left[_, _]]
      result.left.foreach(_ shouldBe a[Error.OneFrameRateLimitExceeded])
    }

    "getAll returns OneFrameRateLimitExceeded on 403 response" in {
      val client  = staticClient(Response[IO](Status.Forbidden).withEntity("forbidden"))
      val service = new OneFrameLive[IO](client, "http://localhost:8080", token)
      val pair    = Rate.Pair(Currency.USD, Currency.JPY)
      val result  = service.getAll(List(pair)).unsafeRunSync()
      result shouldBe a[Left[_, _]]
      result.left.foreach(_ shouldBe a[Error.OneFrameRateLimitExceeded])
    }

    "getAll returns OneFrameLookupFailed on 500 response" in {
      val client  = staticClient(Response[IO](Status.InternalServerError).withEntity("internal error"))
      val service = new OneFrameLive[IO](client, "http://localhost:8080", token)
      val pair    = Rate.Pair(Currency.USD, Currency.JPY)
      val result  = service.getAll(List(pair)).unsafeRunSync()
      result shouldBe a[Left[_, _]]
      result.left.foreach(_ shouldBe a[Error.OneFrameLookupFailed])
    }

    "getAll returns OneFrameLookupFailed on network exception" in {
      val client  = mockClient(_ => IO.raiseError(new java.net.ConnectException("Connection refused")))
      val service = new OneFrameLive[IO](client, "http://localhost:8080", token)
      val pair    = Rate.Pair(Currency.USD, Currency.JPY)
      val result  = service.getAll(List(pair)).unsafeRunSync()
      result shouldBe a[Left[_, _]]
      result.left.foreach(_ shouldBe a[Error.OneFrameLookupFailed])
    }

    "getAll fetches multiple pairs in a single request" in {
      val json = Json.arr(
        rateJson("USD", "JPY", 0.71, "2024-01-01T00:00:00.000Z"),
        rateJson("EUR", "GBP", 0.86, "2024-01-01T00:00:00.000Z")
      )
      val client  = staticClient(Response[IO](Status.Ok).withEntity(json))
      val service = new OneFrameLive[IO](client, "http://localhost:8080", token)
      val pairs   = List(Rate.Pair(Currency.USD, Currency.JPY), Rate.Pair(Currency.EUR, Currency.GBP))
      val result  = service.getAll(pairs).unsafeRunSync()
      result shouldBe a[Right[_, _]]
      result.foreach(_.size shouldEqual 2)
    }

    "getAll sends correct query parameters and token header" in {
      var capturedReq: Option[Request[IO]] = None
      val json   = Json.arr(rateJson("USD", "JPY", 0.71, "2024-01-01T00:00:00.000Z"))
      val client = mockClient { req =>
        capturedReq = Some(req)
        IO.pure(Response[IO](Status.Ok).withEntity(json))
      }
      val service = new OneFrameLive[IO](client, "http://localhost:8080", token)
      val pair    = Rate.Pair(Currency.USD, Currency.JPY)
      val result  = service.getAll(List(pair)).unsafeRunSync()
      result shouldBe a[Right[_, _]]
      capturedReq.isDefined shouldBe true
      capturedReq.foreach { req =>
        req.headers.toString should include(token)
        req.uri.query.multiParams("pair") should contain("USDJPY")
      }
    }

    "get delegates to getAll and extracts single pair" in {
      val json    = Json.arr(rateJson("EUR", "GBP", 0.86, "2024-06-15T12:30:00.000Z"))
      val client  = staticClient(Response[IO](Status.Ok).withEntity(json))
      val service = new OneFrameLive[IO](client, "http://localhost:8080", token)
      val pair    = Rate.Pair(Currency.EUR, Currency.GBP)
      val result  = service.get(pair).unsafeRunSync()
      result shouldBe a[Right[_, _]]
      result.foreach { rate =>
        rate.pair shouldEqual pair
        rate.price.value shouldEqual BigDecimal(0.86)
      }
    }
  }
}
