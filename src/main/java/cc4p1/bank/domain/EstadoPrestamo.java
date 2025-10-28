package cc4p1.bank.domain;

public enum EstadoPrestamo {
    activo, pagado;

    public static EstadoPrestamo from(String s) {
        return EstadoPrestamo.valueOf(s.toLowerCase());
    }

    @Override public String toString() { return name(); }
}
