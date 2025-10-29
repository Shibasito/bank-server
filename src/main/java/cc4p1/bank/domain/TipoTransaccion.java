package cc4p1.bank.domain;

public enum TipoTransaccion {
    deposito, retiro;

    public static TipoTransaccion from(String s) {
        return TipoTransaccion.valueOf(s.toLowerCase());
    }

    @Override public String toString() { return name(); } 
}
