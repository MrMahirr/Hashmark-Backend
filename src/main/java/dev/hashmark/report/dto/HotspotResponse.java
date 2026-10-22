package dev.hashmark.report.dto;

import java.util.List;

/**
 * Bir klasörün borç ısı haritası: doğrudan alt klasör/dosyaları ve altındaki en "sıcak" dosyalar.
 * Isı = ağırlıklı borç (etiket ağırlığı × öncelik çarpanı); yalnızca açık borç etiketleri (NOTE/DOC/INFO hariç).
 *
 * @param path      incelenen klasör (boş = depo kökü), sonunda "/" yok
 * @param children  doğrudan alt öğeler, ısıya göre azalan; en çok {@code MAX_CHILDREN}
 * @param topFiles  klasörün altındaki (her derinlikte) en sıcak dosyalar
 * @param truncated çok büyük depoda yalnızca en kalabalık satırlar okundu; sayılar eksik olabilir
 */
public record HotspotResponse(String path, int debts, double weightedDebt, int staleCount,
                              List<Node> children, int hiddenChildren, List<Node> topFiles, boolean truncated) {

    /**
     * @param path  depo köküne göre tam yol (klasörse sonunda "/" yok)
     * @param files klasörse altındaki borçlu dosya sayısı, dosyaysa 1
     */
    public record Node(String name, String path, boolean directory, int debts, double weightedDebt, int staleCount,
                       int files) {
    }
}
