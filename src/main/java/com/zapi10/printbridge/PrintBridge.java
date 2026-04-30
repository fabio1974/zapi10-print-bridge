package com.zapi10.printbridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.print.DocFlavor;
import javax.print.PrintException;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

public final class PrintBridge {

    private static final String VERSION = "1.0.5";
    private static final int PORT = parseEnvInt("PRINT_BRIDGE_PORT", 9100);
    private static final int HTTP_PORT = parseEnvInt("PRINT_BRIDGE_HTTP_PORT", 9101);
    private static final String PRINTER_NAME_ENV = System.getenv("PRINT_BRIDGE_PRINTER");
    private static final int IDLE_TIMEOUT_MS = parseEnvInt("PRINT_BRIDGE_IDLE_TIMEOUT_MS", 2000);

    public static void main(String[] args) {
        Logger.init();
        Logger.info("=== Zapi10 Print Bridge v" + VERSION + " ===");
        Logger.info("Java " + System.getProperty("java.version") + " | " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        Logger.info("Log file: " + Logger.currentFile());
        Config.init();
        Logger.info("Config file: " + Config.currentFile());
        Logger.info("Listening on TCP port " + PORT + " (raw ESC/POS) | HTTP port " + HTTP_PORT + " (browser)");
        PrintService chosen = resolvePrinter();
        if (chosen == null) {
            Logger.warn("Nenhuma impressora encontrada no SO. Conecte uma e abra http://localhost:" + HTTP_PORT + " pra escolher.");
        } else {
            Logger.info("Impressora alvo: '" + chosen.getName() + "' (" + printerSourceLabel() + ")");
        }
        listAvailablePrinters();

        startHttpServer();

        try (ServerSocket server = new ServerSocket(PORT)) {
            while (true) {
                Socket socket = server.accept();
                Thread.startVirtualThread(() -> handleClient(socket));
            }
        } catch (IOException e) {
            Logger.error("Falhou ao abrir porta " + PORT + ": " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * HTTP server pra o FE (browsers) chamarem direto + UI de setup pro técnico instalador.
     * Endpoints:
     *   GET  /          → HTML página de setup (PT-BR, leigo-friendly)
     *   GET  /health    → 200 {"ok":true,"version":"...","printer":"..."}
     *   GET  /printers  → 200 {"current":"...","source":"...","available":[...]}
     *   GET  /config    → 200 {"printer":"..."}
     *   POST /config    → body {"printer":"..."} → salva persistente, retorna ok
     *   POST /print     → body bytes ESC/POS → encaminha pro spooler
     *   POST /test-print→ imprime ticket de teste padrão
     *   OPTIONS *       → CORS preflight
     */
    private static void startHttpServer() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
            http.createContext("/health", PrintBridge::handleHealth);
            http.createContext("/print", PrintBridge::handlePrintHttp);
            http.createContext("/printers", PrintBridge::handlePrinters);
            http.createContext("/config", PrintBridge::handleConfig);
            http.createContext("/test-print", PrintBridge::handleTestPrint);
            http.createContext("/", PrintBridge::handleRoot);
            http.setExecutor(null);
            http.start();
            Logger.info("HTTP server pronto em http://localhost:" + HTTP_PORT + " (browser/FE + UI de setup)");
        } catch (IOException e) {
            Logger.error("Falhou ao subir HTTP server na porta " + HTTP_PORT + ": " + e.getMessage());
        }
    }

    private static void addCorsHeaders(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");
        ex.getResponseHeaders().add("Access-Control-Max-Age", "3600");
    }

    private static void handleHealth(HttpExchange ex) throws IOException {
        addCorsHeaders(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return;
        }
        PrintService printer = resolvePrinter();
        String name = printer == null ? "" : printer.getName().replace("\"", "\\\"");
        String body = "{\"ok\":true,\"version\":\"" + VERSION + "\",\"printer\":\"" + name + "\","
                + "\"tcpPort\":" + PORT + ",\"httpPort\":" + HTTP_PORT + "}";
        byte[] bytes = body.getBytes("UTF-8");
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void handlePrintHttp(HttpExchange ex) throws IOException {
        addCorsHeaders(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            String msg = "{\"error\":\"Method not allowed. Use POST com bytes ESC/POS no body.\"}";
            byte[] b = msg.getBytes("UTF-8");
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(405, b.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(b); }
            return;
        }
        byte[] payload;
        try (InputStream in = ex.getRequestBody(); ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
            payload = buf.toByteArray();
        }
        String origin = ex.getRemoteAddress().toString();
        if (payload.length == 0) {
            String msg = "{\"error\":\"Body vazio. Envie bytes ESC/POS.\"}";
            byte[] b = msg.getBytes("UTF-8");
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(400, b.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(b); }
            return;
        }
        Logger.info("[HTTP] Recebidos " + payload.length + " bytes de " + origin);
        boolean ok = printRawSync(payload, "[HTTP] " + origin);
        String body = ok
                ? "{\"ok\":true,\"bytes\":" + payload.length + "}"
                : "{\"ok\":false,\"error\":\"Falha ao imprimir — verifique logs\"}";
        byte[] b = body.getBytes("UTF-8");
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(ok ? 200 : 500, b.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(b); }
    }

    private static void handleClient(Socket socket) {
        String remote = socket.getRemoteSocketAddress().toString();
        try (Socket s = socket; InputStream in = s.getInputStream()) {
            s.setSoTimeout(IDLE_TIMEOUT_MS);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            try {
                int n;
                while ((n = in.read(chunk)) != -1) {
                    buf.write(chunk, 0, n);
                }
            } catch (SocketTimeoutException ignored) {
                // cliente terminou (sem mais bytes por IDLE_TIMEOUT_MS)
            }
            byte[] payload = buf.toByteArray();
            if (payload.length == 0) {
                Logger.info("Conexão de " + remote + " sem dados — ignorada (provavelmente probe TCP)");
                return;
            }
            Logger.info("Recebidos " + payload.length + " bytes de " + remote);
            printRaw(payload, remote);
        } catch (IOException e) {
            Logger.error("Erro na conexão " + remote + ": " + e.getMessage());
        }
    }

    private static synchronized void printRaw(byte[] data, String origin) {
        printRawSync(data, origin);
    }

    /** Versão da printRaw que retorna sucesso/falha (usado pelo handler HTTP). */
    private static synchronized boolean printRawSync(byte[] data, String origin) {
        PrintService printer = resolvePrinter();
        if (printer == null) {
            Logger.error("Nenhuma impressora disponível — descartando " + data.length + " bytes (origem " + origin + ")");
            return false;
        }
        long t0 = System.currentTimeMillis();
        try {
            SimpleDoc doc = new SimpleDoc(data, DocFlavor.BYTE_ARRAY.AUTOSENSE, null);
            printer.createPrintJob().print(doc, null);
            long ms = System.currentTimeMillis() - t0;
            Logger.info("Impresso " + data.length + " bytes em '" + printer.getName() + "' (" + ms + "ms, origem " + origin + ")");
            return true;
        } catch (PrintException e) {
            Logger.error("Falha ao imprimir em '" + printer.getName() + "' (origem " + origin + "): " + e.getMessage());
            return false;
        }
    }

    /**
     * Resolve impressora alvo. Prioridade:
     *   1. env PRINT_BRIDGE_PRINTER (override forçado, útil pra dev/CI)
     *   2. config.properties salvo via UI (escolha do técnico/cliente)
     *   3. impressora padrão do SO (fallback "auto" — funciona out-of-the-box)
     */
    private static PrintService resolvePrinter() {
        if (PRINTER_NAME_ENV != null && !PRINTER_NAME_ENV.isBlank()) {
            PrintService p = findPrinterByName(PRINTER_NAME_ENV);
            if (p != null) return p;
            Logger.warn("PRINT_BRIDGE_PRINTER='" + PRINTER_NAME_ENV + "' não encontrado — tentando config/default");
        }
        String saved = Config.getPrinter();
        if (saved != null && !saved.isBlank()) {
            PrintService p = findPrinterByName(saved);
            if (p != null) return p;
            Logger.warn("Impressora salva no config '" + saved + "' não encontrada no SO — caindo no default");
        }
        return PrintServiceLookup.lookupDefaultPrintService();
    }

    private static PrintService findPrinterByName(String name) {
        for (PrintService ps : PrintServiceLookup.lookupPrintServices(null, null)) {
            if (name.equalsIgnoreCase(ps.getName())) return ps;
        }
        return null;
    }

    /** Rótulo curto pra log/UI explicando de onde veio a impressora resolvida. */
    private static String printerSourceLabel() {
        if (PRINTER_NAME_ENV != null && !PRINTER_NAME_ENV.isBlank()
                && findPrinterByName(PRINTER_NAME_ENV) != null) return "env PRINT_BRIDGE_PRINTER";
        String saved = Config.getPrinter();
        if (saved != null && !saved.isBlank() && findPrinterByName(saved) != null) return "config salvo";
        return "default do SO";
    }

    private static void listAvailablePrinters() {
        PrintService[] all = PrintServiceLookup.lookupPrintServices(null, null);
        if (all.length == 0) {
            Logger.info("Impressoras disponíveis: (nenhuma)");
            return;
        }
        StringBuilder sb = new StringBuilder("Impressoras disponíveis (").append(all.length).append("):");
        for (PrintService ps : all) {
            sb.append("\n  - ").append(ps.getName());
        }
        Logger.info(sb.toString());
    }

    private static int parseEnvInt(String name, int fallback) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ============================================================
    // Setup UI handlers (página HTML + endpoints JSON)
    // ============================================================

    private static void handleRoot(HttpExchange ex) throws IOException {
        addCorsHeaders(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            byte[] b = "{\"error\":\"Not found\"}".getBytes("UTF-8");
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(404, b.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(b); }
            return;
        }
        byte[] html = SETUP_PAGE_HTML.getBytes("UTF-8");
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, html.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(html); }
    }

    private static void handlePrinters(HttpExchange ex) throws IOException {
        addCorsHeaders(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return;
        }
        PrintService current = resolvePrinter();
        String currentName = current == null ? "" : current.getName();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"current\":\"").append(jsonEscape(currentName)).append("\",");
        sb.append("\"source\":\"").append(jsonEscape(printerSourceLabel())).append("\",");
        sb.append("\"savedConfig\":\"").append(jsonEscape(Config.getPrinter() == null ? "" : Config.getPrinter())).append("\",");
        sb.append("\"available\":[");
        PrintService[] all = PrintServiceLookup.lookupPrintServices(null, null);
        for (int i = 0; i < all.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(jsonEscape(all[i].getName())).append("\"");
        }
        sb.append("]}");
        byte[] body = sb.toString().getBytes("UTF-8");
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(body); }
    }

    private static void handleConfig(HttpExchange ex) throws IOException {
        addCorsHeaders(ex);
        String method = ex.getRequestMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            ex.sendResponseHeaders(204, -1);
            return;
        }
        if ("GET".equalsIgnoreCase(method)) {
            String saved = Config.getPrinter();
            String body = "{\"printer\":\"" + jsonEscape(saved == null ? "" : saved) + "\"}";
            byte[] b = body.getBytes("UTF-8");
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(b); }
            return;
        }
        if ("POST".equalsIgnoreCase(method)) {
            String bodyStr = readRequestBody(ex);
            // parse minimalista: extrai valor do campo "printer" (string JSON)
            String printer = extractJsonString(bodyStr, "printer");
            if (printer == null) {
                String msg = "{\"ok\":false,\"error\":\"Body inválido. Esperado: {\\\"printer\\\":\\\"nome\\\"}\"}";
                byte[] b = msg.getBytes("UTF-8");
                ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                ex.sendResponseHeaders(400, b.length);
                try (OutputStream out = ex.getResponseBody()) { out.write(b); }
                return;
            }
            try {
                Config.setPrinter(printer);
            } catch (IOException ioe) {
                Logger.error("Falha ao salvar config: " + ioe.getMessage());
                String msg = "{\"ok\":false,\"error\":\"Falha ao salvar: " + jsonEscape(ioe.getMessage()) + "\"}";
                byte[] b = msg.getBytes("UTF-8");
                ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                ex.sendResponseHeaders(500, b.length);
                try (OutputStream out = ex.getResponseBody()) { out.write(b); }
                return;
            }
            Logger.info("Config: impressora alvo trocada via UI para '" + printer + "'");
            String body = "{\"ok\":true,\"printer\":\"" + jsonEscape(printer) + "\"}";
            byte[] b = body.getBytes("UTF-8");
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(b); }
            return;
        }
        String msg = "{\"error\":\"Method not allowed\"}";
        byte[] b = msg.getBytes("UTF-8");
        ex.sendResponseHeaders(405, b.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(b); }
    }

    private static void handleTestPrint(HttpExchange ex) throws IOException {
        addCorsHeaders(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            String msg = "{\"error\":\"Use POST\"}";
            byte[] b = msg.getBytes("UTF-8");
            ex.sendResponseHeaders(405, b.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(b); }
            return;
        }
        byte[] payload = buildTestTicket();
        boolean ok = printRawSync(payload, "[UI] test-print");
        String body = ok
                ? "{\"ok\":true,\"bytes\":" + payload.length + "}"
                : "{\"ok\":false,\"error\":\"Falha ao imprimir — verifique se a impressora está ligada e com papel.\"}";
        byte[] b = body.getBytes("UTF-8");
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(ok ? 200 : 500, b.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(b); }
    }

    /** Ticket ESC/POS de teste — comandos compatíveis com a maioria das térmicas 58/80mm. */
    private static byte[] buildTestTicket() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(new byte[]{0x1B, 0x40}); // ESC @ — init
            out.write(new byte[]{0x1B, 0x61, 0x01}); // ESC a 1 — center
            out.write(new byte[]{0x1B, 0x21, 0x30}); // ESC ! 0x30 — double w/h
            out.write("Zapi10\n".getBytes("UTF-8"));
            out.write(new byte[]{0x1B, 0x21, 0x00}); // reset
            out.write("Print Bridge — Teste\n".getBytes("UTF-8"));
            out.write("--------------------------------\n".getBytes("UTF-8"));
            out.write(new byte[]{0x1B, 0x61, 0x00}); // left
            PrintService p = resolvePrinter();
            String pname = p == null ? "(nenhuma)" : p.getName();
            out.write(("Impressora: " + pname + "\n").getBytes("UTF-8"));
            out.write(("Versao: " + VERSION + "\n").getBytes("UTF-8"));
            out.write(("Data: " + LocalDateTime.now().withNano(0) + "\n").getBytes("UTF-8"));
            out.write("\nSe voce esta lendo isso,\nesta tudo funcionando. :)\n".getBytes("UTF-8"));
            out.write(new byte[]{0x1B, 0x64, 0x05}); // ESC d 5 — feed 5 lines
            out.write(new byte[]{0x1D, 0x56, 0x00}); // GS V 0 — full cut (ignorado se não suportar)
        } catch (IOException ignored) {}
        return out.toByteArray();
    }

    private static String readRequestBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody(); ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
            return buf.toString("UTF-8");
        }
    }

    /** Escape minimalista de string pra JSON (não cobre todos casos exoticos, mas serve pra nomes de impressora). */
    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Extrai valor de string de campo top-level de JSON achatado, ex: {"printer":"Foo \"Bar\""}.
     * Retorna null se campo não encontrado. Suficiente pra body simples da UI; não é parser geral.
     */
    private static String extractJsonString(String body, String field) {
        if (body == null) return null;
        String key = "\"" + field + "\"";
        int k = body.indexOf(key);
        if (k < 0) return null;
        int colon = body.indexOf(':', k + key.length());
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;
        if (i >= body.length() || body.charAt(i) != '"') return null;
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char n = body.charAt(i + 1);
                switch (n) {
                    case '"':  sb.append('"');  i += 2; continue;
                    case '\\': sb.append('\\'); i += 2; continue;
                    case 'n':  sb.append('\n'); i += 2; continue;
                    case 'r':  sb.append('\r'); i += 2; continue;
                    case 't':  sb.append('\t'); i += 2; continue;
                    default: sb.append(n); i += 2; continue;
                }
            }
            if (c == '"') return sb.toString();
            sb.append(c);
            i++;
        }
        return null; // string não fechada
    }

    /** Página HTML de setup — embarcada como string pra manter zero recursos externos. */
    private static final String SETUP_PAGE_HTML =
        "<!doctype html>\n" +
        "<html lang=\"pt-BR\"><head><meta charset=\"utf-8\">\n" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n" +
        "<title>Zapi10 Print Bridge — Configuração</title>\n" +
        "<style>\n" +
        "*{box-sizing:border-box}body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;margin:0;background:#f4f6fa;color:#1a1f2e;line-height:1.5}\n" +
        ".wrap{max-width:560px;margin:0 auto;padding:24px}\n" +
        "h1{font-size:24px;margin:0 0 4px}\n" +
        ".sub{color:#5b6477;font-size:14px;margin-bottom:24px}\n" +
        ".card{background:#fff;border-radius:12px;padding:20px;box-shadow:0 1px 3px rgba(0,0,0,.06);margin-bottom:16px}\n" +
        ".row{margin-bottom:16px}\n" +
        ".row:last-child{margin-bottom:0}\n" +
        "label{display:block;font-weight:600;margin-bottom:8px;font-size:14px}\n" +
        "select,button{font:inherit;padding:12px 16px;border-radius:8px;border:1px solid #d4d9e4;width:100%}\n" +
        "select{background:#fff}\n" +
        "button{cursor:pointer;border:none;font-weight:600;font-size:16px;margin-top:8px}\n" +
        ".btn-primary{background:#2563eb;color:#fff}\n" +
        ".btn-primary:hover{background:#1d4ed8}\n" +
        ".btn-secondary{background:#e5e9f2;color:#1a1f2e}\n" +
        ".btn-secondary:hover{background:#d4d9e4}\n" +
        ".badge{display:inline-block;padding:4px 10px;border-radius:6px;font-size:12px;font-weight:600;margin-left:8px}\n" +
        ".badge-ok{background:#dcfce7;color:#166534}\n" +
        ".badge-warn{background:#fef3c7;color:#92400e}\n" +
        ".badge-err{background:#fee2e2;color:#991b1b}\n" +
        ".current{font-size:18px;font-weight:600;margin:8px 0}\n" +
        ".source{font-size:13px;color:#5b6477}\n" +
        ".msg{padding:12px 16px;border-radius:8px;margin-top:16px;font-size:14px;display:none}\n" +
        ".msg.show{display:block}\n" +
        ".msg-ok{background:#dcfce7;color:#166534}\n" +
        ".msg-err{background:#fee2e2;color:#991b1b}\n" +
        ".msg-info{background:#dbeafe;color:#1e40af}\n" +
        ".footer{text-align:center;font-size:12px;color:#5b6477;margin-top:24px}\n" +
        "</style></head><body><div class=\"wrap\">\n" +
        "<h1>Zapi10 Print Bridge</h1>\n" +
        "<p class=\"sub\">Configuração da impressora térmica</p>\n" +
        "<div class=\"card\">\n" +
        "  <div class=\"row\">\n" +
        "    <label>Impressora ativa <span id=\"badge\" class=\"badge\">…</span></label>\n" +
        "    <div class=\"current\" id=\"current\">carregando…</div>\n" +
        "    <div class=\"source\" id=\"source\"></div>\n" +
        "  </div>\n" +
        "  <div class=\"row\">\n" +
        "    <label for=\"sel\">Trocar impressora</label>\n" +
        "    <select id=\"sel\"></select>\n" +
        "    <button class=\"btn-primary\" id=\"save\">Salvar</button>\n" +
        "  </div>\n" +
        "  <div class=\"row\">\n" +
        "    <button class=\"btn-secondary\" id=\"test\">Imprimir página de teste</button>\n" +
        "  </div>\n" +
        "  <div id=\"msg\" class=\"msg\"></div>\n" +
        "</div>\n" +
        "<div class=\"footer\">Zapi10 Print Bridge v" + VERSION + " — http://localhost:" + HTTP_PORT + "</div>\n" +
        "</div><script>\n" +
        "const $=id=>document.getElementById(id);\n" +
        "function showMsg(text,kind){const m=$('msg');m.textContent=text;m.className='msg show msg-'+kind;}\n" +
        "function hideMsg(){$('msg').className='msg';}\n" +
        "async function load(){\n" +
        "  try{\n" +
        "    const r=await fetch('/printers');const d=await r.json();\n" +
        "    $('current').textContent=d.current||'(nenhuma impressora detectada)';\n" +
        "    $('source').textContent='Origem: '+d.source;\n" +
        "    const b=$('badge');\n" +
        "    if(!d.current){b.textContent='SEM IMPRESSORA';b.className='badge badge-err';}\n" +
        "    else if(d.source==='default do SO'){b.textContent='AUTO';b.className='badge badge-ok';}\n" +
        "    else{b.textContent='CONFIGURADA';b.className='badge badge-ok';}\n" +
        "    const sel=$('sel');sel.innerHTML='';\n" +
        "    if(d.available.length===0){const o=document.createElement('option');o.textContent='Nenhuma impressora encontrada no Windows';o.disabled=true;sel.appendChild(o);}\n" +
        "    d.available.forEach(name=>{const o=document.createElement('option');o.value=name;o.textContent=name;if(name===d.current)o.selected=true;sel.appendChild(o);});\n" +
        "  }catch(e){showMsg('Erro ao carregar impressoras: '+e.message,'err');}\n" +
        "}\n" +
        "$('save').onclick=async()=>{\n" +
        "  hideMsg();const printer=$('sel').value;if(!printer){showMsg('Escolha uma impressora primeiro.','err');return;}\n" +
        "  $('save').disabled=true;\n" +
        "  try{\n" +
        "    const r=await fetch('/config',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({printer})});\n" +
        "    const d=await r.json();\n" +
        "    if(d.ok){showMsg('Salvo. Impressora ativa agora: '+d.printer,'ok');await load();}\n" +
        "    else{showMsg('Erro: '+(d.error||'desconhecido'),'err');}\n" +
        "  }catch(e){showMsg('Erro de rede: '+e.message,'err');}\n" +
        "  finally{$('save').disabled=false;}\n" +
        "};\n" +
        "$('test').onclick=async()=>{\n" +
        "  hideMsg();$('test').disabled=true;showMsg('Enviando para a impressora…','info');\n" +
        "  try{\n" +
        "    const r=await fetch('/test-print',{method:'POST'});const d=await r.json();\n" +
        "    if(d.ok)showMsg('Enviado. Verifique se saiu o ticket de teste na impressora.','ok');\n" +
        "    else showMsg('Falhou: '+(d.error||'desconhecido'),'err');\n" +
        "  }catch(e){showMsg('Erro de rede: '+e.message,'err');}\n" +
        "  finally{$('test').disabled=false;}\n" +
        "};\n" +
        "load();\n" +
        "</script></body></html>";

    // ============================================================
    // Persistência da config (impressora escolhida via UI)
    // ============================================================

    /**
     * Config persistida em arquivo properties (UTF-8) na mesma pasta dos logs (parent dir).
     * Único campo por enquanto: 'printer' (nome exato da impressora no SO).
     * Se faltar/corrompido: tratamos como vazio — bridge cai pro default do SO.
     */
    static final class Config {
        private static final String FILE_NAME = "print-bridge.properties";
        private static volatile Path file;
        private static volatile String printer; // null/vazio = sem override

        static synchronized void init() {
            try {
                file = resolveConfigFile();
                if (Files.exists(file)) {
                    Properties p = new Properties();
                    try (var r = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
                        p.load(r);
                    }
                    String v = p.getProperty("printer");
                    if (v != null && !v.isBlank()) printer = v.trim();
                }
            } catch (Exception e) {
                System.err.println("[CONFIG] Falha ao carregar " + file + ": " + e.getMessage());
            }
        }

        static String getPrinter() { return printer; }

        static synchronized void setPrinter(String name) throws IOException {
            String v = name == null ? "" : name.trim();
            printer = v.isBlank() ? null : v;
            if (file == null) file = resolveConfigFile();
            Files.createDirectories(file.getParent());
            Properties p = new Properties();
            if (printer != null) p.setProperty("printer", printer);
            try (var w = Files.newBufferedWriter(file, java.nio.charset.StandardCharsets.UTF_8)) {
                p.store(w, "Zapi10 Print Bridge config — gerenciado pela UI em http://localhost:" + HTTP_PORT);
            }
        }

        static String currentFile() {
            return file == null ? "(não inicializado)" : file.toString();
        }

        private static Path resolveConfigFile() {
            String env = System.getenv("PRINT_BRIDGE_CONFIG_DIR");
            if (env != null && !env.isBlank()) return Paths.get(env).resolve(FILE_NAME);
            // mesma pasta dos logs (parent de logs/) — no instalador Windows é %BASE%\
            try {
                CodeSource cs = PrintBridge.class.getProtectionDomain().getCodeSource();
                if (cs != null && cs.getLocation() != null) {
                    Path jar = Paths.get(cs.getLocation().toURI());
                    Path parent = Files.isDirectory(jar) ? jar : jar.getParent();
                    if (parent != null) return parent.resolve(FILE_NAME);
                }
            } catch (Exception ignored) {}
            return Paths.get(FILE_NAME);
        }

        private Config() {}
    }

    /**
     * Logger simples com saída em stdout + arquivo, com rotação diária e retenção de 7 dias.
     * Sem dependências externas (zero libs no jar).
     *
     * Path do log:
     *   1) env PRINT_BRIDGE_LOG_DIR (se setado)
     *   2) <diretório do jar>/logs/
     *   3) ./logs/ (cwd) — fallback
     */
    static final class Logger {
        private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        private static final int RETENTION_DAYS = 7;
        private static final long MAX_FILE_BYTES = 10L * 1024 * 1024; // 10MB hard cap

        private static Path logDir;
        private static Path currentFile;
        private static String currentDay;
        private static java.io.PrintWriter writer;

        static synchronized void init() {
            try {
                logDir = resolveLogDir();
                Files.createDirectories(logDir);
                openForToday();
                cleanOldLogs();
            } catch (Exception e) {
                System.err.println("[LOGGER] Falha ao iniciar log em arquivo: " + e.getMessage());
                writer = null;
            }
        }

        static String currentFile() {
            return currentFile == null ? "(stdout only)" : currentFile.toString();
        }

        static synchronized void info(String msg)  { write("INFO ", msg); }
        static synchronized void warn(String msg)  { write("WARN ", msg); }
        static synchronized void error(String msg) { write("ERROR", msg); }

        private static void write(String level, String msg) {
            String today = LocalDateTime.now().format(DAY);
            if (!today.equals(currentDay)) {
                rotate();
            } else if (writer != null && currentFile != null && fileTooBig()) {
                rotate();
            }
            String line = "[" + LocalDateTime.now().format(TS) + "] [" + level + "] " + msg;
            System.out.println(line);
            if (writer != null) {
                writer.println(line);
                writer.flush();
            }
        }

        private static boolean fileTooBig() {
            try {
                return Files.size(currentFile) > MAX_FILE_BYTES;
            } catch (IOException e) {
                return false;
            }
        }

        private static void openForToday() throws IOException {
            currentDay = LocalDateTime.now().format(DAY);
            currentFile = logDir.resolve("print-bridge-" + currentDay + ".log");
            if (writer != null) try { writer.close(); } catch (Exception ignored) {}
            writer = new java.io.PrintWriter(new java.io.FileWriter(currentFile.toFile(), true), true);
        }

        private static void rotate() {
            try {
                openForToday();
                cleanOldLogs();
            } catch (IOException e) {
                System.err.println("[LOGGER] Falha ao rotacionar: " + e.getMessage());
            }
        }

        private static void cleanOldLogs() {
            try (var stream = Files.list(logDir)) {
                long cutoff = System.currentTimeMillis() - (RETENTION_DAYS * 24L * 60 * 60 * 1000);
                stream
                    .filter(p -> p.getFileName().toString().startsWith("print-bridge-") && p.getFileName().toString().endsWith(".log"))
                    .filter(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis() < cutoff; }
                        catch (IOException e) { return false; }
                    })
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            } catch (IOException ignored) {}
        }

        private static Path resolveLogDir() {
            String env = System.getenv("PRINT_BRIDGE_LOG_DIR");
            if (env != null && !env.isBlank()) return Paths.get(env);

            try {
                CodeSource cs = PrintBridge.class.getProtectionDomain().getCodeSource();
                if (cs != null && cs.getLocation() != null) {
                    Path jar = Paths.get(cs.getLocation().toURI());
                    Path parent = Files.isDirectory(jar) ? jar : jar.getParent();
                    if (parent != null) return parent.resolve("logs");
                }
            } catch (Exception ignored) {}
            return Paths.get("logs");
        }

        private Logger() {}
    }

    private PrintBridge() {}
}
