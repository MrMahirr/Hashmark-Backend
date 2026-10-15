package dev.hashmark.common.mail;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BrevoEmailSenderTest {

    private static final String URL = "https://api.brevo.test/v3/smtp/email";

    @Test
    void notConfiguredWhenKeyIsBlankAndNeverCallsApi() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(rest);
        BrevoEmailSender sender = new BrevoEmailSender(rest, "", "from@x.dev", "Hashmark", URL);

        assertFalse(sender.isConfigured());
        assertFalse(sender.send("to@x.dev", "To", "s", "<p>h</p>"));
        server.verify(); // hiç istek beklenmiyordu
    }

    @Test
    void postsBrevoPayloadWithApiKeyHeader() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(rest);
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "xkeysib-test"))
                .andExpect(jsonPath("$.sender.email").value("from@x.dev"))
                .andExpect(jsonPath("$.sender.name").value("Hashmark"))
                .andExpect(jsonPath("$.to[0].email").value("to@x.dev"))
                .andExpect(jsonPath("$.subject").value("Hello"))
                .andExpect(jsonPath("$.htmlContent").value("<p>hi</p>"))
                .andRespond(withSuccess("{\"messageId\":\"<1@brevo>\"}", MediaType.APPLICATION_JSON));

        BrevoEmailSender sender = new BrevoEmailSender(rest, "xkeysib-test", "from@x.dev", "Hashmark", URL);

        assertTrue(sender.isConfigured());
        assertTrue(sender.send("to@x.dev", "To", "Hello", "<p>hi</p>"));
        server.verify();
    }

    @Test
    void returnsFalseOnProviderError() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(rest);
        server.expect(requestTo(URL)).andRespond(withServerError());

        BrevoEmailSender sender = new BrevoEmailSender(rest, "key", "from@x.dev", "Hashmark", URL);

        assertFalse(sender.send("to@x.dev", null, "Hello", "<p>hi</p>"));
        server.verify();
    }

    @Test
    void emptyRecipientIsSkipped() {
        BrevoEmailSender sender = new BrevoEmailSender(new RestTemplate(), "key", "f@x.dev", "H", URL);
        assertFalse(sender.send("  ", null, "s", "h"));
    }
}
