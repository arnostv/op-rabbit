package com.spingo.op_rabbit

import org.scalatest.{FunSpec, Matchers}
import com.spingo.op_rabbit.properties._

class MessageSpec extends FunSpec with Matchers {
  case class Data(name: String, age: Int)

  // these are stupid tests...
  describe("QueueMessage") {
    it("creates a message for delivery, serializes the data, and applies the provided properties, and defaults to persistent") {
      val msg = QueueMessage(
        "very payload",
        queue = "destination.queue",
        properties = List(ReplyTo("respond.here.please")))

      println(msg.properties)
      msg.data should be ("very payload".getBytes)
      msg.publisher.isInstanceOf[QueuePublisher] should be (true)
      msg.properties.getDeliveryMode should be (2)
      msg.properties.getReplyTo should be ("respond.here.please")
    }
  }

  describe("TopicMessage") {
    it("creates a message for delivery, and applies the provided properties, and defaults to persistent") {
      val msg = TopicMessage(
        "very payload",
        routingKey = "destination.topic",
        properties = List(ReplyTo("respond.here.please")))

      println(msg.properties)
      msg.properties.getDeliveryMode should be (2)
      msg.properties.getReplyTo should be ("respond.here.please")
      msg.publisher.isInstanceOf[TopicPublisher] should be (true)
      msg.properties.getReplyTo should be ("respond.here.please")
    }
  }

  describe("ConfirmedMessage") {
    val msg = ConfirmedMessage(TopicPublisher("very.route"), "hi", List(ReplyTo("respond.here.please")))
    msg.properties.getDeliveryMode should be (2)
    msg.properties.getReplyTo should be ("respond.here.please")
  }
}
