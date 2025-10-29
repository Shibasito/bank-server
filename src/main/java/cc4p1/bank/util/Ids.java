package cc4p1.bank.util;

import java.util.UUID;
import java.time.LocalDate;

public class Ids {

  private static String shortUuid() {
    return UUID.randomUUID().toString().substring(0, 6);
  }

  public static String tx() {
    return "TX-" + shortUuid();
  }

  public static String transfer() {
    return "TF-" + LocalDate.now() + "-" + shortUuid();
  }

  public static String loan() {
    return "PR-" + shortUuid();
  }

  public static String client() {
    return "CL-" + shortUuid();
  }

  public static String account() {
    return "CU-" + shortUuid();
  }
}

