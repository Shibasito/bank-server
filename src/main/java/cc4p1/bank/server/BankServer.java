/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 */

package cc4p1.bank.server;

import cc4p1.bank.db.SQLite;

/**
 *
 * @author ak13a
 */
public class BankServer {

    public static void main(String[] args) throws Exception {
    // 1) Helper DB
    SQLite sqlite = new SQLite("data/bank.db");

    // 2) Recreas schema de init_db
    sqlite.initializeIfNeeded("/db/init_db.sql");

    // 3) …continue normal startup (MQ consumers, services, etc.)
    // new BankServer(sqlite).start();
    }
}
