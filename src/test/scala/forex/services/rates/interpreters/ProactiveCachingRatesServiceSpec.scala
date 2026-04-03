package forex.services.rates.interpreters

import cats.effect.IO
import cats.effect.concurrent.Ref
import forex.domain.{ Currency, Price, Rate, Timestamp }
import forex.services.rates.errors.Error
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.OffsetDateTime

class ProactiveCachingRatesServiceSpec extends AnyFreeSpec with Matchers {

  val pair: Rate.Pair = Rate.Pair(Currency.USD, Currency.JPY)
  val rate: Rate      = Rate(pair, Price(BigDecimal(150)), Timestamp.now)

  private def mkService(
      rates: Map[Rate.Pair, Rate],
      refreshTime: Option[OffsetDateTime],
      maxAge: Long = 5
  ): IO[ProactiveCachingRatesService[IO]] =
    for {
      cache   <- Ref.of[IO, Map[Rate.Pair, Rate]](rates)
      lrt     <- Ref.of[IO, Option[OffsetDateTime]](refreshTime)
    } yield new ProactiveCachingRatesService[IO](cache, lrt, maxAge)

  "ProactiveCachingRatesService" - {
    "returns CacheNotReady when lastRefreshTime is None (not yet populated)" in {
      val result = mkService(Map.empty, None).flatMap(_.get(pair)).unsafeRunSync()
      result shouldBe Left(Error.CacheNotReady("Rates not yet available, please retry shortly"))
    }

    "returns Right(rate) when the pair is in the cache and refresh is fresh" in {
      val result = mkService(Map(pair -> rate), Some(OffsetDateTime.now)).flatMap(_.get(pair)).unsafeRunSync()
      result shouldEqual Right(rate)
    }

    "serves all 72 pairs without calling any backend" in {
      val allPairs = Rate.allPairs
      val allRates = allPairs.map(p => p -> Rate(p, Price(BigDecimal(1)), Timestamp.now)).toMap
      val test = for {
        service <- mkService(allRates, Some(OffsetDateTime.now))
        results <- allPairs.foldLeft(IO.pure(List.empty[Either[Error, Rate]])) { (acc, p) =>
                     acc.flatMap(rs => service.get(p).map(r => rs :+ r))
                   }
      } yield results
      val results = test.unsafeRunSync()
      results.size shouldEqual 72
      results.forall(_.isRight) shouldBe true
    }

    "returns CacheNotReady for a pair not in the cache even when other pairs are present" in {
      val otherPair = Rate.Pair(Currency.EUR, Currency.GBP)
      val result = mkService(
        Map(otherPair -> rate.copy(pair = otherPair)),
        Some(OffsetDateTime.now)
      ).flatMap(_.get(pair)).unsafeRunSync()
      result shouldBe a[Left[_, _]]
      result.left.foreach(_ shouldBe a[Error.CacheNotReady])
    }

    "reflects cache updates immediately (simulates background refresh)" in {
      val test = for {
        cache   <- Ref.of[IO, Map[Rate.Pair, Rate]](Map.empty)
        lrt     <- Ref.of[IO, Option[OffsetDateTime]](None)
        service = new ProactiveCachingRatesService[IO](cache, lrt, maxAgeMinutes = 5)
        before  <- service.get(pair)
        _       <- cache.set(Map(pair -> rate))
        _       <- lrt.set(Some(OffsetDateTime.now))
        after   <- service.get(pair)
      } yield (before, after)
      val (before, after) = test.unsafeRunSync()
      before shouldBe a[Left[_, _]]
      after shouldEqual Right(rate)
    }

    "returns StaleRate when lastRefreshTime is older than maxAgeMinutes (regardless of rate timestamp)" in {
      // lastRefreshTime = 6 minutes ago → stale. One-Frame's embedded timestamp is irrelevant.
      val result = mkService(
        Map(pair -> rate),
        Some(OffsetDateTime.now.minusMinutes(6)),
        maxAge = 5
      ).flatMap(_.get(pair)).unsafeRunSync()
      result shouldBe Left(Error.StaleRate("Rate is stale; please retry shortly"))
    }

    "returns Right when lastRefreshTime is within freshness window (even if rate has an old One-Frame timestamp)" in {
      // lastRefreshTime = 4 minutes ago → fresh.
      // The rate timestamp is 1 hour old to prove we don't rely on One-Frame's internal clock.
      val oldTimestampRate = rate.copy(timestamp = Timestamp(OffsetDateTime.now.minusHours(1)))
      val result = mkService(
        Map(pair -> oldTimestampRate),
        Some(OffsetDateTime.now.minusMinutes(4)),
        maxAge = 5
      ).flatMap(_.get(pair)).unsafeRunSync()
      result shouldBe a[Right[_, _]]
    }
  }
}
