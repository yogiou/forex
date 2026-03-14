package forex.services.rates.interpreters

import scala.concurrent.duration.FiniteDuration

import cats.effect.{ Sync, Timer }
import cats.syntax.flatMap._
import forex.domain.Rate
import forex.services.rates.Algebra
import forex.services.rates.errors.Error

/** Wraps a rates algebra with retry: on Left or exception, waits then retries up to maxAttempts. The first attempt
  * counts as 1, so maxAttempts = 3 means 1 initial + 2 retries.
  */
class RetryingRatesService[F[_]: Sync: Timer](
    backend: Algebra[F],
    maxAttempts: Int,
    waitBetweenAttempts: FiniteDuration
) extends Algebra[F] {

  override def get(pair: Rate.Pair): F[Error Either Rate] =
    attempt(pair, maxAttempts)

  private def attempt(pair: Rate.Pair, attemptsLeft: Int): F[Error Either Rate] =
    Sync[F].handleErrorWith(
      backend.get(pair).flatMap {
        case Right(r)                    => Sync[F].pure(Right(r): Error Either Rate)
        case Left(_) if attemptsLeft > 1 =>
          Timer[F].sleep(waitBetweenAttempts) >> attempt(pair, attemptsLeft - 1)
        case other => Sync[F].pure(other)
      }
    ) { t =>
      if (attemptsLeft > 1)
        Timer[F].sleep(waitBetweenAttempts) >> attempt(pair, attemptsLeft - 1)
      else
        Sync[F].pure(Left(Error.OneFrameLookupFailed(t.getMessage)))
    }
}
