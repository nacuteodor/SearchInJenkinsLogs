import java.io.File;
import java.io.IOException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * Created by Teo on 10/11/2016.
 */
public class HtmlGenerator {
    private File file;
    private boolean append;
    private String content = "";

    public HtmlGenerator() {
    }

    public HtmlGenerator(File file) throws IOException {
        this.file = file;
        this.append = this.file.exists();
    }

    public HtmlGenerator startTable() {
        content = content.concat("<table cellspacing=\"0\" border=\"1\" bordercolor=\"#0000\">\n");
        return this;
    }

    public HtmlGenerator endTable() {
        content = content.concat("</table>\n");
        return this;
    }

    public HtmlGenerator startRow() {
        content = content.concat("<tr>\n");
        return this;
    }

    public HtmlGenerator endRow() {
        content = content.concat("</tr>\n");
        return this;
    }

    public HtmlGenerator startColumn() {
        content = content.concat("<td>");
        return this;
    }

    public HtmlGenerator endColumn() {
        content = content.concat("</td>\n");
        return this;
    }

    public HtmlGenerator startBold() {
        content = content.concat("<b>");
        return this;
    }

    public HtmlGenerator endBold() {
        content = content.concat("</b>\n");
        return this;
    }

    public HtmlGenerator addText(String text) {
        content = content.concat(text);
        return this;
    }

    public HtmlGenerator addNewLine() {
        content = content.concat("<br/>\n");
        return this;
    }

    public HtmlGenerator addParagraph(String text) {
        content = content.concat("<p>").concat(text).concat("</p>\n");
        return this;
    }

    public HtmlGenerator addLink(String name, String link) {
        content = content.concat("<a href=\"").concat(link).concat("\">").concat(name).concat("</a>\n");
        return this;
    }

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

    public HtmlGenerator addColumnValue(String text, String link) {
        return addColumnValue(text, link, false);
    }

    public HtmlGenerator addColumnValue(String text, boolean bolded) {
        return addColumnValue(text, null, bolded);
    }

    public HtmlGenerator addColumnValue(String text) {
        return addColumnValue(text, null, false);
    }

    public HtmlGenerator addHtml(HtmlGenerator html) {
        this.content = this.content.concat(html.content);
        return this;
    }

    public String getContent() {
        return content;
    }

    public void saveHtmlFile() throws IOException {
        FileUtils.write(file, content, append);
    }
}
