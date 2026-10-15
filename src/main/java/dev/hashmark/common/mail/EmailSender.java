package dev.hashmark.common.mail;

/** Giden e-posta soyutlaması; uygulama sağlayıcıdan bağımsız kalır (şu an Brevo). */
public interface EmailSender {

    /** Sağlayıcı yapılandırılmış mı (API anahtarı vb.). Değilse {@link #send} hiçbir şey göndermez. */
    boolean isConfigured();

    /**
     * @return gönderildiyse {@code true}; yapılandırılmamış veya hata durumunda {@code false}
     */
    boolean send(String toEmail, String toName, String subject, String htmlContent);
}
