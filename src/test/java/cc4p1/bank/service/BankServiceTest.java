package cc4p1.bank.service;

import cc4p1.bank.db.SQLite;
import cc4p1.bank.repo.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BankServiceTest {

  private Path tempDb;
  private SQLite sqlite;
  private AccountRepo accountRepo;
  private ClientRepo clientRepo;
  private LoanRepo loanRepo;
  private TxRepo txRepo;
  private MessageRepo messageRepo;
  private BankService bank;
  private final ObjectMapper om = new ObjectMapper();

  @BeforeEach
  void setUp() throws Exception {
    tempDb = Files.createTempFile("bank-test-", ".db");
    sqlite = new SQLite(tempDb.toString());
    sqlite.initializeIfNeeded("/db/init_db.sql");

    clientRepo = new ClientRepo();
    accountRepo = new AccountRepo();
    loanRepo = new LoanRepo();
    txRepo = new TxRepo();
    messageRepo = new MessageRepo();

    // Mock RENIEC: always valid
    var reniec = new MockReniecClient(true, 0);

    bank = new BankService(sqlite, clientRepo, accountRepo, loanRepo, txRepo, messageRepo, reniec);
  }

  @AfterEach
  void tearDown() throws IOException {
    Files.deleteIfExists(tempDb);
  }

  private JsonNode call(Map<String, Object> req) throws Exception {
    String body = om.writeValueAsString(req);
    String resp = bank.handle(body, "corr-1");
    return om.readTree(resp);
  }

  @Test
  void getBalance_existingAccount_ok() throws Exception {
    // Debug: inspect DB row
    try (java.sql.Connection c = sqlite.get();
         java.sql.PreparedStatement ps = c.prepareStatement("SELECT id_cuenta, saldo, fecha_apertura FROM CUENTAS WHERE id_cuenta='CU001'");
         java.sql.ResultSet rs = ps.executeQuery()) {
      if (rs.next()) {
        System.out.println("DEBUG CU001 row: id=" + rs.getString(1) + ", saldo=" + rs.getString(2) + ", fecha_apertura=" + rs.getString(3));
      } else {
        System.out.println("DEBUG CU001 row: NOT FOUND");
      }
      c.commit();
    }

    JsonNode res = call(Map.of(
        "type", "GetBalance",
        "accountId", "CU001"
    ));
    if (!res.get("ok").asBoolean()) {
      System.out.println("DEBUG GetBalance response: " + res.toPrettyString());
    }
    assertTrue(res.get("ok").asBoolean());
    assertEquals("CU001", res.path("data").path("accountId").asText());
    assertEquals(2500.00, res.path("data").path("balance").asDouble(), 0.001);
  }

  @Test
  void getClientInfo_existingClient_ok() throws Exception {
    JsonNode res = call(Map.of(
        "type", "GetClientInfo",
        "clientId", "CL001"
    ));
    assertTrue(res.get("ok").asBoolean());
    JsonNode data = res.path("data");
    assertEquals("CL001", data.path("clientId").asText());
    assertEquals("45678912", data.path("dni").asText());
    assertEquals("MARÍA ELENA", data.path("nombres").asText());
    assertEquals("GARCÍA FLORES", data.path("apellidos").asText());
    assertEquals("Av. Universitaria 1234", data.path("direccion").asText());
    assertNotNull(data.get("fechaRegistro"));
    
    // Verify accounts information
    assertTrue(data.has("accounts"));
    assertTrue(data.has("totalAccounts"));
    JsonNode accounts = data.get("accounts");
    assertTrue(accounts.isArray());
    assertEquals(1, data.path("totalAccounts").asInt());
    
    // Verify first account (CU001)
    JsonNode firstAccount = accounts.get(0);
    assertEquals("CU001", firstAccount.path("accountId").asText());
    assertEquals(2500.00, firstAccount.path("balance").asDouble(), 0.001);
    assertNotNull(firstAccount.get("fechaApertura"));
  }

  @Test
  void getClientInfo_nonExistentClient_error() throws Exception {
    JsonNode res = call(Map.of(
        "type", "GetClientInfo",
        "clientId", "CL999"
    ));
    assertFalse(res.get("ok").asBoolean());
    assertEquals("CLIENT_NOT_FOUND", res.path("error").path("message").asText());
  }

  @Test
  void getClientInfo_multipleAccounts_ok() throws Exception {
    try (Connection c = sqlite.get()) {
      accountRepo.insert(c, new cc4p1.bank.domain.Cuenta("CU002", "CL001", new BigDecimal("1500.00"), java.time.LocalDate.now()));
      accountRepo.insert(c, new cc4p1.bank.domain.Cuenta("CU003", "CL001", new BigDecimal("3000.00"), java.time.LocalDate.now()));
      c.commit();
    }

    JsonNode res = call(Map.of(
        "type", "GetClientInfo",
        "clientId", "CL001"
    ));
    assertTrue(res.get("ok").asBoolean());
    JsonNode data = res.path("data");
    
    assertEquals(3, data.path("totalAccounts").asInt());
    JsonNode accounts = data.get("accounts");
    assertEquals(3, accounts.size());
    
    boolean hasCU001 = false, hasCU002 = false, hasCU003 = false;
    for (int i = 0; i < accounts.size(); i++) {
      String accountId = accounts.get(i).path("accountId").asText();
      if ("CU001".equals(accountId)) hasCU001 = true;
      if ("CU002".equals(accountId)) hasCU002 = true;
      if ("CU003".equals(accountId)) hasCU003 = true;
    }
    assertTrue(hasCU001);
    assertTrue(hasCU002);
    assertTrue(hasCU003);
  }

  @Test
  void deposit_then_duplicate_is_idempotent() throws Exception {
    // First deposit
    JsonNode r1 = call(Map.of(
        "type", "Deposit",
        "messageId", "msg-1",
        "accountId", "CU001",
        "amount", "150.00"
    ));
    assertTrue(r1.get("ok").asBoolean());
    double bal1 = r1.path("data").path("newBalance").asDouble();
  assertEquals(2650.00, bal1, 0.001);

    // Duplicate with same messageId
    JsonNode r2 = call(Map.of(
        "type", "Deposit",
        "messageId", "msg-1",
        "accountId", "CU001",
        "amount", "150.00"
    ));
    assertTrue(r2.get("ok").asBoolean());
    assertTrue(r2.path("data").path("duplicate").asBoolean());

    // Balance should remain equal to bal1
    try (Connection c = sqlite.get()) {
      var cu = accountRepo.findById(c, "CU001");
      c.commit();
      assertNotNull(cu);
  assertEquals(0, cu.saldo().compareTo(new BigDecimal("2650.00")));
    }
  }

  @Test
  void withdraw_ok_and_insufficientFunds_error() throws Exception {
    // ok
    JsonNode ok = call(Map.of(
        "type", "Withdraw",
        "messageId", "msg-2",
        "accountId", "CU001",
        "amount", "200.00"
    ));
    assertTrue(ok.get("ok").asBoolean());
    assertEquals(2300.00, ok.path("data").path("newBalance").asDouble(), 0.001);

    // insufficient funds
    JsonNode bad = call(Map.of(
        "type", "Withdraw",
        "messageId", "msg-3",
        "accountId", "CU001",
        "amount", "999999.00"
    ));
    assertFalse(bad.get("ok").asBoolean());
    assertTrue(bad.path("error").path("message").asText().contains("INSUFFICIENT_FUNDS"));
  }

  @Test
  void transfer_between_accounts_two_legs() throws Exception {
    // Create destination account CU002 for CL001 with saldo 1000
    try (Connection c = sqlite.get()) {
      accountRepo.insert(c, new cc4p1.bank.domain.Cuenta("CU002", "CL001", new BigDecimal("1000.00"), java.time.LocalDate.now()));
      c.commit();
    }

    JsonNode res = call(Map.of(
        "type", "Transfer",
        "messageId", "msg-4",
        "fromAccountId", "CU001",
        "toAccountId", "CU002",
        "amount", "100.00"
    ));
    assertTrue(res.get("ok").asBoolean());
    JsonNode data = res.get("data");
    assertNotNull(data.get("txId"));
    assertNotNull(data.get("transferId"));

    // Verify balances
    try (Connection c = sqlite.get()) {
      var from = accountRepo.findById(c, "CU001");
      var to = accountRepo.findById(c, "CU002");
      c.commit();
  assertEquals(0, from.saldo().compareTo(new BigDecimal("2400.00")));
  assertEquals(0, to.saldo().compareTo(new BigDecimal("1100.00")));
    }
  }

  @Test
  void transfer_same_account_error() throws Exception {
    JsonNode res = call(Map.of(
        "type", "Transfer",
        "messageId", "msg-5",
        "fromAccountId", "CU001",
        "toAccountId", "CU001",
        "amount", "10.00"
    ));
    assertFalse(res.get("ok").asBoolean());
    assertEquals("SAME_ACCOUNT", res.path("error").path("message").asText());
  }

  @Test
  void listTransactions_after_operations_has_items() throws Exception {
    // perform two ops: +50, -25 => final balance should be 2500 + 50 - 25 = 2525
    call(Map.of("type", "Deposit", "messageId", "msg-6", "accountId", "CU001", "amount", "50.00"));
    call(Map.of("type", "Withdraw", "messageId", "msg-7", "accountId", "CU001", "amount", "25.00"));

    JsonNode res = call(Map.of(
        "type", "ListTransactions",
        "accountId", "CU001",
        "from", "2000-01-01",
        "to", "2100-01-01",
        "limit", 100,
        "offset", 0
    ));
    if (!res.get("ok").asBoolean()) {
      System.out.println("DEBUG ListTransactions response: " + res.toPrettyString());
    }
    assertTrue(res.get("ok").asBoolean());
    JsonNode data = res.path("data");
    
    // Verify current balance is included
    assertTrue(data.has("currentBalance"));
    assertEquals(2525.00, data.path("currentBalance").asDouble(), 0.001);
    
    JsonNode items = data.path("items");
    assertTrue(items.isArray());
    assertTrue(items.size() >= 2);
    JsonNode first = items.get(0);
    assertNotNull(first.get("txId"));
    assertNotNull(first.get("tipo"));
    assertNotNull(first.get("monto"));
  }

  @Test
  void createLoan_credits_account_and_persists_loan() throws Exception {
    JsonNode res = call(Map.of(
        "type", "CreateLoan",
        "messageId", "msg-8",
        "clientId", "CL001",
        "accountId", "CU001",
        "principal", "1000.00"
    ));
    assertTrue(res.get("ok").asBoolean());
    assertEquals("activo", res.path("data").path("status").asText());

    // new balance = 2500 + 1000 = 3500 (no other ops in this fresh DB for this test)
    try (Connection c = sqlite.get()) {
      var cu = accountRepo.findById(c, "CU001");
      c.commit();
      assertEquals(0, cu.saldo().compareTo(new BigDecimal("3500.00")));
    }
  }

  @Test
  void listTransactions_nonExistentAccount_error() throws Exception {
    JsonNode res = call(Map.of(
        "type", "ListTransactions",
        "accountId", "CU999",
        "from", "2000-01-01",
        "to", "2100-01-01",
        "limit", 100,
        "offset", 0
    ));
    assertFalse(res.get("ok").asBoolean());
    assertEquals("ACCOUNT_NOT_FOUND", res.path("error").path("message").asText());
  }
}
