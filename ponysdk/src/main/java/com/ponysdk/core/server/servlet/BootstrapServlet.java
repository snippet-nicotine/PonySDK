/*
 * Copyright (c) 2011 PonySDK
 *  Owners:
 *  Luciano Broussal  <luciano.broussal AT gmail.com>
 *  Mathieu Barbier   <mathieu.barbier AT gmail.com>
 *  Nicolas Ciaravola <nicolas.ciaravola.pro AT gmail.com>
 *
 *  WebSite:
 *  http://code.google.com/p/pony-sdk/
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.ponysdk.core.server.servlet;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.activation.MimetypesFileTypeMap;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ponysdk.core.server.application.ApplicationManagerOption;

public class BootstrapServlet extends HttpServlet {

    protected static final Logger log = LoggerFactory.getLogger(BootstrapServlet.class);

    private static final long serialVersionUID = 6993633431616272739L;

    private final MimetypesFileTypeMap fileTypeMap = new MimetypesFileTypeMap();

    protected final List<String> meta = new ArrayList<>();
    protected final List<String> stylesheets = new ArrayList<>();
    protected final List<String> javascripts = new ArrayList<>();
    protected final Map<String, String> addons = new LinkedHashMap<>();

    protected String communicationErrorFunction;

    protected ApplicationManagerOption application;

    private Path indexPath;

    @Override
    public void init() throws ServletException {
        super.init();

        addStylesheets(application.getStyle());
        addJavascripts(application.getJavascript());
        addStylesheets(application.getCustomStyle());
        addJavascripts(application.getCustomJavascript());
        // addMetas(application.getMeta());
        // addMetas(application.getCustomMeta());

        initIndexFile();
    }

    @Override
    protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws IOException {
        handlePonyResource(request, response);
    }

    @Override
    public void doPost(final HttpServletRequest request, final HttpServletResponse response) throws IOException {
        handlePonyResource(request, response);
    }

    protected void handlePonyResource(final HttpServletRequest request, final HttpServletResponse response) throws IOException {
        try {
            final String extraPathInfo = getPath(request);

            if (extraPathInfo == null || extraPathInfo.isEmpty() || extraPathInfo.equals("/")) {
                handleRequest(request, response, "/index.html");
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Loading resource: " + extraPathInfo);
                }
                handleRequest(request, response, extraPathInfo);
            }

        } catch (final Throwable e) {
            log.error("Cannot process the request", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    protected String getPath(final HttpServletRequest request) {
        final String contextPath = request.getContextPath();
        return request.getRequestURI().replaceFirst(contextPath, "");
    }

    protected void handleRequest(final HttpServletRequest request, final HttpServletResponse response, final String path)
            throws IOException {
        // Force session creation if there is no session
        request.getSession();

        // Try to load from context
        InputStream inputStream = getServletContext().getResourceAsStream(path);

        File file;

        if (inputStream == null) {
            // Try to load from jar
            final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            final String jarPath = path.substring(1, path.length());
            inputStream = classLoader.getResourceAsStream(jarPath);
            if (inputStream == null) {
                if (path.equals("/index.html")) {
                    file = indexPath.toFile();
                } else {
                    log.error("Failed to load resource: " + request.getPathInfo());
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
            } else {
                file = new File(jarPath);
            }
        } else {
            file = new File(path);
        }

        final String mimeType = fileTypeMap.getContentType(file);
        response.setContentType(mimeType);

        if (inputStream != null) {
            final ReadableByteChannel inputChannel = Channels.newChannel(inputStream);
            final WritableByteChannel outputChannel = Channels.newChannel(response.getOutputStream());
            final ByteBuffer buffer = ByteBuffer.allocateDirect(16 * 1024);

            while (inputChannel.read(buffer) != -1) {
                buffer.flip();
                outputChannel.write(buffer);
                buffer.compact();
            }
            buffer.flip();
            while (buffer.hasRemaining()) {
                outputChannel.write(buffer);
            }
        } else {
            final int fileSize = Long.valueOf(file.length()).intValue();
            response.setContentLength(fileSize);
            Files.copy(file.toPath(), response.getOutputStream());
        }

    }

    private void initIndexFile() {
        try {
            indexPath = Files.createTempFile("index", ".html");
            indexPath.toFile().deleteOnExit();

            try (BufferedWriter writer = Files.newBufferedWriter(indexPath, Charset.forName("UTF8"))) {
                writeIndexHTML(writer);
            }

        } catch (final IOException e) {
            log.error("Cannot generate index.html", e);
        }
    }

    protected void writeIndexHTML(final BufferedWriter writer) throws IOException {
        writer.append("<!doctype html>");
        writer.newLine();
        writer.append("<html>");
        writer.newLine();
        writer.append("<head>");
        writer.newLine();
        writer.append("<!-- Powered by PonySDK http://www.ponysdk.com -->");
        writer.newLine();
        writer.append("<title>").append(application.getApplicationName()).append("</title>");
        writer.newLine();
        writer.append("<meta http-equiv=\"content-type\" content=\"text/html; charset=UTF-8\">");
        writer.newLine();
        for (final String m : meta) {
            writer.append("<meta ").append(m).append(">");
            writer.newLine();
        }

        addToMeta(writer);
        writer.newLine();
        writer.append("<script type=\"text/javascript\" src=\"ponyterminal/ponyterminal.nocache.js\"></script>");
        writer.newLine();

        for (final String style : stylesheets) {
            final String contentType = fileTypeMap.getContentType(style);
            if (!contentType.equals("text/css"))
                writer.append("<link rel=\"stylesheet/less\" type=\"").append(contentType).append("\" href=\"").append(style)
                        .append("\">");
            else
                writer.append("<link rel=\"stylesheet\" type=\"").append(contentType).append("\" href=\"").append(style).append("\">");
            writer.newLine();
        }

        for (final String script : javascripts) {
            writer.append("<script type=\"text/javascript\" src=\"").append(script).append("\"></script>");
            writer.newLine();
        }

        addToHeader(writer);
        writer.newLine();
        writer.append("</head>");
        writer.newLine();
        writer.append("<body>");
        writer.newLine();
        addHistoryIFrame(writer);
        writer.newLine();
        addLoading(writer);
        writer.newLine();
        addNoScript(writer);
        writer.newLine();
        addToBody(writer);
        writer.newLine();
        writer.append("</body>");
        writer.newLine();
        writer.append("</html>");
        writer.newLine();
    }

    protected void addHistoryIFrame(final BufferedWriter writer) throws IOException {
        writer.append(
                "<iframe src=\"javascript:''\" id=\"__gwt_historyFrame\" tabIndex='-1' style=\"position:absolute;width:0;height:0;border:0\"></iframe>");
    }

    protected void addLoading(final BufferedWriter writer) throws IOException {
        writer.append("<div id=\"loading\">Loading ").append(application.getApplicationName()).append("...</div>");
    }

    protected void addNoScript(final BufferedWriter writer) throws IOException {
        writer.append("<noscript>");
        writer.newLine();
        writer.append(
                "<div style=\"width: 22em; position: absolute; left: 50%; margin-left: -11em; color: red; background-color: white; border: 1px solid red; padding: 4px;\">");
        writer.newLine();
        writer.append("Your web browser must have JavaScript enabled");
        writer.newLine();
        writer.append("in order for this application to display correctly.");
        writer.newLine();
        writer.append("</div>");
        writer.newLine();
        writer.append("</noscript>");
    }

    protected void addToMeta(final BufferedWriter writer) {
    }

    protected void addToHeader(final BufferedWriter writer) {
    }

    protected void addToBody(final BufferedWriter writer) {
    }

    public void addStylesheets(final List<String> stylesheetPaths) {
        stylesheets.addAll(stylesheetPaths);
    }

    public void addStylesheet(final String stylesheetPath) {
        stylesheets.add(stylesheetPath);
    }

    public void addMetas(final List<String> m) {
        meta.addAll(m);
    }

    public void addMeta(final String m) {
        meta.add(m);
    }

    public void addJavascripts(final List<String> javascriptPaths) {
        javascripts.addAll(javascriptPaths);
    }

    public void addJavascript(final String javascriptPath) {
        javascripts.add(javascriptPath);
    }

    public void addAddOn(final String signature, final String factory) {
        addons.put(signature, factory);
    }

    public void setApplication(final ApplicationManagerOption application) {
        this.application = application;
    }
}
