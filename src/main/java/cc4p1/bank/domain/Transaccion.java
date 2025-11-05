package cc4p1.bank.domain;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

public record Transaccion(
        String idTransaccion,     // id_transaccion
        String idTransferencia,   // id_transferencia
        String idCuenta,          // id_cuenta
        String idCuentaDestino,   // id_cuenta_destino (puede ser null)
        TipoTransaccion tipo,     // deposito o retiro
        BigDecimal monto,
        LocalDateTime fecha
) {
    public static Transaccion from(ResultSet rs) throws SQLException {
        String rawFecha = rs.getString("fecha");
        // SQLite guarda datetime('now') como "YYYY-MM-DD HH:MM:SS"
        LocalDateTime parsedFecha = rawFecha != null
                ? LocalDateTime.parse(rawFecha.replace(' ', 'T'))
                : null;

        return new Transaccion(
            rs.getString("id_transaccion"),
            rs.getString("id_transferencia"),
            rs.getString("id_cuenta"),
            rs.getString("id_cuenta_destino"),
            TipoTransaccion.from(rs.getString("tipo")),
            rs.getBigDecimal("monto"),
            parsedFecha
        );
    }
}
