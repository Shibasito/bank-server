package cc4p1.bank.mq;

import cc4p1.bank.service.BankService;
import com.rabbitmq.client.*;

public class Rabbit implements AutoCloseable {
  private final Connection conn;
  private final Channel ch;

  public Rabbit(String host) throws Exception {
    ConnectionFactory f = new ConnectionFactory();
    f.setHost(host);
    this.conn = f.newConnection();
    this.ch = conn.createChannel();
  }

  public void serve(BankService bank) throws Exception {
    String queue = "bank_requests";
    ch.queueDeclare(queue, true, false, false, null);
    ch.basicQos(32);

    DeliverCallback cb = (tag, delivery) -> {
      String corrId = delivery.getProperties().getCorrelationId();
      String replyTo = delivery.getProperties().getReplyTo();
      String body = new String(delivery.getBody());

      String response = bank.handle(body, corrId);

      AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
          .correlationId(corrId)
          .build();

      ch.basicPublish("", replyTo, props, response.getBytes());
      ch.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
    };

    ch.basicConsume(queue, false, cb, tag -> {});
  }

  @Override public void close() throws Exception {
    try { ch.close(); } finally { conn.close(); }
  }
}
