package forex.services.rates.interpreters

import cats.effect.Sync
import cats.syntax.flatMap._
import cats.syntax.functor._
import forex.domain.Rate
import forex.services.rates.Algebra
import forex.services.rates.errors.Error
import io.github.resilience4j.circuitbreaker.CircuitBreaker

/** Wraps a rates algebra with a Resilience4j circuit breaker. When the circuit is open, returns
  * Left(OneFrameLookupFailed("Circuit breaker open")) without calling the backend. Records success/failure after each
  * call so the breaker can open after too many failures. Rate-limit (429/403) is recorded as success so the breaker
  * doesn't open due to quota exhaustion — only real failures (e.g. 5xx, timeouts) trip it.
  */
class CircuitBreakingRatesService[F[_]: Sync](backend: Algebra[F], circuitBreaker: CircuitBreaker) extends Algebra[F] {

  override def get(pair: Rate.Pair): F[Error Either Rate] =
    Sync[F].delay(!circuitBreaker.tryAcquirePermission()).flatMap {
      case true =>
        Sync[F].pure(Left(Error.OneFrameLookupFailed("Circuit breaker open")))
      case false =>
        val start = circuitBreaker.getCurrentTimestamp()
        val unit  = circuitBreaker.getTimestampUnit()
        Sync[F].handleErrorWith(
          backend.get(pair).flatMap { result =>
            val duration = circuitBreaker.getCurrentTimestamp() - start
            Sync[F]
              .delay {
                result match {
                  case Right(_) =>
                    circuitBreaker.onSuccess(duration, unit)
                  case Left(Error.OneFrameRateLimitExceeded(_)) =>
                    circuitBreaker.onSuccess(duration, unit)
                  case Left(e) =>
                    circuitBreaker.onError(duration, unit, new Exception(e.toString))
                }
              }
              .as(result)
          }
        ) { t =>
          val duration = circuitBreaker.getCurrentTimestamp() - start
          Sync[F].delay(circuitBreaker.onError(duration, unit, t)) >>
            Sync[F].pure(Left(Error.OneFrameLookupFailed(t.getMessage)))
        }
    }
}
