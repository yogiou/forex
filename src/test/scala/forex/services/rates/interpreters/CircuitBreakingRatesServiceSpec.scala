package forex.services.rates.interpreters

import cats.effect.IO
import cats.effect.concurrent.Ref
import forex.config.CircuitBreakerConfig
import forex.domain.{ Currency, Price, Rate, Timestamp }
import forex.services.rates.Algebra
import forex.services.rates.errors.Error
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class CircuitBreakingRatesServiceSpec extends AnyFreeSpec with Matchers {

  "CircuitBreakingRatesService" - {
    "when circuit is closed, delegates to backend and returns Right(rate)" in {
      val pair                 = Rate.Pair(Currency.USD, Currency.JPY)
      val rate                 = Rate(pair, Price(BigDecimal(100)), Timestamp.now)
      val backend: Algebra[IO] = _ => IO.pure(Right(rate))
      val config               = CircuitBreakerConfig(enabled = true, minimumNumberOfCalls = 1, slidingWindowSize = 1)
      val cb                   = CircuitBreakerFactory.create("test", config)
      val service              = new CircuitBreakingRatesService[IO](backend, cb)
      val result               = service.get(pair).unsafeRunSync()
      result shouldBe Right(rate)
    }

    "when circuit is closed and backend returns Left, propagates Left" in {
      val pair                 = Rate.Pair(Currency.USD, Currency.JPY)
      val backend: Algebra[IO] = _ => IO.pure(Left(Error.OneFrameLookupFailed("down")))
      val config               = CircuitBreakerConfig(enabled = true, minimumNumberOfCalls = 1, slidingWindowSize = 1)
      val cb                   = CircuitBreakerFactory.create("test2", config)
      val service              = new CircuitBreakingRatesService[IO](backend, cb)
      val result               = service.get(pair).unsafeRunSync()
      result shouldBe Left(Error.OneFrameLookupFailed("down"))
    }

    "when backend returns OneFrameRateLimitExceeded, records success for breaker (rate-limit does not open circuit)" in {
      val pair                 = Rate.Pair(Currency.USD, Currency.JPY)
      val backend: Algebra[IO] = _ => IO.pure(Left(Error.OneFrameRateLimitExceeded("429 quota")))
      val config               = CircuitBreakerConfig(enabled = true, minimumNumberOfCalls = 2, slidingWindowSize = 2, failureRateThreshold = 50f)
      val cb                   = CircuitBreakerFactory.create("test-rate-limit", config)
      val service              = new CircuitBreakingRatesService[IO](backend, cb)
      (0 until 2).foreach(_ => service.get(pair).unsafeRunSync())
      val third = service.get(pair).unsafeRunSync()
      third shouldBe Left(Error.OneFrameRateLimitExceeded("429 quota"))
    }

    "when circuit opens after enough failures, returns Left(Circuit breaker open) without calling backend again" in {
      val pair   = Rate.Pair(Currency.USD, Currency.JPY)
      val config = CircuitBreakerConfig(
        enabled = true,
        failureRateThreshold = 50f,
        minimumNumberOfCalls = 2,
        slidingWindowSize = 2
      )
      val cb   = CircuitBreakerFactory.create("test3", config)
      val test = for {
        callCount <- Ref.of[IO, Int](0)
        backend = new Algebra[IO] {
                    override def get(p: Rate.Pair): IO[Error Either Rate] =
                      callCount.update(_ + 1).as(Left(Error.OneFrameLookupFailed("fail")))
                  }
        service = new CircuitBreakingRatesService[IO](backend, cb)
        _ <- service.get(pair)
        _ <- service.get(pair)
        countAfterTwo <- callCount.get
        third <- service.get(pair)
        countAfterThree <- callCount.get
      } yield (countAfterTwo, third, countAfterThree)
      val (count2, thirdResult, count3) = test.unsafeRunSync()
      count2 shouldEqual 2
      thirdResult shouldBe Left(Error.OneFrameLookupFailed("Circuit breaker open"))
      count3 shouldEqual 2
    }
  }
}
