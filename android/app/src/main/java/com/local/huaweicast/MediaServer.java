package com.local.huaweicast;

import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.util.UUID;

public final class MediaServer extends NanoHTTPD {
    private final File file;
    private final String mime;
    public final String mediaPath = "/media/" + UUID.randomUUID();
    public MediaServer(File file, String mime) { super(0); this.file = file; this.mime = mime; }
    @Override public Response serve(IHTTPSession session) {
        if (!session.getUri().equals(mediaPath)) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
        if (session.getMethod() != Method.GET && session.getMethod() != Method.HEAD) return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "GET or HEAD required");
        try {
            ByteRange range = ByteRange.parse(session.getHeaders().get("range"), file.length());
            InputStream input;
            if (session.getMethod() == Method.HEAD) input = new ByteArrayInputStream(new byte[0]);
            else {
                FileInputStream stream = new FileInputStream(file);
                try { stream.getChannel().position(range.start()); input = stream; } catch (IOException error) { stream.close(); throw error; }
            }
            Response response = newFixedLengthResponse(range.partial() ? Response.Status.PARTIAL_CONTENT : Response.Status.OK, mime, input, range.length());
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("transferMode.dlna.org", "Streaming");
            response.addHeader("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_CI=0");
            response.addHeader("Cache-Control", "no-store");
            if (range.partial()) response.addHeader("Content-Range", "bytes " + range.start() + "-" + range.end() + "/" + file.length());
            return response;
        } catch (IllegalArgumentException error) {
            Response response = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Invalid range");
            response.addHeader("Content-Range", "bytes */" + file.length()); return response;
        } catch (IOException error) { return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Media unavailable"); }
    }
}
