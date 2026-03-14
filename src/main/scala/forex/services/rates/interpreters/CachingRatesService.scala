package forex.services.rates.interpreters

import java.time.{ Duration, Instant }

import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.either._
import forex.domain.Rate
import forex.services.rates.Algebra
import forex.services.rates.errors._

/** Wraps an underlying rates algebra with an in-memory cache keyed by Rate.Pair. Time: O(1) lookup on hit; on miss,
  * O(n) update where n = number of cached pairs (evicts expired then inserts). Space: bounded by distinct pairs
  * requested within a TTL window (expired entries are removed on each miss/update). Entries expire after `ttlMinutes`.
  * Reduces calls to One-Frame to support 10k+ proxy requests/day within the 1k/day One-Frame limit.
  */
class CachingRatesService[F[_]: Sync](
    backend: Algebra[F],
    cache: Ref[F, Map[Rate.Pair, (Rate, Instant)]],
    ttlMinutes: Long
) extends Algebra[F] {

  override def get(pair: Rate.Pair): F[Error Either Rate] =
    Sync[F].delay(Instant.now()).flatMap { now =>
      cache.get.flatMap { map =>
        map.get(pair) match {
          case Some((rate, at)) if notExpired(at, now) =>
            Sync[F].pure(rate.asRight[Error])
          case _ =>
            backend.get(pair).flatMap {
              case Right(rate) =>
                cache
                  .update { old =>
                    val withoutExpired = old.filter { case (_, (_, at)) => notExpired(at, now) }
                    withoutExpired + (pair -> (rate, now))
                  }
                  .as(rate.asRight[Error])
              case left =>
                Sync[F].pure(left)
            }
        }
      }
    }

  private def notExpired(cachedAt: Instant, now: Instant): Boolean =
    Duration.between(cachedAt, now).toMinutes < ttlMinutes
}
