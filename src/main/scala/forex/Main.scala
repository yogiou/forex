package forex

import java.time.OffsetDateTime
import scala.concurrent.ExecutionContext

import cats.effect._
import cats.effect.concurrent.Ref
import forex.config._
import forex.domain.Rate
import fs2.Stream
import org.http4s.blaze.client.BlazeClientBuilder
import org.http4s.blaze.server.BlazeServerBuilder

object Main extends IOApp {

  override def run(args: List[String]): IO[ExitCode] =
    new Application[IO].stream(executionContext).compile.drain.as(ExitCode.Success)
}

class Application[F[_]: ConcurrentEffect: Timer] {

  def stream(ec: ExecutionContext): Stream[F, Unit] =
    for {
      config <- Config.stream("app")
      client <- Stream.resource(BlazeClientBuilder[F](ec).resource)
      cache <- Stream.eval(Ref.of[F, Map[Rate.Pair, Rate]](Map.empty))
      lastRefreshTime <- Stream.eval(Ref.of[F, Option[OffsetDateTime]](None))
      module = new Module[F](config, client, cache, lastRefreshTime)
      _ <- Stream.eval(module.initialRefresh)
      _ <- Stream(
             BlazeServerBuilder[F](ec)
               .bindHttp(config.http.port, config.http.host)
               .withHttpApp(module.httpApp)
               .serve
               .drain,
             module.refreshStream
           ).parJoin(2)
    } yield ()
}
