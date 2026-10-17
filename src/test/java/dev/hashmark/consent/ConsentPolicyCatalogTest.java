package dev.hashmark.consent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsentPolicyCatalogTest {

    private final ConsentPolicyCatalog catalog = new ConsentPolicyCatalog();

    @Test
    void everyLanguageHasPolicyAndPrivateRepoNotice() {
        for (String lang : ConsentPolicyCatalog.LANGUAGES) {
            var policy = catalog.policy(1, lang);
            assertTrue(policy.body().startsWith("## "), lang);
            assertEquals(ConsentPolicyCatalog.sha256(policy.body()), policy.hash());
            assertFalse(policy.body().contains("\r"), "satır sonları normalize edilmeli");
            assertFalse(catalog.privateRepo(1, lang).body().isBlank());
        }
        assertNotEquals(catalog.policy(1, "tr").hash(), catalog.policy(1, "en").hash());
    }

    @Test
    void unknownLanguageFallsBackToTurkish() {
        assertEquals("tr", catalog.policy(1, "de").language());
        assertEquals("tr", catalog.policy(1, null).language());
    }

    /** Metin, saklanan verilerle tutarlı olmalı (plan 5.3): kodun saklanmadığı ve yorum satırının saklandığı yazar. */
    @Test
    void policyStatesWhatIsAndIsNotStored() {
        String tr = catalog.policy(1, "tr").body();
        assertTrue(tr.contains("Kaynak kodun kendisi") && tr.contains("saklanmaz"));
        assertTrue(tr.contains("yorum satırları"));
        assertTrue(tr.contains("şifrelenmeden"), "yorum metni şifrelemesi gelmeden 'şifreli' denmemeli");
    }
}
