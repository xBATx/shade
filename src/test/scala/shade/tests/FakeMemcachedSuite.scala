/*
 * Copyright (c) 2012-2017 by its authors. Some rights reserved.
 * See the project homepage at: https://github.com/monix/shade
 *
 * Licensed under the MIT License (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy
 * of the License at:
 *
 * https://github.com/monix/shade/blob/master/LICENSE.txt
 */

package shade.tests

import java.io.{ ByteArrayOutputStream, ObjectOutputStream }

import org.scalatest.funsuite.AnyFunSuite
import shade.testModels.Impression

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class FakeMemcachedSuite extends AnyFunSuite with MemcachedTestHelpers {
  implicit val timeout = 5.second

  test("add") {
    withFakeMemcached { cache =>
      val op1 = cache.awaitAdd("hello", Value("world"), 5.seconds)
      assert(op1 === true)

      val stored = cache.awaitGet[Value]("hello")
      assert(stored === Some(Value("world")))

      val op2 = cache.awaitAdd("hello", Value("changed"), 5.seconds)
      assert(op2 === false)

      val changed = cache.awaitGet[Value]("hello")
      assert(changed === Some(Value("world")))
    }
  }

  test("add-null") {
    withFakeMemcached { cache =>
      val op1 = cache.awaitAdd("hello", null, 5.seconds)
      assert(op1 === false)

      val stored = cache.awaitGet[Value]("hello")
      assert(stored === None)
    }
  }

  test("get") {
    withFakeMemcached { cache =>
      val value = cache.awaitGet[Value]("missing")
      assert(value === None)
    }
  }

  test("set") {
    withFakeMemcached { cache =>
      assert(cache.awaitGet[Value]("hello") === None)

      cache.awaitSet("hello", Value("world"), 3.seconds)
      assert(cache.awaitGet[Value]("hello") === Some(Value("world")))

      cache.awaitSet("hello", Value("changed"), 3.seconds)
      assert(cache.awaitGet[Value]("hello") === Some(Value("changed")))

      Thread.sleep(3000)

      assert(cache.awaitGet[Value]("hello") === None)
    }
  }

  test("set-null") {
    withFakeMemcached { cache =>
      val op1 = cache.awaitAdd("hello", null, 5.seconds)
      assert(op1 === false)

      val stored = cache.awaitGet[Value]("hello")
      assert(stored === None)
    }
  }

  test("delete") {
    withFakeMemcached { cache =>
      cache.awaitDelete("hello")
      assert(cache.awaitGet[Value]("hello") === None)

      cache.awaitSet("hello", Value("world"), 1.minute)
      assert(cache.awaitGet[Value]("hello") === Some(Value("world")))

      assert(cache.awaitDelete("hello") === true)
      assert(cache.awaitGet[Value]("hello") === None)

      assert(cache.awaitDelete("hello") === false)
    }
  }

  test("compareAndSet") {
    withFakeMemcached { cache =>
      cache.awaitDelete("some-key")
      assert(cache.awaitGet[Value]("some-key") === None)

      // no can do
      assert(Await.result(cache.compareAndSet("some-key", Some(Value("invalid")), Value("value1"), 15.seconds), Duration.Inf) === false)
      assert(cache.awaitGet[Value]("some-key") === None)

      // set to value1
      assert(Await.result(cache.compareAndSet("some-key", None, Value("value1"), 5.seconds), Duration.Inf) === true)
      assert(cache.awaitGet[Value]("some-key") === Some(Value("value1")))

      // no can do
      assert(Await.result(cache.compareAndSet("some-key", Some(Value("invalid")), Value("value1"), 15.seconds), Duration.Inf) === false)
      assert(cache.awaitGet[Value]("some-key") === Some(Value("value1")))

      // set to value2, from value1
      assert(Await.result(cache.compareAndSet("some-key", Some(Value("value1")), Value("value2"), 15.seconds), Duration.Inf) === true)
      assert(cache.awaitGet[Value]("some-key") === Some(Value("value2")))

      // no can do
      assert(Await.result(cache.compareAndSet("some-key", Some(Value("invalid")), Value("value1"), 15.seconds), Duration.Inf) === false)
      assert(cache.awaitGet[Value]("some-key") === Some(Value("value2")))

      // set to value3, from value2
      assert(Await.result(cache.compareAndSet("some-key", Some(Value("value2")), Value("value3"), 15.seconds), Duration.Inf) === true)
      assert(cache.awaitGet[Value]("some-key") === Some(Value("value3")))
    }
  }

  test("transformAndGet") {
    withFakeMemcached { cache =>
      cache.awaitDelete("some-key")
      assert(cache.awaitGet[Value]("some-key") === None)

      def incrementValue =
        cache.transformAndGet[Int]("some-key", 5.seconds) {
          case None => 1
          case Some(nr) => nr + 1
        }

      assert(Await.result(incrementValue, Duration.Inf) === 1)
      assert(Await.result(incrementValue, Duration.Inf) === 2)
      assert(Await.result(incrementValue, Duration.Inf) === 3)
      assert(Await.result(incrementValue, Duration.Inf) === 4)
      assert(Await.result(incrementValue, Duration.Inf) === 5)
      assert(Await.result(incrementValue, Duration.Inf) === 6)
    }
  }

  test("getAndTransform") {
    withFakeMemcached { cache =>
      cache.awaitDelete("some-key")
      assert(cache.awaitGet[Value]("some-key") === None)

      def incrementValue = Await.result(
        cache.getAndTransform[Int]("some-key", 5.seconds) {
          case None => 1
          case Some(nr) => nr + 1
        },
        Duration.Inf)

      assert(incrementValue === None)
      assert(incrementValue === Some(1))
      assert(incrementValue === Some(2))
      assert(incrementValue === Some(3))
      assert(incrementValue === Some(4))
      assert(incrementValue === Some(5))
      assert(incrementValue === Some(6))
    }
  }

  test("transformAndGet-concurrent") {
    withFakeMemcached { cache =>
      cache.awaitDelete("some-key")
      assert(cache.awaitGet[Value]("some-key") === None)

      def incrementValue =
        cache.transformAndGet[Int]("some-key", 60.seconds) {
          case None => 1
          case Some(nr) => nr + 1
        }

      val seq = concurrent.Future.sequence((0 until 500).map(nr => incrementValue))
      Await.result(seq, 20.seconds)

      assert(cache.awaitGet[Int]("some-key") === Some(500))
    }
  }

  test("getAndTransform-concurrent") {
    withFakeMemcached { cache =>
      cache.awaitDelete("some-key")
      assert(cache.awaitGet[Value]("some-key") === None)

      def incrementValue =
        cache.getAndTransform[Int]("some-key", 60.seconds) {
          case None => 1
          case Some(nr) => nr + 1
        }

      val seq = concurrent.Future.sequence((0 until 500).map(nr => incrementValue))
      Await.result(seq, 20.seconds)

      assert(cache.awaitGet[Int]("some-key") === Some(500))
    }
  }

  test("increment-decrement") {
    withFakeMemcached { cache =>
      assert(cache.awaitGet[Int]("hello") === None)

      cache.awaitSet("hello", "123", 1.second)(StringBinaryCodec)
      assert(cache.awaitGet[String]("hello")(StringBinaryCodec) === Some("123"))

      cache.awaitIncrement("hello", 1, None, 1.second)
      assert(cache.awaitGet[String]("hello")(StringBinaryCodec) === Some("124"))

      cache.awaitDecrement("hello", 1, None, 1.second)
      assert(cache.awaitGet[String]("hello")(StringBinaryCodec) === Some("123"))

      Thread.sleep(3000)

      assert(cache.awaitGet[String]("hello")(StringBinaryCodec) === None)
    }
  }

  test("increment-decrement-delta") {
    withFakeMemcached { cache =>
      assert(cache.awaitGet[Int]("hello") === None)

      cache.awaitSet("hello", "123", 1.second)(StringBinaryCodec)
      assert(cache.awaitGet[String]("hello")(StringBinaryCodec) === Some("123"))

      cache.awaitIncrement("hello", 5, None, 1.second)
      assert(cache.awaitGet[String]("hello")(StringBinaryCodec) === Some("128"))

      cache.awaitDecrement("hello", 5, None, 1.second)
      assert(cache.awaitGet[String]("hello")(StringBinaryCodec) === Some("123"))

      Thread.sleep(3000)

      assert(cache.awaitGet[String]("hello")(StringBinaryCodec) === None)
    }
  }

  test("increment-default") {
    withFakeMemcached { cache =>
      assert(cache.awaitGet[String]("hello")(StringBinaryCodec) === None)

      cache.awaitIncrement("hello", 1, Some(0), 1.second)
      assert(cache.awaitGet[String]("hello")(StringBinaryCodec) === Some("0"))

      cache.awaitIncrement("hello", 1, Some(0), 1.second)
      assert(cache.awaitGet[String]("hello")(StringBinaryCodec) === Some("1"))

      Thread.sleep(3000)

      assert(cache.awaitGet[String]("hello")(StringBinaryCodec) === None)
    }
  }

  test("increment-overflow") {
    withFakeMemcached { cache =>
      assert(cache.awaitIncrement("hello", 1, Some(Long.MaxValue), 1.minute) === Long.MaxValue)

      assert(cache.awaitIncrement("hello", 1, None, 1.minute) === Long.MinValue)

      assert(cache.awaitGet[String]("hello")(StringBinaryCodec) === Some("9223372036854775808"))
    }
  }

  test("decrement-underflow") {
    withFakeMemcached { cache =>
      assert(cache.awaitDecrement("hello", 1, Some(1), 1.minute) === 1)

      assert(cache.awaitDecrement("hello", 1, None, 1.minute) === 0)

      assert(cache.awaitDecrement("hello", 1, None, 1.minute) === 0)

      assert(cache.awaitGet[String]("hello")(StringBinaryCodec) === Some("0"))
    }
  }

  test("big-instance-1") {
    withFakeMemcached { cache =>
      val impression = shade.testModels.bigInstance
      cache.awaitSet(impression.uuid, impression, 60.seconds)
      assert(cache.awaitGet[Impression](impression.uuid) === Some(impression))
    }
  }

  test("big-instance-1-manual") {
    withFakeMemcached { cache =>
      val byteOut = new ByteArrayOutputStream()
      val objectOut = new ObjectOutputStream(byteOut)

      val impression = shade.testModels.bigInstance
      objectOut.writeObject(impression)
      val byteArray = byteOut.toByteArray

      cache.awaitSet(impression.uuid, byteArray, 60.seconds)

      val inBytes = cache.awaitGet[Array[Byte]](impression.uuid)
      assert(inBytes.isDefined)
      assert(inBytes.get.length == byteArray.length)
    }
  }

  test("big-instance-2") {
    withFakeMemcached { cache =>
      val impression = shade.testModels.bigInstance2
      cache.awaitSet(impression.uuid, impression, 60.seconds)
      assert(cache.awaitGet[Impression](impression.uuid) === Some(impression))
    }
  }

  test("big-instance-3") {
    withFakeMemcached { cache =>
      val impression = shade.testModels.bigInstance
      val result = cache.set(impression.uuid, impression, 60.seconds) flatMap { _ =>
        cache.get[Impression](impression.uuid)
      }

      assert(Await.result(result, Duration.Inf) === Some(impression))
    }
  }

  test("cancel-strategy simple test") {
    withFakeMemcached { cache =>
      Thread.sleep(100)
      val impression = shade.testModels.bigInstance2
      cache.awaitSet(impression.uuid, impression, 60.seconds)
      assert(cache.awaitGet[Impression](impression.uuid) === Some(impression))
    }
  }
}
