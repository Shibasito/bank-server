package cc4p1.bank.mq;

import cc4p1.bank.service.BankService;
import com.rabbitmq.client.*;

public class Rabbit implements AutoCloseable {
  private final Connection conn;
  private final Channel ch;

  private static final String BANK_EXCHANGE = "rabbit_exchange";
  private static final String BANK_QUEUE = "bank_queue";
  private static final String BANK_ROUTING_KEY = "bank_operation";
  private static final String USER = "admin";
  private static final String PASSWORD = "admin";

  public Rabbit(String host) throws Exception {
    ConnectionFactory f = new ConnectionFactory();
    f.setHost(host);
    f.setUsername(USER);
    f.setPassword(PASSWORD);
    this.conn = f.newConnection();
    this.ch = conn.createChannel();
  }

  /**
   * Comienza a consumir mensajes de la cola bank_queue (vinculada a bank_exchange mediante la clave de enrutamiento bank_operation).
   * Las respuestas se publican en la cola indicada por `reply_to` en el mensaje de solicitud.
   */
  public void serve(BankService bank) throws Exception {
    // 1. Declarar exchange y queue, y vincularlos
    ch.exchangeDeclare(BANK_EXCHANGE, BuiltinExchangeType.DIRECT, true);
    ch.queueDeclare(BANK_QUEUE, true, false, false, null);
    ch.queueBind(BANK_QUEUE, BANK_EXCHANGE, BANK_ROUTING_KEY);
    ch.basicQos(32);

    System.out.printf(" [*] Bank Server waiting on exchange=%s key=%s queue=%s%n",
        BANK_EXCHANGE, BANK_ROUTING_KEY, BANK_QUEUE);

    DeliverCallback cb = (tag, delivery) -> {
      String corrId = delivery.getProperties().getCorrelationId();
      String replyTo = delivery.getProperties().getReplyTo();
      String body = new String(delivery.getBody());

      // Lógica de negocio
      String response = bank.handle(body, corrId);

      // Propiedades del mensaje de respuesta
      AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
          .correlationId(corrId)
          .build();

      // Publicar de vuelta en la cola de respuesta del cliente
      ch.basicPublish("", replyTo, props, response.getBytes());
      ch.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
    };

    // Consumir de la cola bank_queue
    ch.basicConsume(BANK_QUEUE, false, cb, tag -> {});
  }

  /**
   * Envía un mensaje a la cola de Reniec a través del intercambio reniec_exchange con la clave de enrutamiento reniec_operation.
   */
  public String sendToReniec(String messageJson, String correlationId, String replyQueue) throws Exception {
    String reniecExchange = "rabbit_exchange";
    String reniecRoutingKey = "reniec_operation";

    // Asegurarse que exista el intercambio
    ch.exchangeDeclare(reniecExchange, BuiltinExchangeType.DIRECT, true);

    AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
        .correlationId(correlationId)
        .replyTo(replyQueue)
        .build();

    ch.basicPublish(reniecExchange, reniecRoutingKey, props, messageJson.getBytes());
    return correlationId;
  }

  @Override
  public void close() throws Exception {
    try { ch.close(); } finally { conn.close(); }
  }
}
