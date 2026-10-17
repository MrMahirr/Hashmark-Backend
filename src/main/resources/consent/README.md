# Onay metinleri (plan 5.10, K7)

- `policy-v<N>.<dil>.txt`: veri işleme onayı. Kullanıcıya backend'in `/public/consent-policy` ucundan
  aynen gösterilir; onay kaydında bu dosyanın SHA-256'sı tutulur (hangi metne onay verildiğinin kanıtı).
- `private-repo-v<N>.<dil>.txt`: özel depo ek uyarısı.
- Biçim: `## Başlık` satırları ve `- ` madde satırları (frontend bunları basitçe işler).

**Metni değiştirmek:** yayınlanmış bir sürüm dosyası değiştirilmez. Yeni `policy-v<N+1>.*.txt` eklenir ve
`consent.policy-version` artırılır → tüm kullanıcılardan yeniden onay istenir.

**Durum (2026-10-17):** v1 metni taslaktır — **hukuki inceleme bekliyor**. İnceleme bitene kadar
`CONSENT_ENFORCED=false` (varsayılan) kalmalıdır. Metin, `Hashmark-Vault/Planlar/Veri Saklama Politikası.md`
ve plan 5.3 ile birebir tutarlı olmalıdır; şifreleme (K3) geldiğinde "Nasıl korunur" bölümü değişir → v2.
