package cc4p1.bank.repo;

import cc4p1.bank.domain.Cuenta;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;

public class AccountRepo {

  public Cuenta findById(Connection c, String accountId) throws SQLException {
    String sql = "SELECT * FROM CUENTAS WHERE id_cuenta=?";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, accountId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? new Cuenta(
            rs.getString("id_cuenta"),
            rs.getString("id_cliente"),
            rs.getBigDecimal("saldo"),
            LocalDate.parse(rs.getString("fecha_apertura"))
        ) : null;
      }
    }
  }

  public void insert(Connection c, Cuenta cu) throws SQLException {
    String sql = """
      INSERT INTO CUENTAS(id_cuenta,id_cliente,saldo,fecha_apertura)
      VALUES(?,?,?,date('now'))
      """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, cu.idCuenta());
      ps.setString(2, cu.idCliente());
      ps.setBigDecimal(3, cu.saldo());
      ps.executeUpdate();
    }
  }

  /** Fails if new balance would be negative. */
  public void changeBalance(Connection c, String accountId, BigDecimal delta) throws SQLException {
    String sql = """
      UPDATE CUENTAS
         SET saldo = saldo + ?
       WHERE id_cuenta = ?
         AND saldo + ? >= 0
      """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setBigDecimal(1, delta);
      ps.setString(2, accountId);
      ps.setBigDecimal(3, delta);
      int n = ps.executeUpdate();
      if (n != 1) throw new SQLException("INSUFFICIENT_FUNDS or ACCOUNT_NOT_FOUND");
    }
  }

  /** Obtiene la primera cuenta de un cliente (o null si no tiene). */
  public Cuenta findAnyByClient(Connection c, String clientId) throws SQLException {
    String sql = "SELECT * FROM CUENTAS WHERE id_cliente=? ORDER BY fecha_apertura ASC LIMIT 1";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, clientId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? new Cuenta(
            rs.getString("id_cuenta"),
            rs.getString("id_cliente"),
            rs.getBigDecimal("saldo"),
            java.time.LocalDate.parse(rs.getString("fecha_apertura"))
        ) : null;
      }
    }
  }

  /** Obtiene todas las cuentas de un cliente. */
  public java.util.List<Cuenta> findAllByClient(Connection c, String clientId) throws SQLException {
    String sql = "SELECT * FROM CUENTAS WHERE id_cliente=? ORDER BY fecha_apertura ASC";
    java.util.List<Cuenta> accounts = new java.util.ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, clientId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          accounts.add(new Cuenta(
              rs.getString("id_cuenta"),
              rs.getString("id_cliente"),
              rs.getBigDecimal("saldo"),
              java.time.LocalDate.parse(rs.getString("fecha_apertura"))
          ));
        }
      }
    }
    return accounts;
  }
}

