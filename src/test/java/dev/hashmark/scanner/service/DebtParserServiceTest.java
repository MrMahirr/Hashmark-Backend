package dev.hashmark.scanner.service;

import dev.hashmark.debt.dto.DebtDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DebtParserServiceTest {

    private final DebtParserService parserService = new DebtParserService();

    @Test
    void testParseDebts() {
        String input = """
                // TODO: implement this
                # FIXME: broken logic
                /* HACK: temporary workaround */
                // normal comment
                -- XXX: database issue
                /** @param id user id */
                // config: max timeout 30s
                """;

        List<DebtDto> debts = parserService.parse(input, "test.txt");

        assertEquals(7, debts.size(), "7 item bulunmali (TODO, FIXME, HACK, XXX, NOTE, DOC, INFO)");

        assertEquals("TODO", debts.get(0).getLabel());
        assertEquals("implement this", debts.get(0).getContent());
        assertEquals(1, debts.get(0).getLineNo());

        assertEquals("FIXME", debts.get(1).getLabel());
        assertEquals("broken logic", debts.get(1).getContent());
        assertEquals(2, debts.get(1).getLineNo());

        assertEquals("HACK", debts.get(2).getLabel());
        assertEquals("temporary workaround", debts.get(2).getContent());
        assertEquals(3, debts.get(2).getLineNo());

        assertEquals("NOTE", debts.get(3).getLabel());
        assertEquals("normal comment", debts.get(3).getContent());
        assertEquals(4, debts.get(3).getLineNo());

        assertEquals("XXX", debts.get(4).getLabel());
        assertEquals("database issue", debts.get(4).getContent());
        assertEquals(5, debts.get(4).getLineNo());

        assertEquals("DOC", debts.get(5).getLabel());
        assertEquals("* @param id user id", debts.get(5).getContent());
        assertEquals(6, debts.get(5).getLineNo());

        assertEquals("INFO", debts.get(6).getLabel());
        assertEquals("config: max timeout 30s", debts.get(6).getContent());
        assertEquals(7, debts.get(6).getLineNo());
    }
}
