package forex.services.rates.interpreters

import cats.effect.IO
import forex.domain.{ Currency, Rate }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class OneFrameDummySpec extends AnyFreeSpec with Matchers {

  "OneFrameDummy.get" - {
    "returns a rate with price 100 for any pair" in {
      val dummy  = new OneFrameDummy[IO]
      val pair   = Rate.Pair(Currency.USD, Currency.JPY)
      val result = dummy.get(pair).unsafeRunSync()
      result shouldBe a[Right[_, _]]
      result.foreach { rate =>
        rate.pair shouldEqual pair
        rate.price.value shouldEqual BigDecimal(100)
      }
    }
    "returns different pairs correctly" in {
      val dummy   = new OneFrameDummy[IO]
      val pair1   = Rate.Pair(Currency.EUR, Currency.GBP)
      val result1 = dummy.get(pair1).unsafeRunSync()
      result1.foreach(_.pair shouldEqual pair1)
      val pair2   = Rate.Pair(Currency.CHF, Currency.USD)
      val result2 = dummy.get(pair2).unsafeRunSync()
      result2.foreach(_.pair shouldEqual pair2)
    }
  }
}
