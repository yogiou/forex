package forex.programs.rates

import cats.effect.IO
import forex.domain.{ Currency, Price, Rate, Timestamp }
import forex.services.RatesService
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class ProgramSpec extends AnyFreeSpec with Matchers {

  "Program.get" - {
    "returns rate when service returns Right" in {
      val pair                      = Rate.Pair(Currency.USD, Currency.JPY)
      val rate                      = Rate(pair, Price(BigDecimal(123.45)), Timestamp.now)
      val service: RatesService[IO] = _ => IO.pure(Right(rate))
      val program                   = Program[IO](service)
      val result                    = program.get(Protocol.GetRatesRequest(Currency.USD, Currency.JPY)).unsafeRunSync()
      result shouldBe Right(rate)
      result.toOption.get.pair shouldEqual pair
      result.toOption.get.price.value shouldEqual BigDecimal(123.45)
    }
    "returns RateLookupFailed when service returns OneFrameLookupFailed" in {
      val service: RatesService[IO] =
        _ => IO.pure(Left(forex.services.rates.errors.Error.OneFrameLookupFailed("upstream error")))
      val program = Program[IO](service)
      val result  = program.get(Protocol.GetRatesRequest(Currency.USD, Currency.JPY)).unsafeRunSync()
      result shouldBe Left(forex.programs.rates.errors.Error.RateLookupFailed("upstream error"))
    }
    "returns RateLimitExceeded when service returns OneFrameRateLimitExceeded" in {
      val service: RatesService[IO] = _ =>
        IO.pure(
          Left(forex.services.rates.errors.Error.OneFrameRateLimitExceeded("One-Frame 429: daily limit exceeded"))
        )
      val program = Program[IO](service)
      val result  = program.get(Protocol.GetRatesRequest(Currency.USD, Currency.JPY)).unsafeRunSync()
      result shouldBe Left(forex.programs.rates.errors.Error.RateLimitExceeded("One-Frame 429: daily limit exceeded"))
    }
    "returns ServiceUnavailable when service returns StaleRate" in {
      val service: RatesService[IO] =
        _ => IO.pure(Left(forex.services.rates.errors.Error.StaleRate("Rate is stale")))
      val program = Program[IO](service)
      val result  = program.get(Protocol.GetRatesRequest(Currency.USD, Currency.JPY)).unsafeRunSync()
      result shouldBe Left(forex.programs.rates.errors.Error.ServiceUnavailable("Rate is stale"))
    }
    "returns ServiceUnavailable when service returns CacheNotReady" in {
      val service: RatesService[IO] =
        _ => IO.pure(Left(forex.services.rates.errors.Error.CacheNotReady("Rates not yet available")))
      val program = Program[IO](service)
      val result  = program.get(Protocol.GetRatesRequest(Currency.USD, Currency.JPY)).unsafeRunSync()
      result shouldBe Left(forex.programs.rates.errors.Error.ServiceUnavailable("Rates not yet available"))
    }
  }
}
