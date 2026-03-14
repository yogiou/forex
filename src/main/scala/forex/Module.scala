package forex

import java.time.Instant

import cats.effect.{ Concurrent, Timer }
import cats.effect.concurrent.Ref
import forex.config.ApplicationConfig
import forex.domain.Rate
import forex.http.rates.RatesHttpRoutes
import forex.programs._
import forex.services._
import forex.services.rates.interpreters.CircuitBreakerFactory
import org.http4s._
import org.http4s.client.Client
import org.http4s.implicits._
import org.http4s.server.middleware.{ AutoSlash, Timeout }

class Module[F[_]: Concurrent: Timer](
    config: ApplicationConfig,
    client: Client[F],
    cache: Ref[F, Map[Rate.Pair, (Rate, Instant)]]
) {

  /** By default uses the OneFrame service (live + cache + optional retry/circuit breaker); use dummy only when
    * config.oneframe.useDummy is true.
    * Layering (outside → in): cache → circuit breaker → retry → live. So one failed logical request (after N
    * retries) counts as one circuit-breaker failure. Putting retry outside the breaker would make each retry
    * attempt count separately (e.g. 3 attempts = 3 failures); current order keeps the breaker from opening
    * too quickly when retries are in use.
    */
  private val ratesService: RatesService[F] =
    if (config.oneframe.useDummy)
      RatesServices.dummy[F]
    else {
      val live           = RatesServices.live[F](client, config.oneframe)
      val withRetryLayer =
        if (config.oneframe.retry.enabled)
          RatesServices.withRetry[F](
            live,
            config.oneframe.retry.maxAttempts,
            config.oneframe.retry.waitBetweenAttempts
          )
        else
          live
      val backend =
        if (config.oneframe.circuitBreaker.enabled) {
          val cb = CircuitBreakerFactory.create("oneframe", config.oneframe.circuitBreaker)
          RatesServices.withCircuitBreaker(withRetryLayer, cb)
        } else
          withRetryLayer
      RatesServices.cached[F](backend, cache, ttlMinutes = config.oneframe.cacheTtlMinutes)
    }
  private val ratesProgram: RatesProgram[F]  = RatesProgram[F](ratesService)
  private val ratesHttpRoutes: HttpRoutes[F] = new RatesHttpRoutes[F](ratesProgram).routes

  type PartialMiddleware = HttpRoutes[F] => HttpRoutes[F]
  type TotalMiddleware   = HttpApp[F] => HttpApp[F]

  private val routesMiddleware: PartialMiddleware = { http: HttpRoutes[F] =>
    AutoSlash(http)
  }

  private val appMiddleware: TotalMiddleware = { http: HttpApp[F] =>
    Timeout(config.http.timeout)(http)
  }

  private val http: HttpRoutes[F] = ratesHttpRoutes

  val httpApp: HttpApp[F] = appMiddleware(routesMiddleware(http).orNotFound)
}
