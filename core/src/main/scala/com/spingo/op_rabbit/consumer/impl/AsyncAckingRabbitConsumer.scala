package com.spingo.op_rabbit.consumer.impl

import akka.actor.{Actor, ActorLogging, Terminated}
import com.rabbitmq.client.AMQP.BasicProperties
import com.spingo.op_rabbit.RabbitExceptionMatchers
import com.spingo.op_rabbit.consumer._
import com.thenewmotion.akka.rabbitmq.{Channel, DefaultConsumer, Envelope}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

protected [op_rabbit] class AsyncAckingRabbitConsumer[T](
  name: String,
  queueName: String,
  recoveryStrategy: com.spingo.op_rabbit.consumer.RecoveryStrategy,
  rabbitErrorLogging: RabbitErrorLogging,
  handle: Handler)(implicit executionContext: ExecutionContext) extends Actor with ActorLogging {

  import Consumer._

  var pendingDeliveries = mutable.Set.empty[Long]

  case class RejectOrAck(ack: Boolean, deliveryTag: Long)

  context watch self

  def receive = {
    case Subscribe(channel) =>
      val consumerTag = setupSubscription(channel)
      context.become(connected(channel, Some(consumerTag)))
    case Unsubscribe =>
      sender ! true
      ()
    case Abort =>
      context stop self
      context.become(stopped)
    case Shutdown =>
      context stop self
      context.become(stopped)
    case Terminated(ref) if ref == self =>
      ()
  }

  def connected(channel: Channel, consumerTag: Option[String]): Receive = {
    case Subscribe(newChannel) =>
      if (channel != newChannel)
        pendingDeliveries.clear()

      val newConsumerTag = setupSubscription(newChannel)
      context.become(connected(newChannel, Some(newConsumerTag)))
    case Unsubscribe =>
      handleUnsubscribe(channel, consumerTag)
      sender ! true
      context.become(connected(channel, None))
    case delivery: Delivery =>
      handleDelivery(channel, delivery)
    case RejectOrAck(ack, consumerTag) =>
      handleRejectOrAck(ack, channel, consumerTag)
    case Shutdown =>
      handleUnsubscribe(channel, consumerTag)
      if(pendingDeliveries.isEmpty) {
        context stop self
        context.become(stopped)
      } else {
        context.become(stopping(channel))
      }
    case Abort =>
      context stop self
      context.become(stopped)
    case Terminated(ref) if ref == self =>
      handleUnsubscribe(channel, consumerTag)
      context.become(stopping(channel))
  }

  def stopping(channel: Channel): Receive = {
    case Subscribe(newChannel) =>
      // we lost our connection while stopping? Just bail. Nothing more to do.
      if (newChannel != channel) {
        pendingDeliveries.clear
        context stop self
      }
    case Unsubscribe =>
      sender ! true
    case RejectOrAck(ack, consumerTag) =>
      handleRejectOrAck(ack, channel, consumerTag)
      if (pendingDeliveries.isEmpty)
        context stop self
    case Delivery(consumerTag, envelope, properties, body) =>
      // note! Before RabbitMQ 2.7.0 does not preserve message order when this happens!
      // https://www.rabbitmq.com/semantics.html
      channel.basicReject(envelope.getDeliveryTag, true)
    case Shutdown =>
      ()
    case Abort =>
      context stop self
      context.become(stopped)
    case Terminated(ref) if ref == self =>
      ()
  }

  val stopped: Receive = {
    case _ =>
      // we're stopped, ignore all the things
  }

  def setupSubscription(channel: Channel): String = {
    channel.basicConsume(queueName, false,
      new DefaultConsumer(channel) {
        override def handleDelivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]): Unit = {
          self ! Delivery(consumerTag, envelope, properties, body)
        }
      }
    )
  }

  def handleUnsubscribe(channel: Channel, consumerTag: Option[String]): Unit = {
    try {
      consumerTag.foreach(channel.basicCancel(_))
    } catch {
      case RabbitExceptionMatchers.NonFatalRabbitException(_) =>
        ()
    }
  }

  def handleRejectOrAck(ack: Boolean, channel: Channel, consumerTag: Long): Unit = {
    pendingDeliveries.remove(consumerTag)
    if (ack)
      Try { channel.basicAck(consumerTag, false) }
    else
      Try { channel.basicReject(consumerTag, true) }
  }

  def handleDelivery(channel: Channel, delivery: Delivery): Unit = {
    val Delivery(consumerTag, envelope, properties, body) = delivery
    pendingDeliveries.add(envelope.getDeliveryTag)

    lazy val reportError = rabbitErrorLogging(name, _: String, _: Throwable, consumerTag, envelope, properties, body)

    val handled = Promise[Result]

    Future {
      try handle(handled, delivery)
      catch {
        case e: Throwable =>
          handled.success(Left(UnhandledExceptionRejection("Error while running handler", e)))
      }
    }

    handled.future.
      recover { case e => Left(UnhandledExceptionRejection("Unhandled exception occurred in async acking Future", e)) }.
      flatMap {
        case Right(_) =>
          Future.successful(true)
        case Left(r @ NackRejection(msg)) =>
          Future.successful(false) // just nack the message; it was intentional. Don't recover. Don't report.
        case Left(r @ UnhandledExceptionRejection(msg, cause)) =>
          reportError(msg, cause)
          recoveryStrategy(cause, channel, queueName, delivery)
        case Left(r @ ExtractRejection(msg, _)) =>
          // retrying is not going to do help. What to do? ¯\_(ツ)_/¯
          reportError(s"Could not extract required data", r)
          recoveryStrategy(r, channel, queueName, delivery)
      }.
      onComplete {
        case Success(ack) =>
          self ! RejectOrAck(ack, envelope.getDeliveryTag())
        case Failure(e) =>
          log.error(s"Recovery strategy failed, or something else went horribly wrong; rejecting, then shutting consumer down.", e)
          self ! RejectOrAck(false, envelope.getDeliveryTag())
          self ! Shutdown
      }
  }
}
