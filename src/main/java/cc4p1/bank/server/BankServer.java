/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 */

package cc4p1.bank.server;

import cc4p1.bank.db.SQLite;
import cc4p1.bank.mq.Rabbit;
import cc4p1.bank.repo.*;
import cc4p1.bank.service.BankService;
import cc4p1.bank.service.MockReniecClient;
import cc4p1.bank.service.ReniecRpcClient;

/**
 *
 * @author ak13a
 */
public class BankServer {

    public static void main(String[] args) throws Exception {
        // 1) Base de datos
        SQLite sqlite = new SQLite("data/bank.db");

        // 2) Inicializar esquema si hace falta
        sqlite.initializeIfNeeded("/db/init_db.sql");

        // 3) Dependencias
        var clientRepo = new ClientRepo();
        var accountRepo = new AccountRepo();
        var loanRepo = new LoanRepo();
        var txRepo = new TxRepo();
        var messageRepo = new MessageRepo();

        String rabbitHost = System.getenv().getOrDefault("RABBIT_HOST", "localhost");
        boolean useMockReniec = "true".equalsIgnoreCase(System.getenv().getOrDefault("USE_RENIEC_MOCK", "true"));

        // 4) Clientes externos (RENIEC) y mensajería (RabbitMQ)
        final BankService.ReniecClient reniec;
        if (useMockReniec) {
            System.out.println("[INFO] Usando MockReniecClient (sin RabbitMQ para RENIEC)");
            reniec = new MockReniecClient(true, 0);
        } else {
            System.out.println("[INFO] Usando ReniecRpcClient (RabbitMQ)");
            reniec = new ReniecRpcClient(rabbitHost);
        }
        final Rabbit mq = new Rabbit(rabbitHost);

        // 5) Servicio principal del banco
        BankService bank = new BankService(
                sqlite,
                clientRepo,
                accountRepo,
                loanRepo,
                txRepo,
                messageRepo,
                reniec
        );

        // 6) Registrar shutdown hook para cerrar recursos ordenadamente
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { mq.close(); } catch (Exception ignored) {}
            // Cerrar Reniec solo si es... cerrable
            if (reniec instanceof AutoCloseable) {
                try { ((AutoCloseable) reniec).close(); } catch (Exception ignored) {}
            }
        }));

        // 7) Iniciar consumidor RabbitMQ
        mq.serve(bank);
        System.out.println("Bank Server iniciado. Esperando mensajes...");

        // 8) Mantener el proceso vivo
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
