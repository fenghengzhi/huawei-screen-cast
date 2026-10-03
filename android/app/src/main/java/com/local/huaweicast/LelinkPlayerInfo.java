package com.local.huaweicast;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Bounded XML response data from /lelink-player-info, not proof of successful pairing. */
public final class LelinkPlayerInfo {
    public static final int MAX_BODY_SIZE = 32 * 1024;
    static final int MAX_ELEMENTS = 512;
    static final int MAX_DEPTH = 32;
    private static final Set<String> FIELDS = Set.of("htv", "atv", "etv", "mst", "ast");
    private static final String[] EXTERNAL_FEATURES = {
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd"
    };
    private final int htv;
    private final int atv;
    private final OptionalInt etv;
    private final OptionalInt mst;
    private final OptionalInt ast;

    private LelinkPlayerInfo(Map<String, Integer> fields) {
        htv = fields.get("htv");
        atv = fields.get("atv");
        etv = optional(fields.get("etv"));
        mst = optional(fields.get("mst"));
        ast = optional(fields.get("ast"));
    }

    public static LelinkPlayerInfo parse(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BODY_SIZE) {
            throw new IOException("Invalid Lelink player-info length");
        }
        for (byte value : bytes) if (value == 0) throw new IOException("Unsupported XML encoding");
        String source = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (source.startsWith("\ufeff")) source = source.substring(1);
        if (source.contains("<!ENTITY")) throw new IOException("Entity declarations are not allowed");
        try {
            checkXmlLimits(source);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setExpandEntityReferences(false);
            try { factory.setXIncludeAware(false); } catch (UnsupportedOperationException ignored) {}
            for (String feature : EXTERNAL_FEATURES) {
                try { factory.setFeature(feature, false); }
                catch (ParserConfigurationException ignored) {}
            }
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> emptySource());
            builder.setErrorHandler(new StrictErrors());
            var document = builder.parse(new InputSource(new StringReader(source)));
            if (document.getDoctype() != null && document.getDoctype().getInternalSubset() != null
                    && !document.getDoctype().getInternalSubset().trim().isEmpty()) {
                throw new IOException("Internal DTD declarations are not allowed");
            }
            Element root = document.getDocumentElement();
            if (!root.getTagName().equals("plist")) throw new IOException("Expected plist");
            List<Element> children = elements(root);
            if (children.size() != 1 || !children.get(0).getTagName().equals("dict")) {
                throw new IOException("Expected player-info dictionary");
            }
            List<Element> entries = elements(children.get(0));
            if (entries.size() % 2 != 0 || entries.size() > 128) throw new IOException("Invalid dictionary");
            Map<String, Integer> fields = new HashMap<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < entries.size(); i += 2) {
                Element key = entries.get(i);
                if (!key.getTagName().equals("key") || hasElements(key)) throw new IOException("Invalid key");
                String name = key.getTextContent().trim();
                if (name.length() > 128) throw new IOException("Invalid key length");
                if (!FIELDS.contains(name)) continue;
                if (!seen.add(name)) throw new IOException("Duplicate player-info field: " + name);
                Element value = entries.get(i + 1);
                boolean numericScalar = Set.of("integer", "real", "string").contains(value.getTagName())
                        && !hasElements(value);
                if (!numericScalar) {
                    if (name.equals("htv") || name.equals("atv")) throw new IOException("Invalid pairing mode scalar");
                    continue;
                }
                fields.put(name, scalar(value));
            }
            if (!fields.containsKey("htv") || !fields.containsKey("atv")) {
                throw new IOException("Missing pairing mode");
            }
            return new LelinkPlayerInfo(fields);
        } catch (ParserConfigurationException | SAXException error) {
            throw new IOException("Invalid Lelink player-info XML", error);
        }
    }

    // Bound depth and element count before constructing a DOM, including ignored extensions.
    private static void checkXmlLimits(String source) throws ParserConfigurationException, SAXException, IOException {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        var reader = factory.newSAXParser().getXMLReader();
        for (String feature : EXTERNAL_FEATURES) {
            try { reader.setFeature(feature, false); } catch (SAXException ignored) {}
        }
        reader.setEntityResolver((publicId, systemId) -> emptySource());
        reader.setErrorHandler(new StrictErrors());
        reader.setContentHandler(new DefaultHandler() {
            private int depth;
            private int count;
            @Override public void startElement(String uri, String localName, String name, Attributes attributes)
                    throws SAXException {
                if (++depth > MAX_DEPTH || ++count > MAX_ELEMENTS) throw new SAXException("XML structure exceeds limits");
            }
            @Override public void endElement(String uri, String localName, String name) { depth--; }
        });
        reader.parse(new InputSource(new StringReader(source)));
    }

    private static List<Element> elements(Element parent) throws IOException {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) result.add(element);
            else if (node.getNodeType() == Node.ENTITY_REFERENCE_NODE
                    || ((node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE)
                    && !node.getNodeValue().trim().isEmpty())) throw new IOException("Invalid dictionary content");
        }
        return result;
    }

    private static boolean hasElements(Element parent) throws IOException {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ENTITY_REFERENCE_NODE) throw new IOException("Entity references are not allowed");
            if (node instanceof Element) return true;
        }
        return false;
    }

    private static int scalar(Element value) throws IOException {
        String text = value.getTextContent().trim();
        String format = value.getTagName().equals("real") ? "[0-9]{1,5}(\\.0{1,8})?" : "[0-9]{1,5}";
        if (!text.matches(format)) throw new IOException("Invalid player-info numeric scalar");
        int number = new BigDecimal(text).intValueExact();
        if (number > 65535) throw new IOException("Player-info numeric scalar out of range");
        return number;
    }

    private static OptionalInt optional(Integer value) { return value == null ? OptionalInt.empty() : OptionalInt.of(value); }
    private static InputSource emptySource() { return new InputSource(new StringReader("")); }
    private static final class StrictErrors extends DefaultHandler {
        @Override public void error(SAXParseException error) throws SAXException { throw error; }
        @Override public void fatalError(SAXParseException error) throws SAXException { throw error; }
    }

    public int htv() { return htv; }
    public int atv() { return atv; }
    public OptionalInt etv() { return etv; }
    public OptionalInt mst() { return mst; }
    public OptionalInt ast() { return ast; }
    public boolean advertisesFreePairing() { return htv == 1 && atv == 0; }
}
