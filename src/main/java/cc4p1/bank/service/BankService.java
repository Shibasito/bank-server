package cc4p1.bank.service;

import cc4p1.bank.db.SQLite;
import cc4p1.bank.repo.*;
import cc4p1.bank.util.Ids;
import cc4p1.bank.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.*;

public class BankService {

  private final SQLite sqlite;
  private final ClientRepo clientRepo;
  private final AccountRepo accountRepo;
  private final LoanRepo loanRepo;
  private final TxRepo txRepo;
  private final MessageRepo messageRepo;
  private final ReniecClient reniec; // interfaz a reniec
  private final ObjectMapper om = new ObjectMapper();

  public BankService(SQLite sqlite,
      ClientRepo clientRepo,
      AccountRepo accountRepo,
      LoanRepo loanRepo,
      TxRepo txRepo,
      MessageRepo messageRepo,
      ReniecClient reniec) {
    this.sqlite = sqlite;
    this.clientRepo = clientRepo;
    this.accountRepo = accountRepo;
    this.loanRepo = loanRepo;
    this.txRepo = txRepo;
    this.messageRepo = messageRepo;
    this.reniec = reniec;
  }

  /** Entry point from Rabbit. corrId is echoed back in the response JSON. */
  public String handle(String body, String corrId) {
    try {
      JsonNode r = om.readTree(body);
      // Compat: aceptar tanto {type} como {operationType}
      String type = r.hasNonNull("type") ? r.get("type").asText()
          : (r.hasNonNull("operationType") ? r.get("operationType").asText() : null);
      if (type == null) {
        return error("MISSING_type", corrId);
      }
  // Normalizar alias en minúsculas comunes (no requerido por ahora)
      switch (type) {
        case "GetBalance" -> {
          return handleGetBalance(r, corrId);
        }
        case "GetClientInfo" -> {
          return handleGetClientInfo(r, corrId);
        }
        case "ListTransactions" -> {
          return handleListTransactions(r, corrId);
        }
        case "Deposit" -> {
          return handleDeposit(r, corrId);
        }
        case "Withdraw" -> {
          return handleWithdraw(r, corrId);
        }
        case "Transfer" -> {
          return handleTransfer(r, corrId);
        }
        case "CreateLoan" -> {
          return handleCreateLoan(r, corrId);
        }
        // Extensiones para el cliente web (alias en minúsculas)
        case "login", "Login" -> {
          return handleLogin(r, corrId);
        }
        case "register", "Register" -> {
          return handleRegister(r, corrId);
        }
        default -> {
          return error("UNKNOWN_TYPE: " + type, corrId);
        }
      }
    } catch (Exception e) {
      return error(e.getMessage(), corrId);
    }
  }

  /* ======================= READS ======================= */

  private String handleGetBalance(JsonNode r, String corrId) throws Exception {
    String accountId = reqStr(r, "accountId");
    try (Connection c = sqlite.get()) {
      Cuenta cu = accountRepo.findById(c, accountId);
      c.commit();
      if (cu == null)
        return error("ACCOUNT_NOT_FOUND", corrId);
      Map<String, Object> data = Map.of(
          "accountId", cu.idCuenta(),
          "balance", cu.saldo(),
          "currency", "PEN");
      return ok(data, corrId);
    }
  }

  private String handleGetClientInfo(JsonNode r, String corrId) throws Exception {
    String clientId = reqStr(r, "clientId");
    try (Connection c = sqlite.get()) {
      Cliente cli = clientRepo.findById(c, clientId);
      if (cli == null) {
        c.commit();
        return error("CLIENT_NOT_FOUND", corrId);
      }
      
      // Get all accounts for this client
      List<Cuenta> accounts = accountRepo.findAllByClient(c, clientId);
      c.commit();
      
      // Use LinkedHashMap to allow null values
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("clientId", cli.idCliente());
      data.put("dni", cli.dni());
      data.put("nombres", cli.nombres());
      // Combine apellidos
      String apellidos = cli.apellidoPat();
      if (cli.apellidoMat() != null && !cli.apellidoMat().isEmpty()) {
        apellidos = apellidos + " " + cli.apellidoMat();
      }
      data.put("apellidos", apellidos);
      data.put("direccion", cli.direccion());
      data.put("telefono", cli.telefono());
      data.put("correo", cli.correo());
      data.put("fechaRegistro", String.valueOf(cli.fechaRegistro()).replace('T', ' '));
      
      List<Map<String, Object>> accountsList = new ArrayList<>();
      for (Cuenta account : accounts) {
        Map<String, Object> accountInfo = new LinkedHashMap<>();
        accountInfo.put("accountId", account.idCuenta());
        accountInfo.put("balance", account.saldo());
        accountInfo.put("fechaApertura", account.fechaApertura().toString());
        accountsList.add(accountInfo);
      }
      data.put("accounts", accountsList);
      data.put("totalAccounts", accountsList.size());
      
      return ok(data, corrId);
    }
  }

  private String handleListTransactions(JsonNode r, String corrId) throws Exception {
    String accountId = reqStr(r, "accountId");
    String from = optStr(r, "from", "0001-01-01");
    String to = optStr(r, "to", "9999-12-31");
    int limit = optInt(r, "limit", 100);
    int offset = optInt(r, "offset", 0);

    try (Connection c = sqlite.get()) {
      // Get account info for balance
      Cuenta cuenta = accountRepo.findById(c, accountId);
      if (cuenta == null) {
        c.commit();
        return error("ACCOUNT_NOT_FOUND", corrId);
      }
      
      List<Transaccion> items = txRepo.listByAccountAndDate(c, accountId, from, to, limit, offset);
      c.commit();
      List<Map<String, Object>> list = new ArrayList<>();
      for (Transaccion t : items) {
        java.util.Map<String, Object> it = new java.util.LinkedHashMap<>();
        it.put("txId", t.idTransaccion());
        it.put("idTransferencia", t.idTransferencia()); // puede ser null
        it.put("tipo", t.tipo().toString());
        it.put("monto", t.monto());
        it.put("fecha", t.fecha() == null ? null : t.fecha().toString().replace('T', ' '));
        list.add(it);
      }
      java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
      data.put("accountId", accountId);
      data.put("currentBalance", cuenta.saldo());
      data.put("items", list);
      data.put("count", list.size());
      data.put("hasMore", list.size() == limit);
      return ok(data, corrId);
    }
  }

  /* ======================= WRITES (idempotent) ======================= */

  /** Login por DNI y password (solo lectura; no requiere idempotencia). */
  private String handleLogin(JsonNode r, String corrId) throws Exception {
    JsonNode src = r.has("payload") ? r.get("payload") : r;
    String dni = src.hasNonNull("dni") ? src.get("dni").asText() : reqStr(src, "usuario");
    String password = reqStr(src, "password");
    try (Connection c = sqlite.get()) {
      var cli = clientRepo.authenticate(c, dni, password);
      c.commit();
      if (cli == null) return error("INVALID_CREDENTIALS", corrId);
      var acct = accountRepo.findAnyByClient(c, cli.idCliente());
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("clientId", cli.idCliente());
      // Alias para clientes web en español
      data.put("clienteId", cli.idCliente());
      data.put("dni", cli.dni());
      if (acct != null) {
        data.put("accountId", acct.idCuenta());
        data.put("balance", acct.saldo());
      }
      // Agregar banderita de compatibilidad
      data.put("status", "ok");
      return ok(data, corrId);
    }
  }

  /** Registro de cliente + creación de cuenta vacía. Requiere idempotencia. */
  private String handleRegister(JsonNode r, String corrId) throws Exception {
    JsonNode p = r.has("payload") ? r.get("payload") : r;
    String msgId = optStr(r, "messageId", optStr(p, "messageId", null));
    if (msgId == null) throw new IllegalArgumentException("MISSING_messageId");

  String dni = p.hasNonNull("dni") ? p.get("dni").asText() : reqStr(p, "usuario");
    String password = reqStr(p, "password");
    String nombres = optStr(p, "nombres", "");
    String apePat = optStr(p, "apellidoPat", "");
    String apeMat = optStr(p, "apellidoMat", "");
    String direccion = optStr(p, "direccion", null);
    String telefono = optStr(p, "telefono", null);
    String correo = optStr(p, "correo", null);
    java.math.BigDecimal initial = p.hasNonNull("saldo") ? new java.math.BigDecimal(p.get("saldo").asText()) : java.math.BigDecimal.ZERO;

    try (Connection c = sqlite.get()) {
      if (messageRepo.alreadyProcessed(c, msgId)) {
        c.rollback();
        return ok(Map.of("duplicate", true), corrId);
      }

      // Unicidad por DNI
      if (clientRepo.findByDni(c, dni) != null) {
        c.rollback();
        return error("CLIENT_ALREADY_EXISTS", corrId);
      }

      String clientId = cc4p1.bank.util.Ids.client();
      var cli = new cc4p1.bank.domain.Cliente(clientId, dni, nombres, apePat, apeMat, direccion, telefono, correo, java.time.LocalDateTime.now());
      clientRepo.insert(c, cli, password);

      // Crear cuenta con saldo inicial
      String accountId = cc4p1.bank.util.Ids.account();
      var cu = new cc4p1.bank.domain.Cuenta(accountId, clientId, initial, java.time.LocalDate.now());
      accountRepo.insert(c, cu);

      messageRepo.markProcessed(c, msgId);
      c.commit();
      java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
      data.put("clientId", clientId);
      // Alias para clientes web en español
      data.put("clienteId", clientId);
      data.put("accountId", accountId);
      data.put("initialBalance", initial);
      data.put("status", "ok");
      return ok(data, corrId);
    }
  }

  private String handleDeposit(JsonNode r, String corrId) throws Exception {
    String msgId = reqStr(r, "messageId");
    String accountId = reqStr(r, "accountId");
    BigDecimal amount = reqBig(r, "amount");

    try (Connection c = sqlite.get()) {
      if (messageRepo.alreadyProcessed(c, msgId)) {
        c.rollback();
        return ok(Map.of("duplicate", true), corrId);
      }
      String txId = Ids.tx();
      txRepo.deposit(c, accountRepo, txId, accountId, amount, null);
      messageRepo.markProcessed(c, msgId);
      var newBal = accountRepo.findById(c, accountId).saldo();
      c.commit();
      return ok(Map.of("accountId", accountId, "newBalance", newBal, "txId", txId), corrId);
    } catch (Exception e) {
      return error(e.getMessage(), corrId);
    }
  }

  private String handleWithdraw(JsonNode r, String corrId) throws Exception {
    String msgId = reqStr(r, "messageId");
    String accountId = reqStr(r, "accountId");
    BigDecimal amount = reqBig(r, "amount");

    try (Connection c = sqlite.get()) {
      if (messageRepo.alreadyProcessed(c, msgId)) {
        c.rollback();
        return ok(Map.of("duplicate", true), corrId);
      }
      String txId = Ids.tx();
      txRepo.withdraw(c, accountRepo, txId, accountId, amount, null); // throws if insufficient
      messageRepo.markProcessed(c, msgId);
      var newBal = accountRepo.findById(c, accountId).saldo();
      c.commit();
      return ok(Map.of("accountId", accountId, "newBalance", newBal, "txId", txId), corrId);
    } catch (Exception e) {
      return error(e.getMessage(), corrId);
    }
  }

  private String handleTransfer(JsonNode r, String corrId) throws Exception {
    String msgId = reqStr(r, "messageId");
    String from = reqStr(r, "fromAccountId");
    String to = reqStr(r, "toAccountId");
    BigDecimal amount = reqBig(r, "amount");
    if (from.equals(to))
      return error("SAME_ACCOUNT", corrId);

    try (Connection c = sqlite.get()) {
      if (messageRepo.alreadyProcessed(c, msgId)) {
        c.rollback();
        return ok(Map.of("duplicate", true), corrId);
      }
      String transferId = Ids.transfer();
      String txId = Ids.tx();

      txRepo.transfer(c, transferId, txId, from, to, amount, accountRepo);

      messageRepo.markProcessed(c, msgId);
      var fromBal = accountRepo.findById(c, from).saldo();
      var toBal = accountRepo.findById(c, to).saldo();
      c.commit();

      Map<String, Object> data = Map.of(
          "txId", txId,
          "transferId", transferId,
          "fromAccountNewBalance", fromBal,
          "toAccountNewBalance", toBal);
      return ok(data, corrId);
    } catch (Exception e) {
      return error(e.getMessage(), corrId);
    }
  }

  private String handleCreateLoan(JsonNode r, String corrId) throws Exception {
    String msgId = reqStr(r, "messageId");
    String clientId = reqStr(r, "clientId");
    String accountId = reqStr(r, "accountId"); // where to credit the loan
    BigDecimal principal = reqBig(r, "principal");

    try (Connection c = sqlite.get()) {
      if (messageRepo.alreadyProcessed(c, msgId)) {
        c.rollback();
        return ok(Map.of("duplicate", true), corrId);
      }

      // 1) Validate client exists
      var cli = clientRepo.findById(c, clientId);
      if (cli == null) {
        c.rollback();
        return error("CLIENT_NOT_FOUND", corrId);
      }

      // 2) RENIEC validation (RPC); fail if not valid
      var v = reniec.verify(cli.dni()); // should throw or return a struct {valid, ...}
      if (!v.valid()) {
        c.rollback();
        return error("RENIEC_INVALID_ID", corrId);
      }

      // 3) Create loan and credit account
      String loanId = Ids.loan();
      loanRepo.createAndCredit(c, loanId, clientId, accountId, principal, txRepo, accountRepo);

      messageRepo.markProcessed(c, msgId);
      var newBal = accountRepo.findById(c, accountId).saldo();
      c.commit();

      Map<String, Object> data = Map.of(
          "loanId", loanId, "clientId", clientId,
          "creditedAccountId", accountId,
          "principal", principal, "status", "activo",
          "newBalance", newBal);
      return ok(data, corrId);
    } catch (Exception e) {
      return error(e.getMessage(), corrId);
    }
  }

  /* ======================= helpers ======================= */

  private static String reqStr(JsonNode r, String name) {
    if (!r.hasNonNull(name))
      throw new IllegalArgumentException("MISSING_" + name);
    return r.get(name).asText();
  }

  private static String optStr(JsonNode r, String name, String def) {
    return r.hasNonNull(name) ? r.get(name).asText() : def;
  }

  private static int optInt(JsonNode r, String name, int def) {
    return r.hasNonNull(name) ? r.get(name).asInt() : def;
  }

  private static BigDecimal reqBig(JsonNode r, String name) {
    if (!r.hasNonNull(name))
      throw new IllegalArgumentException("MISSING_" + name);
    return new BigDecimal(r.get(name).asText());
  }

  private String ok(Object data, String corrId) throws Exception {
    // Map.of no permite valores nulos; construir mapa mutable explícito
    java.util.Map<String, Object> res = new java.util.LinkedHashMap<>();
    res.put("ok", true);
    res.put("status", "ok"); // compat con algunos clientes web
    res.put("data", data);
    res.put("error", null);
    res.put("correlationId", corrId);
    return om.writeValueAsString(res);
  }

  private String error(String msg, String corrId) {
    try {
      // Evitar Map.of con nulos; usar mapas mutables
      java.util.Map<String, Object> err = new java.util.LinkedHashMap<>();
      err.put("message", msg);
      java.util.Map<String, Object> res = new java.util.LinkedHashMap<>();
      res.put("ok", false);
  res.put("status", "error"); // compat con algunos clientes web
      res.put("data", null);
      res.put("error", err);
      res.put("correlationId", corrId);
      return om.writeValueAsString(res);
    } catch (Exception e) {
      return "{\"ok\":false,\"error\":{\"message\":\"" + msg + "\"}}";
    }
  }

  /* Minimal RENIEC client contract */
  public interface ReniecClient {
    Verification verify(String dni) throws Exception;

    record Verification(boolean valid, String dni, String nombres, String apellidoPat, String apellidoMat) {
    }
  }
}
