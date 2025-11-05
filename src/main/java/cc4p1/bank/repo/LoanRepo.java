package cc4p1.bank.repo;

import cc4p1.bank.domain.EstadoPrestamo;
import cc4p1.bank.domain.Prestamo;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;

public class LoanRepo {

  public Prestamo findById(Connection c, String loanId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT * FROM PRESTAMOS WHERE id_prestamo=?")) {
      ps.setString(1, loanId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? new Prestamo(
            rs.getString("id_prestamo"),
            rs.getString("id_cliente"),
            rs.getString("id_cuenta"),
            rs.getBigDecimal("monto_inicial"),
            rs.getBigDecimal("monto_pendiente"),
            EstadoPrestamo.from(rs.getString("estado")),
            LocalDate.parse(rs.getString("fecha_solicitud"))) : null;
      }
    }
  }

  public String createAndCredit(Connection c, String loanId, String clientId,
      String accountId, BigDecimal principal,
      TxRepo txRepo, AccountRepo accountRepo) throws SQLException {

    // 1. Create the loan record
    String sql = """
        INSERT INTO PRESTAMOS(id_prestamo,id_cliente,id_cuenta,monto_inicial,monto_pendiente,estado,fecha_solicitud)
        VALUES(?,?,?,?,?, 'activo', date('now'))
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, loanId);
      ps.setString(2, clientId);
      ps.setString(3, accountId);
      ps.setBigDecimal(4, principal);
      ps.setBigDecimal(5, principal);
      ps.executeUpdate();
    }

    // 2. Credit the account as deposit (same amount)
    String txId = "TX-" + loanId; // or a UUID
    txRepo.deposit(c, accountRepo, txId, accountId, principal, null);

    return loanId;
  }

}
