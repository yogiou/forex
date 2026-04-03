package forex.services.rates.interpreters

import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.syntax.flatMap._
import cats.syntax.functor._
import forex.domain.Rate
import forex.services.rates.Algebra
import forex.services.rates.errors.Error

import java.time.OffsetDateTime

/** Serves all rate requests from a pre-warmed in-memory cache. The cache is populated and
  * periodically refreshed by a background job (see Module.refreshStream), which fetches all
  * currency pairs from One-Frame in a single HTTP call. Incoming requests never call One-Frame
  * directly; every lookup is an O(1) map read.
  *
  * Freshness is measured against lastRefreshTime — when WE last successfully fetched from
  * One-Frame. We deliberately do NOT use the timestamp embedded inside the rate by One-Frame,
  * because that reflects One-Frame's internal update schedule, which we cannot control. The
  * spec example shows "2019-01-01T00:00:00.000"; relying on it would cause every request to
  * return StaleRate if One-Frame uses a static or old timestamp.
  */
class ProactiveCachingRatesService[F[_]: Sync](
    cache: Ref[F, Map[Rate.Pair, Rate]],
    lastRefreshTime: Ref[F, Option[OffsetDateTime]],
    maxAgeMinutes: Long
) extends Algebra[F] {

  override def get(pair: Rate.Pair): F[Error Either Rate] =
    for {
      now         <- Sync[F].delay(OffsetDateTime.now)
      rates       <- cache.get
      refreshTime <- lastRefreshTime.get
    } yield refreshTime match {
      case None =>
        Left(Error.CacheNotReady("Rates not yet available, please retry shortly"))
      case Some(rt) if now.isAfter(rt.plusMinutes(maxAgeMinutes)) =>
        Left(Error.StaleRate("Rate is stale; please retry shortly"))
      case Some(_) =>
        rates.get(pair).toRight(Error.CacheNotReady(s"No rate available for $pair"))
    }
}
