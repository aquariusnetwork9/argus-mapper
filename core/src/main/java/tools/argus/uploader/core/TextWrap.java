package tools.argus.uploader.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Greedy word wrap for popup text; {@code widthOf} is the font's pixel width of a string. */
public final class TextWrap {

    private TextWrap() {
    }

    /** Newlines in {@code text} start a new paragraph; a blank one is kept as an empty line. */
    public static List<String> wrap(String text, int maxWidth, ToIntFunction<String> widthOf) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (word.isEmpty()) {
                    continue;
                }
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (!line.isEmpty() && widthOf.applyAsInt(candidate) > maxWidth) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }
}
