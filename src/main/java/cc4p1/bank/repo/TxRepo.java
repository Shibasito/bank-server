package cc4p1.bank.repo;

import cc4p1.bank.domain.TipoTransaccion;
import cc4p1.bank.domain.Transaccion;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class TxRepo {

  public Transaccion findById(Connection c, String txId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT * FROM TRANSACCIONES WHERE id_transaccion=?")) {
      ps.setString(1, txId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? map(rs) : null;
      }
    }
  }

  public List<Transaccion> listByAccountAndDate(Connection c, String accountId, String from, String to, int limit,
      int offset) throws SQLException {
    // Use index-friendly range filter on ISO datetime strings
    // Lower bound inclusive: from 00:00:00
    // Upper bound exclusive: to + 1 day at 00:00:00
    String sql = """
        SELECT * FROM TRANSACCIONES
         WHERE id_cuenta=?
           AND fecha >= ?
           AND fecha < datetime(date(?), '+1 day')
         ORDER BY fecha DESC
         LIMIT ? OFFSET ?
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, accountId);
      ps.setString(2, from + " 00:00:00");
      ps.setString(3, to);
      ps.setInt(4, limit);
      ps.setInt(5, offset);
      try (ResultSet rs = ps.executeQuery()) {
        List<Transaccion> out = new ArrayList<>();
        while (rs.next())
          out.add(map(rs));
        return out;
      }
    }
  }

  public String deposit(Connection c, AccountRepo accounts,
      String txId, String accountId, BigDecimal amount,
      String transferId) throws SQLException {

    // Step 1: increase balance
    accounts.changeBalance(c, accountId, amount);

    // Step 2: register transaction
  insertTx(c, txId, transferId, accountId, null, null, TipoTransaccion.deposito, amount);

    return txId;
  }

  public String withdraw(Connection c, AccountRepo accounts,
      String txId, String accountId, BigDecimal amount,
      String transferId) throws SQLException {

    // Step 1: decrease balance (AccountRepo will throw if insufficient funds)
    accounts.changeBalance(c, accountId, amount.negate());

    // Step 2: register transaction
  insertTx(c, txId, transferId, accountId, null, null, TipoTransaccion.retiro, amount);

    return txId;
  }

  /**
   * Atomic two-leg transfer: retiro origen + deposito destino. Throws if
   * insufficient funds.
   */
  public void transfer(Connection c, String transferId, String txId, String fromAccount,
      String toAccount, BigDecimal amount,
    AccountRepo accounts, String metadataJson) throws SQLException {
    // 1) debit (fails if negative via AccountRepo.changeBalance)
    accounts.changeBalance(c, fromAccount, amount.negate());
  insertTx(c, txId, transferId, fromAccount, toAccount, metadataJson, TipoTransaccion.retiro, amount);

    // 2) credit
    accounts.changeBalance(c, toAccount, amount);
    // usar un id de transacción distinto para la segunda pata
    String txId2 = cc4p1.bank.util.Ids.tx();
  insertTx(c, txId2, transferId, toAccount, toAccount, metadataJson, TipoTransaccion.deposito, amount);
  }

  /**
   * Registers a loan payment: debit from account and create a 'deuda' transaction.
   */
  public String payDebt(Connection c, AccountRepo accounts, String txId, String accountId, BigDecimal amount) throws SQLException {
    // Debit
    accounts.changeBalance(c, accountId, amount.negate());
    // Log transaction as 'deuda'
  insertTx(c, txId, null, accountId, null, null, TipoTransaccion.deuda, amount);
    return txId;
  }

  /* ===== Helpers ===== */

  private void insertTx(Connection c, String txId, String transferId, String accountId, String destAccountId, String metadataJson, TipoTransaccion tipo,
      BigDecimal amount) throws SQLException {
    String sql = """
        INSERT INTO TRANSACCIONES(id_transaccion,id_transferencia,id_cuenta,id_cuenta_destino,metadata,tipo,monto,fecha)
        VALUES(?,?,?,?,?,?, ?,datetime('now'))
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, txId);
      if (transferId == null)
        ps.setNull(2, Types.VARCHAR);
      else
        ps.setString(2, transferId);
      ps.setString(3, accountId);
      if (destAccountId == null) ps.setNull(4, Types.VARCHAR); else ps.setString(4, destAccountId);
      if (metadataJson == null) ps.setNull(5, Types.VARCHAR); else ps.setString(5, metadataJson);
      ps.setString(6, tipo.toString());
      ps.setBigDecimal(7, amount);
      ps.executeUpdate();
    }
  }

  private Transaccion map(ResultSet rs) throws SQLException {
    String raw = rs.getString("fecha");
    LocalDateTime when = raw != null ? LocalDateTime.parse(raw.replace(' ', 'T')) : null;
    return new Transaccion(
        rs.getString("id_transaccion"),
        rs.getString("id_transferencia"),
        rs.getString("id_cuenta"),
        rs.getString("id_cuenta_destino"),
        rs.getString("metadata"),
        TipoTransaccion.from(rs.getString("tipo")),
        rs.getBigDecimal("monto"),
        when);
  }
}
