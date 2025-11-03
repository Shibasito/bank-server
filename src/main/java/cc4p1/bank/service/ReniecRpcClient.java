package cc4p1.bank.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ReniecRpcClient implements BankService.ReniecClient, AutoCloseable {

  private static final String EXCHANGE = "rabbit_exchange";   // direct exchange shared
  private static final String ROUTING_KEY = "reniec_operation";
  private static final String USER = "admin";
  private static final String PASSWORD = "admin";

  private final Connection conn;
  private final Channel ch;
  private final ObjectMapper om = new ObjectMapper();

  public ReniecRpcClient(String host) throws Exception {
    ConnectionFactory f = new ConnectionFactory();
    f.setHost(host);
    f.setUsername(USER);
    f.setPassword(PASSWORD);
    this.conn = f.newConnection();
    this.ch = conn.createChannel();
    // asegurar que el intercambio exista
    ch.exchangeDeclare(EXCHANGE, BuiltinExchangeType.DIRECT, true);
  }

  @Override
  public Verification verify(String dni) throws Exception {
    String corrId = UUID.randomUUID().toString();

    // 1) Cola de respuesta temporal y exclusiva para este RPC
    String replyQueue = ch.queueDeclare("", false, true, true, null).getQueue();

    // 2) Construir la solicitud
    byte[] body = om.writeValueAsBytes(Map.of("type", "VerifyIdentity", "dni", dni));
    AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
        .correlationId(corrId)
        .replyTo(replyQueue)
        .contentType("application/json")
        .build();

    // 3) Publicar a RENIEC mediante exchange directo y clave de enrutamiento
    ch.basicPublish(EXCHANGE, ROUTING_KEY, props, body);

    // 4) Esperar la respuesta con el mismo correlation_id (RPC bloqueante simple)
    final BlockingQueue<String> response = new ArrayBlockingQueue<>(1);
    String ctag = ch.basicConsume(replyQueue, true, (tag, delivery) -> {
      if (corrId.equals(delivery.getProperties().getCorrelationId())) {
        response.offer(new String(delivery.getBody(), StandardCharsets.UTF_8));
      }
    }, tag -> {});

    String resp = response.poll(5, TimeUnit.SECONDS); // tiempo de espera configurable
    ch.basicCancel(ctag);
    if (resp == null) throw new TimeoutException("RENIEC timeout");

    // 5) Analizar el sobre común de respuesta
    JsonNode root = om.readTree(resp);
    if (!root.path("ok").asBoolean(false)) {
      String msg = root.path("error").path("message").asText("RENIEC error");
      throw new IllegalStateException(msg);
    }
    JsonNode data = root.path("data");
    boolean valid = data.path("valid").asBoolean(false);
    String nombres = data.path("nombres").isMissingNode() ? null : data.get("nombres").asText();
    String apPat   = data.path("apellidoPat").isMissingNode() ? null : data.get("apellidoPat").asText();
    String apMat   = data.path("apellidoMat").isMissingNode() ? null : data.get("apellidoMat").asText();

    return new Verification(valid, dni, nombres, apPat, apMat);
  }

  @Override
  public void close() throws Exception {
    try { ch.close(); } finally { conn.close(); }
  }
}
