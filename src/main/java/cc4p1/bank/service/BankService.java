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
      String type = reqStr(r, "type");
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
      c.commit();
      if (cli == null)
        return error("CLIENT_NOT_FOUND", corrId);
      Map<String, Object> data = Map.of(
          "clientId", cli.idCliente(),
          "dni", cli.dni(),
          "nombres", cli.nombres(),
          "apellidoPat", cli.apellidoPat(),
          "apellidoMat", cli.apellidoMat(),
          "direccion", cli.direccion(),
          "telefono", cli.telefono(),
          "correo", cli.correo(),
          "fechaRegistro", String.valueOf(cli.fechaRegistro()).replace('T', ' '));
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
      List<Transaccion> items = txRepo.listByAccountAndDate(c, accountId, from, to, limit, offset);
      c.commit();
      List<Map<String, Object>> list = new ArrayList<>();
      for (Transaccion t : items) {
        list.add(Map.of(
            "txId", t.idTransaccion(),
            "idTransferencia", t.idTransferencia(),
            "tipo", t.tipo().toString(),
            "monto", t.monto(),
            "fecha", t.fecha() == null ? null : t.fecha().toString().replace('T', ' ')));
      }
      Map<String, Object> data = Map.of("accountId", accountId, "items", list, "count", list.size(), "hasMore",
          list.size() == limit);
      return ok(data, corrId);
    }
  }

  /* ======================= WRITES (idempotent) ======================= */

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
    Map<String, Object> res = Map.of("ok", true, "data", data, "error", null, "correlationId", corrId);
    return om.writeValueAsString(res);
  }

  private String error(String msg, String corrId) {
    try {
      Map<String, Object> res = Map.of("ok", false, "data", null, "error", Map.of("message", msg), "correlationId",
          corrId);
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
