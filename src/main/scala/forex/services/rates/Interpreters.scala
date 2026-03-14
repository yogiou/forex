package forex.services.rates

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

  /** Wraps the backend with retry (on Left or exception, wait then retry up to config.maxAttempts). */
  def withRetry[F[_]: Sync: Timer](
      backend: Algebra[F],
      maxAttempts: Int,
      waitBetweenAttempts: scala.concurrent.duration.FiniteDuration
  ): Algebra[F] =
    new RetryingRatesService[F](backend, maxAttempts, waitBetweenAttempts)

  /** Wraps the backend with a Resilience4j circuit breaker when config.circuitBreaker.enabled is true. */
  def withCircuitBreaker[F[_]: Sync](
      backend: Algebra[F],
      circuitBreaker: io.github.resilience4j.circuitbreaker.CircuitBreaker
  ): Algebra[F] =
    new CircuitBreakingRatesService[F](backend, circuitBreaker)

  /** Cache with 5-minute TTL and O(1) lookup by pair. Wraps the live interpreter. */
  def cached[F[_]: Sync](
      backend: Algebra[F],
      cache: Ref[F, Map[Rate.Pair, (Rate, java.time.Instant)]],
      ttlMinutes: Long = 5
  ): Algebra[F] =
    new CachingRatesService[F](backend, cache, ttlMinutes)
}
