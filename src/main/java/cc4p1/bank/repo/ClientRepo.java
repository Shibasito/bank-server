package cc4p1.bank.repo;

import cc4p1.bank.domain.Cliente;
import java.sql.*;
import java.time.LocalDateTime;

public class ClientRepo {

  public Cliente findById(Connection c, String clientId) throws SQLException {
    String sql = "SELECT * FROM CLIENTES WHERE id_cliente=?";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, clientId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? new Cliente(
            rs.getString("id_cliente"),
            rs.getString("dni"),
            rs.getString("nombres"),
            rs.getString("apellido_pat"),
            rs.getString("apellido_mat"),
            rs.getString("direccion"),
            rs.getString("telefono"),
            rs.getString("correo"),
            LocalDateTime.parse(rs.getString("fecha_registro").replace(' ', 'T'))
        ) : null;
      }
    }
  }

  public void insert(Connection c, Cliente cli) throws SQLException {
    String sql = """
      INSERT INTO CLIENTES(id_cliente,dni,nombres,apellido_pat,apellido_mat,direccion,telefono,correo,fecha_registro)
      VALUES(?,?,?,?,?,?,?,?,datetime('now'))
      """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, cli.idCliente());
      ps.setString(2, cli.dni());
      ps.setString(3, cli.nombres());
      ps.setString(4, cli.apellidoPat());
      ps.setString(5, cli.apellidoMat());
      ps.setString(6, cli.direccion());
      ps.setString(7, cli.telefono());
      ps.setString(8, cli.correo());
      ps.executeUpdate();
    }
  }
}
