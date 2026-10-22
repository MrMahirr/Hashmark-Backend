package dev.hashmark.badge;

/**
 * shields.io "flat" görünümünde iki parçalı SVG rozet. Metin genişliği karakter başına yaklaşık ölçülür
 * (Verdana 11 px); yazı tipi farkında birkaç piksel kayma olur, rozet okunaklı kalır.
 */
public final class BadgeSvg {

    private BadgeSvg() {
    }

    public static String render(String label, String message, String color) {
        int labelWidth = textWidth(label) + 10;
        int messageWidth = textWidth(message) + 10;
        int width = labelWidth + messageWidth;
        String l = escape(label);
        String m = escape(message);
        String c = color.matches("#[0-9a-fA-F]{6}") ? color : "#9f9f9f";
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + width + "\" height=\"20\" role=\"img\" "
                + "aria-label=\"" + l + ": " + m + "\"><title>" + l + ": " + m + "</title>"
                + "<linearGradient id=\"s\" x2=\"0\" y2=\"100%\"><stop offset=\"0\" stop-color=\"#bbb\" stop-opacity=\".1\"/>"
                + "<stop offset=\"1\" stop-opacity=\".1\"/></linearGradient>"
                + "<clipPath id=\"r\"><rect width=\"" + width + "\" height=\"20\" rx=\"3\" fill=\"#fff\"/></clipPath>"
                + "<g clip-path=\"url(#r)\"><rect width=\"" + labelWidth + "\" height=\"20\" fill=\"#555\"/>"
                + "<rect x=\"" + labelWidth + "\" width=\"" + messageWidth + "\" height=\"20\" fill=\"" + c + "\"/>"
                + "<rect width=\"" + width + "\" height=\"20\" fill=\"url(#s)\"/></g>"
                + "<g fill=\"#fff\" text-anchor=\"middle\" font-family=\"Verdana,Geneva,DejaVu Sans,sans-serif\" "
                + "font-size=\"11\">"
                + "<text x=\"" + (labelWidth / 2.0) + "\" y=\"15\" fill=\"#010101\" fill-opacity=\".3\">" + l + "</text>"
                + "<text x=\"" + (labelWidth / 2.0) + "\" y=\"14\">" + l + "</text>"
                + "<text x=\"" + (labelWidth + messageWidth / 2.0) + "\" y=\"15\" fill=\"#010101\" fill-opacity=\".3\">"
                + m + "</text>"
                + "<text x=\"" + (labelWidth + messageWidth / 2.0) + "\" y=\"14\">" + m + "</text></g></svg>";
    }

    static int textWidth(String text) {
        double width = 0;
        for (char ch : text.toCharArray()) {
            if (ch == ' ' || ch == 'i' || ch == 'l' || ch == '.' || ch == '·' || ch == '/') {
                width += 3.5;
            } else if (Character.isUpperCase(ch) || Character.isDigit(ch) || ch == 'm' || ch == 'w') {
                width += 7.5;
            } else {
                width += 6.5;
            }
        }
        return (int) Math.ceil(width);
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
