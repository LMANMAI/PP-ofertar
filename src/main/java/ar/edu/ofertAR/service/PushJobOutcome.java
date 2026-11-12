package ar.edu.ofertAR.service;

/**
 * Qué hizo un job de notificaciones con un usuario: si le mandó algo y, si
 * no, por qué. Los jobs programados lo ignoran; lo devuelve la corrida manual
 * de {@code POST /admin/push/jobs/...}.
 */
public record PushJobOutcome(boolean sent, String reason) {

    public static PushJobOutcome sent(String reason) {
        return new PushJobOutcome(true, reason);
    }

    public static PushJobOutcome skipped(String reason) {
        return new PushJobOutcome(false, reason);
    }
}
