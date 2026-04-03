package forex.services.rates

import java.time.OffsetDateTime

import cats.effect.{ Sync, Timer }
import cats.effect.concurrent.Ref
import forex.config.OneFrameConfig
import forex.domain.Rate
import interpreters._
import org.http4s.client.Client

object Interpreters {
  def dummy[F[_]: cats.Applicative]: Algebra[F] = new OneFrameDummy[F]()

  def live[F[_]: Sync](client: Client[F], config: OneFrameConfig): Algebra[F] =
    new OneFrameLive[F](client, config.baseUrl, config.token)

  /** Serves requests from a pre-warmed cache populated by the background refresh job. Freshness is determined by
    * lastRefreshTime (when we last fetched from One-Frame).
    */
  def proactive[F[_]: Sync](
      cache: Ref[F, Map[Rate.Pair, Rate]],
      lastRefreshTime: Ref[F, Option[OffsetDateTime]],
      maxAgeMinutes: Long
  ): Algebra[F] =
    new ProactiveCachingRatesService[F](cache, lastRefreshTime, maxAgeMinutes)

  /** Per-request retry wrapper. Available for reactive (non-proactive) architectures; the proactive architecture uses
    * batch-level retry in Module.
    */
  def withRetry[F[_]: Sync: Timer](
      backend: Algebra[F],
      maxAttempts: Int,
      waitBetweenAttempts: scala.concurrent.duration.FiniteDuration
  ): Algebra[F] =
    new RetryingRatesService[F](backend, maxAttempts, waitBetweenAttempts)

  /** Per-request circuit breaker wrapper. Available for reactive (non-proactive) architectures; the proactive
    * architecture uses batch-level circuit breaking in Module.
    */
  def withCircuitBreaker[F[_]: Sync](
      backend: Algebra[F],
      circuitBreaker: io.github.resilience4j.circuitbreaker.CircuitBreaker
  ): Algebra[F] =
    new CircuitBreakingRatesService[F](backend, circuitBreaker)

  /** Reactive per-pair cache with TTL. Available as an alternative to proactive caching. */
  def cached[F[_]: Sync](
      backend: Algebra[F],
      cache: Ref[F, Map[Rate.Pair, (Rate, java.time.Instant)]],
      ttlMinutes: Long = 5
  ): Algebra[F] =
    new CachingRatesService[F](backend, cache, ttlMinutes)
}
