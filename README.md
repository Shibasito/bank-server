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
| **password**     | TEXT | NOT NULL | Contraseña en texto plano (solo entorno local). |
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
| **id_transaccion** | TEXT | PRIMARY KEY | Identificador de la transacción (ej. `TX001`). |
| **id_transferencia** | TEXT | Opcional, por defecto NULL |  Identificador de transferencia (ej. `TR001`). |
| **id_cuenta**      | TEXT | FOREIGN KEY → CUENTAS(id_cuenta) | Cuenta sobre la cual se ejecuta la transacción. |
| **tipo**           | TEXT | CHECK (tipo IN ('deposito','retiro') | Tipo de transacción realizada. |
| **monto**          | REAL | CHECK (monto >= 0) | Monto del movimiento. |
| **fecha**     | TEXT | DEFAULT datetime('now') | Fecha y hora de la transacción. |

- Para las transferencias, se usa un id_transaccion y un id_transferencia. La cuenta de origen que realiza la transferencia hace un "retiro" hacia la cuenta de destino que recibe la transferencia, recibiendo un "depósito".

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

## 📬 Contrato de Mensajería — Banco (RabbitMQ)

Definir **cómo el Banco recibe y responde** mensajes en RabbitMQ, y **cómo el Banco consulta a RENIEC**. Estandariza encabezados AMQP, cuerpo JSON, correlación de respuestas e idempotencia.


### 0) Convenciones comunes

**Cola de entrada del Banco:** `bank.requests`  
**Cola de RENIEC (escuchada por RENIEC):** `reniec.verify`  
**Formato de mensajes:** JSON UTF-8

#### 0.1 Encabezados AMQP (obligatorio en toda petición al Banco)
- `reply_to`: cola temporal del cliente para recibir la respuesta.
- `correlation_id`: UUID único por solicitud (para emparejar respuesta).
- (Opcional) `expiration`: TTL del mensaje si aplica.

#### 0.2 Envoltorio común de **respuesta** del Banco (hacia clientes)

```json
{
  "ok": true,
  "data": { /* resultado */ },
  "error": null,
  "correlationId": "UUID-mismo-que-AMQP"
}
```

En caso de error:

```json
{
  "ok": false,
  "data": null,
  "error": { "code": "ENUM_OPCIONAL", "message": "texto legible" },
  "correlationId": "UUID-mismo-que-AMQP"
}
```

> La respuesta **siempre** replica el `correlation_id` en encabezado AMQP **y** en `correlationId` del JSON.

#### 0.3 Idempotencia de operaciones de **escritura**

* Toda petición de escritura incluye `messageId` (UUID) y el Banco lo usa para evitar re-procesar (reintentos/redeliveries).
* Las operaciones **solo-lectura** pueden omitir `messageId`.


### 1) Operaciones del Banco (Cliente → Banco)

#### 1.1 `GetBalance`

**Body (request)**

```json
{
  "type": "GetBalance",
  "accountId": "CU001",
  "messageId": "opcional-para-lectura"
}
```

**Body (response ok)**

```json
{
  "ok": true,
  "data": { "accountId": "CU001", "balance": 2750.00, "currency": "PEN" },
  "error": null,
  "correlationId": "..."
}
```

---

#### 1.2 `GetClientInfo`

**Body (request)**

```json
{
  "type": "GetClientInfo",
  "clientId": "CL001"
}
```

**Body (response ok)**

```json
{
  "ok": true,
  "status": "ok",
  "data": {
    "clientId": "CL001",
    "dni": "45678912",
    "nombres": "MARÍA ELENA",
    "apellidoPat": "GARCÍA",
    "apellidoMat": "FLORES",
    "direccion": "Av. Universitaria 1234",
    "telefono": "999-888-777",
    "correo": "maria@example.com",
    "fechaRegistro": "2025-10-27 16:35:10",
    "accounts": [
      {
        "accountId": "CU001",
        "balance": 2500.00,
        "fechaApertura": "2025-10-15"
      },
      {
        "accountId": "CU002",
        "balance": 1500.00,
        "fechaApertura": "2025-10-20"
      }
    ],
    "totalAccounts": 2
  },
  "error": null,
  "correlationId": "..."
}
```

> **Nota:** La respuesta incluye todas las cuentas asociadas al cliente con sus saldos actuales.

---

#### 1.3 `Deposit` (💾 escritura, requiere `messageId`)

**Body (request)**

```json
{
  "type": "Deposit",
  "messageId": "a7d9a4f3-...",
  "accountId": "CU001",
  "amount": 150.00,
  "metadata": { "source": "app-mobile" }
}
```

**Body (response ok)**

```json
{
  "ok": true,
  "data": { "accountId": "CU001", "newBalance": 2900.00, "txId": "TR1042" },
  "error": null,
  "correlationId": "..."
}
```

---

#### 1.4 `Withdraw` (💾 escritura, requiere `messageId`)

**Body (request)**

```json
{
  "type": "Withdraw",
  "messageId": "7c1b7c54-...",
  "accountId": "CU001",
  "amount": 200.00
}
```

**Body (response ok)**

```json
{
  "ok": true,
  "data": { "accountId": "CU001", "newBalance": 2700.00, "txId": "TR1043" },
  "error": null,
  "correlationId": "..."
}
```

> Errores frecuentes: `INSUFFICIENT_FUNDS`, `ACCOUNT_NOT_FOUND`, `VALIDATION_ERROR`.

---

#### 1.5 `ListTransactions`

**Body (request)**

```json
{
  "type": "ListTransactions",
  "accountId": "CU001",
  "from": "2025-10-01",
  "to": "2025-10-31",
  "limit": 100,
  "offset": 0
}
```

**Body (response ok)**

```json
{
  "ok": true,
  "data": {
    "accountId": "CU001",
    "items": [
      { "txId": "TX1042", "idTransferencia": null, "tipo": "deposito", "monto": 150.00, "fecha": "2025-10-28 12:30:10" },
      { "txId": "TX1040", "idTransferencia": null, "tipo": "retiro",   "monto":  50.00, "fecha": "2025-10-28 09:15:02" }
      { "txId": "TX1051", "idTransferencia": "TR1051", "tipo": "retiro",   "monto":  50.00, "fecha": "2025-10-28 09:15:02" }
    ],
    "count": 2,
    "hasMore": false
  },
  "error": null,
  "correlationId": "..."
}
```

---

#### 1.6 `CreateLoan` (💾 escritura, requiere `messageId` y validación RENIEC)

**Body (request)**

```json
{
  "type": "CreateLoan",
  "messageId": "6f7b2d90-...",
  "clientId": "CL001",
  "principal": 7500.00,
  "currency": "PEN"
}
```

**Flujo interno:** el Banco valida primero identidad con RENIEC (ver sección 3).
**Body (response ok)**

```json
{
  "ok": true,
  "data": { "loanId": "PR0012", "clientId": "CL001", "principal": 7500.00, "status": "activo" },
  "error": null,
  "correlationId": "..."
}
```

---

#### 1.7 `Transfer` (transferencia entre cuentas) — **dos cuentas** (💾 escritura)

Para transferencias reales, el sistema aplica **doble asiento** (retiro + depósito).

**Body (request)**

```json
{
  "type": "Transfer",
  "messageId": "f9b8c3b1-...",
  "fromAccountId": "CU_ORIGEN",
  "toAccountId": "CU_DESTINO",
  "amount": 150.00,
  "metadata": { "note": "Pago de servicios" }
}
```

**Body (response ok)**

```json
{
  "ok": true,
  "data": {
    "idTransaccion": "TX2001",
    "idTransferencia": "TR0001",
    "fromAccountNewBalance": 2350.00,
    "toAccountNewBalance": 4180.00
  },
  "error": null,
  "correlationId": "..."
}
```

> Errores frecuentes: `ACCOUNT_NOT_FOUND`, `SAME_ACCOUNT`, `INSUFFICIENT_FUNDS`.

---

#### 1.8 `Login` (autenticación)

Compatibilidad de entradas: se aceptan tanto `type` como `operationType` (cliente web).

Body (request, estilo cliente web):

```json
{
  "operationType": "login",
  "payload": { "usuario": "45678912", "password": "secret1" }
}
```

Body (request, estilo servicio):

```json
{ "type": "Login", "dni": "45678912", "password": "secret1" }
```

Body (response ok):

```json
{
  "ok": true,
  "status": "ok",
  "data": {
    "clientId": "CL001",
    "clienteId": "CL001",
    "dni": "45678912",
    "accountId": "CU001",
    "balance": 2500.00
  },
  "error": null,
  "correlationId": "..."
}
```

- [ ] TODO: Adaptar cliente a respuestas del servidor

> Errores frecuentes: `INVALID_CREDENTIALS`.

---

#### 1.9 `Register` (registro de cliente + cuenta) — 💾 escritura, idempotente

Compatibilidad de entradas: `type` o `operationType` con `payload`.

Body (request, estilo cliente web):

```json
{
  "operationType": "register",
  "messageId": "uuid-123",
  "payload": {
    "usuario": "87654321",     // alias de dni
    "password": "mypass",
    "nombres": "ANA",
    "apellidoPat": "PEREZ",
    "apellidoMat": "ROJAS",
    "saldo": 1000
  }
}
```

Body (request, estilo servicio):

```json
{
  "type": "Register",
  "messageId": "uuid-123",
  "dni": "87654321",
  "password": "mypass",
  "nombres": "ANA",
  "apellidoPat": "PEREZ",
  "apellidoMat": "ROJAS",
  "saldo": "1000.00"
}
```

Body (response ok):

```json
{
  "ok": true,
  "status": "ok",
  "data": {
    "clientId": "CL-xxxxxx",
    "accountId": "CU-xxxxxx",
    "initialBalance": 1000
  },
  "error": null,
  "correlationId": "..."
}
```

> Notas:
> - Requiere `messageId` para idempotencia (reintentos seguros).
> - Falla con `CLIENT_ALREADY_EXISTS` si el DNI ya está registrado.


### 2) Reglas de negocio y validaciones (resumen)

* **amount** `> 0` en escrituras.
* `from <= to` en `ListTransactions`.
* `fromAccountId != toAccountId` en transferencias.
* Idempotencia: **reutilizar el mismo `messageId`** al reintentar; el Banco debe devolver un **no-op exitoso** (misma `data` o una marca de “duplicate”).

---

### 3) Interacción con RENIEC (Banco ↔ RENIEC)

#### 3.1 Banco → RENIEC (request)

**Cola destino:** `reniec.verify`
**Encabezados AMQP:** (no requiere `reply_to` si se usa RPC tradicional con su propia cola de respuesta; recomendable incluir `correlation_id`)
**Body**

```json
{
  "type": "VerifyIdentity",
  "dni": "45678912"
}
```

#### 3.2 RENIEC → Banco (response)

**Encabezados AMQP:** `correlation_id` = mismo del request del Banco
**Body (envoltorio común)**

```json
{
  "ok": true,
  "data": {
    "valid": true,
    "dni": "45678912",
    "nombres": "MARÍA ELENA",
    "apellidoPat": "GARCÍA",
    "apellidoMat": "FLORES"
  },
  "error": null,
  "correlationId": "..."
}
```

En caso de no válido o error:

```json
{
  "ok": true,
  "data": { "valid": false, "dni": "45678912" },
  "error": null,
  "correlationId": "..."
}
```

o bien:

```json
{
  "ok": false,
  "data": null,
  "error": { "code": "RENIEC_UNAVAILABLE", "message": "Timeout o servicio caído" },
  "correlationId": "..."
}
```

**Uso en el Banco:**

* `CreateLoan` (y cualquier operación que requiera identidad) debe:

  1. Enviar `VerifyIdentity` a `reniec.verify`.
  2. Esperar respuesta (RPC) con **mismo `correlation_id`**.
  3. Si `data.valid == true`, continuar; si no, responder al cliente con `ok=false` y `error` adecuado.

---

### 4) Códigos de error sugeridos (opcional)

* `ACCOUNT_NOT_FOUND`, `CLIENT_NOT_FOUND`
* `INSUFFICIENT_FUNDS`
* `VALIDATION_ERROR` (payload inválido)
* `DUPLICATE_REQUEST` (idempotencia)
* `RENIEC_UNAVAILABLE`, `RENIEC_INVALID_ID`
* `INTERNAL_ERROR`

---

### 5) Ejemplo de cabeceras y flujo (RPC)

1. **Cliente → Banco**

   * AMQP: `reply_to=amq.gen-xyz`, `correlation_id=ab12-...`
   * Body: `{ "type":"Deposit", "messageId":"...", "accountId":"CU001", "amount":100 }`
2. **Banco → Cliente**

   * AMQP: `correlation_id=ab12-...`
   * Body: `{ "ok":true, "data":{...}, "error":null, "correlationId":"ab12-..." }`

> **Regla de oro:** El Banco **siempre** copia el `correlation_id` del request en el **encabezado** de la respuesta y lo refleja en `correlationId` del body.

---