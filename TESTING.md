# Guía de Pruebas — Servidor Bancario

## Ejecutar Pruebas

**Ejecutar todas las pruebas:**
```fish
mvn test
```

**Ejecutar una clase de prueba específica:**
```fish
mvn -Dtest=BankServiceTest test
```

**Ejecutar un método de prueba específico:**
```fish
mvn -Dtest=BankServiceTest#deposit_then_duplicate_is_idempotent test
```

## Cobertura de Pruebas

### BankServiceTest
Ubicación: `src/test/java/cc4p1/bank/service/BankServiceTest.java`

**Operaciones cubiertas:**
- ✓ `GetBalance` — leer saldo de cuenta
- ✓ `Deposit` — acreditar cuenta con verificación de idempotencia
- ✓ `Withdraw` — debitar con validación de fondos insuficientes
- ✓ `Transfer` — transferencia atómica de dos patas entre cuentas
- ✓ `ListTransactions` — consultar historial de transacciones
- ✓ `CreateLoan` — creación de préstamo con validación RENIEC (simulada)

**Comportamientos clave verificados:**
- Procesamiento exactly-once (duplicado `messageId` retorna `duplicate: true`)
- Prevención de saldo negativo a nivel SQL
- Transferencias de dos patas usan IDs de transacción distintos con ID de transferencia compartido
- Integridad del contrato de respuesta JSON (`ok`, `data`, `error`, `correlationId`)

## Modo de Desarrollo Local

**Ejecutar servidor con RENIEC simulado (sin conexión RabbitMQ a RENIEC):**
```fish
set -x USE_RENIEC_MOCK true
set -x RABBIT_HOST localhost
mvn exec:java
```

Esto evita la conexión RabbitMQ del ReniecRpcClient y siempre retorna verificación de identidad válida. Útil para:
- Pruebas locales sin infraestructura completa
- Iteración rápida en lógica de negocio
- Depuración de operaciones bancarias de forma independiente

**Ejecutar con RENIEC real (requiere servicio RENIEC en RabbitMQ):**
```fish
set -e USE_RENIEC_MOCK  # o establecerlo a "false"
set -x RABBIT_HOST localhost
mvn exec:java
```

## Base de Datos de Pruebas

Las pruebas usan un archivo SQLite temporal aislado por ejecución de prueba (`Files.createTempFile("bank-test-", ".db")`).
- Esquema inicializado desde `src/main/resources/db/init_db.sql`
- Datos semilla incluyen CL001, CU001, PR001 del script de inicialización
- Limpieza automática en `@AfterEach`

## Integración Continua

Agregar al pipeline de CI:
```yaml
# Ejemplo GitHub Actions
- name: Ejecutar pruebas
  run: mvn -B test
```

## Advertencias Conocidas en Pruebas

- **SLF4J NOP logger**: Inofensivo. Las pruebas se ejecutan con binding de logger no-operacional. Agregar `slf4j-simple` al scope de test si deseas logs de prueba.

## Agregar Nuevas Pruebas

1. Crear método de prueba con anotación `@Test`
2. Usar helper `call(Map.of(...))` para invocar BankService vía JSON
3. Afirmar estructura de respuesta: `res.get("ok").asBoolean()`, `res.path("data").path("field")`
4. Para comparaciones BigDecimal, usar `assertEquals(0, actual.compareTo(expected))` para ignorar escala
5. Agregar logging de depuración para fallos: `System.out.println("DEBUG: " + res.toPrettyString())`
