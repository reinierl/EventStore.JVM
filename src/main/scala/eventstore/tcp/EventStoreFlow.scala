package eventstore
package tcp

import java.nio.ByteOrder

import akka.NotUsed
import akka.event.LoggingAdapter
import akka.stream.scaladsl._
import eventstore.tcp.EventStoreFormats._
import eventstore.util.{ BidiFraming, BidiLogging, BytesReader, BytesWriter }

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

object EventStoreFlow {

  def apply(
    heartbeatInterval: FiniteDuration,
    parallelism:       Int,
    ordered:           Boolean,
    log:               LoggingAdapter
  )(implicit ec: ExecutionContext): BidiFlow[ByteString, PackIn, PackOut, ByteString, NotUsed] = {

    val incoming = Flow[ByteString]
      .mapFuture(ordered, parallelism) { x => BytesReader[PackIn].read(x) }

    val outgoing = Flow[PackOut]
      .mapFuture(ordered, parallelism) { x => BytesWriter[PackOut].toByteString(x) }
      .keepAlive(heartbeatInterval, () => BytesWriter[PackOut].toByteString(PackOut(HeartbeatRequest)))

    val autoReply = {
      def reply(message: Out, byteString: ByteString): ByteString = {
        val packIn = BytesReader[PackIn].read(byteString)
        val packOut = PackOut(message, packIn.correlationId)
        BytesWriter[PackOut].toByteString(packOut)
      }

      BidiReply[ByteString, ByteString] {
        case x if x.head == 0x01 => reply(HeartbeatResponse, x)
        case x if x.head == 0x03 => reply(Pong, x)
      }
    }

    val framing = BidiFraming(fieldLength = 4, maxFrameLength = 64 * 1024 * 1024)(ByteOrder.LITTLE_ENDIAN)

    val serialization = BidiFlow.fromFlows(incoming, outgoing)

    val logging = BidiLogging(log)

    framing atop autoReply atop serialization atop logging
  }

  private implicit class FlowOps[In](self: Flow[In, In, NotUsed]) {
    def mapFuture[Out](ordered: Boolean, parallelism: Int)(f: In => Out)(implicit ec: ExecutionContext): Flow[In, Out, NotUsed] = {
      if (ordered) self.mapAsync(parallelism) { x => Future { f(x) } }
      else self.mapAsyncUnordered(parallelism) { x => Future { f(x) } }
    }
  }
}