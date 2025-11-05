PRAGMA foreign_keys = ON;

-- =========================================================
-- TABLA: CLIENTES
-- =========================================================
CREATE TABLE IF NOT EXISTS CLIENTES (
    id_cliente     TEXT PRIMARY KEY,             -- CL001, CL002...
    dni            TEXT NOT NULL UNIQUE,         -- Documento Nacional de Identidad
    nombres        TEXT NOT NULL,
    apellido_pat  TEXT NOT NULL,
    apellido_mat  TEXT NOT NULL,
    password       TEXT NOT NULL,                -- Para autenticación (texto plano en entorno local)
    direccion      TEXT,
    telefono       TEXT,
    correo         TEXT,
    fecha_registro TEXT DEFAULT (datetime('now'))
);

-- =========================================================
-- TABLA: CUENTAS
-- =========================================================
CREATE TABLE IF NOT EXISTS CUENTAS (
    id_cuenta      TEXT PRIMARY KEY,             -- CU001, CU002...
    id_cliente     TEXT NOT NULL,
    saldo          REAL NOT NULL DEFAULT 0,
    fecha_apertura TEXT NOT NULL DEFAULT (date('now')),
    FOREIGN KEY (id_cliente) REFERENCES CLIENTES(id_cliente)
);

-- =========================================================
-- TABLA: PRESTAMOS
-- =========================================================
CREATE TABLE IF NOT EXISTS PRESTAMOS (
    id_prestamo     TEXT PRIMARY KEY,            -- PR001, PR002...
    id_cliente      TEXT NOT NULL,
    id_cuenta       TEXT NOT NULL,               -- Cuenta que recibe el préstamo
    monto_inicial   REAL NOT NULL,
    monto_pendiente REAL NOT NULL,
    estado          TEXT NOT NULL CHECK (estado IN ('activo','pagado')),
    fecha_solicitud TEXT NOT NULL DEFAULT (date('now')),
    FOREIGN KEY (id_cliente) REFERENCES CLIENTES(id_cliente),
    FOREIGN KEY (id_cuenta) REFERENCES CUENTAS(id_cuenta)
);

-- =========================================================
-- TABLA: TRANSACCIONES
-- =========================================================
CREATE TABLE IF NOT EXISTS TRANSACCIONES (
    id_transaccion TEXT PRIMARY KEY,             -- TX001, TX002...
    id_transferencia TEXT DEFAULT NULL,          -- Opcional, para transferencias entre cuentas. Formato TR001, TR002...
    id_cuenta      TEXT NOT NULL,
    id_cuenta_destino TEXT DEFAULT NULL,         -- Para transferencias: cuenta destino
    metadata       TEXT DEFAULT NULL,            -- JSON con metadatos opcionales (p.ej., {"note":"..."})
    tipo           TEXT NOT NULL CHECK (tipo IN ('deposito','retiro', 'deuda')),
    monto          REAL NOT NULL CHECK (monto >= 0),
    fecha          TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (id_cuenta) REFERENCES CUENTAS(id_cuenta)
);

-- =========================================================
-- TABLA: MENSAJES_PROCESADOS
-- =========================================================
-- Control de idempotencia para mensajes recibidos por RabbitMQ.
CREATE TABLE IF NOT EXISTS MENSAJES_PROCESADOS (
    id_mensaje   TEXT PRIMARY KEY,
    fecha_guardado TEXT NOT NULL DEFAULT (datetime('now')),
    estado TEXT NOT NULL DEFAULT ('procesado') CHECK (estado IN ('procesado','en_proceso'))
);

-- =========================================================
-- ÍNDICES
-- =========================================================
CREATE INDEX IF NOT EXISTS idx_cuentas_cliente ON CUENTAS(id_cliente);
CREATE INDEX IF NOT EXISTS idx_prestamos_cliente ON PRESTAMOS(id_cliente);
CREATE INDEX IF NOT EXISTS idx_transacciones_cuenta_fecha ON TRANSACCIONES(id_cuenta, fecha DESC);

INSERT INTO CLIENTES(id_cliente, dni, nombres, apellido_pat, apellido_mat, password, direccion)
VALUES
    ('CL001','45678912','MARÍA ELENA','GARCÍA','FLORES','secret1','Av. Universitaria 1234'),
    ('CL002','12345678','JUAN CARLOS','RAMÍREZ','QUISPE','secret2','Av. La Molina 5678');

INSERT INTO CUENTAS(id_cuenta, id_cliente, saldo)
VALUES ('CU001','CL001',2500.00),
       ('CU002','CL002',1500.00);

INSERT INTO PRESTAMOS(id_prestamo, id_cliente, id_cuenta, monto_inicial, monto_pendiente, estado)
VALUES ('PR001','CL001','CU001',10000.00,8000.00,'activo');

INSERT INTO TRANSACCIONES(id_transaccion, id_cuenta, id_cuenta_destino, tipo, monto, fecha)
VALUES ('TX001','CU001', NULL,'deposito',500.00, '2025-10-01 10:00:00'),
       ('TX002','CU001', NULL,'deposito',500.00 , '2025-10-02 11:00:00');


INSERT INTO TRANSACCIONES(id_transaccion, id_transferencia, id_cuenta, id_cuenta_destino, tipo, monto, fecha)
VALUES ('TX003','TR001','CU001','CU002','retiro',300.00, '2025-10-03 12:00:00'),
       ('TX004','TR001','CU002','CU002','deposito',300.00, '2025-10-03 12:00:00');