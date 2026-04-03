package forex

import java.time.OffsetDateTime
import scala.concurrent.duration.{ FiniteDuration, MINUTES }

import cats.effect.{ Concurrent, Sync, Timer }
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.effect.concurrent.Ref
import forex.config.ApplicationConfig
import forex.domain.Rate
import forex.http.rates.RatesHttpRoutes
import forex.programs._
import forex.services._
import forex.services.rates.errors.{ Error => RatesError }
import forex.services.rates.interpreters.{ CircuitBreakerFactory, OneFrameLive }
import fs2.Stream
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import org.http4s._
import org.http4s.client.Client
import org.http4s.implicits._
import org.http4s.server.middleware.{ AutoSlash, Timeout }
import org.slf4j.LoggerFactory

class Module[F[_]: Concurrent: Timer](
    config: ApplicationConfig,
    client: Client[F],
    cache: Ref[F, Map[Rate.Pair, Rate]],
    lastRefreshTime: Ref[F, Option[OffsetDateTime]]
) {
  private val logger = LoggerFactory.getLogger(getClass)

  private val live = new OneFrameLive[F](client, config.oneframe.baseUrl, config.oneframe.token)
  // Strict requirement: never serve data older than 5 minutes.
  private val freshnessLimitMinutes: Long                   = Math.min(config.oneframe.cacheTtlMinutes, 5L)
  private val refreshCircuitBreaker: Option[CircuitBreaker] =
    if (config.oneframe.circuitBreaker.enabled)
      Some(CircuitBreakerFactory.create("oneframe-refresh-all", config.oneframe.circuitBreaker))
    else None

  /** All requests are served from the proactive cache. The background refreshStream keeps it current, fetching all 72
    * pairs from One-Frame in a single call every cacheTtlMinutes. This guarantees at most 1440/cacheTtlMinutes
    * One-Frame calls per day regardless of traffic distribution across pairs — well within the 1k/day limit at the
    * default 5-minute TTL (288/day).
    */
  private val ratesService: RatesService[F] =
    if (config.oneframe.useDummy)
      RatesServices.dummy[F]
    else
      RatesServices.proactive[F](cache, lastRefreshTime, freshnessLimitMinutes)

  private val ratesProgram: RatesProgram[F]  = RatesProgram[F](ratesService)
  private val ratesHttpRoutes: HttpRoutes[F] = new RatesHttpRoutes[F](ratesProgram).routes

  type PartialMiddleware = HttpRoutes[F] => HttpRoutes[F]
  type TotalMiddleware   = HttpApp[F] => HttpApp[F]

  private val routesMiddleware: PartialMiddleware = AutoSlash(_)
  private val appMiddleware: TotalMiddleware      = Timeout(config.http.timeout)(_)

  val httpApp: HttpApp[F] = appMiddleware(routesMiddleware(ratesHttpRoutes).orNotFound)

  /** Batch-level circuit breaker for the background refresh path. The per-request
    * CircuitBreakingRatesService/RetryingRatesService wrappers in interpreters/ are available for reactive
    * architectures; here the proactive design needs batch-level resilience because getAll operates on all 72 pairs at
    * once and isn't on the single-pair Algebra trait.
    */
  private def getAllRatesWithCircuitBreaker: F[RatesError Either Map[Rate.Pair, Rate]] =
    refreshCircuitBreaker match {
      case None     => live.getAll(Rate.allPairs)
      case Some(cb) =>
        Sync[F].delay(!cb.tryAcquirePermission()).flatMap {
          case true =>
            Sync[F].delay(logger.warn("Circuit breaker open, skipping One-Frame refresh")) >>
              Sync[F].pure(Left(RatesError.OneFrameLookupFailed("Circuit breaker open for One-Frame refresh")))
          case false =>
            val start = cb.getCurrentTimestamp()
            val unit  = cb.getTimestampUnit()
            live.getAll(Rate.allPairs).flatMap { result =>
              val duration = cb.getCurrentTimestamp() - start
              Sync[F]
                .delay {
                  result match {
                    case Right(_)                                      => cb.onSuccess(duration, unit)
                    case Left(RatesError.OneFrameRateLimitExceeded(_)) => cb.onSuccess(duration, unit)
                    case Left(e) => cb.onError(duration, unit, new Exception(e.toString))
                  }
                }
                .as(result)
            }
        }
    }

  private def getAllRatesWithResilience: F[RatesError Either Map[Rate.Pair, Rate]] = {
    def loop(attemptsLeft: Int): F[RatesError Either Map[Rate.Pair, Rate]] =
      getAllRatesWithCircuitBreaker.flatMap {
        case right @ Right(_)                                     => Sync[F].pure(right)
        case left @ Left(RatesError.OneFrameRateLimitExceeded(_)) =>
          Sync[F].pure(left)
        case Left(e) if config.oneframe.retry.enabled && attemptsLeft > 1 =>
          Sync[F].delay(logger.warn(s"One-Frame refresh failed ($e), retrying (${attemptsLeft - 1} attempts left)")) >>
            Timer[F].sleep(config.oneframe.retry.waitBetweenAttempts) >> loop(attemptsLeft - 1)
        case left =>
          Sync[F].pure(left)
      }

    val attempts = Math.max(config.oneframe.retry.maxAttempts, 1)
    loop(attempts)
  }

  private val refreshOnce: F[Unit] =
    getAllRatesWithResilience.flatMap {
      case Right(rates) =>
        for {
          now <- Sync[F].delay(OffsetDateTime.now)
          _ <- Sync[F].delay(logger.info(s"Refreshed ${rates.size} rates from One-Frame"))
          _ <- cache.set(rates)
          _ <- lastRefreshTime.set(Some(now))
        } yield ()
      case Left(e) =>
        Sync[F].delay(logger.warn(s"Failed to refresh rates from One-Frame: $e"))
    }

  /** Populates the cache once before the server starts accepting traffic. Called during startup so the very first
    * request is always served from a warm cache rather than returning a 503. On failure (One-Frame unreachable at boot)
    * the cache stays empty; the periodic refreshStream will keep retrying every cacheTtlMinutes until One-Frame comes
    * back.
    */
  val initialRefresh: F[Unit] =
    if (config.oneframe.useDummy) Sync[F].unit
    else
      Sync[F].delay(logger.info("Performing initial cache refresh from One-Frame")) >>
        refreshOnce

  /** Refreshes all 72 pairs from One-Frame every cacheTtlMinutes. A single HTTP call per tick. On failure the cache is
    * left as-is so requests continue to be served from the last snapshot.
    */
  val refreshStream: Stream[F, Unit] =
    if (config.oneframe.useDummy) Stream.empty
    else {
      val interval = FiniteDuration(config.oneframe.cacheTtlMinutes, MINUTES)
      Stream
        .fixedRate[F](interval)
        .evalMap(_ => refreshOnce)
    }
}
