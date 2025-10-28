package cc4p1.bank.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

public record Cliente(
        String idCliente,      // id_cliente
        String dni,
        String nombres,
        String apellidoPat,
        String apellidoMat,
        String direccion,
        String telefono,
        String correo,
        LocalDateTime fechaRegistro
) {
    public static Cliente from(ResultSet rs) throws SQLException {
        return new Cliente(
            rs.getString("id_cliente"),
            rs.getString("dni"),
            rs.getString("nombres"),
            rs.getString("apellido_pat"),
            rs.getString("apellido_mat"),
            rs.getString("direccion"),
            rs.getString("telefono"),
            rs.getString("correo"),
            // TEXT ISO-8601: "yyyy-MM-dd HH:mm:ss"
            LocalDateTime.parse(rs.getString("fecha_registro").replace(' ', 'T'))
        );
    }
}
