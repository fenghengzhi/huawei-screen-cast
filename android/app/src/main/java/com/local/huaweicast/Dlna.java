package com.local.huaweicast;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Minimal UPnP AV control point: discovery, device descriptions and AVTransport. */
public final class Dlna {
    public record Device(String id, String name, String manufacturer, String controlUrl, String serviceType) {}
    public interface Listener { void found(Device device); default void diagnostic(String message) {} }
    public interface SocketBinder { void bind(DatagramSocket socket) throws Exception; }
    public static String location(String packet) {
        for (String line : packet.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("location")) return line.substring(colon + 1).trim();
        }
        return null;
    }
    public static void discover(Listener listener) throws Exception { discover(listener, socket -> {}); }
    public static void discover(Listener listener, SocketBinder binder) throws Exception {
        Set<String> seen = new HashSet<>();
        List<String> descriptions = new ArrayList<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            binder.bind(socket);
            socket.setSoTimeout(700);
            for (String target : new String[]{"urn:schemas-upnp-org:device:MediaRenderer:1", "urn:schemas-upnp-org:service:AVTransport:1", "ssdp:all"}) {
                byte[] query = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + target + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                socket.send(new DatagramPacket(query, query.length, InetAddress.getByName("239.255.255.250"), 1900));
            }
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && descriptions.size() < 40) {
                byte[] bytes = new byte[8192]; DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
                try {
                    socket.receive(packet);
                    String url = location(new String(bytes, 0, packet.getLength(), StandardCharsets.UTF_8));
                    if (url != null && seen.add(url)) { descriptions.add(url); listener.diagnostic("SSDP: " + url); }
                } catch (SocketTimeoutException ignored) {}
            }
        }
        for (String url : descriptions) {
            try { Device device = describe(url); if (device != null) listener.found(device); }
            catch (Exception error) { listener.diagnostic("描述读取失败 " + url + ": " + error.getMessage()); }
        }
        listener.diagnostic("SSDP 描述地址数: " + descriptions.size());
    }
    static Document parse(byte[] xml) throws Exception {
        // UPnP requires UTF-8 XML. Reject DTDs before parsing, including on Android
        // parsers that do not implement the Xerces security feature flags.
        for (byte value : xml) if (value == 0) throw new IOException("仅支持 UTF-8 XML");
        String source = new String(xml, StandardCharsets.UTF_8);
        if (source.contains("<!DOCTYPE") || source.contains("<!ENTITY")) throw new IOException("不允许 XML DTD");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        for (String feature : new String[]{"http://apache.org/xml/features/disallow-doctype-decl", "http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities"}) {
            try { factory.setFeature(feature, feature.contains("disallow-doctype")); }
            catch (javax.xml.parsers.ParserConfigurationException ignored) {}
        }
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }
    static String text(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && name.equals(element.getLocalName() == null ? element.getTagName() : element.getLocalName())) return element.getTextContent().trim();
        }
        return "";
    }
    public static Device parseDescription(String location, byte[] xml) throws Exception {
        Document document = parse(xml);
        String base = location;
        NodeList bases = document.getElementsByTagNameNS("*", "URLBase");
        if (bases.getLength() > 0 && !bases.item(0).getTextContent().trim().isEmpty()) base = bases.item(0).getTextContent().trim();
        NodeList devices = document.getElementsByTagNameNS("*", "device");
        for (int d = 0; d < devices.getLength(); d++) {
            Element device = (Element) devices.item(d);
            NodeList children = device.getChildNodes();
            for (int c = 0; c < children.getLength(); c++) {
                if (!(children.item(c) instanceof Element list) || !"serviceList".equals(list.getLocalName())) continue;
                NodeList services = list.getElementsByTagNameNS("*", "service");
                for (int s = 0; s < services.getLength(); s++) {
                    Element service = (Element) services.item(s);
                    String type = text(service, "serviceType");
                    if (!type.startsWith("urn:schemas-upnp-org:service:AVTransport:")) continue;
                    String control = new URL(new URL(base), text(service, "controlURL")).toString();
                    String name = text(device, "friendlyName");
                    return new Device(text(device, "UDN"), name.isEmpty() ? new URL(location).getHost() : name, text(device, "manufacturer"), control, type);
                }
            }
        }
        return null;
    }
    public static Device describe(String location) throws Exception {
        HttpURLConnection conn = connection(location); conn.setRequestMethod("GET");
        try (InputStream input = conn.getInputStream()) { return parseDescription(location, readLimited(input, 512 * 1024)); }
        finally { conn.disconnect(); }
    }
    static HttpURLConnection connection(String url) throws Exception {
        URL parsed = new URL(url);
        if (!"http".equals(parsed.getProtocol()) && !"https".equals(parsed.getProtocol())) throw new IOException("不支持的设备地址");
        HttpURLConnection conn = (HttpURLConnection) parsed.openConnection();
        conn.setConnectTimeout(3500); conn.setReadTimeout(5000); conn.setInstanceFollowRedirects(false); return conn;
    }
    static byte[] readLimited(InputStream input, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
        while ((n = input.read(buffer)) != -1) { if (out.size() + n > max) throw new IOException("设备响应过大"); out.write(buffer, 0, n); }
        return out.toByteArray();
    }
    public static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }
    public static String metadata(String title, String mime, String url, long size) {
        String klass = mime.startsWith("image/") ? "object.item.imageItem.photo" : mime.startsWith("audio/") ? "object.item.audioItem.musicTrack" : "object.item.videoItem";
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"1\" parentID=\"0\" restricted=\"1\"><dc:title>" + escape(title) + "</dc:title><upnp:class>" + klass + "</upnp:class><res protocolInfo=\"http-get:*:" + escape(mime) + ":*\"" + (size >= 0 ? " size=\"" + size + "\"" : "") + ">" + escape(url) + "</res></item></DIDL-Lite>";
    }
    public static void action(Device device, String action, Map<String, String> args) throws Exception {
        invoke(device, action, args);
    }
    public static Map<String, String> invoke(Device device, String action, Map<String, String> args) throws Exception {
        StringBuilder body = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + action + " xmlns:u=\"" + escape(device.serviceType()) + "\">");
        body.append("<InstanceID>0</InstanceID>");
        for (Map.Entry<String, String> entry : args.entrySet()) body.append('<').append(entry.getKey()).append('>').append(escape(entry.getValue())).append("</").append(entry.getKey()).append('>');
        body.append("</u:").append(action).append("></s:Body></s:Envelope>");
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = connection(device.controlUrl());
        conn.setRequestMethod("POST"); conn.setDoOutput(true); conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        conn.setRequestProperty("SOAPAction", "\"" + device.serviceType() + "#" + action + "\""); conn.setFixedLengthStreamingMode(data.length);
        try {
            try (OutputStream out = conn.getOutputStream()) { out.write(data); }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                String detail = "";
                try (InputStream input = conn.getErrorStream()) {
                    Document error = parse(readLimited(input, 65536));
                    NodeList desc = error.getElementsByTagNameNS("*", "errorDescription");
                    if (desc.getLength() > 0) detail = desc.item(0).getTextContent();
                } catch (Exception ignored) {}
                throw new IOException("电视拒绝 " + action + " (" + status + ") " + detail);
            }
            try (InputStream input = conn.getInputStream()) {
                Document response = parse(readLimited(input, 65536));
                Map<String, String> values = new HashMap<>();
                NodeList nodes = response.getElementsByTagNameNS("*", action + "Response");
                if (nodes.getLength() > 0) {
                    NodeList children = nodes.item(0).getChildNodes();
                    for (int i = 0; i < children.getLength(); i++) if (children.item(i) instanceof Element element) values.put(element.getLocalName(), element.getTextContent().trim());
                }
                return values;
            }
        } finally { conn.disconnect(); }
    }
}
