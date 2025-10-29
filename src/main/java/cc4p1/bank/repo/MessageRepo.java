package cc4p1.bank.repo;

import java.sql.*;

public class MessageRepo {

  /** Intenta reclamar un mensaje. Devuelve verdadero si este proceso lo posee. */
  public boolean tryAcquire(Connection c, String messageId) throws SQLException {
    String sql = """
          INSERT INTO MENSAJES_PROCESADOS (id_mensaje, estado)
          VALUES (?, 'en_proceso')
          ON CONFLICT(id_mensaje) DO NOTHING
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, messageId);
      return ps.executeUpdate() == 1; // 1 = claimed, 0 = already exists (en_proceso/procesado)
    }
  }

  /** Marca el mensaje como completado. */
  public void markDone(Connection c, String messageId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "UPDATE MENSAJES_PROCESADOS SET estado='procesado' WHERE id_mensaje=?")) {
      ps.setString(1, messageId);
      ps.executeUpdate();
    }
  }

  /**
   * Libera la reclamación después de un fallo, permitiendo un nuevo intento más
   * tarde.
   */
  public void release(Connection c, String messageId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "DELETE FROM MENSAJES_PROCESADOS WHERE id_mensaje=? AND estado='en_proceso'")) {
      ps.setString(1, messageId);
      ps.executeUpdate();
    }
  }

  /** Para lecturas o registros: ¿ya se procesó esto? */
  public boolean isDone(Connection c, String messageId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT 1 FROM MENSAJES_PROCESADOS WHERE id_mensaje=? AND estado='procesado'")) {
      ps.setString(1, messageId);
      return ps.executeQuery().next();
    }
  }

  /** Comprueba si un mensaje ya fue procesado (idempotencia). */
  public boolean alreadyProcessed(Connection c, String messageId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT 1 FROM MENSAJES_PROCESADOS WHERE id_mensaje=? AND estado='procesado'")) {
      ps.setString(1, messageId);
      return ps.executeQuery().next();
    }
  }

  /** Marca un mensaje como procesado (exactly-once). */
  public void markProcessed(Connection c, String messageId) throws SQLException {
    // Si ya existe una fila, actualiza su estado; si no, la inserta directamente
    // como procesada.
    String upsert = """
          INSERT INTO MENSAJES_PROCESADOS(id_mensaje, estado)
          VALUES (?, 'procesado')
          ON CONFLICT(id_mensaje) DO UPDATE SET estado='procesado'
        """;
    try (PreparedStatement ps = c.prepareStatement(upsert)) {
      ps.setString(1, messageId);
      ps.executeUpdate();
    }
  }
}
