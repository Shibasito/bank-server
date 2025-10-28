package cc4p1.bank.domain;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

public record Prestamo(
        String idPrestamo,            // id_prestamo
        String idCliente,             // id_cliente
        BigDecimal montoInicial,
        BigDecimal montoPendiente,
        EstadoPrestamo estado,
        LocalDate fechaSolicitud
) {
    public static Prestamo from(ResultSet rs) throws SQLException {
        return new Prestamo(
            rs.getString("id_prestamo"),
            rs.getString("id_cliente"),
            rs.getBigDecimal("monto_inicial"),
            rs.getBigDecimal("monto_pendiente"),
            EstadoPrestamo.from(rs.getString("estado")),
            LocalDate.parse(rs.getString("fecha_solicitud"))
        );
    }
}

