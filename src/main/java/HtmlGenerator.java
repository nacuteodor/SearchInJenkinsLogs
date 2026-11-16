import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * Builds HTML fragments through a fluent API and optionally writes them to a file.
 * Raw-text methods accept trusted HTML; link labels and targets are HTML-escaped.
 *
 * Created by Teo on 10/11/2016.
 */
public class HtmlGenerator {
    private File file;
    private boolean append;
    private String content = "";

    /** Creates an in-memory fragment builder without a file destination. */
    public HtmlGenerator() {
    }

    /**
     * Creates a file-backed builder, appending on save if the file already exists.
     *
     * @param file destination for {@link #saveHtmlFile()}
     */
    public HtmlGenerator(File file) throws IOException {
        this.file = file;
        this.append = this.file.exists();
    }

    /** Starts a table and returns this builder for chaining. */
    public HtmlGenerator startTable() {
        content = content.concat("<table cellspacing=\"0\" border=\"1\" bordercolor=\"#0000\">\n");
        return this;
    }

    /** Closes the current table and returns this builder. */
    public HtmlGenerator endTable() {
        content = content.concat("</table>\n");
        return this;
    }

    /** Starts a table row and returns this builder. */
    public HtmlGenerator startRow() {
        content = content.concat("<tr>\n");
        return this;
    }

    /** Closes the current table row and returns this builder. */
    public HtmlGenerator endRow() {
        content = content.concat("</tr>\n");
        return this;
    }

    /** Starts a table cell and returns this builder. */
    public HtmlGenerator startColumn() {
        content = content.concat("<td>");
        return this;
    }

    /** Closes the current table cell and returns this builder. */
    public HtmlGenerator endColumn() {
        content = content.concat("</td>\n");
        return this;
    }

    /** Starts bold formatting and returns this builder. */
    public HtmlGenerator startBold() {
        content = content.concat("<b>");
        return this;
    }

    /** Ends bold formatting and returns this builder. */
    public HtmlGenerator endBold() {
        content = content.concat("</b>\n");
        return this;
    }

    /**
     * Appends trusted HTML without escaping it.
     *
     * @param text HTML fragment; callers must escape untrusted text
     * @return this builder
     */
    public HtmlGenerator addText(String text) {
        content = content.concat(text);
        return this;
    }

    /** Appends an HTML line break and returns this builder. */
    public HtmlGenerator addNewLine() {
        content = content.concat("<br/>\n");
        return this;
    }

    /**
     * Wraps trusted HTML in a paragraph.
     *
     * @param text paragraph contents, not HTML-escaped
     * @return this builder
     */
    public HtmlGenerator addParagraph(String text) {
        content = content.concat("<p>").concat(text).concat("</p>\n");
        return this;
    }

    /**
     * Appends a hyperlink with an HTML-escaped label and target attribute.
    * Opens the target in a new tab without granting access to the report's window.
    * Unsafe or malformed targets are rendered as escaped, unlinked text.
     *
     * @param name plain-text link label
     * @param link target URL
     * @return this builder
     */
    public HtmlGenerator addLink(String name, String link) {
        if (!isSafeLink(link)) {
            return addText(StringEscapeUtils.escapeHtml4(name));
        }
        content = content.concat("<a href=\"").concat(StringEscapeUtils.escapeHtml4(link))
            .concat("\" target=\"_blank\" rel=\"noopener noreferrer\">")
            .concat(StringEscapeUtils.escapeHtml4(name)).concat("</a>\n");
        return this;
    }

    /**
     * Allows HTTP(S) URLs without embedded credentials and relative report links.
     *
     * @param link target to validate
     * @return whether the target can be rendered as a report hyperlink
     */
    private static boolean isSafeLink(String link) {
        if (link == null || link.trim().isEmpty()) {
            return false;
        }
        try {
            URI target = new URI(link);
            String scheme = target.getScheme();
            if (scheme == null) {
                return target.getRawAuthority() == null;
            }
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && target.getHost() != null && target.getRawUserInfo() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Adds a complete table cell, optionally linked and bolded.
     * Text is escaped for links; otherwise it is treated as trusted HTML.
     *
     * @param text cell content or link label
     * @param link target URL, or null/empty for an unlinked cell
     * @param bolded whether to wrap the contents in bold formatting
     * @return this builder
     */
    public HtmlGenerator addColumnValue(String text, String link, boolean bolded) {
        HtmlGenerator html = this.startColumn();
        html = (bolded) ? html.startBold() : html;
        if (StringUtils.isEmpty(link)) {
            html.addText(text);
        } else {
            html.addLink(text, link);
        }
        html = (bolded) ? html.endBold() : html;
        return html.endColumn();
    }

    /** Adds a linked cell without bold formatting; see {@link #addColumnValue(String, String, boolean)}. */
    public HtmlGenerator addColumnValue(String text, String link) {
        return addColumnValue(text, link, false);
    }

    /** Adds an unlinked cell containing trusted HTML, optionally bolded. */
    public HtmlGenerator addColumnValue(String text, boolean bolded) {
        return addColumnValue(text, null, bolded);
    }

    /** Adds an unlinked, non-bold cell containing trusted HTML. */
    public HtmlGenerator addColumnValue(String text) {
        return addColumnValue(text, null, false);
    }

    /**
     * Appends another builder's current fragment without changing either file destination.
     *
     * @param html source builder whose contents are copied
     * @return this builder
     */
    public HtmlGenerator addHtml(HtmlGenerator html) {
        this.content = this.content.concat(html.content);
        return this;
    }

    /** Returns the accumulated HTML fragment without writing it to disk. */
    public String getContent() {
        return content;
    }

    /**
     * Writes the fragment to the configured destination using the platform default charset.
     * Appends when the destination existed at construction time; otherwise overwrites it.
     *
     * @throws IOException if the destination cannot be written
     */
    public void saveHtmlFile() throws IOException {
        FileUtils.write(file, content, append);
    }
}
