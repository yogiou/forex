package forex.services.rates.interpreters

import cats.effect.Sync
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.either._
import forex.domain.{ Currency, Price, Rate, Timestamp }
import forex.services.rates.Algebra
import forex.services.rates.errors._
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import org.http4s._
import org.http4s.circe.jsonOf
import org.http4s.client.Client

import fs2.text.utf8Decode

import java.time.OffsetDateTime

/** One-Frame API response item:
  * {"from":"USD","to":"JPY","bid":0.61,"ask":0.82,"price":0.71,"time_stamp":"2019-01-01T00:00:00.000"}
  */
private[interpreters] final case class OneFrameRate(
    from: String,
    to: String,
    price: BigDecimal,
    time_stamp: String
)

private[interpreters] object OneFrameRate {
  implicit val decoder: Decoder[OneFrameRate] = deriveDecoder[OneFrameRate]
}

class OneFrameLive[F[_]: Sync](client: Client[F], baseUrl: String, token: String) extends Algebra[F] {

  private implicit def oneFrameListDecoder: org.http4s.EntityDecoder[F, List[OneFrameRate]] =
    jsonOf[F, List[OneFrameRate]]

  override def get(pair: Rate.Pair): F[Error Either Rate] = {
    val pairStr = s"${Currency.show.show(pair.from)}${Currency.show.show(pair.to)}"
    val uriE    = Uri.fromString(s"$baseUrl/rates").map(_.withQueryParam("pair", pairStr))
    val req     = uriE.map { uri =>
      Request[F](Method.GET, uri).putHeaders("token" -> token)
    }
    Sync[F].fromEither(req.leftMap(e => Error.OneFrameLookupFailed(e.message))).flatMap { request =>
      val useBlock: org.http4s.Response[F] => F[Error Either Rate] = { resp =>
        if (resp.status.isSuccess) {
          resp.as[List[OneFrameRate]].flatMap { list =>
            list match {
              case (one: OneFrameRate) :: _ =>
                Sync[F].pure(toDomain(one, pair))
              case Nil =>
                Sync[F].pure(Error.OneFrameLookupFailed("One-Frame returned empty rates").asLeft[Rate])
            }
          }
        } else {
          resp.body
            .through(utf8Decode)
            .compile
            .foldMonoid
            .map { body =>
              val msg = s"One-Frame ${resp.status}: $body"
              if (resp.status.code == 429 || resp.status.code == 403)
                Error.OneFrameRateLimitExceeded(msg).asLeft[Rate]
              else
                Error.OneFrameLookupFailed(msg).asLeft[Rate]
            }
        }
      }
      Sync[F].handleErrorWith(client.run(request).use(useBlock)) { t =>
        Sync[F].pure(Error.OneFrameLookupFailed(t.getMessage).asLeft[Rate])
      }
    }
  }

  /** Parses One-Frame rate to domain; returns Left on malformed timestamp so we don't throw. */
  private def toDomain(raw: OneFrameRate, pair: Rate.Pair): Error Either Rate =
    Either
      .catchNonFatal(OffsetDateTime.parse(raw.time_stamp))
      .leftMap(t => Error.OneFrameLookupFailed(s"Invalid One-Frame timestamp: ${t.getMessage}"))
      .map(ts => Rate(pair, Price(raw.price), Timestamp(ts)))
}
