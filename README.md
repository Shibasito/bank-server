# Servicio de banco

## Base de Datos — Banco (BD1)

Para crear la base de datos, solo basta ejecutar BankServer.java.

### 🧾 TABLA: CLIENTES
Contiene la información general de los clientes registrados en el banco.

| Columna          | Tipo | Restricciones | Descripción |
|------------------|------|----------------|--------------|
| **id_cliente**   | TEXT | PRIMARY KEY | Identificador único del cliente (ej. `CL001`). |
| **dni**          | TEXT | UNIQUE, NOT NULL | Documento Nacional de Identidad (relación con RENIEC). |
| **nombres**      | TEXT | NOT NULL | Nombres del cliente. |
| **apellido_pat** | TEXT | NOT NULL | Apellido paterno. |
| **apellido_mat** | TEXT | NOT NULL | Apellido materno. |
| **direccion**    | TEXT | — | Dirección del domicilio. |
| **telefono**     | TEXT | — | Teléfono de contacto. |
| **correo**       | TEXT | — | Correo electrónico. |
| **fecha_registro** | TEXT | DEFAULT datetime('now') | Fecha y hora de registro en el sistema. |


### 🧾 TABLA: CUENTAS
Registra las cuentas bancarias asociadas a los clientes.

| Columna          | Tipo | Restricciones | Descripción |
|------------------|------|----------------|--------------|
| **id_cuenta**    | TEXT | PRIMARY KEY | Identificador único de la cuenta (ej. `CU001`). |
| **id_cliente**   | TEXT | FOREIGN KEY → CLIENTES(id_cliente) | Cliente propietario de la cuenta. |
| **saldo**        | REAL | DEFAULT 0, NOT NULL | Saldo actual de la cuenta. |
| **fecha_apertura** | TEXT | DEFAULT date('now') | Fecha de apertura de la cuenta. |


### 🧾 TABLA: PRESTAMOS
Registra los préstamos otorgados a los clientes.

| Columna           | Tipo | Restricciones | Descripción |
|-------------------|------|----------------|--------------|
| **id_prestamo**   | TEXT | PRIMARY KEY | Identificador del préstamo (ej. `PR001`). |
| **id_cliente**    | TEXT | FOREIGN KEY → CLIENTES(id_cliente) | Cliente titular del préstamo. |
| **monto_inicial** | REAL | NOT NULL | Monto total otorgado. |
| **monto_pendiente** | REAL | NOT NULL | Saldo pendiente de pago. |
| **estado**        | TEXT | CHECK (estado IN ('activo','pagado')) | Estado actual del préstamo. |
| **fecha_solicitud** | TEXT | DEFAULT date('now') | Fecha en que se solicitó el préstamo. |


### 🧾 TABLA: TRANSACCIONES
Almacena todas las operaciones realizadas sobre las cuentas.

| Columna           | Tipo | Restricciones | Descripción |
|-------------------|------|----------------|--------------|
| **id_transaccion** | TEXT | PRIMARY KEY | Identificador de la transacción (ej. `TR001`). |
| **id_cuenta**      | TEXT | FOREIGN KEY → CUENTAS(id_cuenta) | Cuenta sobre la cual se ejecuta la transacción. |
| **tipo**           | TEXT | CHECK (tipo IN ('deposito','retiro','transferencia','comision')) | Tipo de transacción realizada. |
| **monto**          | REAL | CHECK (monto >= 0) | Monto del movimiento. |
| **fecha_hora**     | TEXT | DEFAULT datetime('now') | Fecha y hora de la transacción. |


### 🧾 TABLA: MENSAJES_PROCESADOS
Controla los mensajes ya atendidos para asegurar **idempotencia** en la comunicación por RabbitMQ.

| Columna        | Tipo | Restricciones | Descripción |
|----------------|------|----------------|--------------|
| **id_mensaje** | TEXT | PRIMARY KEY | Identificador único del mensaje recibido. |
| **fecha_guardado** | TEXT | DEFAULT datetime('now') | Fecha de registro del mensaje procesado. |
| **estado** | TEXT | DEFAULT 'procesado' CHECK IN ('procesado', 'en_proceso') | Estadp del mensaje. |


### 🔐 Relaciones principales
- **CLIENTES (1) → (N) CUENTAS**  
- **CLIENTES (1) → (N) PRESTAMOS**  
- **CUENTAS (1) → (N) TRANSACCIONES**


### ⚙️ Notas
- La base de datos activa `PRAGMA foreign_keys = ON` para mantener la integridad referencial.  
- Todos los registros utilizan texto o valores numéricos simples para compatibilidad con SQLite.  
- La tabla `MENSAJES_PROCESADOS` evita procesamientos duplicados en entornos concurrentes.

## Idempotencia

### `MesageRepo.java`

`MessageRepo.java` incluye métodos para manejar idempotencia exactly-once para mensajes de RabbitMQ.

#### Métodos

1. tryAcquire(messageId) - Intento de reclamar
    - Primera vez: Inserta con estado 'en_proceso' → retorna true (este proceso es dueño)
    - Mensaje duplicado: Ya existe en DB → retorna false (otro proceso lo está procesando o ya lo completó)
    - Garantía: Solo UN consumidor puede reclamar el mensaje gracias a PRIMARY KEY(id_mensaje)
2. markDone(messageId) - Completar procesamiento
    - Marca el mensaje como procesado permanentemente
    - Previene re-procesamiento futuro del mismo messageId
3. release(messageId) - Liberar tras fallo
    - Si el procesamiento falla, libera el claim
    - Permite que otro consumidor (o reintentos) procese el mensaje
    - Solo elimina si está 'en_proceso' (no toca mensajes ya procesados)
4. isDone(messageId) - Verificar estado
    - Consulta si el mensaje ya fue procesado completamente
    - Útil para logging o validaciones