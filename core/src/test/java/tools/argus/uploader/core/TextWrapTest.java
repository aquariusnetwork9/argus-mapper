package tools.argus.uploader.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextWrapTest {

    private static List<String> wrap(String text, int maxChars) {
        return TextWrap.wrap(text, maxChars, String::length);
    }

    @Test
    void wrapsAtWordBoundaries() {
        assertEquals(List.of("one two", "three four"), wrap("one two three four", 10));
    }

    @Test
    void keepsParagraphBreaksAndBlankLines() {
        assertEquals(List.of("a b", "", "c"), wrap("a b\n\nc", 10));
    }

    @Test
    void aWordLongerThanTheWidthGetsItsOwnLine() {
        assertEquals(List.of("ab", "abcdefghij", "cd"), wrap("ab abcdefghij cd", 5));
    }

    @Test
    void emptyTextIsOneEmptyLine() {
        assertEquals(List.of(""), wrap("", 10));
    }
}
