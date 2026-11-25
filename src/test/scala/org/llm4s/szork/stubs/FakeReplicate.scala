package org.llm4s.szork.stubs

import com.sun.net.httpserver.HttpServer

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters._

/** A local stand-in for Replicate's predictions API (and the place its audio is downloaded from): the code under test
  * talks to it over HTTP, so a test sees the requests as they go on the wire and scripts any status the API could
  * answer with.
  *
  * Every request is answered by `answer`; the audio is served at `/audio.mp3`.
  */
final class FakeReplicate(answer: FakeReplicate.Request => FakeReplicate.Reply) {
  import FakeReplicate._

  private val received = new CopyOnWriteArrayList[Request]()
  private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)

  server.createContext(
    "/",
    exchange => {
      val request = Request(
        exchange.getRequestMethod,
        exchange.getRequestURI.getPath,
        new String(exchange.getRequestBody.readAllBytes(), UTF_8),
        Option(exchange.getRequestHeaders.getFirst("Authorization"))
      )
      received.add(request)
      val reply = answer(request)
      val body = reply.body
      exchange.sendResponseHeaders(reply.status, if (body.isEmpty) -1L else body.length.toLong)
      if (body.nonEmpty) exchange.getResponseBody.write(body)
      exchange.close()
    }
  )
  server.start()

  /** The requests received, oldest first. */
  def requests: List[Request] = received.asScala.toList

  /** How many requests `method` `path` has had. */
  def count(method: String, path: String): Int = requests.count(r => r.method == method && r.path == path)

  /** Where the API is, for [[org.llm4s.szork.media.MusicGeneration]]'s `baseUrl`. */
  def baseUrl: String = s"http://127.0.0.1:${server.getAddress.getPort}/v1"

  /** Where the audio is. */
  def audioUrl: String = s"http://127.0.0.1:${server.getAddress.getPort}/audio.mp3"

  def stop(): Unit = server.stop(0)
}

object FakeReplicate {
  final case class Request(method: String, path: String, body: String, authorization: Option[String])
  final case class Reply(status: Int, body: Array[Byte] = Array.emptyByteArray)

  def json(status: Int, value: ujson.Value): Reply = Reply(status, ujson.write(value).getBytes(UTF_8))

  /** The audio the server serves. */
  val Audio: Array[Byte] = "ID3 pretend this is an mp3".getBytes(UTF_8)

  /** Runs `test` against a server answering with `answer`, and stops it. */
  def using[A](answer: FakeReplicate => Request => Reply)(test: FakeReplicate => A): A = {
    // The answer may need the server's own address (the audio URL), which exists only once it is running
    var server: FakeReplicate = null
    server = new FakeReplicate(request => answer(server)(request))
    try test(server)
    finally server.stop()
  }
}
