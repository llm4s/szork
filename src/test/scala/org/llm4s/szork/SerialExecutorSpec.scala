package org.llm4s.szork

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters._
import org.llm4s.szork.api.SerialExecutor

class SerialExecutorSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private val pool = Executors.newFixedThreadPool(8)

  override def afterAll(): Unit = pool.shutdownNow()

  test("tasks run one at a time, in the order they were submitted, even when submitted from many threads") {
    val executor = new SerialExecutor(pool, _ => ())
    val running = new AtomicInteger(0)
    val overlapped = new AtomicInteger(0)
    val order = new ConcurrentLinkedQueue[Int]()
    val done = new CountDownLatch(1000)

    // One submitter: its tasks must run in order
    (1 to 500).foreach { i =>
      executor.execute { () =>
        if (running.incrementAndGet() > 1) overlapped.incrementAndGet()
        order.add(i)
        running.decrementAndGet()
        done.countDown()
      }
    }
    // Many submitters at once: their tasks must still never overlap
    (1 to 500).foreach { _ =>
      pool.execute { () =>
        executor.execute { () =>
          if (running.incrementAndGet() > 1) overlapped.incrementAndGet()
          running.decrementAndGet()
          done.countDown()
        }
      }
    }

    done.await(10, TimeUnit.SECONDS) shouldBe true
    overlapped.get shouldBe 0
    order.asScala.toList shouldBe (1 to 500).toList
  }

  test("a failing task is reported and does not stop the tasks after it") {
    val failures = new ConcurrentLinkedQueue[Throwable]()
    val executor = new SerialExecutor(pool, e => failures.add(e))
    val done = new CountDownLatch(1)
    executor.execute(() => throw new IllegalStateException("boom"))
    executor.execute(() => done.countDown())
    done.await(5, TimeUnit.SECONDS) shouldBe true
    failures.asScala.map(_.getMessage).toList shouldBe List("boom")
  }

  test("separate executors on one pool run their tasks concurrently") {
    val first = new SerialExecutor(pool, _ => ())
    val second = new SerialExecutor(pool, _ => ())
    val bothRunning = new CountDownLatch(2)
    val finished = new CountDownLatch(2)
    Seq(first, second).foreach { executor =>
      executor.execute { () =>
        bothRunning.countDown()
        // Each waits for the other: only possible if they run at the same time
        bothRunning.await(5, TimeUnit.SECONDS)
        finished.countDown()
      }
    }
    finished.await(10, TimeUnit.SECONDS) shouldBe true
    bothRunning.getCount shouldBe 0
  }
}
