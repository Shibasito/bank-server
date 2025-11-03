package cc4p1.bank.service;

/**
 * Cliente RENIEC simulado (mock) que no usa RabbitMQ.
 * Siempre retorna una verificación exitosa (configurable) para pruebas locales
 * o entornos sin dependencia externa.
 */
public final class MockReniecClient implements BankService.ReniecClient {

  private final boolean valid;
  private final long delayMillis;

  /**
   * Crea un mock que siempre valida correctamente sin retardo.
   */
  public MockReniecClient() {
    this(true, 0);
  }

  /**
   * Crea un mock con resultado y retardo configurables.
   * @param valid si la verificación debe ser válida o no
   * @param delayMillis retardo artificial en milisegundos antes de responder
   */
  public MockReniecClient(boolean valid, long delayMillis) {
    this.valid = valid;
    this.delayMillis = delayMillis;
  }

  @Override
  public Verification verify(String dni) throws Exception {
    if (delayMillis > 0) {
      try { Thread.sleep(delayMillis); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
    // Datos ficticios; se ignoran en la lógica actual salvo el flag valid
    return new Verification(valid, dni, "NOMBRE MOCK", "APELLIDO_PAT", "APELLIDO_MAT");
  }

  public void close() throws Exception {
    // no-op
  }
}
