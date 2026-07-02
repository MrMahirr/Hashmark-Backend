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
                """;

        List<DebtDto> debts = parserService.parse(input, "test.txt");

        assertEquals(4, debts.size(), "4 debt bulunmali (TODO, FIXME, HACK, XXX)");

        assertEquals("TODO", debts.get(0).getLabel());
        assertEquals("implement this", debts.get(0).getContent());
        assertEquals(1, debts.get(0).getLineNo());

        assertEquals("FIXME", debts.get(1).getLabel());
        assertEquals("broken logic", debts.get(1).getContent());
        assertEquals(2, debts.get(1).getLineNo());

        assertEquals("HACK", debts.get(2).getLabel());
        assertEquals("temporary workaround */", debts.get(2).getContent());
        assertEquals(3, debts.get(2).getLineNo());

        assertEquals("XXX", debts.get(3).getLabel());
        assertEquals("database issue", debts.get(3).getContent());
        assertEquals(5, debts.get(3).getLineNo());
    }
}
