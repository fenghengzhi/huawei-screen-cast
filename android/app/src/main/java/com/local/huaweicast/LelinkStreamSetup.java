package com.local.huaweicast;

import com.dd.plist.BinaryPropertyListWriter;
import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/** Native FREE-session media negotiation; no pairing or transport authorization is implied. */
public final class LelinkStreamSetup {
    public static final int MAX_BODY_SIZE = 32 * 1024;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_ELEMENTS = 512;
    private static final String[] EXTERNAL_FEATURES = {
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd"
    };

    private LelinkStreamSetup() {}

    public record VideoPorts(int dataPort, int udpPort, int timingPort, int maxSequence) {}
    public record AudioPorts(int dataPort, int controlPort, int timingPort) {}

    public static byte[] videoRequest(int timingPort) throws IOException {
        requirePort(timingPort);
        NSDictionary stream = new NSDictionary();
        stream.put("type", 97);
        stream.put("mirror-tunnel", 0);
        NSDictionary request = request(timingPort, stream);
        request.put("mst", 1);
        return BinaryPropertyListWriter.writeToArray(request);
    }

    public static byte[] audioRequest(int rate, int controlPort, int timingPort) throws IOException {
        if (rate != 44100 && rate != 48000) throw new IllegalArgumentException("Unsupported audio sample rate");
        requirePort(controlPort);
        requirePort(timingPort);
        NSDictionary stream = new NSDictionary();
        stream.put("type", 96);
        stream.put("sample-rate", rate);
        stream.put("sample-format", 212);
        // This native SETUP parser does not consume a sender control-port field.
        NSDictionary request = request(timingPort, stream);
        request.put("ast", 1);
        return BinaryPropertyListWriter.writeToArray(request);
    }

    /** Selects one stream on the caller's existing authenticated control session. */
    public static byte[] teardownRequest(int type) throws IOException {
        if (type != 96 && type != 97) throw new IllegalArgumentException("Unsupported media stream type");
        NSDictionary stream = new NSDictionary();
        stream.put("type", type);
        NSArray streams = new NSArray(1);
        streams.setValue(0, stream);
        NSDictionary request = new NSDictionary();
        request.put("streams", streams);
        return BinaryPropertyListWriter.writeToArray(request);
    }

    private static NSDictionary request(int timingPort, NSDictionary stream) {
        NSDictionary request = new NSDictionary();
        request.put("encrypt-mode", 1);
        request.put("timing-port", timingPort);
        NSArray streams = new NSArray(1);
        streams.setValue(0, stream);
        request.put("streams", streams);
        return request;
    }

    public static VideoPorts parseVideo(byte[] xml) throws IOException {
        Map<String, Element> fields = parse(xml);
        Map<String, Element> stream = stream(fields, 97);
        // TCP mode does not initialize or use the optional UDP transport fields.
        return new VideoPorts(port(stream, "data-port"), optionalNumber(stream, "udp-port"),
                optionalPort(fields, "timing-port"), optionalNumber(stream, "max-seq-num"));
    }

    public static AudioPorts parseAudio(byte[] xml) throws IOException {
        Map<String, Element> fields = parse(xml);
        Map<String, Element> stream = stream(fields, 96);
        if (number(stream.get("ast")) != 1) throw new IOException("Receiver did not select native UDP audio");
        return new AudioPorts(port(stream, "data-port"), port(stream, "control-port"),
                optionalPort(fields, "timing-port"));
    }

    private static Map<String, Element> stream(Map<String, Element> fields, int expectedType) throws IOException {
        Element streams = fields.get("streams");
        if (streams == null || !streams.getTagName().equals("array")) throw new IOException("Missing media streams");
        List<Element> entries = elements(streams);
        if (entries.size() != 1) throw new IOException("Expected one media stream");
        Map<String, Element> stream = dictionary(entries.get(0));
        if (number(stream.get("type")) != expectedType) throw new IOException("Unexpected media stream type");
        return stream;
    }

    private static int optionalPort(Map<String, Element> fields, String key) throws IOException {
        return fields.containsKey(key) ? port(fields, key) : 0;
    }

    private static int optionalNumber(Map<String, Element> fields, String key) throws IOException {
        return fields.containsKey(key) ? number(fields.get(key)) : 0;
    }

    private static int port(Map<String, Element> fields, String key) throws IOException {
        int value = number(fields.get(key));
        if (value == 0) throw new IOException("Invalid " + key);
        return value;
    }

    private static int number(Element element) throws IOException {
        if (element == null || !element.getTagName().equals("integer") || hasElements(element)) {
            throw new IOException("Expected media integer");
        }
        String text = element.getTextContent().trim();
        if (!text.matches("[0-9]{1,5}")) throw new IOException("Invalid media integer");
        int value = Integer.parseInt(text);
        if (value > 65535) throw new IOException("Media integer out of range");
        return value;
    }

    private static void requirePort(int port) {
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("Invalid sender port");
    }

    private static Map<String, Element> parse(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BODY_SIZE) {
            throw new IOException("Invalid media SETUP response length");
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
                try { factory.setFeature(feature, false); } catch (ParserConfigurationException ignored) {}
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
            List<Element> values = elements(root);
            if (values.size() != 1) throw new IOException("Expected media dictionary");
            validateValue(values.get(0));
            return dictionary(values.get(0));
        } catch (ParserConfigurationException | SAXException error) {
            throw new IOException("Invalid media SETUP response XML", error);
        }
    }

    // Check ignored extensions too so duplicate keys or deeply nested values cannot hide there.
    private static void validateValue(Element value) throws IOException {
        switch (value.getTagName()) {
            case "dict" -> {
                for (Element child : dictionary(value).values()) validateValue(child);
            }
            case "array" -> {
                for (Element child : elements(value)) validateValue(child);
            }
            case "integer", "real", "string", "data", "date" -> {
                if (hasElements(value)) throw new IOException("Nested plist scalar");
            }
            case "true", "false" -> {
                if (hasElements(value) || !value.getTextContent().trim().isEmpty()) throw new IOException("Invalid plist boolean");
            }
            default -> throw new IOException("Unexpected plist value");
        }
    }

    private static Map<String, Element> dictionary(Element element) throws IOException {
        if (!element.getTagName().equals("dict")) throw new IOException("Expected dictionary");
        List<Element> entries = elements(element);
        if (entries.size() % 2 != 0 || entries.size() > 128) throw new IOException("Invalid dictionary");
        Map<String, Element> result = new HashMap<>();
        for (int i = 0; i < entries.size(); i += 2) {
            Element key = entries.get(i);
            if (!key.getTagName().equals("key") || hasElements(key)) throw new IOException("Invalid dictionary key");
            String name = key.getTextContent().trim();
            if (name.isEmpty() || name.length() > 128 || result.put(name, entries.get(i + 1)) != null) {
                throw new IOException("Invalid or duplicate dictionary key");
            }
        }
        return result;
    }

    private static List<Element> elements(Element parent) throws IOException {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) result.add(element);
            else if (node.getNodeType() == Node.ENTITY_REFERENCE_NODE
                    || ((node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE)
                    && !node.getNodeValue().trim().isEmpty())) throw new IOException("Invalid plist content");
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

    private static InputSource emptySource() { return new InputSource(new StringReader("")); }
    private static final class StrictErrors extends DefaultHandler {
        @Override public void error(SAXParseException error) throws SAXException { throw error; }
        @Override public void fatalError(SAXParseException error) throws SAXException { throw error; }
    }
}
