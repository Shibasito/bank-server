package cc4p1.bank.domain;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

public record Cuenta(
        String idCuenta,       // id_cuenta
        String idCliente,      // id_cliente
        BigDecimal saldo,
        LocalDate fechaApertura
) {
    public static Cuenta from(ResultSet rs) throws SQLException {
        return new Cuenta(
            rs.getString("id_cuenta"),
            rs.getString("id_cliente"),
            rs.getBigDecimal("saldo"),
            LocalDate.parse(rs.getString("fecha_apertura"))
        );
    }
}

