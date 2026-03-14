package forex.services.rates.interpreters

import java.time.Instant

import cats.effect.IO
import cats.effect.concurrent.Ref
import forex.domain.{ Currency, Price, Rate, Timestamp }
import forex.services.rates.Algebra
import forex.services.rates.errors._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class CachingRatesServiceSpec extends AnyFreeSpec with Matchers {

  "CachingRatesService" - {
    "returns cached rate on second call within TTL (does not call backend twice)" in {
      val pair       = Rate.Pair(Currency.USD, Currency.JPY)
      val rate       = Rate(pair, Price(BigDecimal(150)), Timestamp.now)
      val ttlMinutes = 5L
      val test       = for {
        callCount <- Ref.of[IO, Int](0)
        backend = new Algebra[IO] {
                    override def get(p: Rate.Pair): IO[Error Either Rate] =
                      callCount.update(_ + 1).as(Right(rate))
                  }
        cache <- Ref.of[IO, Map[Rate.Pair, (Rate, Instant)]](Map.empty)
        caching = new CachingRatesService[IO](backend, cache, ttlMinutes)
        _ <- caching.get(pair)
        _ <- caching.get(pair)
        cnt <- callCount.get
      } yield cnt
      test.unsafeRunSync() shouldEqual 1
    }
    "calls backend again when cache is empty" in {
      val pair       = Rate.Pair(Currency.USD, Currency.JPY)
      val rate       = Rate(pair, Price(BigDecimal(150)), Timestamp.now)
      val ttlMinutes = 5L
      val test       = for {
        callCount <- Ref.of[IO, Int](0)
        backend = new Algebra[IO] {
                    override def get(p: Rate.Pair): IO[Error Either Rate] =
                      callCount.update(_ + 1).as(Right(rate))
                  }
        cache <- Ref.of[IO, Map[Rate.Pair, (Rate, Instant)]](Map.empty)
        caching = new CachingRatesService[IO](backend, cache, ttlMinutes)
        r1 <- caching.get(pair)
        cnt <- callCount.get
      } yield (cnt, r1)
      val (cnt, r1) = test.unsafeRunSync()
      cnt shouldEqual 1
      r1 shouldBe Right(rate)
    }
    "propagates backend Left (does not cache errors)" in {
      val pair                 = Rate.Pair(Currency.USD, Currency.JPY)
      val backend: Algebra[IO] = _ => IO.pure(Left(Error.OneFrameLookupFailed("down")))
      val ttlMinutes           = 5L
      val test                 = for {
        cache <- Ref.of[IO, Map[Rate.Pair, (Rate, Instant)]](Map.empty)
        caching = new CachingRatesService[IO](backend, cache, ttlMinutes)
        r1 <- caching.get(pair)
        r2 <- caching.get(pair)
      } yield (r1, r2)
      val (r1, r2) = test.unsafeRunSync()
      r1 shouldBe Left(Error.OneFrameLookupFailed("down"))
      r2 shouldBe Left(Error.OneFrameLookupFailed("down"))
    }
  }
}
