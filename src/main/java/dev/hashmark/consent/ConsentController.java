package dev.hashmark.consent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Onay ve aydınlatma uçları (plan 5.10). */
@RestController
@Tag(name = "Consent", description = "Kullanıcı onayı ve aydınlatma metni")
public class ConsentController {

    public record PolicyResponse(int version, String language, String text, String hash) {}

    public record AcceptRequest(int version, String language, String hash) {}

    private final ConsentService consent;

    public ConsentController(ConsentService consent) {
        this.consent = consent;
    }

    /** Giriş yapmadan da okunabilir: kullanıcı neye onay vereceğini önceden görebilmeli. */
    @GetMapping("/public/consent-policy")
    @Operation(summary = "Geçerli aydınlatma/onay metni ve özeti")
    public PolicyResponse policy(@RequestParam(defaultValue = "tr") String lang) {
        ConsentPolicyCatalog.Text t = consent.currentPolicy(lang);
        return new PolicyResponse(t.version(), t.language(), t.body(), t.hash());
    }

    @GetMapping("/public/private-repo-notice")
    @Operation(summary = "Özel depo ek uyarı metni")
    public PolicyResponse privateRepoNotice(@RequestParam(defaultValue = "tr") String lang) {
        ConsentPolicyCatalog.Text t = consent.currentPrivateRepoNotice(lang);
        return new PolicyResponse(t.version(), t.language(), t.body(), t.hash());
    }

    @GetMapping("/user/consent")
    @Operation(summary = "Onay durumu")
    public ConsentService.Status status(@AuthenticationPrincipal Long userId) {
        return consent.status(userId);
    }

    @PostMapping("/user/consent")
    @Operation(summary = "Gösterilen metne onay ver (sürüm + özet eşleşmeli)")
    public ConsentService.Status accept(@AuthenticationPrincipal Long userId, @RequestBody AcceptRequest request) {
        return consent.accept(userId, request.version(), request.language(), request.hash());
    }

    @DeleteMapping("/user/consent")
    @Operation(summary = "Onayı geri çek (tarama ve özetler durur; veriler silinmez)")
    public ConsentService.Status withdraw(@AuthenticationPrincipal Long userId) {
        return consent.withdraw(userId);
    }

    @PostMapping("/repos/{id}/private-ack")
    @Operation(summary = "Özel depo ek uyarısını onayla")
    public ResponseEntity<Void> acknowledgePrivateRepo(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        consent.acknowledgePrivateRepo(userId, id);
        return ResponseEntity.noContent().build();
    }
}
