package dev.hashmark.channel;

/** Kanal adresine JSON gönderir. Testlerde sahtesi kullanılır. */
public interface ChannelTransport {

    /**
     * @param error başarısızsa kısa, adres içermeyen açıklama (ör. "HTTP 404", "timeout")
     */
    record Result(boolean ok, String error) {
        public static Result success() {
            return new Result(true, null);
        }

        public static Result failure(String error) {
            return new Result(false, error);
        }
    }

    Result post(String url, String json);
}
