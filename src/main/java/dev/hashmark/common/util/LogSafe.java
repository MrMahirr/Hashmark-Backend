package dev.hashmark.common.util;

/**
 * Kullanıcı verisi (dosya içeriği, yorum metni) taşıyabilecek istisnaları loglamak için. JDK istisnalarının
 * mesajı girdiden parça içerebilir (ör. {@code NumberFormatException: For input string: "..."}); bu yüzden
 * mesaj yerine yalnızca tür ve kaynağın ilk satırı yazılır. Hata ayıklamak için konum yeterlidir.
 */
public final class LogSafe {

    private LogSafe() {
    }

    /** {@code IllegalStateException at dev.hashmark.x.Y.method(Y.java:42) <- caused by ...} */
    public static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable current = t;
        for (int depth = 0; current != null && depth < 5; depth++, current = current.getCause()) {
            if (depth > 0) {
                sb.append(" <- caused by ");
            }
            sb.append(current.getClass().getName());
            StackTraceElement[] frames = current.getStackTrace();
            if (frames.length > 0) {
                sb.append(" at ").append(frames[0]);
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return sb.toString();
    }
}
