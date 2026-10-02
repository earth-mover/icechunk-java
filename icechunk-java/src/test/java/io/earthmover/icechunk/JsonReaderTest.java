package io.earthmover.icechunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonReaderTest {
    @Test
    void values() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("s", "a\"b\\c\né😀");
        expected.put("i", -12L);
        expected.put("big", new BigInteger("18446744073709551615"));
        expected.put("d", 1.5e-3);
        expected.put("list", Arrays.asList(true, false, null, Collections.emptyList()));
        expected.put("empty", Collections.emptyMap());
        assertEquals(
                expected,
                JsonReader.parse(" {\"s\":\"a\\\"b\\\\c\\n\\u00e9\\ud83d\\ude00\",\"i\":-12,"
                        + "\"big\":18446744073709551615,\"d\":1.5e-3,\"list\":[true,false,null,[]],\"empty\":{}} "));
    }

    @Test
    void writerOutputReadsBack() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("text", "x\u0001y");
        value.put("n", 7L);
        value.put("nested", Collections.singletonMap("k", Arrays.asList(1L, 2.5, null)));
        String json = Json.object().putValue("v", value).toString();
        assertEquals(Collections.singletonMap("v", value), JsonReader.parse(json));
    }

    @Test
    void malformedInputFails() {
        assertThrows(IllegalStateException.class, () -> JsonReader.parse("{\"a\":1"));
        assertThrows(IllegalStateException.class, () -> JsonReader.parse("[1,]"));
        assertThrows(IllegalStateException.class, () -> JsonReader.parse("1 2"));
    }
}
