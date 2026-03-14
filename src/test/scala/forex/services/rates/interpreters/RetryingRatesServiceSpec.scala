package forex.services.rates.interpreters

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.concurrent.Ref
import forex.domain.{ Currency, Price, Rate, Timestamp }
import forex.services.rates.Algebra
import forex.services.rates.errors.Error
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class RetryingRatesServiceSpec extends AnyFreeSpec with Matchers {

  private implicit val timer: cats.effect.Timer[IO] = IO.timer(ExecutionContext.global)

  val pair      = Rate.Pair(Currency.USD, Currency.JPY)
  val rate      = Rate(pair, Price(BigDecimal(100)), Timestamp.now)
  val waitShort = 10.millis

  "RetryingRatesService" - {
    "returns Right on first success without retrying" in {
      val backend: Algebra[IO] = _ => IO.pure(Right(rate))
      val service              = new RetryingRatesService[IO](backend, maxAttempts = 3, waitShort)
      val result               = service.get(pair).unsafeRunSync()
      result shouldBe Right(rate)
    }

    "retries on Left and returns Right when a later attempt succeeds" in {
      val test = for {
        attempts <- Ref.of[IO, Int](0)
        backend = new Algebra[IO] {
                    override def get(p: Rate.Pair): IO[Error Either Rate] =
                      attempts.update(_ + 1).flatMap { _ =>
                        attempts.get.flatMap { n =>
                          if (n < 2) IO.pure(Left(Error.OneFrameLookupFailed("temp")))
                          else IO.pure(Right(rate))
                        }
                      }
                  }
        service = new RetryingRatesService[IO](backend, maxAttempts = 3, waitShort)
        result <- service.get(pair)
        cnt <- attempts.get
      } yield (result, cnt)
      val (result, cnt) = test.unsafeRunSync()
      result shouldBe Right(rate)
      cnt shouldEqual 2
    }

    "returns final Left when all attempts return Left" in {
      val test = for {
        attempts <- Ref.of[IO, Int](0)
        backend = new Algebra[IO] {
                    override def get(p: Rate.Pair): IO[Error Either Rate] =
                      attempts.update(_ + 1).as(Left(Error.OneFrameLookupFailed("down")))
                  }
        service = new RetryingRatesService[IO](backend, maxAttempts = 3, waitShort)
        result <- service.get(pair)
        cnt <- attempts.get
      } yield (result, cnt)
      val (result, cnt) = test.unsafeRunSync()
      result shouldBe Left(Error.OneFrameLookupFailed("down"))
      cnt shouldEqual 3
    }

    "retries on exception and returns Right when a later attempt succeeds" in {
      val test = for {
        attempts <- Ref.of[IO, Int](0)
        backend = new Algebra[IO] {
                    override def get(p: Rate.Pair): IO[Error Either Rate] =
                      attempts.update(_ + 1).flatMap { _ =>
                        attempts.get.flatMap { n =>
                          if (n < 2) IO.raiseError(new RuntimeException("network"))
                          else IO.pure(Right(rate))
                        }
                      }
                  }
        service = new RetryingRatesService[IO](backend, maxAttempts = 3, waitShort)
        result <- service.get(pair)
        cnt <- attempts.get
      } yield (result, cnt)
      val (result, cnt) = test.unsafeRunSync()
      result shouldBe Right(rate)
      cnt shouldEqual 2
    }
  }
}
