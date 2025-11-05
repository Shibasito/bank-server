/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
// db/SQLite.java
package cc4p1.bank.db;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class SQLite {
  private final String url;
  private final String dbFile; // usado para verificar existencia

  public SQLite(String filePath) throws IOException {
    this.dbFile = filePath;
    this.url = "jdbc:sqlite:" + filePath;
    // In SQLite.java constructor or initializeIfNeeded()
    Path p = Paths.get(dbFile).toAbsolutePath();
    Path dir = p.getParent();
    if (dir != null && !Files.exists(dir)) {
      Files.createDirectories(dir);
    }

  }

  public Connection get() throws SQLException {
    Connection c = DriverManager.getConnection(url);
    c.setAutoCommit(false);
    try (Statement s = c.createStatement()) {
      s.execute("PRAGMA foreign_keys = ON");
    }
    return c;
  }

  /** Llamado una vez al iniciar */
  public void initializeIfNeeded(String resourcePathInClasspath) throws Exception {
    boolean needInit = !Files.exists(Paths.get(dbFile));
    try (Connection c = get()) {
      if (!needInit) {
        // Si existe, verificar al menos una tabla existente
        needInit = !tableExists(c, "CLIENTES");
      }
      if (needInit) {
        String sql = readResource(resourcePathInClasspath);
        runSqlScript(c, sql);
        c.commit();
      } else {
        // Migraciones ligeras para esquemas previos
        migrateIfNeeded(c);
        c.commit();
      }
    }
  }

  private void migrateIfNeeded(Connection c) throws SQLException {
    // TRANSACCIONES.id_cuenta_destino (nullable)
    if (!columnExists(c, "TRANSACCIONES", "id_cuenta_destino")) {
      try (Statement s = c.createStatement()) {
        s.executeUpdate("ALTER TABLE TRANSACCIONES ADD COLUMN id_cuenta_destino TEXT");
      }
    }

    // PRESTAMOS.id_cuenta (nullable=false) introduced in newer schema
    if (!columnExists(c, "PRESTAMOS", "id_cuenta")) {
      try (Statement s = c.createStatement()) {
        s.executeUpdate("ALTER TABLE PRESTAMOS ADD COLUMN id_cuenta TEXT");
      }
    }

    // CLIENTES.password introduced in newer schema
    if (!columnExists(c, "CLIENTES", "password")) {
      try (Statement s = c.createStatement()) {
        s.executeUpdate("ALTER TABLE CLIENTES ADD COLUMN password TEXT");
      }
    }
  }

  private boolean columnExists(Connection c, String tableName, String columnName) throws SQLException {
    String q = "PRAGMA table_info('" + tableName + "')";
    try (PreparedStatement ps = c.prepareStatement(q); ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        String col = rs.getString("name");
        if (columnName.equalsIgnoreCase(col)) return true;
      }
      return false;
    }
  }

  private boolean tableExists(Connection c, String tableName) throws SQLException {
    String q = "SELECT name FROM sqlite_master WHERE type='table' AND upper(name)=upper(?)";
    try (PreparedStatement ps = c.prepareStatement(q)) {
      ps.setString(1, tableName);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private String readResource(String classpathLocation) throws IOException {
    // classpathLocation like "/db/init_db.sql"
    try (InputStream in = getClass().getResourceAsStream(classpathLocation)) {
      if (in == null) {
        throw new IOException("Schema resource not found: " + classpathLocation);
      }
      try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        return sb.toString();
      }
    }
  }

  private void runSqlScript(Connection c, String script) throws SQLException {
    List<String> stmts = splitSqlStatements(script);
    try (Statement st = c.createStatement()) {
      for (String s : stmts) {
        if (!s.isBlank()) st.executeUpdate(s);
      }
    }
  }

  // Simple splitter for plain schema files (no semicolons inside strings).
  private List<String> splitSqlStatements(String script) {
    String[] parts = script.split(";");
    List<String> out = new ArrayList<>(parts.length);
    for (String p : parts) {
      String trimmed = p.trim();
      if (!trimmed.isEmpty()) out.add(trimmed + ";");
    }
    return out;
  }
}
