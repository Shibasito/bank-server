package cc4p1.bank.domain;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

public record Transaccion(
        String idTransaccion,     // id_transaccion
        String idCuenta,          // id_cuenta
        TipoTransaccion tipo,
        BigDecimal monto,
        LocalDateTime fechaHora
) {
    public static Transaccion from(ResultSet rs) throws SQLException {
        return new Transaccion(
            rs.getString("id_transaccion"),
            rs.getString("id_cuenta"),
            TipoTransaccion.from(rs.getString("tipo")),
            rs.getBigDecimal("monto"),
            LocalDateTime.parse(rs.getString("fecha_hora").replace(' ', 'T'))
        );
    }
}
