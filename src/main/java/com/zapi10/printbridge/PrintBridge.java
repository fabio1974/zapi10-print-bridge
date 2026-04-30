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

public final class PrintBridge {

    private static final String VERSION = "1.0.4";
    private static final int PORT = parseEnvInt("PRINT_BRIDGE_PORT", 9100);
    private static final int HTTP_PORT = parseEnvInt("PRINT_BRIDGE_HTTP_PORT", 9101);
    private static final String PRINTER_NAME = System.getenv("PRINT_BRIDGE_PRINTER");
    private static final int IDLE_TIMEOUT_MS = parseEnvInt("PRINT_BRIDGE_IDLE_TIMEOUT_MS", 2000);

    public static void main(String[] args) {
        Logger.init();
        Logger.info("=== Zapi10 Print Bridge v" + VERSION + " ===");
        Logger.info("Java " + System.getProperty("java.version") + " | " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        Logger.info("Log file: " + Logger.currentFile());
        Logger.info("Listening on TCP port " + PORT + " (raw ESC/POS) | HTTP port " + HTTP_PORT + " (browser)");
        PrintService chosen = resolvePrinter();
        if (chosen == null) {
            Logger.warn("Nenhuma impressora encontrada no SO. Conecte uma e configure como padrão.");
        } else {
            Logger.info("Impressora alvo: '" + chosen.getName() + "'"
                    + (PRINTER_NAME == null ? " (default do SO)" : " (env PRINT_BRIDGE_PRINTER)"));
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
     * HTTP server pra o FE (browsers) chamarem direto.
     * Endpoints:
     *   GET  /health  → 200 {"ok":true,"version":"1.0.4","printer":"..."}
     *   POST /print   → recebe bytes ESC/POS, encaminha pro spooler
     *   OPTIONS *     → CORS preflight
     */
    private static void startHttpServer() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
            http.createContext("/health", PrintBridge::handleHealth);
            http.createContext("/print", PrintBridge::handlePrintHttp);
            http.setExecutor(null);
            http.start();
            Logger.info("HTTP server pronto em http://localhost:" + HTTP_PORT + " (browser/FE)");
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

    private static PrintService resolvePrinter() {
        if (PRINTER_NAME != null && !PRINTER_NAME.isBlank()) {
            for (PrintService ps : PrintServiceLookup.lookupPrintServices(null, null)) {
                if (PRINTER_NAME.equalsIgnoreCase(ps.getName())) {
                    return ps;
                }
            }
            Logger.warn("PRINT_BRIDGE_PRINTER='" + PRINTER_NAME + "' não encontrado — caindo no default");
        }
        return PrintServiceLookup.lookupDefaultPrintService();
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
