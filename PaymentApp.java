import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Online Payment Processing System.
 *
 * Run with: javac PaymentApp.java && java PaymentApp
 */
public class PaymentApp {

    public static void main(String[] args) throws Exception {
        int startPort = 8080;
        String envPort = System.getenv("PORT");
        if (envPort != null && envPort.matches("[0-9]{2,5}")) {
            startPort = Integer.parseInt(envPort);
        }

        PaymentGateway gateway = new PaymentGateway();
        HttpServer server = null;
        int port = startPort;
        for (int candidate = startPort; candidate <= startPort + 20; candidate++) {
            try {
                server = HttpServer.create(new InetSocketAddress(candidate), 0);
                port = candidate;
                break;
            } catch (IOException busyPort) {
                // try the next port
            }
        }
        if (server == null) {
            System.out.println("Could not start the server near port " + startPort + ".");
            return;
        }

        final int activePort = port;
        server.createContext("/", exchange -> {
            try {
                route(exchange, gateway);
            } catch (Exception error) {
                error.printStackTrace();
                try {
                    sendJson(exchange, 500, "{\"success\":false,\"message\":\"Server error: "
                            + Json.quote(error.getMessage() == null ? "unknown" : error.getMessage()) + "\"}");
                } catch (IOException ignored) {
                    // response already committed
                }
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println();
        System.out.println("  PayFlow - Online Payment Processing System");
        System.out.println("  ------------------------------------------");
        System.out.println("  Server running at: http://localhost:" + activePort);
        System.out.println("  API base:          http://localhost:" + activePort + "/api/state");
        System.out.println("  Press Ctrl+C to stop.");
        System.out.println();
    }

    private static void route(HttpExchange exchange, PaymentGateway gateway) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);

        if ("OPTIONS".equals(method)) {
            exchange.getResponseHeaders().add("Allow", "GET, POST, OPTIONS");
            send(exchange, 204, "text/plain; charset=utf-8", new byte[0]);
            return;
        }

        if (path.equals("/") || path.equals("/index.html")) {
            if (!"GET".equals(method)) {
                sendJson(exchange, 405, "{\"success\":false,\"message\":\"Method not allowed.\"}");
                return;
            }
            send(exchange, 200, "text/html; charset=utf-8", uiPage().getBytes(StandardCharsets.UTF_8));
            return;
        }

        if (path.equals("/favicon.ico")) {
            send(exchange, 204, "text/plain; charset=utf-8", new byte[0]);
            return;
        }

        if (path.equals("/api/state")) {
            if (!"GET".equals(method)) {
                sendJson(exchange, 405, "{\"success\":false,\"message\":\"Method not allowed.\"}");
                return;
            }
            sendJson(exchange, 200, gateway.stateJson());
            return;
        }

        if (path.equals("/api/methods")) {
            if (!"GET".equals(method)) {
                sendJson(exchange, 405, "{\"success\":false,\"message\":\"Method not allowed.\"}");
                return;
            }
            sendJson(exchange, 200, METHODS_JSON);
            return;
        }

        if (path.equals("/api/pay")) {
            if (!"POST".equals(method)) {
                sendJson(exchange, 405, "{\"success\":false,\"message\":\"Use POST for this endpoint.\"}");
                return;
            }
            Map<String, Object> body = readJson(exchange);
            String methodKey = text(body.get("method")).toLowerCase(Locale.ROOT);
            double amount = amountOf(body.get("amount"));
            Map<String, String> details = stringMap(body.get("details"));

            Payment payment = createPayment(methodKey, amount, details);
            if (payment == null) {
                sendJson(exchange, 400, "{\"success\":false,\"message\":\"Choose a valid payment method: credit_card, paypal or bank_transfer.\"}");
                return;
            }

            Transaction transaction = gateway.pay(payment);
            boolean accepted = !"FAILED".equals(transaction.getStatus());
            String payload = "{\"success\":" + accepted
                    + ",\"message\":" + Json.quote(transaction.getMessage())
                    + ",\"transaction\":" + transaction.toJson() + "}";
            sendJson(exchange, 200, payload);
            return;
        }

        if (path.equals("/api/refund")) {
            if (!"POST".equals(method)) {
                sendJson(exchange, 405, "{\"success\":false,\"message\":\"Use POST for this endpoint.\"}");
                return;
            }
            Map<String, Object> body = readJson(exchange);
            String transactionId = text(body.get("transactionId")).trim();
            double amount = amountOf(body.get("amount"));

            PaymentGateway.RefundOutcome outcome = gateway.refund(transactionId, amount);
            String payload = "{\"success\":" + outcome.success()
                    + ",\"message\":" + Json.quote(outcome.message())
                    + ",\"transaction\":" + (outcome.transaction() == null ? "null" : outcome.transaction().toJson()) + "}";
            sendJson(exchange, 200, payload);
            return;
        }

        sendJson(exchange, 404, "{\"success\":false,\"message\":\"Endpoint not found.\"}");
    }

    private static Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        if (raw.length == 0) {
            return Collections.emptyMap();
        }
        Object parsed;
        try {
            parsed = Json.parse(new String(raw, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException invalid) {
            return Collections.emptyMap();
        }
        return objectMap(parsed);
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    static Payment createPayment(String methodKey, double amount, Map<String, String> details) {
        switch (methodKey == null ? "" : methodKey) {
            case "credit_card":
                return new CreditCardPayment(amount, details);
            case "paypal":
                return new PayPalPayment(amount, details);
            case "bank_transfer":
                return new BankTransferPayment(amount, details);
            default:
                return null;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> objectMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    static Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : objectMap(value).entrySet()) {
            Object item = entry.getValue();
            result.put(entry.getKey(), item == null ? "" : String.valueOf(item));
        }
        return result;
    }

    static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static double amountOf(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble(((String) value).trim());
            } catch (NumberFormatException invalid) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    static String money(double value) {
        return "\u20B9" + String.format(Locale.forLanguageTag("en-IN"), "%,.2f", value);
    }

    static String uiPage() {
        return new StringBuilder()
                .append(UI_HEAD)
                .append(UI_BODY)
                .append(UI_SCRIPT_ONE)
                .append(UI_SCRIPT_TWO)
                .toString();
    }

    static final String METHODS_JSON = "{\"methods\":["
            + "{\"key\":\"credit_card\",\"name\":\"Credit Card\",\"description\":\"Visa, Mastercard, Amex & RuPay\",\"tag\":\"Instant authorization\"},"
            + "{\"key\":\"paypal\",\"name\":\"PayPal\",\"description\":\"Pay with your PayPal balance\",\"tag\":\"Instant settlement\"},"
            + "{\"key\":\"bank_transfer\",\"name\":\"Bank Transfer\",\"description\":\"NEFT / IMPS to your bank account\",\"tag\":\"Confirmed in seconds\"}"
            + "]}";

    // UI_HEAD, UI_BODY and UI_SCRIPT are defined below (presentation layer only).
    static final String UI_HEAD = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#0B1226">
<title>PayFlow - Payment Management</title>
<link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'%3E%3Crect width='64' height='64' rx='16' fill='%235667FF'/%3E%3Cpath d='M35 10 20 36h10l-2 18 16-27H33z' fill='white'/%3E%3C/svg%3E">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;500;600;700;800&display=swap" rel="stylesheet">
<style>
*,*::before,*::after{box-sizing:border-box}
html,body{margin:0;padding:0}
body{
  --bg:#F3F5FB;--surface:#fff;--surface-2:#F8FAFF;--line:#E6EAF4;--line-strong:#D8DEEF;
  --ink:#0C1530;--ink-2:#38425C;--muted:#707B99;--muted-2:#98A1B9;
  --brand:#5468FF;--brand-2:#8B5CF6;--brand-soft:#EEF0FF;
  --green:#0FA968;--green-bg:#E8F8F0;--orange:#D97706;--orange-bg:#FEF3E2;
  --red:#E2483D;--red-bg:#FDECEA;--blue:#2F6FED;--blue-bg:#EAF1FE;
  --shadow-sm:0 1px 2px rgba(12,21,48,.04),0 6px 16px -8px rgba(12,21,48,.10);
  --shadow-lg:0 18px 40px -18px rgba(20,32,72,.28);
  --radius:18px;--font:'Plus Jakarta Sans',system-ui,-apple-system,'Segoe UI',sans-serif;
  font-family:var(--font);background:var(--bg);color:var(--ink);font-size:14px;line-height:1.5;
  letter-spacing:-.005em;min-height:100vh;
  background-image:radial-gradient(900px 520px at 88% -12%,rgba(84,104,255,.10),transparent 60%),radial-gradient(760px 440px at 6% -4%,rgba(139,92,246,.07),transparent 60%);
  background-attachment:fixed;-webkit-text-size-adjust:100%;
}
button,input,select,textarea{font:inherit;color:inherit}
button{cursor:pointer;background:none;border:0;padding:0}
a{color:inherit;text-decoration:none}
h1,h2,h3,h4,p{margin:0}
ul{list-style:none;margin:0;padding:0}
::selection{background:rgba(84,104,255,.22)}
textarea:focus,input:focus,select:focus{outline:none}
:focus-visible{outline:2px solid #7D8CFF;outline-offset:2px}
.ico{width:20px;height:20px;fill:none;stroke:currentColor;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round;flex:none}
.ico--fill{fill:currentColor;stroke:none}
.hidden{display:none!important}
.muted{color:var(--muted)}
.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap}
::-webkit-scrollbar{width:10px;height:10px}
::-webkit-scrollbar-thumb{background:#CFD6E8;border-radius:20px;border:3px solid transparent;background-clip:content-box}
::-webkit-scrollbar-track{background:transparent}

/* ---------- Shell ---------- */
.app{display:flex;min-height:100vh}
.sidebar{position:fixed;inset:0 auto 0 0;width:266px;z-index:60;display:flex;flex-direction:column;
  padding:22px 16px 18px;overflow-y:auto;color:#C3CCE4;
  background:linear-gradient(180deg,#0B1226 0%,#0D1834 56%,#0A1122 100%);
  box-shadow:1px 0 0 rgba(255,255,255,.04),18px 0 50px -30px rgba(5,10,30,.45)}
.brand{display:flex;align-items:center;gap:12px;padding:4px 8px 22px;position:relative}
.brand-mark{width:42px;height:42px;border-radius:13px;display:grid;place-items:center;color:#fff;flex:none;
  background:linear-gradient(135deg,#5B6CFF,#9B5CF6);box-shadow:0 12px 24px -10px rgba(91,108,255,.8);position:relative;overflow:hidden}
.brand-mark::after{content:"";position:absolute;inset:0;background:linear-gradient(140deg,rgba(255,255,255,.35),transparent 46%)}
.brand-mark svg{width:22px;height:22px;position:relative;z-index:1}
.brand-text strong{display:block;color:#fff;font-size:17px;font-weight:700;letter-spacing:-.025em;line-height:1.2}
.brand-text span{font-size:10.5px;color:#8290B4;letter-spacing:.03em}
.sidebar-close{display:none;margin-left:auto;color:#9AA7C8;width:32px;height:32px;border-radius:9px;align-items:center;justify-content:center}
.sidebar-close:hover{background:rgba(255,255,255,.08);color:#fff}
.nav{display:flex;flex-direction:column;gap:4px;flex:1}
.nav-label{font-size:10px;text-transform:uppercase;letter-spacing:.14em;color:#5F6B8C;margin:18px 12px 6px;font-weight:700}
.nav-label:first-child{margin-top:0}
.nav-item{display:flex;align-items:center;gap:12px;width:100%;padding:11px 12px;border-radius:11px;
  color:#AAB5D2;font-size:13px;font-weight:500;text-align:left;position:relative;transition:background .18s,color .18s}
.nav-item .ico{width:19px;height:19px;opacity:.88}
.nav-item:hover{background:rgba(255,255,255,.05);color:#fff}
.nav-item.active{color:#fff;background:linear-gradient(90deg,rgba(84,104,255,.24),rgba(139,92,246,.10));
  box-shadow:inset 0 0 0 1px rgba(120,135,255,.22)}
.nav-item.active::before{content:"";position:absolute;left:-16px;top:9px;bottom:9px;width:3px;border-radius:0 3px 3px 0;
  background:linear-gradient(180deg,#5B6CFF,#9B5CF6)}
.nav-count{margin-left:auto;background:rgba(255,255,255,.09);color:#DDE4F7;font-size:10.5px;font-weight:700;
  padding:2px 8px;border-radius:20px;min-width:24px;text-align:center}
.nav-item.active .nav-count{background:rgba(255,255,255,.16)}
.sidebar-bottom{margin-top:22px;display:grid;gap:12px}
.api-status{display:flex;gap:10px;align-items:center;padding:12px;border-radius:12px;
  background:rgba(255,255,255,.045);border:1px solid rgba(255,255,255,.07)}
.dot{width:8px;height:8px;border-radius:50%;background:#22C58B;box-shadow:0 0 0 4px rgba(34,197,139,.13);
  animation:pulseDot 2.6s ease-in-out infinite;flex:none}
.api-status strong{display:block;font-size:11.5px;color:#E7ECFA;font-weight:600}
.api-status span{font-size:10.5px;color:#7E8CB0;font-family:ui-monospace,'SFMono-Regular',monospace}
.support-card{position:relative;overflow:hidden;padding:16px;border-radius:14px;color:#C9D2EA;
  background:linear-gradient(150deg,rgba(84,104,255,.20),rgba(139,92,246,.10) 60%,rgba(255,255,255,.03));
  border:1px solid rgba(140,155,255,.20)}
.support-card::after{content:"";position:absolute;width:120px;height:120px;border-radius:50%;
  background:radial-gradient(circle,rgba(139,92,246,.45),transparent 70%);top:-40px;right:-40px}
.support-card .ico{width:19px;height:19px;color:#B9C3FF;margin-bottom:8px}
.support-card strong{display:block;color:#fff;font-size:12.5px;margin-bottom:4px}
.support-card p{font-size:11px;line-height:1.5;color:#9FADD2;margin-bottom:10px}
.link-btn{font-size:11.5px;font-weight:600;color:#C7CFFF;display:inline-flex;align-items:center;gap:5px}
.link-btn:hover{color:#fff}
.link-btn .ico{width:13px;height:13px;margin:0}

.shell{margin-left:266px;min-height:100vh;display:flex;flex-direction:column;min-width:0}
.topbar{position:sticky;top:0;z-index:40;display:flex;align-items:center;gap:16px;padding:14px 30px;
  background:rgba(255,255,255,.80);backdrop-filter:blur(18px) saturate(150%);
  border-bottom:1px solid rgba(226,231,243,.9)}
.menu-toggle{display:none}
.search-box{flex:1;max-width:430px;display:flex;align-items:center;gap:10px;height:42px;padding:0 12px;
  background:#F1F4FB;border:1px solid transparent;border-radius:12px;transition:.18s}
.search-box:focus-within{background:#fff;border-color:#BAC5FF;box-shadow:0 0 0 4px rgba(84,104,255,.12)}
.search-box .ico{width:17px;height:17px;color:#8B96B2}
.search-box input{flex:1;min-width:0;background:none;border:0;font-size:13px}
.search-box input::placeholder{color:#939DB8}
.search-box kbd{font-family:var(--font);font-size:10.5px;font-weight:700;color:#7B87A6;background:#fff;
  border:1px solid #E3E8F3;border-radius:6px;padding:1px 6px;box-shadow:0 1px 0 #E9EDF6}
.topbar-actions{margin-left:auto;display:flex;align-items:center;gap:12px;position:relative}
.env-pill{display:inline-flex;align-items:center;gap:7px;font-size:11px;font-weight:600;color:#0C8A5A;
  background:#E7F8F0;border:1px solid #C6EEDD;border-radius:999px;padding:6px 11px;white-space:nowrap}
.pulse{width:7px;height:7px;border-radius:50%;background:#16B981;box-shadow:0 0 0 3px rgba(22,185,129,.14);
  animation:pulseDot 2.6s ease-in-out infinite;flex:none}
.icon-btn{width:40px;height:40px;border-radius:11px;display:grid;place-items:center;color:#5A6684;
  background:#fff;border:1px solid var(--line);transition:.18s;position:relative;flex:none}
.icon-btn:hover{color:#243052;border-color:#CFD7EA;transform:translateY(-1px);box-shadow:var(--shadow-sm)}
.icon-btn .ico{width:18px;height:18px}
.badge-dot{position:absolute;top:9px;right:10px;width:7px;height:7px;border-radius:50%;background:#FF5A5F;
  border:2px solid #fff;display:none}
.badge-dot.show{display:block}
.profile{display:flex;align-items:center;gap:10px;padding:5px 8px 5px 5px;border-radius:13px;transition:.18s}
.profile:hover{background:#F3F6FD}
.avatar{width:38px;height:38px;border-radius:12px;display:grid;place-items:center;color:#fff;flex:none;
  background:linear-gradient(135deg,#26345E,#111B36);font-weight:700;font-size:12.5px;letter-spacing:.02em;
  box-shadow:inset 0 0 0 1px rgba(255,255,255,.10)}
.profile-meta{display:flex;flex-direction:column;line-height:1.2;text-align:left}
.profile-meta strong{font-size:12.5px;font-weight:600}
.profile-meta small{font-size:10.5px;color:var(--muted)}
.profile > .ico{width:15px;height:15px;color:#8B96B2}

.notif-panel,.profile-menu{position:absolute;top:52px;right:0;background:#fff;border:1px solid var(--line);
  border-radius:16px;box-shadow:0 26px 60px -22px rgba(16,24,64,.36);z-index:70;
  opacity:0;transform:translateY(-8px) scale(.98);pointer-events:none;transition:.2s cubic-bezier(.2,.8,.3,1)}
.notif-panel.open,.profile-menu.open{opacity:1;transform:none;pointer-events:auto}
.notif-panel{width:360px;max-width:calc(100vw - 28px);max-height:450px;display:flex;flex-direction:column;overflow:hidden}
.notif-head{display:flex;justify-content:space-between;align-items:center;padding:15px 18px;border-bottom:1px solid #F0F3FA}
.notif-head strong{font-size:13.5px}
.notif-head button{font-size:11px;font-weight:600;color:#5468FF}
.notif-list{overflow-y:auto;padding:6px}
.notif{display:flex;gap:11px;padding:11px;border-radius:11px;cursor:pointer;align-items:flex-start}
.notif:hover{background:#F7F9FE}
.notif .mini-icon{width:34px;height:34px;border-radius:10px}
.notif strong{font-size:12px;display:block;font-weight:600}
.notif small{font-size:11px;color:var(--muted);line-height:1.45;display:block;margin-top:2px}
.notif time{font-size:10px;color:var(--muted-2);white-space:nowrap;margin-left:auto;padding-top:2px}
.profile-menu{min-width:224px;padding:6px}
.profile-menu .menu-user{padding:10px 11px 12px;border-bottom:1px solid #F0F3FA;margin-bottom:5px}
.profile-menu .menu-user strong{font-size:12.5px;display:block}
.profile-menu .menu-user span{font-size:11px;color:var(--muted)}
.profile-menu button{display:flex;width:100%;gap:9px;align-items:center;padding:9px 11px;border-radius:9px;
  font-size:12.5px;font-weight:500;color:#3A4666;text-align:left}
.profile-menu button:hover{background:#F5F7FD}
.profile-menu button .ico{width:15px;height:15px;color:#7A86A4}
.profile-menu button.danger{color:#C9362B}
.profile-menu button.danger .ico{color:#C9362B}

/* ---------- Main ---------- */
.main{flex:1;width:100%;max-width:1440px;margin:0 auto;padding:30px 30px 44px}
.view{display:none}
.view.active{display:block;animation:viewIn .4s cubic-bezier(.2,.75,.3,1)}
.page-head{display:flex;align-items:flex-end;justify-content:space-between;gap:18px;margin-bottom:26px;flex-wrap:wrap}
.eyebrow{font-size:11.5px;font-weight:600;color:#6E7BA0;margin-bottom:6px;letter-spacing:.01em}
.page-head h1{margin:0;font-size:27px;font-weight:700;letter-spacing:-.035em;line-height:1.2}
.page-head .sub{margin-top:6px;color:var(--muted);font-size:13px;max-width:560px}
.head-actions{display:flex;gap:10px;flex-wrap:wrap}

/* ---------- Buttons ---------- */
.btn{display:inline-flex;align-items:center;justify-content:center;gap:8px;height:42px;padding:0 16px;
  border-radius:11px;font-size:13px;font-weight:600;letter-spacing:-.01em;white-space:nowrap;
  transition:transform .16s ease,box-shadow .2s ease,background .2s ease,border-color .2s ease,color .2s ease}
.btn .ico{width:16px;height:16px}
.btn-primary{color:#fff;background:linear-gradient(135deg,#5567FF,#8A5CF6);
  box-shadow:0 10px 20px -10px rgba(84,104,255,.85),inset 0 1px 0 rgba(255,255,255,.22)}
.btn-primary:hover{transform:translateY(-1px);box-shadow:0 15px 26px -10px rgba(84,104,255,.9)}
.btn-primary:active{transform:translateY(0)}
.btn-ghost{color:#33405F;background:#fff;border:1px solid var(--line);box-shadow:var(--shadow-sm)}
.btn-ghost:hover{border-color:#C9D3E8;color:#1B2745}
.btn-soft{color:#4255E8;background:#EEF1FF;border:1px solid #DCE2FF}
.btn-soft:hover{background:#E6EBFF}
.btn-danger{color:#fff;background:linear-gradient(135deg,#F05252,#D92D20);
  box-shadow:0 10px 20px -10px rgba(217,45,32,.65)}
.btn-danger:hover{transform:translateY(-1px)}
.btn-white{background:#fff;color:#1B2457;box-shadow:0 12px 24px -12px rgba(0,0,0,.5)}
.btn-light{background:rgba(255,255,255,.12);color:#fff;border:1px solid rgba(255,255,255,.22)}
.btn-light:hover{background:rgba(255,255,255,.18)}
.btn-block{width:100%}
.btn-sm{height:36px;padding:0 12px;font-size:12px;border-radius:9px}
.btn[disabled]{opacity:.55;cursor:not-allowed;transform:none!important;box-shadow:none!important}
.spinner{width:16px;height:16px;border-radius:50%;border:2px solid rgba(255,255,255,.35);
  border-top-color:#fff;display:none;animation:spin .7s linear infinite}
.btn.loading .spinner{display:inline-block}
.btn.loading .btn-label{opacity:.8}
.btn.loading{pointer-events:none}
.text-btn{font-size:12px;font-weight:600;color:#4255E8}
.text-btn:hover{color:#2637C9}

/* ---------- Cards & stats ---------- */
.card{background:var(--surface);border:1px solid var(--line);border-radius:var(--radius);box-shadow:var(--shadow-sm)}
.stat-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px;margin-bottom:18px}
.stat-card{padding:20px;display:flex;flex-direction:column;gap:13px;position:relative;overflow:hidden;
  transition:transform .2s ease,box-shadow .28s ease}
.stat-card:hover{transform:translateY(-3px);box-shadow:var(--shadow-lg)}
.stat-top{display:flex;align-items:flex-start;justify-content:space-between;gap:10px}
.stat-icon{width:44px;height:44px;border-radius:13px;display:grid;place-items:center;flex:none}
.stat-icon .ico{width:21px;height:21px}
.tint-indigo{background:#EEF0FF;color:#4C5BFF}
.tint-green{background:#E7F8EF;color:#0E9F6E}
.tint-red{background:#FDECEC;color:#E0413A}
.tint-purple{background:#F3ECFF;color:#8B5CF6}
.stat-card h3{font-size:12px;font-weight:600;color:var(--muted);margin-bottom:7px;letter-spacing:0}
.stat-value{font-size:27px;font-weight:700;letter-spacing:-.045em;line-height:1;font-variant-numeric:tabular-nums}
.stat-value.small{font-size:23px;padding-top:3px}
.stat-foot{display:flex;align-items:center;gap:7px;padding-top:11px;border-top:1px dashed #E9EDF7;
  font-size:11px;color:var(--muted)}
.stat-foot .trend{display:inline-flex;align-items:center;gap:3px;font-weight:700;font-size:10.5px;
  padding:2px 6px;border-radius:6px}
.trend.up{color:#0C8A5A;background:#E9F8F1}
.trend.warn{color:#B45309;background:#FEF3E2}
.trend.neutral{color:#5B6788;background:#F0F3FA}

.dash-grid{display:grid;grid-template-columns:minmax(0,1.7fr) minmax(0,1fr);gap:18px;margin-bottom:18px}
.dash-grid.lower{grid-template-columns:minmax(0,1.3fr) minmax(0,1fr)}
.card-head{display:flex;align-items:center;justify-content:space-between;gap:14px;padding:20px 22px 0}
.card-head h2{font-size:15.5px;font-weight:700;letter-spacing:-.02em}
.card-head p{margin-top:3px;font-size:11.5px;color:var(--muted)}
.chip{display:inline-flex;align-items:center;gap:6px;font-size:10.5px;font-weight:600;color:#5B6788;
  background:#F3F6FC;border:1px solid #E9EDF7;border-radius:999px;padding:5px 10px;white-space:nowrap}
.chip .ico{width:12px;height:12px}
.chart{padding:26px 22px 16px;display:flex;align-items:flex-end;gap:10px;height:244px}
.bar-col{flex:1;height:100%;display:flex;flex-direction:column;align-items:center;gap:9px;justify-content:flex-end;min-width:0}
.bar-track{width:100%;max-width:46px;height:100%;display:flex;flex-direction:column;justify-content:flex-end;
  align-items:center;gap:6px}
.bar-amount{font-size:9.5px;font-weight:600;color:#8A94AE;height:12px;line-height:12px;white-space:nowrap}
.bar{width:100%;min-height:4px;border-radius:8px;background:linear-gradient(180deg,#95A3FF,#5A6CFF);
  transition:height .7s cubic-bezier(.2,.8,.3,1),filter .2s;position:relative;cursor:default}
.bar:hover{filter:brightness(1.08)}
.bar.peak{background:linear-gradient(180deg,#B39BFF,#7C5CFF);box-shadow:0 8px 18px -8px rgba(124,92,255,.6)}
.bar-col span{font-size:10.5px;font-weight:500;color:var(--muted)}
.bar-col.today span{color:var(--ink);font-weight:700}
.chart-empty{flex:1;height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6px;text-align:center}
.chart-empty svg{width:96px;height:74px;margin-bottom:4px}
.chart-empty strong{font-size:12.5px;font-weight:600;color:var(--ink-2)}
.chart-empty p{font-size:11.5px;color:var(--muted);max-width:250px;line-height:1.5}

.mix-body{padding:20px 22px 24px;display:flex;align-items:center;gap:26px;flex-wrap:wrap}
.donut{width:146px;height:146px;border-radius:50%;position:relative;display:grid;place-items:center;
  flex:none;background:conic-gradient(#E9EDF7 0 100%);transition:.4s}
.donut::after{content:"";position:absolute;inset:17px;border-radius:50%;background:#fff;
  box-shadow:inset 0 0 0 1px #F0F3FA}
.donut-center{position:relative;z-index:1;text-align:center;line-height:1.2}
.donut-center strong{display:block;font-size:22px;font-weight:700;letter-spacing:-.03em;font-variant-numeric:tabular-nums}
.donut-center small{font-size:9.5px;color:var(--muted);text-transform:uppercase;letter-spacing:.09em;font-weight:700}
.legend{flex:1;min-width:140px;display:grid;gap:13px}
.legend li{display:flex;align-items:center;gap:9px;font-size:12px;color:var(--ink-2)}
.legend .swatch{width:9px;height:9px;border-radius:3px;flex:none}
.legend b{margin-left:auto;font-weight:700;color:var(--ink);font-variant-numeric:tabular-nums;font-size:11.5px}

.activity-list{padding:10px 10px 14px}
.activity{display:flex;align-items:center;gap:12px;padding:10px 12px;border-radius:12px;transition:.16s;cursor:pointer}
.activity:hover{background:#F7F9FE}
.mini-icon{width:38px;height:38px;border-radius:11px;display:grid;place-items:center;flex:none}
.mini-icon .ico{width:18px;height:18px}
.mi-card{background:linear-gradient(135deg,#EEF0FF,#E3E8FF);color:#4C5BFF}
.mi-paypal{background:linear-gradient(135deg,#E8F0FF,#DEEAFF);color:#1D4ED8}
.mi-bank{background:linear-gradient(135deg,#E6F8F0,#DDF5EA);color:#0E9F6E}
.activity-info{flex:1;min-width:0}
.activity-info strong{display:block;font-size:12.5px;font-weight:600}
.activity-info small{display:block;font-size:11px;color:var(--muted);white-space:nowrap;overflow:hidden;
  text-overflow:ellipsis;margin-top:1px}
.activity-amt{display:flex;flex-direction:column;align-items:flex-end;gap:4px}
.activity-amt b{font-size:12.5px;font-variant-numeric:tabular-nums;font-weight:700}
.activity-empty{padding:22px 16px}

.quick-card{position:relative;overflow:hidden;display:flex;flex-direction:column;justify-content:space-between;
  gap:22px;min-height:270px;padding:26px;color:#fff;border:0;
  background:linear-gradient(135deg,#0E1730 0%,#1B2457 52%,#3B2B6E 100%)}
.quick-card::before{content:"";position:absolute;width:240px;height:240px;border-radius:50%;
  background:radial-gradient(circle,rgba(124,92,255,.5),transparent 70%);top:-90px;right:-70px}
.quick-card::after{content:"";position:absolute;width:200px;height:200px;border-radius:50%;
  border:1px solid rgba(255,255,255,.10);bottom:-90px;left:-60px}
.quick-card > *{position:relative;z-index:1}
.quick-card .chip{background:rgba(255,255,255,.12);border-color:rgba(255,255,255,.16);color:#D9E0F8;align-self:flex-start}
.quick-card h2{font-size:21px;line-height:1.25;letter-spacing:-.03em;margin-top:14px;font-weight:700}
.quick-card p{font-size:12.5px;line-height:1.6;color:#B9C2E4;margin-top:8px;max-width:330px}
.quick-actions{display:flex;gap:10px;flex-wrap:wrap}
.quick-actions .btn{height:38px;font-size:12px}

/* ---------- Status pills ---------- */
.pill{display:inline-flex;align-items:center;gap:6px;font-size:10.5px;font-weight:700;padding:4px 9px;
  border-radius:999px;letter-spacing:.01em;white-space:nowrap}
.pill::before{content:"";width:5px;height:5px;border-radius:50%;background:currentColor;flex:none}
.pill-success{color:#087A4C;background:#E5F7EE}
.pill-pending{color:#9A5B00;background:#FEF1DC}
.pill-failed{color:#B42318;background:#FDEAE8}
.pill-refunded{color:#1D4ED8;background:#E8EFFE}
.pill-muted{color:#5C6784;background:#EEF1F8}

/* ---------- Forms ---------- */
.field{display:flex;flex-direction:column;gap:7px;min-width:0}
.field > label,.field-label{display:flex;align-items:center;justify-content:space-between;gap:8px;
  font-size:12px;font-weight:600;color:#3C4867}
.field .hint{font-size:10.5px;color:var(--muted-2);font-weight:500}
.method-label{font-size:12px;font-weight:600;color:#3C4867;margin-bottom:9px;display:block}
.input,.select{height:45px;width:100%;border:1px solid var(--line-strong);background:#FBFCFE;border-radius:12px;
  padding:0 13px;font-size:13px;transition:.18s;color:var(--ink);min-width:0}
.input::placeholder{color:#A4AEC7}
.input:focus,.select:focus{border-color:#8D9BFF;background:#fff;box-shadow:0 0 0 4px rgba(84,104,255,.13)}
.input.invalid,.select.invalid{border-color:#F04438;background:#FFFBFB;box-shadow:0 0 0 4px rgba(240,68,56,.10)}
.select{appearance:none;background-image:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='14' height='14' viewBox='0 0 24 24' fill='none' stroke='%23707B99' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E");
  background-repeat:no-repeat;background-position:right 13px center;padding-right:36px;cursor:pointer}
.field-error{font-size:11px;color:#C9362B;display:none;align-items:center;gap:5px;line-height:1.4}
.field-error.show{display:flex}
.field-error::before{content:"";width:5px;height:5px;border-radius:50%;background:currentColor;flex:none}
.field-grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}
.field-grid .span-2{grid-column:1/-1}
.form-note{display:flex;gap:8px;align-items:flex-start;font-size:11px;color:#66718F;line-height:1.5;
  background:#F7F9FE;border:1px solid #EDF0F8;border-radius:10px;padding:10px 12px;margin-top:14px}
.form-note .ico{width:14px;height:14px;color:#7B87A6;margin-top:1px}
.form-actions{display:flex;gap:10px;justify-content:flex-end;margin-top:22px;padding-top:20px;
  border-top:1px solid #EEF1F8}
.form-head{display:flex;justify-content:space-between;align-items:center;gap:12px;
  padding-bottom:18px;border-bottom:1px solid #EEF1F8;margin-bottom:20px}
.form-head h2{font-size:17px;font-weight:700;letter-spacing:-.02em}
.form-head p{font-size:11.5px;color:var(--muted);margin-top:3px}
.secure-tag{display:inline-flex;gap:6px;align-items:center;font-size:10.5px;font-weight:700;color:#0C8A5A;
  background:#EAF9F2;padding:5px 9px;border-radius:999px;white-space:nowrap}
.secure-tag .ico{width:12px;height:12px}

/* ---------- Payment methods ---------- */
.method-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:18px;margin-bottom:18px}
.method-card{padding:22px;display:flex;flex-direction:column;gap:14px;transition:transform .2s,box-shadow .25s}
.method-card:hover{transform:translateY(-2px);box-shadow:var(--shadow-lg)}
.method-top{display:flex;justify-content:space-between;align-items:flex-start;gap:10px}
.method-icon{width:52px;height:52px;border-radius:15px;display:grid;place-items:center;color:#fff;flex:none;
  box-shadow:inset 0 1px 0 rgba(255,255,255,.28),0 12px 22px -12px rgba(30,40,90,.55)}
.method-icon .ico{width:25px;height:25px}
.mi-cc{background:linear-gradient(135deg,#5567FF,#8A5CF6)}
.mi-pp{background:linear-gradient(135deg,#1B3BDF,#4F8DF7)}
.mi-bt{background:linear-gradient(135deg,#0E9F6E,#35C08A)}
.method-card h3{font-size:16px;font-weight:700;letter-spacing:-.02em;margin-top:2px}
.method-card .method-desc{font-size:12px;color:var(--muted);line-height:1.55;margin-top:-6px}
.method-card .method-tag{display:inline-flex;align-items:center;gap:5px;font-size:10.5px;font-weight:600;
  color:#5C6784;background:#F5F7FC;border-radius:7px;padding:4px 8px;align-self:flex-start}
.method-card .method-tag .ico{width:11px;height:11px}
.saved-method{display:flex;align-items:center;gap:9px;padding:10px 11px;border-radius:10px;
  background:#F7F9FF;border:1px dashed #DDE3F6;font-size:11.5px}
.saved-method .ico{width:14px;height:14px;color:#0E9F6E}
.saved-method b{font-weight:600}
.saved-method button{margin-left:auto;color:#98A1B9;display:grid;place-items:center;width:22px;height:22px;border-radius:6px}
.saved-method button:hover{color:#C9362B;background:#FDECEA}
.saved-method button .ico{width:12px;height:12px;color:currentColor}
.method-actions{display:flex;gap:8px;margin-top:auto;flex-wrap:wrap}
.method-actions .btn{flex:1;min-width:118px;height:38px;font-size:12px}
.add-form{display:none;gap:12px;padding-top:16px;border-top:1px dashed #E8ECF6}
.method-card.open .add-form{display:grid;animation:viewIn .28s cubic-bezier(.2,.8,.3,1)}
.add-form .field-grid{grid-template-columns:1fr 1fr;gap:11px}
.add-form .input{height:40px;font-size:12px;border-radius:10px}
.add-form .field > label{font-size:11px}
.add-form .form-actions{margin-top:2px;padding-top:13px;gap:8px}
.add-form .btn{height:36px;font-size:11.5px}
.info-strip{display:flex;align-items:center;gap:14px;padding:18px 20px;flex-wrap:wrap}
.info-strip .ico-badge{width:38px;height:38px;border-radius:11px;background:#EFF2FF;color:#4255E8;
  display:grid;place-items:center;flex:none}
.info-strip b{font-size:12.5px}
.info-strip p{font-size:11.5px;color:var(--muted);line-height:1.55}
.info-strip .chip{margin-left:auto}

/* ---------- Make payment ---------- */
.pay-grid{display:grid;grid-template-columns:minmax(0,1.5fr) minmax(304px,1fr);gap:18px;align-items:start}
.pay-form{padding:24px}
.method-picker{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-bottom:22px}
.pick{position:relative;display:flex;flex-direction:column;gap:7px;padding:14px;cursor:pointer;
  border:1.5px solid var(--line);border-radius:13px;background:#fff;transition:.18s;min-width:0}
.pick:hover{border-color:#C9D1F5;background:#FAFBFF}
.pick input{position:absolute;opacity:0;pointer-events:none}
.pick-icon{width:34px;height:34px;border-radius:10px;display:grid;place-items:center;
  background:#F1F4FB;color:#4E5C86;transition:.18s}
.pick-icon .ico{width:18px;height:18px}
.pick-text{display:flex;flex-direction:column;gap:1px;min-width:0}
.pick strong{display:block;font-size:12px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.pick small{display:block;font-size:10px;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.pick .pick-check{position:absolute;top:12px;right:12px;width:18px;height:18px;border-radius:50%;
  border:1.5px solid #D5DCEF;display:grid;place-items:center;color:transparent;transition:.18s}
.pick .pick-check .ico{width:10px;height:10px}
.pick.active{border-color:#7D8CFF;background:linear-gradient(180deg,#F9FAFF,#F3F5FF);
  box-shadow:0 8px 18px -14px rgba(84,104,255,.6)}
.pick.active .pick-icon{background:#E7EAFF;color:#4255E8}
.pick.active .pick-check{background:linear-gradient(135deg,#5567FF,#8A5CF6);border-color:transparent;color:#fff}
.amount-wrap{display:flex;align-items:center;gap:8px;height:60px;padding:0 16px;background:#FBFCFE;
  border:1px solid var(--line-strong);border-radius:14px;transition:.18s;margin-top:9px}
.amount-wrap:focus-within{border-color:#8D9BFF;background:#fff;box-shadow:0 0 0 4px rgba(84,104,255,.13)}
.amount-wrap .currency{font-size:22px;font-weight:600;color:#57648A}
.amount-wrap input{flex:1;min-width:0;border:0;background:none;font-size:24px;font-weight:700;
  letter-spacing:-.035em;font-variant-numeric:tabular-nums}
.amount-wrap input::placeholder{color:#B7C0D5}
.amount-wrap .amount-hint{font-size:10px;font-weight:700;color:#8A95B0;background:#EEF1F8;
  padding:4px 7px;border-radius:6px;letter-spacing:.03em}
.quick-amounts{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}
.quick-amounts button{font-size:11.5px;font-weight:600;color:#4A5677;border:1px solid var(--line);
  background:#fff;border-radius:9px;padding:7px 12px;transition:.15s}
.quick-amounts button:hover{border-color:#BFC9FF;color:#4255E8;background:#F5F7FF}
.pay-side{display:grid;gap:16px}
.summary-card{padding:22px}
.summary-card h2{font-size:15px;font-weight:700;letter-spacing:-.02em}
.summary-method{display:flex;gap:12px;align-items:center;padding:14px;border-radius:13px;margin:16px 0 6px;
  background:linear-gradient(135deg,#F7F8FF,#F3F5FE);border:1px solid #E7EAFA}
.summary-method .mini-icon{width:40px;height:40px}
.summary-method strong{display:block;font-size:12.5px}
.summary-method small{display:block;font-size:11px;color:var(--muted);margin-top:1px;word-break:break-all}
.srow{display:flex;justify-content:space-between;gap:12px;padding:9px 0;font-size:12.5px;color:var(--muted)}
.srow b{color:var(--ink);font-weight:600;font-variant-numeric:tabular-nums;text-align:right}
.srow.total{border-top:1px dashed #E6EAF4;margin-top:6px;padding-top:14px;font-size:13px;color:#3C4867}
.srow.total b{font-size:19px;letter-spacing:-.03em;font-weight:700}
.srow.total b small{font-size:10px;color:var(--muted);font-weight:600;letter-spacing:.04em}
.summary-note{display:flex;gap:9px;align-items:flex-start;margin-top:16px;padding:11px 12px;border-radius:10px;
  background:#F5F8FF;color:#5B6788;font-size:11px;line-height:1.5}
.summary-note .ico{width:14px;height:14px;margin-top:1px;color:#5B6788}
.tips-card{padding:20px 22px}
.tips-card h3{font-size:12.5px;margin-bottom:12px;display:flex;align-items:center;gap:7px}
.tips-card h3 .ico{width:14px;height:14px;color:#8B5CF6}
.tips-card dl{margin:0;display:grid;gap:9px}
.tips-card dt{font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:.07em;color:#8A94AE}
.tips-card dd{margin:1px 0 0;font-size:11.5px;color:#3F4B6A;font-family:ui-monospace,'SFMono-Regular',monospace}
.result-card{padding:22px;border-radius:18px;border:1px solid var(--line);background:#fff;
  animation:viewIn .35s cubic-bezier(.2,.8,.3,1);box-shadow:var(--shadow-sm)}
.result-card.success{background:linear-gradient(180deg,#F2FBF7,#fff);border-color:#C9EDDD}
.result-card.failed{background:linear-gradient(180deg,#FEF6F5,#fff);border-color:#F6D5D2}
.result-head{display:flex;gap:12px;align-items:flex-start}
.result-icon{width:42px;height:42px;border-radius:12px;display:grid;place-items:center;flex:none}
.result-card.success .result-icon{background:#DFF6EA;color:#0E9F6E}
.result-card.failed .result-icon{background:#FDE7E4;color:#D92D20}
.result-head h3{font-size:15px;font-weight:700;letter-spacing:-.015em}
.result-head p{font-size:12px;color:#5E6A87;line-height:1.55;margin-top:3px}
.result-meta{margin-top:15px;padding-top:13px;border-top:1px dashed #E8ECF5;display:grid;gap:7px}
.result-meta li{display:flex;justify-content:space-between;gap:10px;font-size:11.5px;color:var(--muted)}
.result-meta b{color:var(--ink);font-weight:600;font-variant-numeric:tabular-nums;text-align:right}

/* ---------- Transactions ---------- */
.table-card{padding:0;overflow:hidden}
.toolbar{display:flex;align-items:center;justify-content:space-between;gap:14px;padding:17px 20px;
  border-bottom:1px solid #EEF1F8;flex-wrap:wrap}
.toolbar-left{display:flex;align-items:center;gap:10px;flex:1;min-width:0;flex-wrap:wrap}
.txn-search{display:flex;align-items:center;gap:8px;height:38px;padding:0 12px;border-radius:10px;
  background:#F5F7FC;border:1px solid transparent;min-width:230px;flex:0 1 300px;transition:.18s}
.txn-search:focus-within{background:#fff;border-color:#C3CBFA;box-shadow:0 0 0 3px rgba(84,104,255,.10)}
.txn-search .ico{width:15px;height:15px;color:#8B96B2}
.txn-search input{flex:1;min-width:0;border:0;background:none;font-size:12.5px}
.chips{display:flex;gap:6px;flex-wrap:wrap}
.filter-chip{display:inline-flex;gap:6px;align-items:center;font-size:11.5px;font-weight:600;color:#5B6788;
  background:#F5F7FC;border:1px solid transparent;padding:7px 11px;border-radius:999px;transition:.15s}
.filter-chip span{font-size:10px;color:#8B95AF;background:#fff;border-radius:20px;padding:1px 6px;font-weight:700}
.filter-chip:hover{background:#EEF1FA;color:#2F3B5C}
.filter-chip.active{background:#111A33;color:#fff;border-color:#111A33}
.filter-chip.active span{background:rgba(255,255,255,.16);color:#fff}
.method-filter{height:38px;border-radius:10px;font-size:12px;padding:0 32px 0 11px;border-color:var(--line);
  background-color:#fff;width:auto;min-width:150px}
.table-wrap{overflow-x:auto}
table{width:100%;border-collapse:collapse;min-width:860px}
thead th{font-size:10px;text-transform:uppercase;letter-spacing:.09em;color:#8A94AE;font-weight:700;
  text-align:left;padding:12px 20px;background:#FAFBFE;border-bottom:1px solid #EEF1F8;white-space:nowrap}
thead th:last-child{text-align:right}
tbody td{padding:14px 20px;border-bottom:1px solid #F1F4FA;vertical-align:middle;font-size:12.5px}
tbody tr{transition:background .15s}
tbody tr:hover{background:#FAFBFF}
tbody tr:last-child td{border-bottom:0}
.txn-id{font-weight:600;font-size:12px;color:#2A3557;display:inline-flex;align-items:center;gap:5px;
  font-variant-numeric:tabular-nums}
.txn-id:hover{color:#4255E8}
.txn-sub{display:block;font-size:10.5px;color:var(--muted-2);margin-top:2px}
.method-cell{display:flex;align-items:center;gap:10px}
.method-cell .mini-icon{width:34px;height:34px;border-radius:10px}
.method-cell strong{display:block;font-size:12px;font-weight:600}
.method-cell small{display:block;font-size:10.5px;color:var(--muted);margin-top:1px;max-width:190px;
  white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.amount-cell{font-weight:700;font-variant-numeric:tabular-nums;letter-spacing:-.01em;white-space:nowrap}
.row-actions{display:flex;gap:5px;justify-content:flex-end}
.row-btn{width:30px;height:30px;border-radius:8px;display:grid;place-items:center;color:#6B7796;
  border:1px solid transparent;transition:.15s}
.row-btn:hover{background:#F0F3FB;color:#2A3557;border-color:#E4E9F5}
.row-btn .ico{width:15px;height:15px}
.row-btn[disabled]{opacity:.32;cursor:not-allowed}
.row-btn[disabled]:hover{background:none;border-color:transparent;color:#6B7796}
.table-foot{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:13px 20px;
  border-top:1px solid #F1F4FA;font-size:11px;color:var(--muted);flex-wrap:wrap}
.compact table tbody td{padding:9px 20px}

/* ---------- Empty states ---------- */
.empty{padding:36px 20px 32px;display:flex;flex-direction:column;align-items:center;text-align:center;gap:5px}
.empty .art{width:124px;height:96px;margin-bottom:6px}
.empty strong{font-size:13px;font-weight:700}
.empty p{margin:0;font-size:11.5px;color:var(--muted);max-width:300px;line-height:1.55}
.empty .btn{margin-top:13px;height:38px;font-size:12px}
.activity .empty{padding:24px 10px}

/* ---------- Refunds ---------- */
.refund-grid{display:grid;grid-template-columns:minmax(0,1.4fr) minmax(0,1fr);gap:18px;align-items:start}
.refund-form-card{padding:24px}
.refund-preview{margin:16px 0 20px;padding:16px;border:1px solid #E9EDF8;background:#FAFBFF;border-radius:14px;display:grid;gap:12px}
.rp-head{display:flex;align-items:center;gap:12px}
.rp-head strong{display:block;font-size:12.5px}
.rp-head small{display:block;font-size:11px;color:var(--muted);margin-top:1px}
.rp-head .pill{margin-left:auto}
.rp-rows{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;padding-top:13px;border-top:1px dashed #E4E9F4}
.rp-rows span{display:block;font-size:9.5px;color:var(--muted);text-transform:uppercase;letter-spacing:.07em;font-weight:700}
.rp-rows b{font-size:12.5px;font-weight:600;font-variant-numeric:tabular-nums}
.refund-side{display:grid;gap:16px}
.policy-card{padding:22px}
.policy-card h2{font-size:15px;font-weight:700;letter-spacing:-.02em}
.policy-card ul{display:grid;gap:12px;margin-top:15px}
.policy-card li{display:flex;gap:10px;font-size:12px;color:#4A5678;line-height:1.5}
.policy-card li .ico{width:15px;height:15px;color:#0E9F6E;flex:none;margin-top:2px}
.refund-list-card{padding:22px}
.refund-list-card h2{font-size:15px;font-weight:700;letter-spacing:-.02em}
.refund-list{display:grid;gap:2px;margin-top:10px}
.refund-item{display:flex;gap:11px;align-items:center;padding:11px 4px;border-bottom:1px solid #F3F5FA}
.refund-item:last-child{border-bottom:0}
.refund-item strong{display:block;font-size:12px;font-weight:600}
.refund-item small{display:block;font-size:10.5px;color:var(--muted);margin-top:1px;max-width:210px}
.refund-item b{margin-left:auto;font-size:12px;font-variant-numeric:tabular-nums;white-space:nowrap;color:#0C8A5A}
.full-link{font-size:11.5px;font-weight:600;color:#4255E8;display:inline-flex;align-items:center;gap:5px}
.full-link:hover{color:#2637C9}

/* ---------- Settings ---------- */
.settings-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px;align-items:start}
.settings-card{padding:22px}
.settings-card h2{font-size:15px;font-weight:700;letter-spacing:-.02em}
.settings-card .card-sub{font-size:11.5px;color:var(--muted);margin-top:3px;margin-bottom:16px}
.profile-big{display:flex;gap:14px;align-items:center;margin-bottom:16px}
.avatar-big{width:58px;height:58px;border-radius:16px;font-size:18px}
.profile-big strong{display:block;font-size:15px;letter-spacing:-.01em}
.profile-big span{display:block;font-size:11.5px;color:var(--muted);margin-top:2px}
.profile-big .chip{margin-top:7px}
.kv{display:flex;justify-content:space-between;gap:14px;padding:9px 0;border-top:1px solid #F1F4FA;font-size:12px;color:var(--muted)}
.kv b{color:var(--ink);font-weight:600;text-align:right;word-break:break-all}
.switch-row{display:flex;justify-content:space-between;align-items:center;gap:16px;padding:12px 0;border-top:1px solid #F1F4FA}
.switch-row:first-of-type{border-top:0}
.switch-row strong{display:block;font-size:12.5px;font-weight:600}
.switch-row small{display:block;font-size:11px;color:var(--muted);margin-top:2px;line-height:1.45}
.switch{position:relative;width:42px;height:24px;flex:none;display:inline-block}
.switch input{opacity:0;width:0;height:0;position:absolute}
.switch .track{position:absolute;inset:0;background:#DCE1EE;border-radius:99px;transition:.2s;cursor:pointer}
.switch .track::after{content:"";position:absolute;top:3px;left:3px;width:18px;height:18px;border-radius:50%;
  background:#fff;box-shadow:0 1px 3px rgba(16,24,40,.2);transition:.2s}
.switch input:checked + .track{background:linear-gradient(135deg,#5567FF,#8A5CF6)}
.switch input:checked + .track::after{transform:translateX(18px)}
.endpoint{display:flex;align-items:center;gap:9px;padding:10px 0;border-top:1px solid #F1F4FA;font-size:12px}
.endpoint:first-of-type{border-top:0}
.verb{font-size:9px;font-weight:800;letter-spacing:.07em;padding:3px 7px;border-radius:6px;flex:none}
.verb.get{background:#E7F8EF;color:#0C8A5A}
.verb.post{background:#EEF0FF;color:#4255E8}
.endpoint code{font-family:ui-monospace,'SFMono-Regular',monospace;font-size:11.5px;color:#273356}
.endpoint span{margin-left:auto;font-size:10.5px;color:var(--muted);white-space:nowrap}
.tech-chips{display:flex;flex-wrap:wrap;gap:6px;margin-top:14px}
.tech-chip{font-size:10px;font-weight:600;background:#F4F6FC;border:1px solid #E7EBF5;color:#4B5878;
  padding:4px 9px;border-radius:7px}
.demo-box{display:flex;gap:12px;align-items:flex-start;padding:13px 14px;border-radius:12px;
  background:linear-gradient(135deg,#F7F8FF,#F4F1FF);border:1px solid #E9E7FF;margin-top:14px}
.demo-box .ico{width:17px;height:17px;color:#7C5CFF;margin-top:2px}
.demo-box p{font-size:11px;color:#5B6788;line-height:1.5}
.demo-box strong{display:block;font-size:11.5px;color:#2F3B5C;margin-bottom:2px}
.settings-actions{display:flex;gap:9px;margin-top:16px;flex-wrap:wrap}
.settings-actions .btn{height:38px;font-size:12px}

/* ---------- Modals ---------- */
.modal-scrim{position:fixed;inset:0;z-index:90;display:grid;place-items:center;padding:20px;
  background:rgba(10,16,36,.46);backdrop-filter:blur(5px);opacity:0;pointer-events:none;transition:.2s}
.modal-scrim.open{opacity:1;pointer-events:auto}
.modal{width:min(440px,100%);padding:26px;border-radius:20px;background:#fff;
  box-shadow:0 40px 90px -30px rgba(10,16,40,.55);transform:translateY(12px) scale(.97);
  transition:.26s cubic-bezier(.2,.8,.3,1);max-height:calc(100vh - 40px);overflow-y:auto}
.modal-scrim.open .modal{transform:none}
.modal-icon{width:48px;height:48px;border-radius:14px;display:grid;place-items:center;margin-bottom:16px}
.modal-icon .ico{width:22px;height:22px}
.modal-icon.warn{background:#FEF3E2;color:#B45309}
.modal-icon.primary{background:#EEF0FF;color:#4C5BFF}
.modal h3{font-size:18px;font-weight:700;letter-spacing:-.025em;margin-bottom:8px}
.modal > p{font-size:12.5px;color:var(--muted);line-height:1.6}
.modal-summary{margin-top:16px;padding:14px;border-radius:12px;background:#F8FAFF;border:1px solid #EDF1FA;display:grid;gap:7px}
.modal-summary div{display:flex;justify-content:space-between;gap:10px;font-size:12px;color:var(--muted)}
.modal-summary b{color:var(--ink);font-weight:600;font-variant-numeric:tabular-nums;text-align:right}
.modal-actions{display:flex;justify-content:flex-end;gap:10px;margin-top:22px}
.modal-wide{width:min(560px,100%)}
.modal-head{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:14px}
.modal-head h3{margin:0}
.modal-close{width:32px;height:32px;border-radius:9px;display:grid;place-items:center;color:#7A86A4;
  border:1px solid var(--line);flex:none}
.modal-close:hover{background:#F5F7FD;color:#26324F}
.modal-close .ico{width:15px;height:15px}
.detail-hero{display:flex;align-items:center;gap:14px;padding:16px;border-radius:14px;
  background:#F9FAFE;border:1px solid #EEF1F9;margin-bottom:16px}
.detail-hero .mini-icon{width:44px;height:44px}
.detail-hero strong{display:block;font-size:15px;letter-spacing:-.01em;font-variant-numeric:tabular-nums}
.detail-hero small{display:block;font-size:11.5px;color:var(--muted);margin-top:2px}
.detail-hero .pill{margin-left:auto}
.detail-rows{display:grid;gap:0}
.detail-rows div{display:flex;justify-content:space-between;gap:16px;padding:10px 0;border-bottom:1px solid #F2F4FA;font-size:12px;color:var(--muted)}
.detail-rows div:last-child{border-bottom:0}
.detail-rows b{color:var(--ink);font-weight:600;text-align:right;word-break:break-word;max-width:65%}
.detail-message{margin-top:14px;padding:12px 14px;border-radius:11px;background:#F8FAFF;border:1px solid #EDF1FA;
  font-size:11.5px;color:#4A5678;line-height:1.55}
.refund-history{margin-top:16px}
.refund-history h4{font-size:11px;text-transform:uppercase;letter-spacing:.08em;color:#8A94AE;margin-bottom:8px}
.refund-history div{display:flex;justify-content:space-between;gap:10px;font-size:11.5px;color:var(--muted);padding:7px 0;border-bottom:1px solid #F4F6FB}
.refund-history b{color:#0C8A5A}

/* ---------- Toasts ---------- */
.toast-stack{position:fixed;top:82px;right:24px;z-index:120;display:flex;flex-direction:column;gap:10px;
  width:min(380px,calc(100vw - 32px))}
.toast{display:flex;gap:11px;align-items:flex-start;padding:13px 14px 15px;border-radius:14px;background:#fff;
  border:1px solid var(--line);box-shadow:0 22px 48px -18px rgba(16,24,64,.35);position:relative;overflow:hidden;
  animation:toastIn .32s cubic-bezier(.2,.8,.3,1)}
.toast::after{content:"";position:absolute;left:0;bottom:0;height:3px;width:100%;background:currentColor;
  animation:toastBar 4.6s linear forwards;opacity:.75}
.toast.success{color:#0E9F6E}
.toast.error{color:#D92D20}
.toast.info{color:#4255E8}
.toast-icon{width:32px;height:32px;border-radius:10px;display:grid;place-items:center;flex:none}
.toast.success .toast-icon{background:#E7F8EF}
.toast.error .toast-icon{background:#FDECEA}
.toast.info .toast-icon{background:#EEF0FF}
.toast-icon .ico{width:16px;height:16px}
.toast-body{flex:1;min-width:0}
.toast-body strong{display:block;font-size:12px;font-weight:700;color:var(--ink)}
.toast-body p{font-size:11px;color:var(--muted);line-height:1.45;margin-top:2px;word-break:break-word}
.toast-close{color:#98A1B9;width:22px;height:22px;border-radius:6px;display:grid;place-items:center;flex:none}
.toast-close:hover{background:#F3F6FC;color:#485471}
.toast-close .ico{width:12px;height:12px}

/* ---------- Boot ---------- */
.boot{position:fixed;inset:0;z-index:200;display:grid;place-items:center;background:#080E1F;
  transition:opacity .45s ease,visibility .45s ease}
.boot.done{opacity:0;visibility:hidden}
.boot-inner{display:flex;flex-direction:column;align-items:center;gap:18px;color:#8B99C0;
  font-size:11.5px;letter-spacing:.08em;text-transform:uppercase;font-weight:600}
.boot-logo{width:54px;height:54px;border-radius:16px;display:grid;place-items:center;color:#fff;
  background:linear-gradient(135deg,#5B6CFF,#9B5CF6);box-shadow:0 18px 40px -14px rgba(91,108,255,.9);
  animation:bootPulse 1.8s ease-in-out infinite}
.boot-logo .ico{width:26px;height:26px}

/* ---------- Footer ---------- */
.footer{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;
  padding:16px 30px 22px;font-size:10.5px;color:#98A1B9}
.footer span:last-child{font-variant-numeric:tabular-nums}

/* ---------- Animations ---------- */
@keyframes viewIn{from{opacity:0;transform:translateY(10px)}to{opacity:1;transform:translateY(0)}}
@keyframes toastIn{from{opacity:0;transform:translateX(18px)}to{opacity:1;transform:translateX(0)}}
@keyframes toastBar{from{width:100%}to{width:0}}
@keyframes spin{to{transform:rotate(360deg)}}
@keyframes pulseDot{0%,100%{opacity:1}50%{opacity:.45}}
@keyframes bootPulse{0%,100%{transform:scale(1);box-shadow:0 18px 40px -14px rgba(91,108,255,.9)}50%{transform:scale(1.06);box-shadow:0 22px 50px -14px rgba(139,92,246,.95)}}

/* ---------- Responsive ---------- */
@media (max-width:1240px){
  .stat-grid{grid-template-columns:repeat(2,minmax(0,1fr))}
  .dash-grid{grid-template-columns:minmax(0,1fr)}
  .method-grid{grid-template-columns:repeat(2,minmax(0,1fr))}
  .settings-grid{grid-template-columns:minmax(0,1fr)}
}
@media (max-width:1100px){
  .pay-grid,.refund-grid{grid-template-columns:minmax(0,1fr)}
  .pay-side{grid-template-columns:repeat(2,minmax(0,1fr));align-items:start}
  .refund-side{grid-template-columns:repeat(2,minmax(0,1fr));align-items:start}
}
@media (max-width:1024px){
  .sidebar{transform:translateX(-100%);transition:transform .3s cubic-bezier(.3,.8,.3,1);box-shadow:30px 0 70px -30px rgba(5,10,30,.6)}
  .sidebar.open{transform:none}
  .sidebar-close{display:flex}
  .shell{margin-left:0}
  .menu-toggle{display:grid}
  .main{padding:24px 20px 36px}
  .topbar{padding:12px 20px}
  .sidebar-scrim{position:fixed;inset:0;background:rgba(10,16,36,.5);backdrop-filter:blur(3px);
    z-index:55;opacity:0;pointer-events:none;transition:.25s}
  .sidebar-scrim.open{opacity:1;pointer-events:auto}
}
@media (max-width:860px){
  .method-grid{grid-template-columns:minmax(0,1fr)}
  .pay-side{grid-template-columns:minmax(0,1fr)}
  .refund-side{grid-template-columns:minmax(0,1fr)}
  .page-head h1{font-size:24px}
}
@media (max-width:720px){
  .env-pill{display:none}
  .profile-meta{display:none}
  .profile > .ico{display:none}
  .search-box kbd{display:none}
  .topbar{gap:10px}
  .stat-grid{gap:12px}
  .stat-card{padding:17px}
  .stat-value{font-size:24px}
  .main{padding:20px 14px 32px}
  .topbar{padding:11px 14px}
  .page-head{margin-bottom:20px}
  .head-actions .btn{height:38px;font-size:12px}
  .field-grid{grid-template-columns:minmax(0,1fr)}
  .field-grid .span-2{grid-column:auto}
  .method-picker{grid-template-columns:minmax(0,1fr)}
  .pick{flex-direction:row;align-items:center;gap:11px;padding:12px 14px}
  .pick-text{flex:1}
  .pick .pick-check{position:static;margin-left:auto}
  .pick small{display:none}
  .rp-rows{grid-template-columns:1fr 1fr}
  .footer{padding:14px 16px 20px}
  .toast-stack{top:74px;right:12px}
  .modal{padding:22px}
}
@media (max-width:520px){
  .stat-grid{grid-template-columns:minmax(0,1fr)}
  .card-head{flex-wrap:wrap}
  .chart{gap:6px;padding:20px 14px 14px;height:210px}
  .bar-amount{font-size:8.5px}
  .activity-amt b{font-size:12px}
  .profile{padding:3px}
  .avatar{width:35px;height:35px}
  .icon-btn{width:36px;height:36px}
  .head-actions{width:100%}
  .head-actions .btn{flex:1}
  .quick-card{padding:22px}
  .quick-card h2{font-size:19px}
  .toolbar{flex-wrap:wrap}
  .toolbar-left{width:100%}
  .txn-search{flex:1 1 100%}
  .method-filter{flex:1 1 100%;width:100%}
  .form-actions{flex-direction:column-reverse}
  .form-actions .btn{width:100%}
  .srow.total b{font-size:17px}
  .search-box{max-width:none}
  .section-head h1{font-size:22px}
}
@media (prefers-reduced-motion:reduce){
  *,*::before,*::after{animation-duration:.001ms!important;transition-duration:.001ms!important}
}
</style>
</head>
<body>
""";

    static final String UI_BODY = """
<svg xmlns="http://www.w3.org/2000/svg" style="display:none" aria-hidden="true">
  <symbol id="i-bolt" viewBox="0 0 24 24"><path d="M13 2 4 14h6l-1 8 9-12h-6z"/></symbol>
  <symbol id="i-home" viewBox="0 0 24 24"><path d="M3 10.4 12 3l9 7.4V20a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z"/></symbol>
  <symbol id="i-card" viewBox="0 0 24 24"><rect x="2.5" y="5" width="19" height="14" rx="2.5"/><path d="M2.5 9.8h19"/><path d="M6 15.2h4.5"/></symbol>
  <symbol id="i-wallet" viewBox="0 0 24 24"><path d="M20 7H5a2 2 0 0 1 0-4h13v4"/><path d="M3 5.5V18a3 3 0 0 0 3 3h14a1 1 0 0 0 1-1V8a1 1 0 0 0-1-1H6"/><path d="M16.5 13.5h2.5"/></symbol>
  <symbol id="i-paypal" viewBox="0 0 24 24"><path d="M9.7 20.6H6.2L8.8 3.9h5.6c2.9 0 4.6 1.7 4.1 4.5-.5 3.1-2.8 4.8-5.9 4.8H10z"/><path d="M14.2 12.1c2.2.4 3.4 1.9 3 4.1-.4 2.5-2.4 4-5.2 4h-2.1l.9-4.9" opacity=".5"/></symbol>
  <symbol id="i-bank" viewBox="0 0 24 24"><path d="M3 9.5 12 4l9 5.5"/><path d="M5 10v8.5M9.5 10v8.5M14.5 10v8.5M19 10v8.5M3 21h18"/></symbol>
  <symbol id="i-send" viewBox="0 0 24 24"><path d="M21 3 10.5 13.5"/><path d="M21 3l-6.8 18-3.7-7.5L3 9.8z"/></symbol>
  <symbol id="i-list" viewBox="0 0 24 24"><path d="M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01"/></symbol>
  <symbol id="i-refund" viewBox="0 0 24 24"><path d="M3 8h5V3"/><path d="M3.5 8a9 9 0 1 0 .9-2.4"/></symbol>
  <symbol id="i-settings" viewBox="0 0 24 24"><path d="M4 21v-6M4 11V3M12 21v-9M12 8V3M20 21v-4M20 13V3M1.5 15h5M9.5 8h5M17.5 17h5"/></symbol>
  <symbol id="i-search" viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.6-3.6"/></symbol>
  <symbol id="i-bell" viewBox="0 0 24 24"><path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.7 21a2 2 0 0 1-3.4 0"/></symbol>
  <symbol id="i-chevron" viewBox="0 0 24 24"><path d="m6 9 6 6 6-6"/></symbol>
  <symbol id="i-menu" viewBox="0 0 24 24"><path d="M4 7h16M4 12h16M4 17h16"/></symbol>
  <symbol id="i-close" viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18"/></symbol>
  <symbol id="i-check" viewBox="0 0 24 24"><path d="m5 13 4 4L19 7"/></symbol>
  <symbol id="i-alert" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 8v5M12 16.5h.01"/></symbol>
  <symbol id="i-info" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 7.5h.01"/></symbol>
  <symbol id="i-shield" viewBox="0 0 24 24"><path d="M12 3l7 3v6c0 4.4-2.9 7.4-7 9-4.1-1.6-7-4.6-7-9V6z"/><path d="m9 12 2 2 4-4"/></symbol>
  <symbol id="i-download" viewBox="0 0 24 24"><path d="M12 3v12m0 0 4-4m-4 4-4-4"/><path d="M4 20h16"/></symbol>
  <symbol id="i-plus" viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></symbol>
  <symbol id="i-trash" viewBox="0 0 24 24"><path d="M4 7h16M9 7V5h6v2M7 7l1 13h8l1-13"/></symbol>
  <symbol id="i-arrow-right" viewBox="0 0 24 24"><path d="M5 12h14m-6-6 6 6-6 6"/></symbol>
  <symbol id="i-arrow-up-right" viewBox="0 0 24 24"><path d="M7 17 17 7M8 7h9v9"/></symbol>
  <symbol id="i-lock" viewBox="0 0 24 24"><rect x="4.5" y="10.5" width="15" height="10" rx="2.5"/><path d="M8 10.5V8a4 4 0 0 1 8 0v2.5"/></symbol>
  <symbol id="i-user" viewBox="0 0 24 24"><circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/></symbol>
  <symbol id="i-clock" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></symbol>
  <symbol id="i-refresh" viewBox="0 0 24 24"><path d="M21 12a9 9 0 1 1-2.6-6.4M21 3v6h-6"/></symbol>
  <symbol id="i-external" viewBox="0 0 24 24"><path d="M14 4h6v6M20 4l-9 9"/><path d="M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/></symbol>
  <symbol id="i-spark" viewBox="0 0 24 24"><path d="M12 3v4M12 17v4M3 12h4M17 12h4M5.6 5.6l2.8 2.8M15.6 15.6l2.8 2.8M18.4 5.6l-2.8 2.8M8.4 15.6l-2.8 2.8"/></symbol>
  <symbol id="i-eye" viewBox="0 0 24 24"><path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/></symbol>
</svg>

<aside class="sidebar" id="sidebar" aria-label="Main navigation">
  <div class="brand">
    <div class="brand-mark"><svg class="ico ico--fill"><use href="#i-bolt"></use></svg></div>
    <div class="brand-text"><strong>PayFlow</strong><span>Payment Management</span></div>
    <button class="sidebar-close" id="sidebarClose" aria-label="Close navigation"><svg class="ico"><use href="#i-close"></use></svg></button>
  </div>

  <nav class="nav">
    <p class="nav-label">Workspace</p>
    <button class="nav-item active" data-view="dashboard"><svg class="ico"><use href="#i-home"></use></svg><span>Dashboard</span></button>
    <button class="nav-item" data-view="methods"><svg class="ico"><use href="#i-card"></use></svg><span>Payment Methods</span></button>
    <button class="nav-item" data-view="pay"><svg class="ico"><use href="#i-send"></use></svg><span>Make Payment</span></button>
    <p class="nav-label">Operations</p>
    <button class="nav-item" data-view="transactions"><svg class="ico"><use href="#i-list"></use></svg><span>Transactions</span><span class="nav-count" id="navTxnCount">0</span></button>
    <button class="nav-item" data-view="refunds"><svg class="ico"><use href="#i-refund"></use></svg><span>Refunds</span></button>
    <p class="nav-label">System</p>
    <button class="nav-item" data-view="settings"><svg class="ico"><use href="#i-settings"></use></svg><span>Settings</span></button>
  </nav>

  <div class="sidebar-bottom">
    <div class="api-status">
      <span class="dot"></span>
      <div><strong>API connected</strong><span id="apiStatusText">/api/state</span></div>
    </div>
    <div class="support-card">
      <svg class="ico"><use href="#i-shield"></use></svg>
      <strong>Demo environment</strong>
      <p>Every payment is validated and settled by the local Java gateway.</p>
      <button class="link-btn" data-view="settings">View API reference <svg class="ico"><use href="#i-arrow-right"></use></svg></button>
    </div>
  </div>
</aside>
<div class="sidebar-scrim" id="sidebarScrim"></div>

<div class="shell">
  <header class="topbar">
    <button class="icon-btn menu-toggle" id="menuBtn" aria-label="Open navigation"><svg class="ico"><use href="#i-menu"></use></svg></button>
    <div class="search-box">
      <svg class="ico"><use href="#i-search"></use></svg>
      <input id="globalSearch" type="search" placeholder="Search transactions, IDs, methods..." autocomplete="off" aria-label="Search transactions">
      <kbd>/</kbd>
    </div>
    <div class="topbar-actions">
      <span class="env-pill"><span class="pulse"></span>Live API</span>
      <button class="icon-btn" id="bellBtn" aria-label="Notifications"><svg class="ico"><use href="#i-bell"></use></svg><span class="badge-dot" id="bellDot"></span></button>
      <button class="profile" id="profileBtn" aria-label="Account menu">
        <span class="avatar">AK</span>
        <span class="profile-meta"><strong>Arjun Kumar</strong><small>Finance Admin</small></span>
        <svg class="ico"><use href="#i-chevron"></use></svg>
      </button>

      <div class="notif-panel" id="notifPanel">
        <div class="notif-head"><strong>Notifications</strong><button id="markRead">Mark all read</button></div>
        <div class="notif-list" id="notifList"></div>
      </div>

      <div class="profile-menu" id="profileMenu">
        <div class="menu-user"><strong>Arjun Kumar</strong><span>arjun@payflow.in</span></div>
        <button data-view="settings"><svg class="ico"><use href="#i-user"></use></svg>Profile &amp; settings</button>
        <button data-view="transactions"><svg class="ico"><use href="#i-list"></use></svg>Transaction history</button>
        <button data-view="refunds"><svg class="ico"><use href="#i-refund"></use></svg>Refund centre</button>
        <button id="refreshBtn"><svg class="ico"><use href="#i-refresh"></use></svg>Refresh data</button>
      </div>
    </div>
  </header>

  <main class="main">
    <!-- DASHBOARD -->
    <section class="view active" id="view-dashboard">
      <div class="page-head">
        <div>
          <p class="eyebrow" id="dashDate">Today</p>
          <h1 id="dashGreeting">Welcome back, Arjun</h1>
          <p class="sub">Here is what is happening across your payment operations.</p>
        </div>
        <div class="head-actions">
          <button class="btn btn-ghost" id="exportBtn"><svg class="ico"><use href="#i-download"></use></svg>Export</button>
          <button class="btn btn-primary" data-view="pay"><svg class="ico"><use href="#i-plus"></use></svg>New payment</button>
        </div>
      </div>

      <div class="stat-grid">
        <article class="card stat-card">
          <div class="stat-top">
            <div class="stat-icon tint-indigo"><svg class="ico"><use href="#i-list"></use></svg></div>
            <span class="trend neutral" id="statCountTrend">0 today</span>
          </div>
          <div>
            <h3>Total Transactions</h3>
            <div class="stat-value" id="statCount">0</div>
          </div>
          <div class="stat-foot"><span class="trend neutral">All channels</span>attempts recorded</div>
        </article>
        <article class="card stat-card">
          <div class="stat-top">
            <div class="stat-icon tint-green"><svg class="ico"><use href="#i-check"></use></svg></div>
            <span class="trend up" id="statSuccessRate">0%</span>
          </div>
          <div>
            <h3>Successful Payments</h3>
            <div class="stat-value" id="statSuccess">0</div>
          </div>
          <div class="stat-foot"><span class="trend up">Cleared</span>authorized by the gateway</div>
        </article>
        <article class="card stat-card">
          <div class="stat-top">
            <div class="stat-icon tint-red"><svg class="ico"><use href="#i-alert"></use></svg></div>
            <span class="trend warn" id="statFailedRate">0%</span>
          </div>
          <div>
            <h3>Failed Payments</h3>
            <div class="stat-value" id="statFailed">0</div>
          </div>
          <div class="stat-foot"><span class="trend warn">Review</span>validation declines</div>
        </article>
        <article class="card stat-card">
          <div class="stat-top">
            <div class="stat-icon tint-purple"><svg class="ico"><use href="#i-wallet"></use></svg></div>
            <span class="trend up" id="statRefunded">0 refunded</span>
          </div>
          <div>
            <h3>Total Amount Processed</h3>
            <div class="stat-value small" id="statAmount">&#8377;0.00</div>
          </div>
          <div class="stat-foot"><span class="trend neutral">Settled</span>lifetime volume</div>
        </article>
      </div>

      <div class="dash-grid">
        <article class="card">
          <div class="card-head">
            <div><h2>Payment volume</h2><p id="chartSub">No activity in the last 7 days</p></div>
            <span class="chip"><svg class="ico"><use href="#i-clock"></use></svg>Last 7 days</span>
          </div>
          <div class="chart" id="volumeChart"></div>
        </article>
        <article class="card">
          <div class="card-head">
            <div><h2>Payment mix</h2><p>Share of processed volume</p></div>
            <span class="chip">By method</span>
          </div>
          <div class="mix-body">
            <div class="donut" id="mixDonut">
              <div class="donut-center"><strong id="mixTotal">0</strong><small>payments</small></div>
            </div>
            <ul class="legend" id="mixLegend"></ul>
          </div>
        </article>
      </div>

      <div class="dash-grid lower">
        <article class="card">
          <div class="card-head">
            <div><h2>Recent activity</h2><p>Latest payments across every method</p></div>
            <button class="text-btn" data-view="transactions">View all</button>
          </div>
          <div class="activity-list" id="activityList"></div>
        </article>
        <article class="card quick-card">
          <span class="chip"><svg class="ico"><use href="#i-spark"></use></svg>Fast settlement</span>
          <div>
            <h2>Move money with confidence.</h2>
            <p>Cards, PayPal and bank transfers flow through one validated Java gateway, with refunds handled by each payment method.</p>
          </div>
          <div class="quick-actions">
            <button class="btn btn-white" data-view="pay">Make a payment</button>
            <button class="btn btn-light" data-view="transactions">View history</button>
          </div>
        </article>
      </div>
    </section>

    <!-- PAYMENT METHODS -->
    <section class="view" id="view-methods">
      <div class="page-head">
        <div>
          <p class="eyebrow">Accept payments</p>
          <h1>Payment methods</h1>
          <p class="sub">Three channels, one gateway. Save a method on this device for faster checkout during your demo.</p>
        </div>
        <div class="head-actions">
          <button class="btn btn-primary" data-view="pay"><svg class="ico"><use href="#i-send"></use></svg>Make payment</button>
        </div>
      </div>
      <div class="method-grid" id="methodGrid"></div>
      <div class="card info-strip">
        <div class="ico-badge"><svg class="ico"><use href="#i-shield"></use></svg></div>
        <div>
          <b>Gateway-grade validation</b>
          <p>Card numbers use the Luhn checksum, expiry dates must be in the future, IFSC codes are format checked and amounts are capped at &#8377;1,00,000. No payment result is simulated.</p>
        </div>
        <span class="chip"><svg class="ico"><use href="#i-lock"></use></svg>Served by Java 17+</span>
      </div>
    </section>

    <!-- MAKE PAYMENT -->
    <section class="view" id="view-pay">
      <div class="page-head">
        <div>
          <p class="eyebrow">Checkout</p>
          <h1>Make a payment</h1>
          <p class="sub">Choose a channel, enter the details and the Java gateway will authorize it live.</p>
        </div>
        <div class="head-actions">
          <span class="chip"><svg class="ico"><use href="#i-lock"></use></svg>PCI-style validation</span>
        </div>
      </div>

      <div class="pay-grid">
        <form class="card pay-form" id="payForm" novalidate>
          <div class="form-head">
            <div><h2>Payment details</h2><p>All fields are verified before a transaction is created.</p></div>
            <span class="secure-tag"><svg class="ico"><use href="#i-lock"></use></svg>Secured</span>
          </div>

          <span class="method-label">Payment method</span>
          <div class="method-picker" id="methodPicker"></div>

          <div class="field-grid" id="payFields"></div>

          <div class="field" style="margin-top:18px">
            <label for="payAmount">Amount <span class="hint">Limit &#8377;1,00,000</span></label>
            <div class="amount-wrap">
              <span class="currency">&#8377;</span>
              <input id="payAmount" name="amount" inputmode="decimal" placeholder="0.00" autocomplete="off">
              <span class="amount-hint">INR</span>
            </div>
            <p class="field-error" id="payAmountError"></p>
            <div class="quick-amounts">
              <button type="button" data-amount="500">&#8377;500</button>
              <button type="button" data-amount="1000">&#8377;1,000</button>
              <button type="button" data-amount="2500">&#8377;2,500</button>
              <button type="button" data-amount="5000">&#8377;5,000</button>
            </div>
          </div>

          <div class="form-actions">
            <button type="button" class="btn btn-ghost" id="cancelPay">Cancel</button>
            <button type="submit" class="btn btn-primary" id="paySubmit">
              <span class="btn-label">Pay Now</span><span class="spinner"></span>
            </button>
          </div>
        </form>

        <aside class="pay-side">
          <div class="card summary-card">
            <h2>Order summary</h2>
            <div class="summary-method">
              <div class="mini-icon mi-cc" id="summaryIcon"><svg class="ico"><use href="#i-card"></use></svg></div>
              <div><strong id="summaryName">Credit Card</strong><small id="summaryMask">Card &#8226;&#8226;&#8226;&#8226; ----</small></div>
            </div>
            <div class="srow"><span>Amount</span><b id="summaryAmount">&#8377;0.00</b></div>
            <div class="srow"><span>Processing fee</span><b>&#8377;0.00</b></div>
            <div class="srow total"><span>Total payable</span><b id="summaryTotal">&#8377;0.00</b></div>
            <div class="summary-note"><svg class="ico"><use href="#i-shield"></use></svg><span>Card data is validated in memory by the gateway and never stored.</span></div>
          </div>

          <div class="card tips-card">
            <h3><svg class="ico"><use href="#i-spark"></use></svg>Demo credentials</h3>
            <dl>
              <div><dt>Credit card</dt><dd>4111 1111 1111 1111 &#183; 12/28 &#183; 123</dd></div>
              <div><dt>PayPal</dt><dd>demo@payflow.in</dd></div>
              <div><dt>Bank transfer</dt><dd>123456789012 &#183; HDFC0001234 &#183; HDFC Bank</dd></div>
            </dl>
          </div>

          <div id="payResult"></div>
        </aside>
      </div>
    </section>

    <!-- TRANSACTIONS -->
    <section class="view" id="view-transactions">
      <div class="page-head">
        <div>
          <p class="eyebrow">Ledger</p>
          <h1>Transactions</h1>
          <p class="sub">Every attempt is recorded by the gateway, including validation failures.</p>
        </div>
        <div class="head-actions">
          <button class="btn btn-ghost" id="exportBtn2"><svg class="ico"><use href="#i-download"></use></svg>Export CSV</button>
          <button class="btn btn-primary" data-view="pay"><svg class="ico"><use href="#i-plus"></use></svg>New payment</button>
        </div>
      </div>

      <div class="card table-card" id="txnTableCard">
        <div class="toolbar">
          <div class="toolbar-left">
            <div class="txn-search">
              <svg class="ico"><use href="#i-search"></use></svg>
              <input id="txnSearch" type="search" placeholder="Search by ID, method or amount" autocomplete="off">
            </div>
            <div class="chips" id="filterChips"></div>
          </div>
          <select class="select method-filter" id="methodFilter" aria-label="Filter by payment method">
            <option value="all">All methods</option>
          </select>
        </div>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Transaction ID</th>
                <th>Payment Method</th>
                <th>Amount</th>
                <th>Date</th>
                <th>Status</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody id="txnBody"></tbody>
          </table>
        </div>
        <div class="empty hidden" id="txnEmpty"></div>
        <div class="table-foot" id="txnFoot"></div>
      </div>
    </section>

    <!-- REFUNDS -->
    <section class="view" id="view-refunds">
      <div class="page-head">
        <div>
          <p class="eyebrow">Aftercare</p>
          <h1>Refunds</h1>
          <p class="sub">Refunds are processed by the same payment method that captured the funds.</p>
        </div>
        <div class="head-actions">
          <span class="chip"><svg class="ico"><use href="#i-refund"></use></svg>Method-aware refunds</span>
        </div>
      </div>

      <div class="refund-grid">
        <div class="card refund-form-card">
          <div class="form-head">
            <div><h2>Issue a refund</h2><p>Select a successful transaction and choose an amount.</p></div>
            <span class="secure-tag"><svg class="ico"><use href="#i-shield"></use></svg>Protected</span>
          </div>

          <div class="field">
            <label for="refundTxn">Transaction</label>
            <select class="select" id="refundTxn"></select>
            <p class="field-error" id="refundTxnError"></p>
          </div>

          <div class="refund-preview" id="refundPreview"></div>

          <div class="field">
            <label for="refundAmount">Refund amount <button type="button" class="full-link" id="refundFull">Refund full amount</button></label>
            <div class="amount-wrap" style="height:54px">
              <span class="currency" style="font-size:19px">&#8377;</span>
              <input id="refundAmount" inputmode="decimal" placeholder="0.00" autocomplete="off" style="font-size:20px">
              <span class="amount-hint">INR</span>
            </div>
            <p class="field-error" id="refundAmountError"></p>
          </div>

          <div class="form-actions">
            <button type="button" class="btn btn-ghost" id="clearRefund">Clear</button>
            <button type="button" class="btn btn-primary" id="refundSubmit"><span class="btn-label">Refund payment</span><span class="spinner"></span></button>
          </div>

          <div id="refundResult" style="margin-top:18px"></div>
        </div>

        <div class="refund-side">
          <div class="card policy-card">
            <h2>Refund policy</h2>
            <ul>
              <li><svg class="ico"><use href="#i-check"></use></svg><span>Only successful payments can be refunded.</span></li>
              <li><svg class="ico"><use href="#i-check"></use></svg><span>Partial refunds are supported up to the remaining balance.</span></li>
              <li><svg class="ico"><use href="#i-check"></use></svg><span>Pending bank transfers unlock once confirmed.</span></li>
              <li><svg class="ico"><use href="#i-check"></use></svg><span>Settlement time depends on the original method.</span></li>
            </ul>
          </div>
          <div class="card refund-list-card">
            <h2>Recent refunds</h2>
            <div class="refund-list" id="refundList"></div>
          </div>
        </div>
      </div>
    </section>

    <!-- SETTINGS -->
    <section class="view" id="view-settings">
      <div class="page-head">
        <div>
          <p class="eyebrow">Workspace</p>
          <h1>Settings</h1>
          <p class="sub">Presentation preferences and the API endpoints powering this dashboard.</p>
        </div>
      </div>

      <div class="settings-grid">
        <div class="card settings-card">
          <h2>Profile</h2>
          <p class="card-sub">The administrator signed into this demo workspace.</p>
          <div class="profile-big">
            <div class="avatar avatar-big">AK</div>
            <div>
              <strong>Arjun Kumar</strong>
              <span>arjun@payflow.in</span>
              <span class="chip">Finance Admin</span>
            </div>
          </div>
          <div class="kv"><span>Workspace</span><b>PayFlow Operations</b></div>
          <div class="kv"><span>Currency</span><b>INR (&#8377;)</b></div>
          <div class="kv"><span>Session</span><b>Local demo session</b></div>
          <div class="kv"><span>Server time</span><b id="settingsServerTime">-</b></div>
        </div>

        <div class="card settings-card">
          <h2>Preferences</h2>
          <p class="card-sub">Stored in this browser only, presentation only.</p>
          <div class="switch-row">
            <div><strong>Toast notifications</strong><small>Show confirmations for payments and refunds.</small></div>
            <label class="switch"><input type="checkbox" id="prefToasts" checked><span class="track"></span></label>
          </div>
          <div class="switch-row">
            <div><strong>Auto-refresh</strong><small>Poll the gateway every 15 seconds for settlement updates.</small></div>
            <label class="switch"><input type="checkbox" id="prefAuto" checked><span class="track"></span></label>
          </div>
          <div class="switch-row">
            <div><strong>Compact transactions</strong><small>Reduce row height in the transaction ledger.</small></div>
            <label class="switch"><input type="checkbox" id="prefCompact"><span class="track"></span></label>
          </div>
        </div>

        <div class="card settings-card">
          <h2>API reference</h2>
          <p class="card-sub">The frontend consumes the original endpoints. No backend changes.</p>
          <div class="endpoint"><span class="verb get">GET</span><code>/</code><span>Dashboard</span></div>
          <div class="endpoint"><span class="verb get">GET</span><code>/api/state</code><span>Totals + ledger</span></div>
          <div class="endpoint"><span class="verb get">GET</span><code>/api/methods</code><span>Channel catalogue</span></div>
          <div class="endpoint"><span class="verb post">POST</span><code>/api/pay</code><span>Process a payment</span></div>
          <div class="endpoint"><span class="verb post">POST</span><code>/api/refund</code><span>Refund a payment</span></div>
          <div class="tech-chips">
            <span class="tech-chip">Payment</span>
            <span class="tech-chip">CreditCardPayment</span>
            <span class="tech-chip">PayPalPayment</span>
            <span class="tech-chip">BankTransferPayment</span>
            <span class="tech-chip">PaymentValidator</span>
            <span class="tech-chip">PaymentGateway</span>
            <span class="tech-chip">Transaction</span>
          </div>
        </div>

        <div class="card settings-card">
          <h2>Demo dataset</h2>
          <p class="card-sub">Populate the dashboard for a presentation.</p>
          <div class="demo-box">
            <svg class="ico"><use href="#i-info"></use></svg>
            <p><strong>Uses the live API.</strong>Each sample is posted to <code>/api/pay</code>, so validation, pending bank settlements and refunds behave exactly like real activity.</p>
          </div>
          <div class="settings-actions">
            <button class="btn btn-primary btn-sm" id="demoBtn"><span class="btn-label">Generate sample activity</span><span class="spinner"></span></button>
            <button class="btn btn-ghost btn-sm" id="refreshBtn2"><svg class="ico"><use href="#i-refresh"></use></svg>Refresh</button>
          </div>
          <p class="card-sub" id="demoStatus" style="margin-top:12px;margin-bottom:0"></p>
        </div>

        <div class="card settings-card">
          <h2>About PayFlow</h2>
          <p class="card-sub">Premium dashboard over the original Java OOP gateway.</p>
          <div class="kv"><span>Frontend</span><b>HTML, CSS, vanilla JS</b></div>
          <div class="kv"><span>Backend</span><b>Java HttpServer, JSON</b></div>
          <div class="kv"><span>Architecture</span><b>Inheritance + dynamic polymorphism</b></div>
          <div class="kv"><span>Run command</span><b>javac PaymentApp.java &amp;&amp; java PaymentApp</b></div>
          <div class="kv"><span>Version</span><b>PayFlow 2.0</b></div>
        </div>
      </div>
    </section>
  </main>

  <footer class="footer">
    <span>PayFlow &#183; Online Payment Processing System</span>
    <span>Java 17+ &#183; OOP gateway</span>
    <span id="footerTime"></span>
  </footer>
</div>

<div class="toast-stack" id="toastStack" aria-live="polite"></div>

<div class="modal-scrim" id="modalScrim">
  <div class="modal" role="dialog" aria-modal="true" aria-labelledby="modalTitle">
    <div class="modal-icon" id="modalIcon"></div>
    <h3 id="modalTitle">Confirm</h3>
    <p id="modalBody"></p>
    <div class="modal-summary hidden" id="modalSummary"></div>
    <div class="modal-actions">
      <button class="btn btn-ghost" id="modalCancel">Cancel</button>
      <button class="btn btn-primary" id="modalConfirm">Confirm</button>
    </div>
  </div>
</div>

<div class="modal-scrim" id="detailScrim">
  <div class="modal modal-wide" role="dialog" aria-modal="true" aria-labelledby="detailTitle">
    <div class="modal-head">
      <h3 id="detailTitle">Transaction details</h3>
      <button class="modal-close" id="detailClose" aria-label="Close"><svg class="ico"><use href="#i-close"></use></svg></button>
    </div>
    <div id="detailBody"></div>
  </div>
</div>

<div class="boot" id="boot">
  <div class="boot-inner">
    <div class="boot-logo"><svg class="ico ico--fill"><use href="#i-bolt"></use></svg></div>
    <span>Loading your workspace</span>
  </div>
</div>
""";

    static final String UI_SCRIPT_ONE = """
<script>
(function () {
  'use strict';

  const $ = (selector, root) => (root || document).querySelector(selector);
  const $$ = (selector, root) => Array.from((root || document).querySelectorAll(selector));
  const STORE = { saved: 'payflow.saved', prefs: 'payflow.prefs', seen: 'payflow.seen' };

  const METHOD_META = {
    credit_card: { icon: 'i-card', cls: 'mi-cc', accent: 'cc', short: 'Visa, Mastercard & more', color: '#5567FF' },
    paypal: { icon: 'i-paypal', cls: 'mi-pp', accent: 'paypal', short: 'PayPal balance', color: '#2F6FED' },
    bank_transfer: { icon: 'i-bank', cls: 'mi-bt', accent: 'bank', short: 'NEFT / IMPS', color: '#12B76A' }
  };

  const STATUS = {
    SUCCESS: { label: 'Success', cls: 'pill-success' },
    PENDING: { label: 'Pending', cls: 'pill-pending' },
    FAILED: { label: 'Failed', cls: 'pill-failed' },
    REFUNDED: { label: 'Refunded', cls: 'pill-refunded' }
  };

  const state = {
    data: null,
    methods: [],
    payMethod: 'credit_card',
    filter: 'all',
    methodFilter: 'all',
    search: '',
    saved: [],
    prefs: { toasts: true, auto: true, compact: false },
    lastPayResult: null,
    confirming: null
  };

  /* ---------- helpers ---------- */
  const esc = (value) => String(value == null ? '' : value).replace(/[&<>"']/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
  const moneyFmt = new Intl.NumberFormat('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const money = (value) => '₹' + moneyFmt.format(Number(value) || 0);
  const digits = (value) => String(value || '').replace(/[^0-9]/g, '');

  function shortMoney(value) {
    const v = Number(value) || 0;
    if (v >= 10000000) return '₹' + (v / 10000000).toFixed(1) + 'Cr';
    if (v >= 100000) return '₹' + (v / 100000).toFixed(1) + 'L';
    if (v >= 1000) return '₹' + (v / 1000).toFixed(1) + 'k';
    return '₹' + Math.round(v);
  }

  function relativeTime(iso) {
    const then = new Date(iso).getTime();
    if (isNaN(then)) return '';
    const diff = Math.max(0, Date.now() - then);
    const mins = Math.floor(diff / 60000);
    if (mins < 1) return 'just now';
    if (mins < 60) return mins + 'm ago';
    const hours = Math.floor(mins / 60);
    if (hours < 24) return hours + 'h ago';
    const days = Math.floor(hours / 24);
    if (days < 7) return days + 'd ago';
    return new Date(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
  }

  function timeOnly(iso) {
    const d = new Date(iso);
    if (isNaN(d.getTime())) return '';
    return d.toLocaleTimeString('en-IN', { hour: 'numeric', minute: '2-digit' });
  }

  function maskFor(method, fields) {
    if (method === 'credit_card') {
      const d = digits(fields.cardNumber);
      if (d.length < 4) return 'Card •••• ----';
      const last4 = d.slice(-4);
      const first = d.charAt(0);
      const network = first === '4' ? 'Visa' : first === '5' ? 'Mastercard' : first === '3' ? 'Amex' : first === '6' ? 'RuPay' : 'Card';
      return network + ' •••• ' + last4;
    }
    if (method === 'paypal') return fields.email || 'your PayPal email';
    const d = digits(fields.accountNumber);
    if (d.length < 4) return 'Bank •••• ----';
    return (fields.bankName || 'Bank') + ' •••• ' + d.slice(-4);
  }

  function loadSaved() {
    try {
      const raw = JSON.parse(localStorage.getItem(STORE.saved) || '[]');
      return Array.isArray(raw) ? raw.filter((item) => item && item.method) : [];
    } catch (e) { return []; }
  }
  function persistSaved() {
    try { localStorage.setItem(STORE.saved, JSON.stringify(state.saved)); } catch (e) { /* ignore */ }
  }
  function loadPrefs() {
    const base = { toasts: true, auto: true, compact: false };
    try {
      const raw = JSON.parse(localStorage.getItem(STORE.prefs) || '{}');
      return Object.assign(base, raw && typeof raw === 'object' ? raw : {});
    } catch (e) { return base; }
  }
  function persistPrefs() {
    try { localStorage.setItem(STORE.prefs, JSON.stringify(state.prefs)); } catch (e) { /* ignore */ }
  }

  async function getJson(path) {
    const response = await fetch(path, { headers: { 'Accept': 'application/json' } });
    if (!response.ok) throw new Error('Request failed with status ' + response.status);
    return response.json();
  }
  async function postJson(path, payload) {
    const response = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    if (!response.ok) throw new Error('Request failed with status ' + response.status);
    return response.json();
  }

  /* ---------- toasts & modal ---------- */
  function toast(type, title, message) {
    if (!state.prefs.toasts && type !== 'error') return;
    const stack = $('#toastStack');
    if (stack.children.length >= 4) stack.removeChild(stack.firstElementChild);
    const icon = type === 'success' ? 'i-check' : type === 'error' ? 'i-alert' : 'i-info';
    const el = document.createElement('div');
    el.className = 'toast ' + type;
    el.innerHTML = '<div class="toast-icon"><svg class="ico"><use href="#' + icon + '"></use></svg></div>' +
      '<div class="toast-body"><strong>' + esc(title) + '</strong><p>' + esc(message || '') + '</p></div>' +
      '<button class="toast-close" aria-label="Dismiss"><svg class="ico"><use href="#i-close"></use></svg></button>';
    stack.appendChild(el);
    const timer = setTimeout(() => el.remove(), 4600);
    $('.toast-close', el).addEventListener('click', () => { clearTimeout(timer); el.remove(); });
  }

  function openConfirm(options) {
    $('#modalIcon').className = 'modal-icon ' + (options.tone || 'primary');
    $('#modalIcon').innerHTML = '<svg class="ico"><use href="#' + (options.icon || 'i-info') + '"></use></svg>';
    $('#modalTitle').textContent = options.title || 'Please confirm';
    $('#modalBody').textContent = options.body || '';
    const summary = $('#modalSummary');
    if (options.summary) { summary.innerHTML = options.summary; summary.classList.remove('hidden'); }
    else { summary.innerHTML = ''; summary.classList.add('hidden'); }
    const confirm = $('#modalConfirm');
    confirm.textContent = options.confirmText || 'Confirm';
    confirm.className = 'btn ' + (options.danger ? 'btn-danger' : 'btn-primary');
    confirm.disabled = false;
    state.confirming = options.onConfirm || null;
    $('#modalScrim').classList.add('open');
  }

  function closeConfirm() {
    $('#modalScrim').classList.remove('open');
    state.confirming = null;
  }

  function openDetails(transactionId) {
    const t = (state.data ? state.data.transactions : []).find((item) => item.id === transactionId);
    if (!t) return;
    const meta = METHOD_META[t.methodKey] || METHOD_META.credit_card;
    const status = STATUS[t.status] || STATUS.SUCCESS;
    let history = '';
    if (t.refunds && t.refunds.length) {
      history = '<div class="refund-history"><h4>Refund history</h4>' + t.refunds.map((r) =>
        '<div><span>' + esc(r.id) + ' • ' + esc(r.dateLabel) + '</span><b>' + money(r.amount) + '</b></div>').join('') + '</div>';
    }
    $('#detailBody').innerHTML =
      '<div class="detail-hero"><div class="mini-icon ' + meta.cls + '"><svg class="ico ' + (t.methodKey === 'paypal' ? 'ico--fill' : '') + '"><use href="#' + meta.icon + '"></use></svg></div>' +
      '<div><strong>' + money(t.amount) + '</strong><small>' + esc(t.id) + ' • ' + esc(t.dateLabel) + '</small></div>' +
      '<span class="pill ' + status.cls + '">' + status.label + '</span></div>' +
      '<div class="detail-rows">' +
      '<div><span>Payment method</span><b>' + esc(t.methodName) + '</b></div>' +
      '<div><span>Instrument</span><b>' + esc(t.masked) + '</b></div>' +
      '<div><span>Gateway status</span><b>' + status.label + '</b></div>' +
      '<div><span>Refunded</span><b>' + money(t.refundedAmount) + '</b></div>' +
      '<div><span>Remaining refundable</span><b>' + money(t.remainingRefundable) + '</b></div>' +
      '</div>' +
      '<div class="detail-message">' + esc(t.message || 'No gateway message.') + '</div>' +
      history;
    $('#detailScrim').classList.add('open');
  }

  function closeDetails() {
    $('#detailScrim').classList.remove('open');
  }

  /* ---------- navigation ---------- */
  const VIEWS = ['dashboard', 'methods', 'pay', 'transactions', 'refunds', 'settings'];

  function showView(name) {
    if (VIEWS.indexOf(name) === -1) name = 'dashboard';
    $$('.view').forEach((view) => view.classList.toggle('active', view.id === 'view-' + name));
    $$('.nav-item').forEach((item) => item.classList.toggle('active', item.dataset.view === name));
    closeSidebar();
    closeMenus();
    if (location.hash !== '#' + name) {
      try { history.replaceState(null, '', '#' + name); } catch (e) { location.hash = name; }
    }
    if (name === 'transactions') renderTransactions();
    if (name === 'refunds') renderRefunds();
    if (name === 'settings') renderSettings();
    if (name !== 'pay') $('#payResult').innerHTML = '';
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function openSidebar() {
    $('#sidebar').classList.add('open');
    $('#sidebarScrim').classList.add('open');
  }
  function closeSidebar() {
    $('#sidebar').classList.remove('open');
    $('#sidebarScrim').classList.remove('open');
  }
  function closeMenus() {
    $('#notifPanel').classList.remove('open');
    $('#profileMenu').classList.remove('open');
  }

  /* ---------- data loading ---------- */
  async function loadMethods() {
    try {
      const payload = await getJson('/api/methods');
      state.methods = Array.isArray(payload.methods) ? payload.methods : [];
    } catch (e) {
      state.methods = [
        { key: 'credit_card', name: 'Credit Card', description: 'Visa, Mastercard, Amex & RuPay', tag: 'Instant authorization' },
        { key: 'paypal', name: 'PayPal', description: 'Pay with your PayPal balance', tag: 'Instant settlement' },
        { key: 'bank_transfer', name: 'Bank Transfer', description: 'NEFT / IMPS to your bank account', tag: 'Confirmed in seconds' }
      ];
    }
    renderMethodPicker();
    renderMethods();
    renderMethodFilter();
  }

  async function refreshState(options) {
    try {
      state.data = await getJson('/api/state');
      renderAll();
      if (options && options.toast) toast('info', 'Fresh data', 'Ledger synced with the Java gateway.');
      return true;
    } catch (error) {
      $('#apiStatusText').textContent = 'offline - retrying';
      if (options && options.toast) toast('error', 'Gateway unreachable', error.message);
      return false;
    }
  }

  function renderAll() {
    renderSummary();
    renderChart();
    renderMix();
    renderActivity();
    renderTransactions();
    renderRefunds();
    renderNotifications();
    renderSettings();
    const count = state.data ? state.data.transactions.length : 0;
    $('#navTxnCount').textContent = count;
    $('#footerTime').textContent = state.data && state.data.serverTime ? 'Synced ' + state.data.serverTime : '';
  }

  /* ---------- dashboard ---------- */
  function animateValue(el, value, formatter) {
    const from = typeof el._last === 'number' ? el._last : 0;
    el._last = value;
    const start = performance.now();
    const duration = 620;
    function frame(now) {
      const progress = Math.min(1, (now - start) / duration);
      const eased = 1 - Math.pow(1 - progress, 3);
      el.textContent = formatter(from + (value - from) * eased);
      if (progress < 1) requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
  }

  function renderSummary() {
    const totals = state.data ? state.data.totals : { count: 0, success: 0, failed: 0, pending: 0, refunded: 0, processedAmount: 0, refundedAmount: 0 };
    animateValue($('#statCount'), totals.count, (v) => Math.round(v).toLocaleString('en-IN'));
    animateValue($('#statSuccess'), totals.success, (v) => Math.round(v).toLocaleString('en-IN'));
    animateValue($('#statFailed'), totals.failed, (v) => Math.round(v).toLocaleString('en-IN'));
    animateValue($('#statAmount'), Number(totals.processedAmount) || 0, (v) => money(v));

    const rate = (part) => totals.count ? Math.round((part / totals.count) * 100) + '%' : '0%';
    $('#statSuccessRate').textContent = rate(totals.success);
    $('#statFailedRate').textContent = rate(totals.failed);

    const today = new Date().toDateString();
    const todayCount = (state.data ? state.data.transactions : []).filter((t) => new Date(t.date).toDateString() === today).length;
    $('#statCountTrend').textContent = todayCount + ' today';
    $('#statRefunded').textContent = (totals.refunded || 0) + ' refunded';
    $('#apiStatusText').textContent = '/api/state • ' + totals.count + ' records';
  }

  function renderChart() {
    const days = [];
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    for (let i = 6; i >= 0; i--) {
      const day = new Date(today);
      day.setDate(day.getDate() - i);
      days.push({ date: day, total: 0 });
    }
    (state.data ? state.data.transactions : []).forEach((t) => {
      if (t.status === 'FAILED') return;
      const when = new Date(t.date);
      when.setHours(0, 0, 0, 0);
      const slot = days.find((day) => day.date.getTime() === when.getTime());
      if (slot) slot.total += Number(t.amount) || 0;
    });

    const max = Math.max.apply(null, days.map((d) => d.total).concat([1]));
    const weekTotal = days.reduce((sum, d) => sum + d.total, 0);
    const peak = days.reduce((best, d, i) => (d.total > days[best].total ? i : best), 0);

    $('#chartSub').textContent = weekTotal > 0
      ? shortMoney(weekTotal) + ' processed across the last 7 days'
      : 'No activity in the last 7 days';

    if (weekTotal <= 0) {
      $('#volumeChart').innerHTML = '<div class="chart-empty">' +
        '<svg viewBox="0 0 120 90" fill="none" aria-hidden="true">' +
        '<rect x="8" y="18" width="104" height="64" rx="10" fill="#F7F9FE" stroke="#E7EBF6"/>' +
        '<path d="M20 70V54M40 70V44M60 70V58M80 70V36M100 70V48" stroke="#DDE4F5" stroke-width="7" stroke-linecap="round"/>' +
        '</svg>' +
        '<strong>No payment volume yet</strong>' +
        '<p>Successful payments will appear here over the last seven days.</p></div>';
      return;
    }

    $('#volumeChart').innerHTML = days.map((day, index) => {
      const height = day.total > 0 ? Math.max(8, Math.round((day.total / max) * 100)) : 3;
      const label = day.date.toLocaleDateString('en-IN', { weekday: 'short' });
      const isToday = index === days.length - 1;
      const isPeak = day.total > 0 && index === peak;
      return '<div class="bar-col' + (isToday ? ' today' : '') + '">' +
        '<span class="bar-amount">' + (day.total > 0 ? shortMoney(day.total) : '') + '</span>' +
        '<div class="bar-track"><div class="bar' + (isPeak ? ' peak' : '') + '" style="height:' + height + '%" title="' + esc(money(day.total)) + '"></div></div>' +
        '<span>' + label + '</span></div>';
    }).join('');
  }

  function renderMix() {
    const mix = { credit_card: 0, paypal: 0, bank_transfer: 0 };
    let count = 0;
    (state.data ? state.data.transactions : []).forEach((t) => {
      if (t.status === 'FAILED') return;
      const key = mix[t.methodKey] == null ? 'credit_card' : t.methodKey;
      mix[key] += Number(t.amount) || 0;
      count++;
    });
    const total = Object.keys(mix).reduce((sum, key) => sum + mix[key], 0);
    const donut = $('#mixDonut');
    $('#mixTotal').textContent = count;

    if (total <= 0) {
      donut.style.background = 'conic-gradient(#E9EDF7 0 100%)';
    } else {
      let cursor = 0;
      const stops = Object.keys(mix).map((key) => {
        const span = (mix[key] / total) * 100;
        const stop = METHOD_META[key].color + ' ' + cursor.toFixed(2) + '% ' + (cursor + span).toFixed(2) + '%';
        cursor += span;
        return stop;
      });
      donut.style.background = 'conic-gradient(from -90deg, ' + stops.join(', ') + ')';
    }

    $('#mixLegend').innerHTML = Object.keys(mix).map((key) => {
      const pct = total > 0 ? Math.round((mix[key] / total) * 100) : 0;
      return '<li><span class="swatch" style="background:' + METHOD_META[key].color + '"></span>' +
        esc(METHOD_META[key].accent === 'cc' ? 'Credit Card' : key === 'paypal' ? 'PayPal' : 'Bank Transfer') +
        '<b>' + pct + '%</b></li>';
    }).join('');
  }

  function renderActivity() {
    const list = $('#activityList');
    const items = (state.data ? state.data.transactions : []).slice(0, 4);
    if (!items.length) {
      list.innerHTML = emptyState('No payments yet', 'Create your first payment to see live gateway activity here.', 'pay', 'New payment');
      return;
    }
    list.innerHTML = items.map((t) => {
      const meta = METHOD_META[t.methodKey] || METHOD_META.credit_card;
      const status = STATUS[t.status] || STATUS.SUCCESS;
      return '<div class="activity" data-details="' + esc(t.id) + '">' +
        '<div class="mini-icon ' + meta.cls + '"><svg class="ico ' + (t.methodKey === 'paypal' ? 'ico--fill' : '') + '"><use href="#' + meta.icon + '"></use></svg></div>' +
        '<div class="activity-info"><strong>' + esc(t.methodName) + ' · ' + esc(t.masked) + '</strong><small>' + esc(t.id) + ' • ' + relativeTime(t.date) + '</small></div>' +
        '<div class="activity-amt"><b>' + money(t.amount) + '</b><span class="pill ' + status.cls + '">' + status.label + '</span></div></div>';
    }).join('');
  }

  function emptyState(title, text, view, action) {
    return '<div class="empty">' +
      '<svg class="art" viewBox="0 0 160 120" fill="none" aria-hidden="true">' +
      '<rect x="22" y="26" width="112" height="72" rx="14" fill="#F4F6FD" stroke="#E1E6F4"/>' +
      '<rect x="22" y="26" width="112" height="22" rx="12" fill="#E9EEFB"/>' +
      '<circle cx="38" cy="37" r="4" fill="#C6D0EC"/><circle cx="51" cy="37" r="4" fill="#D8DFF0"/>' +
      '<circle cx="38" cy="65" r="9" fill="#E6EBFF"/>' +
      '<rect x="54" y="58" width="52" height="6" rx="3" fill="#DCE3F5"/>' +
      '<rect x="54" y="70" width="34" height="6" rx="3" fill="#E8EDF9"/>' +
      '<rect x="84" y="88" width="48" height="18" rx="9" fill="#EEF1FF"/>' +
      '<circle cx="128" cy="30" r="15" fill="#F1EDFF"/><path d="m122 30 4 4 8-8" stroke="#8B5CF6" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/>' +
      '</svg>' +
      '<strong>' + esc(title) + '</strong><p>' + esc(text) + '</p>' +
      (view ? '<button class="btn btn-soft btn-sm" data-view="' + view + '">' + esc(action) + '</button>' : '') +
      '</div>';
  }

  /* ---------- payment method cards ---------- */
  function addFieldsMarkup(method) {
    if (method === 'credit_card') {
      return '<div class="field-grid">' +
        '<div class="field span-2"><label>Cardholder name</label><input class="input" data-field="cardName" placeholder="Name on the card" autocomplete="off"><p class="field-error" data-err="cardName"></p></div>' +
        '<div class="field span-2"><label>Card number</label><input class="input" data-field="cardNumber" inputmode="numeric" placeholder="4111 1111 1111 1111" autocomplete="off"><p class="field-error" data-err="cardNumber"></p></div>' +
        '<div class="field"><label>Expiry</label><input class="input" data-field="expiry" inputmode="numeric" placeholder="MM/YY" maxlength="5" autocomplete="off"><p class="field-error" data-err="expiry"></p></div>' +
        '<div class="field"><label>CVV</label><input class="input" data-field="cvv" inputmode="numeric" placeholder="123" maxlength="4" autocomplete="off"><p class="field-error" data-err="cvv"></p></div>' +
        '</div>';
    }
    if (method === 'paypal') {
      return '<div class="field"><label>PayPal email</label><input class="input" data-field="email" type="email" placeholder="you@example.com" autocomplete="off"><p class="field-error" data-err="email"></p></div>';
    }
    return '<div class="field-grid">' +
      '<div class="field span-2"><label>Account holder</label><input class="input" data-field="accountName" placeholder="Full name" autocomplete="off"><p class="field-error" data-err="accountName"></p></div>' +
      '<div class="field span-2"><label>Account number</label><input class="input" data-field="accountNumber" inputmode="numeric" placeholder="123456789012" autocomplete="off"><p class="field-error" data-err="accountNumber"></p></div>' +
      '<div class="field"><label>IFSC code</label><input class="input" data-field="ifsc" placeholder="HDFC0001234" autocomplete="off"><p class="field-error" data-err="ifsc"></p></div>' +
      '<div class="field"><label>Bank name</label><input class="input" data-field="bankName" placeholder="HDFC Bank" autocomplete="off"><p class="field-error" data-err="bankName"></p></div>' +
      '</div>';
  }

  function renderMethods() {
    const grid = $('#methodGrid');
    if (!state.methods.length) { grid.innerHTML = ''; return; }
    grid.innerHTML = state.methods.map((method) => {
      const meta = METHOD_META[method.key] || METHOD_META.credit_card;
      const saved = state.saved.find((item) => item.method === method.key);
      return '<article class="card method-card" data-card="' + method.key + '">' +
        '<div class="method-top"><div class="method-icon ' + meta.cls + '"><svg class="ico ' + (method.key === 'paypal' ? 'ico--fill' : '') + '"><use href="#' + meta.icon + '"></use></svg></div>' +
        '<span class="chip">Active</span></div>' +
        '<div><h3>' + esc(method.name) + '</h3><p class="method-desc">' + esc(method.description) + '</p></div>' +
        '<span class="method-tag"><svg class="ico"><use href="#i-clock"></use></svg>' + esc(method.tag) + '</span>' +
        (saved ? '<div class="saved-method"><svg class="ico"><use href="#i-check"></use></svg><b>' + esc(saved.label) + '</b>' +
          '<button type="button" data-remove-saved="' + method.key + '" aria-label="Remove saved method"><svg class="ico"><use href="#i-close"></use></svg></button></div>' : '') +
        '<div class="method-actions"><button class="btn btn-soft" data-use-method="' + method.key + '">Use this method</button>' +
        '<button class="btn btn-ghost" type="button" data-toggle-add="' + method.key + '">Add payment method</button></div>' +
        '<form class="add-form" data-add-form="' + method.key + '" novalidate>' + addFieldsMarkup(method.key) +
        '<p class="form-note"><svg class="ico"><use href="#i-lock"></use></svg><span>Saved on this device only. Card numbers and CVV are never stored.</span></p>' +
        '<div class="form-actions"><button type="button" class="btn btn-ghost" data-toggle-add="' + method.key + '">Close</button>' +
        '<button type="submit" class="btn btn-primary">Save method</button></div></form>' +
        '</article>';
    }).join('');

    $$('[data-add-form]').forEach((form) => {
      form.addEventListener('submit', (event) => {
        event.preventDefault();
        saveMethodFromForm(form);
      });
      const method = form.dataset.addForm;
      if (method === 'credit_card') {
        formatInput(form.querySelector('[data-field="cardNumber"]'), formatCard);
        formatInput(form.querySelector('[data-field="expiry"]'), formatExpiry);
        formatInput(form.querySelector('[data-field="cvv"]'), (v) => digits(v).slice(0, 4));
      }
      if (method === 'bank_transfer') {
        formatInput(form.querySelector('[data-field="accountNumber"]'), (v) => digits(v).slice(0, 18));
        formatInput(form.querySelector('[data-field="ifsc"]'), (v) => v.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 11));
      }
    });
  }

  function toggleAddForm(method) {
    const card = $('[data-card="' + method + '"]');
    if (!card) return;
    card.classList.toggle('open');
    if (card.classList.contains('open')) {
      const first = card.querySelector('.add-form .input');
      if (first) setTimeout(() => first.focus(), 80);
    }
  }

  function renderMethodFilter() {
    const select = $('#methodFilter');
    if (!select) return;
    select.innerHTML = '<option value="all">All methods</option>' + state.methods.map((method) =>
      '<option value="' + esc(method.key) + '">' + esc(method.name) + '</option>').join('');
    if (state.methodFilter !== 'all' && state.methods.some((method) => method.key === state.methodFilter)) {
      select.value = state.methodFilter;
    } else {
      state.methodFilter = 'all';
    }
  }

  function saveMethodFromForm(form) {
    const method = form.dataset.addForm;
    const fields = gatherFields(form);
    const errors = validateMethod(method, fields);
    showScopedErrors(form, errors);
    if (Object.keys(errors).length) {
      toast('error', 'Check the details', Object.values(errors)[0]);
      return;
    }
    const label = maskFor(method, fields);
    state.saved = state.saved.filter((item) => item.method !== method);
    state.saved.push({ method: method, label: label });
    persistSaved();
    renderMethods();
    toast('success', 'Payment method saved', label + ' will be available on this device.');
  }

  function removeSaved(method) {
    state.saved = state.saved.filter((item) => item.method !== method);
    persistSaved();
    renderMethods();
    toast('info', 'Saved method removed', 'The method was deleted from this device.');
  }

  /* ---------- field helpers ---------- */
  function gatherFields(scope) {
    const values = {};
    $$('[data-field]', scope).forEach((input) => { values[input.dataset.field] = input.value.trim(); });
    return values;
  }

  function formatInput(input, formatter) {
    if (!input) return;
    input.addEventListener('input', () => {
      const caretEnd = input.selectionStart === input.value.length;
      input.value = formatter(input.value);
      if (caretEnd) input.setSelectionRange(input.value.length, input.value.length);
    });
  }

  function formatCard(value) {
    const d = digits(value).slice(0, 19);
    const groups = [];
    for (let i = 0; i < d.length; i += 4) groups.push(d.slice(i, i + 4));
    return groups.join(' ');
  }
  function formatExpiry(value) {
    const d = digits(value).slice(0, 4);
    return d.length > 2 ? d.slice(0, 2) + '/' + d.slice(2) : d;
  }

  function luhnValid(number) {
    const d = digits(number);
    if (!/^[0-9]{13,19}$/.test(d)) return false;
    let sum = 0;
    let dbl = false;
    for (let i = d.length - 1; i >= 0; i--) {
      let digit = Number(d.charAt(i));
      if (dbl) { digit *= 2; if (digit > 9) digit -= 9; }
      sum += digit;
      dbl = !dbl;
    }
    return sum % 10 === 0;
  }

  function emailValid(value) {
    const v = String(value || '').trim();
    if (!v || v.indexOf(' ') >= 0) return false;
    const at = v.indexOf('@');
    if (at <= 0 || at !== v.lastIndexOf('@')) return false;
    const domain = v.slice(at + 1);
    if (!domain || domain.indexOf('.') <= 0 || domain.slice(-1) === '.') return false;
    return /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}$/.test(v);
  }

  function expiryValid(value) {
    const parts = String(value || '').split('/');
    if (parts.length !== 2) return false;
    if (!/^[0-9]{2}$/.test(parts[0]) || !/^[0-9]{2}$/.test(parts[1])) return false;
    const month = Number(parts[0]);
    const year = Number(parts[1]);
    if (month < 1 || month > 12) return false;
    const expiry = new Date(2000 + year, month, 1);
    return expiry > new Date();
  }

  function amountError(value) {
    const raw = String(value == null ? '' : value).trim();
    if (!raw) return 'Enter an amount to continue.';
    if (!/^[0-9]+([.][0-9]{1,2})?$/.test(raw)) return 'Use numbers only, with up to two decimal places.';
    const amount = Number(raw);
    if (!(amount > 0)) return 'Enter an amount greater than zero.';
    if (amount > 100000) return 'Amount exceeds the per-transaction limit of ₹1,00,000.';
    return '';
  }

  function validateMethod(method, fields) {
    const errors = {};
    if (method === 'credit_card') {
      if (!fields.cardName) errors.cardName = 'Cardholder name is required.';
      else if (fields.cardName.length < 3) errors.cardName = 'Cardholder name must be at least 3 characters.';
      if (!luhnValid(fields.cardNumber)) errors.cardNumber = 'This card number is not valid. Check the digits and try again.';
      if (!expiryValid(fields.expiry)) errors.expiry = 'Use a future expiry date in MM/YY format.';
      if (!/^[0-9]{3,4}$/.test(fields.cvv || '')) errors.cvv = 'CVV must be 3-4 digits.';
    } else if (method === 'paypal') {
      if (!emailValid(fields.email)) errors.email = 'Enter a valid PayPal email address.';
    } else {
      if (!fields.accountName) errors.accountName = 'Account holder name is required.';
      if (!/^[0-9]{9,18}$/.test(fields.accountNumber || '')) errors.accountNumber = 'Account number must contain 9-18 digits.';
      if (!/^[A-Z]{4}0[A-Z0-9]{6}$/.test(fields.ifsc || '')) errors.ifsc = 'IFSC must be 11 characters, e.g. HDFC0001234.';
      if (!fields.bankName) errors.bankName = 'Bank name is required.';
    }
    return errors;
  }

  function showScopedErrors(scope, errors) {
    $$('[data-err]', scope).forEach((error) => { error.textContent = ''; error.classList.remove('show'); });
    $$('.input', scope).forEach((input) => input.classList.remove('invalid'));
    Object.keys(errors).forEach((key) => {
      const error = $('[data-err="' + key + '"]', scope);
      const input = $('[data-field="' + key + '"]', scope);
      if (error) { error.textContent = errors[key]; error.classList.add('show'); }
      if (input) input.classList.add('invalid');
    });
  }

  /* ---------- make payment ---------- */
  function renderMethodPicker() {
    if (!state.methods.length) return;
    if (!state.methods.some((m) => m.key === state.payMethod)) state.payMethod = state.methods[0].key;
    $('#methodPicker').innerHTML = state.methods.map((method) => {
      const meta = METHOD_META[method.key] || METHOD_META.credit_card;
      const active = state.payMethod === method.key;
      return '<label class="pick' + (active ? ' active' : '') + '" data-pick="' + method.key + '">' +
        '<input type="radio" name="payMethod" value="' + method.key + '"' + (active ? ' checked' : '') + '>' +
        '<span class="pick-icon"><svg class="ico ' + (method.key === 'paypal' ? 'ico--fill' : '') + '"><use href="#' + meta.icon + '"></use></svg></span>' +
        '<span class="pick-text"><strong>' + esc(method.name) + '</strong><small>' + esc(meta.short) + '</small></span>' +
        '<span class="pick-check"><svg class="ico"><use href="#i-check"></use></svg></span></label>';
    }).join('');
    renderPayFields();
    updateSummary();
  }

  function payFieldsMarkup(method) {
    if (method === 'credit_card') {
      return '<div class="field span-2"><label for="cardName">Cardholder name</label><input class="input" id="cardName" name="cardName" placeholder="Name as printed on the card" autocomplete="off"><p class="field-error" data-err="cardName"></p></div>' +
        '<div class="field span-2"><label for="cardNumber">Card number <span class="hint">Luhn checked</span></label><input class="input" id="cardNumber" name="cardNumber" inputmode="numeric" placeholder="4111 1111 1111 1111" autocomplete="off"><p class="field-error" data-err="cardNumber"></p></div>' +
        '<div class="field"><label for="expiry">Expiry date</label><input class="input" id="expiry" name="expiry" inputmode="numeric" placeholder="MM/YY" maxlength="5" autocomplete="off"><p class="field-error" data-err="expiry"></p></div>' +
        '<div class="field"><label for="cvv">CVV</label><input class="input" id="cvv" name="cvv" inputmode="numeric" placeholder="123" maxlength="4" autocomplete="off"><p class="field-error" data-err="cvv"></p></div>';
    }
    if (method === 'paypal') {
      return '<div class="field span-2"><label for="email">PayPal email</label><input class="input" id="email" name="email" type="email" placeholder="you@example.com" autocomplete="off"><p class="field-error" data-err="email"></p>' +
        '<p class="form-note" style="margin-top:12px"><svg class="ico"><use href="#i-info"></use></svg><span>Use demo@payflow.in for a working demo account.</span></p></div>';
    }
    return '<div class="field span-2"><label for="accountName">Account holder name</label><input class="input" id="accountName" name="accountName" placeholder="Full name" autocomplete="off"><p class="field-error" data-err="accountName"></p></div>' +
      '<div class="field span-2"><label for="accountNumber">Account number</label><input class="input" id="accountNumber" name="accountNumber" inputmode="numeric" placeholder="123456789012" autocomplete="off"><p class="field-error" data-err="accountNumber"></p></div>' +
      '<div class="field"><label for="ifsc">IFSC code</label><input class="input" id="ifsc" name="ifsc" placeholder="HDFC0001234" autocomplete="off"><p class="field-error" data-err="ifsc"></p></div>' +
      '<div class="field"><label for="bankName">Bank name</label><input class="input" id="bankName" name="bankName" placeholder="HDFC Bank" autocomplete="off"><p class="field-error" data-err="bankName"></p></div>';
  }

  function renderPayFields() {
    $('#payFields').innerHTML = payFieldsMarkup(state.payMethod);
    const scope = $('#payFields');
    if (state.payMethod === 'credit_card') {
      formatInput($('#cardNumber'), formatCard);
      formatInput($('#expiry'), formatExpiry);
      formatInput($('#cvv'), (v) => digits(v).slice(0, 4));
    }
    if (state.payMethod === 'bank_transfer') {
      formatInput($('#accountNumber'), (v) => digits(v).slice(0, 18));
      formatInput($('#ifsc'), (v) => v.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 11));
    }
    $$('.input', scope).forEach((input) => {
      input.addEventListener('input', () => {
        input.classList.remove('invalid');
        const error = $('[data-err="' + input.getAttribute('name') + '"]', scope);
        if (error) { error.textContent = ''; error.classList.remove('show'); }
        updateSummary();
      });
    });
  }

  function selectMethod(method) {
    state.payMethod = method;
    $$('#methodPicker .pick').forEach((pick) => pick.classList.toggle('active', pick.dataset.pick === method));
    $$('#methodPicker input').forEach((input) => { input.checked = input.value === method; });
    renderPayFields();
    updateSummary();
    $('#payResult').innerHTML = '';
  }

  function readPayDetails() {
    const values = {};
    $$('#payFields [name]').forEach((input) => { values[input.getAttribute('name')] = input.value.trim(); });
    return values;
  }

  function updateSummary() {
    if (!state.methods.length) return;
    const method = state.payMethod;
    const meta = METHOD_META[method] || METHOD_META.credit_card;
    const icon = $('#summaryIcon');
    const use = $('#summaryIcon use');
    icon.className = 'mini-icon ' + meta.cls;
    $('#summaryIcon').innerHTML = '<svg class="ico ' + (method === 'paypal' ? 'ico--fill' : '') + '"><use href="#' + meta.icon + '"></use></svg>';
    $('#summaryName').textContent = (state.methods.find((m) => m.key === method) || {}).name || 'Payment method';
    $('#summaryMask').textContent = maskFor(method, readPayDetails());
    const amount = Number($('#payAmount').value) || 0;
    $('#summaryAmount').textContent = money(amount);
    $('#summaryTotal').textContent = money(amount);
    $('#paySubmit').querySelector('.btn-label').textContent = amount > 0 ? 'Pay ' + money(amount) : 'Pay Now';
  }

  function setPayError(field, message) {
    const input = $('#payFields [name="' + field + '"]');
    const error = $('#payFields [data-err="' + field + '"]');
    if (input) input.classList.toggle('invalid', !!message);
    if (error) { error.textContent = message || ''; error.classList.toggle('show', !!message); }
  }

  function validatePayForm() {
    const details = readPayDetails();
    const errors = validateMethod(state.payMethod, details);
    Object.keys(errors).forEach((key) => setPayError(key, errors[key]));
    if (state.payMethod === 'credit_card') { setPayError('cardName', errors.cardName); setPayError('cardNumber', errors.cardNumber); setPayError('expiry', errors.expiry); setPayError('cvv', errors.cvv); }
    const amountMessage = amountError($('#payAmount').value);
    $('#payAmountError').textContent = amountMessage;
    $('#payAmountError').classList.toggle('show', !!amountMessage);
    $('#payAmount').classList.toggle('invalid', !!amountMessage);
    if (amountMessage) return { ok: false, focus: $('#payAmount') };
    const firstField = Object.keys(errors)[0];
    if (firstField) return { ok: false, focus: $('#payFields [name="' + firstField + '"]') };
    return { ok: true, details: details };
  }

  function payResultMarkup(result) {
    if (!result) return '';
    const t = result.transaction;
    const ok = result.success;
    const pending = t && t.status === 'PENDING';
    const title = ok ? (pending ? 'Transfer pending' : 'Payment successful') : 'Payment failed';
    let meta = '';
    if (t) {
      meta = '<ul class="result-meta">' +
        '<li><span>Transaction</span><b>' + esc(t.id) + '</b></li>' +
        '<li><span>Method</span><b>' + esc(t.methodName) + '</b></li>' +
        '<li><span>Amount</span><b>' + money(t.amount) + '</b></li>' +
        '<li><span>Recorded</span><b>' + esc(t.dateLabel) + '</b></li>' +
        '</ul>' +
        '<div style="display:flex;gap:8px;margin-top:14px;flex-wrap:wrap">' +
        '<button class="btn btn-ghost btn-sm" data-details="' + esc(t.id) + '">View details</button>' +
        '<button class="btn btn-soft btn-sm" data-view="transactions">Open ledger</button></div>';
    }
    return '<div class="result-card ' + (ok ? 'success' : 'failed') + '">' +
      '<div class="result-head"><div class="result-icon"><svg class="ico"><use href="#' + (ok ? 'i-check' : 'i-alert') + '"></use></svg></div>' +
      '<div><h3>' + title + '</h3><p>' + esc(result.message || 'No gateway message returned.') + '</p></div></div>' + meta + '</div>';
  }

  async function submitPayment() {
    const validation = validatePayForm();
    if (!validation.ok) {
      toast('error', 'Check your details', 'Some fields need attention before the gateway can process this payment.');
      if (validation.focus) validation.focus.focus();
      return;
    }
    const button = $('#paySubmit');
    button.classList.add('loading');
    const started = Date.now();
    try {
      const payload = {
        method: state.payMethod,
        amount: $('#payAmount').value.trim(),
        details: validation.details
      };
      const result = await postJson('/api/pay', payload);
      const wait = Math.max(0, 620 - (Date.now() - started));
      if (wait) await new Promise((resolve) => setTimeout(resolve, wait));
      state.lastPayResult = result;
      $('#payResult').innerHTML = payResultMarkup(result);
      if (result.success) {
        toast('success', result.transaction && result.transaction.status === 'PENDING' ? 'Transfer initiated' : 'Payment successful',
          money(result.transaction ? result.transaction.amount : 0) + ' • ' + (result.transaction ? result.transaction.id : ''));
        $$('#payFields .input').forEach((input) => {
          const name = input.getAttribute('name');
          if (name === 'cardNumber' || name === 'cvv') input.value = '';
          if (name === 'expiry') input.value = '';
        });
        $('#payAmount').value = '';
        updateSummary();
      } else {
        toast('error', 'Payment failed', result.message);
      }
      await refreshState();
      const panel = $('#payResult');
      if (panel) panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    } catch (error) {
      const failure = { success: false, message: 'The gateway could not be reached: ' + error.message };
      $('#payResult').innerHTML = payResultMarkup(failure);
      toast('error', 'Gateway error', error.message);
    } finally {
      button.classList.remove('loading');
    }
  }

  function resetPayForm() {
    $('#payForm').reset();
    $('#payFields').innerHTML = '';
    renderPayFields();
    $('#payAmountError').textContent = '';
    $('#payAmountError').classList.remove('show');
    $('#payResult').innerHTML = '';
    updateSummary();
  }

  function payFormHasData() {
    if (String($('#payAmount').value).trim()) return true;
    return $$('#payFields .input').some((input) => String(input.value).trim().length > 0);
  }

  /* ---------- transactions ---------- */
  function filteredTransactions() {
    const items = state.data ? state.data.transactions : [];
    const query = state.search.trim().toLowerCase();
    return items.filter((t) => {
      if (state.filter !== 'all' && t.status !== state.filter) return false;
      if (state.methodFilter !== 'all' && t.methodKey !== state.methodFilter) return false;
      if (!query) return true;
      const haystack = [t.id, t.methodName, t.masked, t.status, String(t.amount), t.dateLabel].join(' ').toLowerCase();
      return haystack.indexOf(query) !== -1;
    });
  }

  function statusPill(status) {
    const meta = STATUS[status] || STATUS.SUCCESS;
    return '<span class="pill ' + meta.cls + '">' + meta.label + '</span>';
  }

  function renderTransactions() {
    if (!state.data) return;
    const all = state.data.transactions;
    const rows = filteredTransactions();

    const counts = { all: all.length };
    all.forEach((t) => { counts[t.status] = (counts[t.status] || 0) + 1; });
    const filters = [
      { key: 'all', label: 'All', count: counts.all },
      { key: 'SUCCESS', label: 'Success', count: counts.SUCCESS || 0 },
      { key: 'PENDING', label: 'Pending', count: counts.PENDING || 0 },
      { key: 'FAILED', label: 'Failed', count: counts.FAILED || 0 },
      { key: 'REFUNDED', label: 'Refunded', count: counts.REFUNDED || 0 }
    ];
    $('#filterChips').innerHTML = filters.map((f) =>
      '<button type="button" class="filter-chip' + (state.filter === f.key ? ' active' : '') + '" data-filter="' + f.key + '">' +
      f.label + '<span>' + f.count + '</span></button>').join('');

    const body = $('#txnBody');
    if (!rows.length) {
      body.innerHTML = '';
      $('.table-wrap', $('#txnTableCard')).classList.add('hidden');
      const empty = $('#txnEmpty');
      empty.classList.remove('hidden');
      empty.innerHTML = all.length
        ? emptyState('No matching transactions', 'Try a different search term, status filter or payment method.', null)
        : emptyState('No transactions yet', 'Once a payment is processed, the gateway ledger will appear here in real time.', 'pay', 'Create a payment');
    } else {
      $('.table-wrap', $('#txnTableCard')).classList.remove('hidden');
      $('#txnEmpty').classList.add('hidden');
      body.innerHTML = rows.map((t) => {
        const meta = METHOD_META[t.methodKey] || METHOD_META.credit_card;
        return '<tr>' +
          '<td><button class="txn-id" data-details="' + esc(t.id) + '">' + esc(t.id) + '</button><span class="txn-sub">' + timeOnly(t.date) + '</span></td>' +
          '<td><div class="method-cell"><div class="mini-icon ' + meta.cls + '"><svg class="ico ' + (t.methodKey === 'paypal' ? 'ico--fill' : '') + '"><use href="#' + meta.icon + '"></use></svg></div>' +
          '<div><strong>' + esc(t.methodName) + '</strong><small>' + esc(t.masked) + '</small></div></div></td>' +
          '<td class="amount-cell">' + money(t.amount) + '</td>' +
          '<td><span>' + esc(t.dateLabel) + '</span></td>' +
          '<td>' + statusPill(t.status) + '</td>' +
          '<td><div class="row-actions">' +
          '<button class="row-btn" data-details="' + esc(t.id) + '" title="View details" aria-label="View details"><svg class="ico"><use href="#i-eye"></use></svg></button>' +
          '<button class="row-btn" data-refund-txn="' + esc(t.id) + '" title="' + (t.refundable ? 'Refund this payment' : 'Refund unavailable') + '" aria-label="Refund" ' + (t.refundable ? '' : 'disabled') + '><svg class="ico"><use href="#i-refund"></use></svg></button>' +
          '</div></td></tr>';
      }).join('');
    }
    $('#txnFoot').innerHTML = '<span>Showing ' + rows.length + ' of ' + all.length + ' transactions</span>' +
      '<span>' + (state.data.serverTime ? 'Gateway time ' + esc(state.data.serverTime) : '') + '</span>';
  }

  function setSearch(value) {
    state.search = value;
    if ($('#txnSearch').value !== value) $('#txnSearch').value = value;
    if ($('#globalSearch').value !== value) $('#globalSearch').value = value;
    renderTransactions();
  }

  function gotoRefund(transactionId) {
    showView('refunds');
    const select = $('#refundTxn');
    if (select.querySelector('option[value="' + transactionId + '"]')) {
      select.value = transactionId;
      onRefundSelection();
    }
  }

  /* ---------- refunds ---------- */
  function refundableTransactions() {
    return (state.data ? state.data.transactions : []).filter((t) => t.status === 'SUCCESS' && t.refundable);
  }

  function renderRefunds() {
    if (!state.data) return;
    const select = $('#refundTxn');
    const current = select.value;
    const options = refundableTransactions();
    select.innerHTML = options.length
      ? '<option value="">Select a successful transaction</option>' + options.map((t) =>
        '<option value="' + esc(t.id) + '">' + esc(t.id) + ' • ' + esc(t.methodName) + ' • ' + money(t.amount) + '</option>').join('')
      : '<option value="">No refundable transactions available</option>';
    if (current && select.querySelector('option[value="' + current + '"]')) select.value = current;
    select.disabled = !options.length;
    $('#refundSubmit').disabled = !options.length;

    const list = $('#refundList');
    const refunds = state.data.refunds || [];
    list.innerHTML = refunds.length
      ? refunds.slice(0, 5).map((r) => '<div class="refund-item">' +
        '<div class="mini-icon mi-card"><svg class="ico"><use href="#i-refund"></use></svg></div>' +
        '<div><strong>' + esc(r.transactionId) + '</strong><small>' + esc(r.methodName) + ' • ' + esc(r.dateLabel) + '</small></div>' +
        '<b>' + money(r.amount) + '</b></div>').join('')
      : '<div class="empty" style="padding:22px 6px"><p>No refunds issued yet.</p></div>';

    const selected = options.find((t) => t.id === select.value);
    const preview = $('#refundPreview');
    if (!selected) {
      preview.innerHTML = '<div class="empty" style="padding:14px 6px">' +
        '<p>Successful payments appear here with their refundable balance.</p>' +
        (state.data.transactions.length ? '' : '<button class="btn btn-soft btn-sm" data-view="pay" style="margin-top:8px">Make a payment</button>') +
        '</div>';
      $('#refundAmount').value = '';
      return;
    }
    const meta = METHOD_META[selected.methodKey] || METHOD_META.credit_card;
    preview.innerHTML =
      '<div class="rp-head"><div class="mini-icon ' + meta.cls + '"><svg class="ico ' + (selected.methodKey === 'paypal' ? 'ico--fill' : '') + '"><use href="#' + meta.icon + '"></use></svg></div>' +
      '<div><strong>' + esc(selected.methodName) + '</strong><small>' + esc(selected.masked) + '</small></div>' +
      statusPill(selected.status) + '</div>' +
      '<div class="rp-rows">' +
      '<div><span>Original amount</span><b>' + money(selected.amount) + '</b></div>' +
      '<div><span>Already refunded</span><b>' + money(selected.refundedAmount) + '</b></div>' +
      '<div><span>Remaining</span><b>' + money(selected.remainingRefundable) + '</b></div>' +
      '</div>';
    if (!$('#refundAmount').value) $('#refundAmount').value = String(selected.remainingRefundable || '');
  }

  function onRefundSelection() {
    const selected = refundableTransactions().find((t) => t.id === $('#refundTxn').value);
    $('#refundAmount').value = selected ? String(selected.remainingRefundable) : '';
    $('#refundAmountError').textContent = '';
    $('#refundAmountError').classList.remove('show');
    $('#refundResult').innerHTML = '';
    renderRefunds();
  }

  function submitRefund() {
    const selected = refundableTransactions().find((t) => t.id === $('#refundTxn').value);
    if (!selected) {
      toast('error', 'Select a transaction', 'Choose a successful payment to refund.');
      return;
    }
    const message = amountError($('#refundAmount').value);
    if (message) {
      $('#refundAmountError').textContent = message;
      $('#refundAmountError').classList.add('show');
      $('#refundAmount').classList.add('invalid');
      toast('error', 'Check the refund amount', message);
      return;
    }
    const amount = Number($('#refundAmount').value);
    if (amount > Number(selected.remainingRefundable) + 0.0001) {
      const over = 'Refund exceeds the refundable balance of ' + money(selected.remainingRefundable) + '.';
      $('#refundAmountError').textContent = over;
      $('#refundAmountError').classList.add('show');
      toast('error', 'Refund too large', over);
      return;
    }

    openConfirm({
      tone: 'primary',
      icon: 'i-refund',
      title: 'Confirm refund',
      body: 'This refund will be processed by the ' + selected.methodName + ' implementation and cannot be undone.',
      confirmText: 'Refund ' + money(amount),
      summary: '<div><span>Transaction</span><b>' + esc(selected.id) + '</b></div>' +
        '<div><span>Method</span><b>' + esc(selected.methodName) + ' • ' + esc(selected.masked) + '</b></div>' +
        '<div><span>Refund amount</span><b>' + money(amount) + '</b></div>' +
        '<div><span>Remaining after</span><b>' + money(Math.max(0, Number(selected.remainingRefundable) - amount)) + '</b></div>',
      onConfirm: async () => {
        const result = await postJson('/api/refund', { transactionId: selected.id, amount: amount });
        $('#refundResult').innerHTML = refundResultMarkup(result);
        if (result.success) {
          toast('success', 'Refund processed', result.message);
          $('#refundAmount').value = '';
        } else {
          toast('error', 'Refund declined', result.message);
        }
        await refreshState();
        onRefundSelection();
        $('#refundResult').innerHTML = refundResultMarkup(result);
        $('#refundResult').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      }
    });
  }

  function refundResultMarkup(result) {
    if (!result) return '';
    const t = result.transaction;
    return '<div class="result-card ' + (result.success ? 'success' : 'failed') + '">' +
      '<div class="result-head"><div class="result-icon"><svg class="ico"><use href="#' + (result.success ? 'i-check' : 'i-alert') + '"></use></svg></div>' +
      '<div><h3>' + (result.success ? 'Refund completed' : 'Refund not processed') + '</h3><p>' + esc(result.message) + '</p></div></div>' +
      (t ? '<ul class="result-meta"><li><span>Transaction</span><b>' + esc(t.id) + '</b></li>' +
        '<li><span>Status</span><b>' + (STATUS[t.status] || STATUS.SUCCESS).label + '</b></li>' +
        '<li><span>Refunded total</span><b>' + money(t.refundedAmount) + '</b></li></ul>' : '') +
      '</div>';
  }

""";

    static final String UI_SCRIPT_TWO = """
  /* ---------- notifications ---------- */
  function notificationEvents() {
    if (!state.data) return [];
    const events = [];
    state.data.transactions.slice(0, 6).forEach((t) => {
      const meta = STATUS[t.status] || STATUS.SUCCESS;
      events.push({
        id: t.id,
        date: t.date,
        icon: t.status === 'FAILED' ? 'i-alert' : t.status === 'PENDING' ? 'i-clock' : 'i-check',
        cls: t.status === 'FAILED' ? 'mi-cc' : t.status === 'PENDING' ? 'mi-bank' : 'mi-bank',
        title: t.status === 'FAILED' ? 'Payment failed' : t.status === 'PENDING' ? 'Bank transfer pending' : t.status === 'REFUNDED' ? 'Payment refunded' : 'Payment successful',
        text: t.id + ' • ' + money(t.amount) + ' • ' + meta.label,
        kind: 'txn'
      });
    });
    (state.data.refunds || []).slice(0, 3).forEach((r) => {
      events.push({ id: r.id, date: r.date, icon: 'i-refund', cls: 'mi-pp', title: 'Refund issued', text: money(r.amount) + ' for ' + r.transactionId, kind: 'refund' });
    });
    return events.sort((a, b) => new Date(b.date) - new Date(a.date)).slice(0, 6);
  }

  function renderNotifications() {
    const events = notificationEvents();
    const list = $('#notifList');
    list.innerHTML = events.length
      ? events.map((event) => '<div class="notif" data-notif-kind="' + event.kind + '" data-notif-id="' + esc(event.id) + '">' +
        '<div class="mini-icon ' + event.cls + '"><svg class="ico"><use href="#' + event.icon + '"></use></svg></div>' +
        '<div style="flex:1;min-width:0"><strong>' + esc(event.title) + '</strong><small>' + esc(event.text) + '</small></div>' +
        '<time>' + relativeTime(event.date) + '</time></div>').join('')
      : '<div class="empty" style="padding:26px 12px"><p>No notifications yet. Payment activity will show up here.</p></div>';

    let seen = 0;
    try { seen = Number(localStorage.getItem(STORE.seen) || 0); } catch (e) { seen = 0; }
    const newest = events.length ? new Date(events[0].date).getTime() : 0;
    $('#bellDot').classList.toggle('show', newest > seen);
  }

  function markNotificationsRead() {
    const events = notificationEvents();
    const newest = events.length ? new Date(events[0].date).getTime() : 0;
    try { localStorage.setItem(STORE.seen, String(newest || Date.now())); } catch (e) { /* ignore */ }
    $('#bellDot').classList.remove('show');
  }

  /* ---------- settings ---------- */
  function renderSettings() {
    if (state.data && state.data.serverTime) {
      const el = $('#settingsServerTime');
      if (el) el.textContent = state.data.serverTime;
    }
  }

  async function loadDemoData() {
    openConfirm({
      tone: 'primary',
      icon: 'i-spark',
      title: 'Generate sample activity?',
      body: 'Six payments will be posted to the live API, including one declined card and one pending bank transfer. This keeps all behaviour genuine.',
      confirmText: 'Generate activity',
      onConfirm: async () => {
        const button = $('#demoBtn');
        button.classList.add('loading');
        $('#demoStatus').textContent = 'Posting sample payments to /api/pay...';
        const samples = [
          { method: 'credit_card', amount: '2450.00', details: { cardName: 'Arjun Kumar', cardNumber: '4111111111111111', expiry: '12/28', cvv: '123' } },
          { method: 'paypal', amount: '899.00', details: { email: 'arjun@payflow.in' } },
          { method: 'bank_transfer', amount: '12500.00', details: { accountName: 'Arjun Kumar', accountNumber: '123456789012', ifsc: 'HDFC0001234', bankName: 'HDFC Bank' } },
          { method: 'credit_card', amount: '5600.00', details: { cardName: 'Neha Sharma', cardNumber: '5555555555554444', expiry: '09/27', cvv: '456' } },
          { method: 'paypal', amount: '320.50', details: { email: 'billing@acme.co' } },
          { method: 'credit_card', amount: '1800.00', details: { cardName: 'Rahul Verma', cardNumber: '4000000000000002', expiry: '01/25', cvv: '111' } }
        ];
        let ok = 0;
        try {
          for (const sample of samples) {
            try {
              const result = await postJson('/api/pay', sample);
              if (result.success) ok++;
            } catch (e) { /* keep going */ }
            await new Promise((resolve) => setTimeout(resolve, 140));
          }
          await refreshState();
          $('#demoStatus').textContent = ok + ' of ' + samples.length + ' sample payments accepted. The declined card is recorded as a failed attempt.';
          toast('success', 'Sample activity added', 'The dashboard now reflects live gateway results.');
        } finally {
          button.classList.remove('loading');
        }
      }
    });
  }

  /* ---------- export ---------- */
  function exportCsv() {
    const items = state.data ? state.data.transactions : [];
    if (!items.length) {
      toast('info', 'Nothing to export', 'Process a payment first, then export the ledger.');
      return;
    }
    const quote = (value) => '"' + String(value == null ? '' : value).replace(/"/g, '""') + '"';
    const lines = ['Transaction ID,Method,Instrument,Amount,Status,Date,Refunded'];
    items.forEach((t) => {
      lines.push([t.id, t.methodName, t.masked, Number(t.amount).toFixed(2), t.status, t.dateLabel, Number(t.refundedAmount).toFixed(2)].map(quote).join(','));
    });
    const blob = new Blob([lines.join('\\r\\n')], { type: 'text/csv;charset=utf-8' });
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = 'payflow-transactions.csv';
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(link.href);
    toast('success', 'Ledger exported', items.length + ' transactions written to CSV.');
  }

  /* ---------- wiring ---------- */
  function applyPrefs() {
    $('#prefToasts').checked = !!state.prefs.toasts;
    $('#prefAuto').checked = !!state.prefs.auto;
    $('#prefCompact').checked = !!state.prefs.compact;
    $('#txnTableCard').classList.toggle('compact', !!state.prefs.compact);
  }

  function setGreeting() {
    const now = new Date();
    const hour = now.getHours();
    const greeting = hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening';
    $('#dashGreeting').textContent = greeting + ', Arjun';
    $('#dashDate').textContent = now.toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
  }

  function bind() {
    document.addEventListener('click', (event) => {
      const viewButton = event.target.closest('[data-view]');
      if (viewButton) { showView(viewButton.dataset.view); return; }
      const detailsButton = event.target.closest('[data-details]');
      if (detailsButton) { openDetails(detailsButton.dataset.details); return; }
      const useButton = event.target.closest('[data-use-method]');
      if (useButton) { selectMethod(useButton.dataset.useMethod); showView('pay'); return; }
      const addToggle = event.target.closest('[data-toggle-add]');
      if (addToggle) { toggleAddForm(addToggle.dataset.toggleAdd); return; }
      const removeButton = event.target.closest('[data-remove-saved]');
      if (removeButton) { removeSaved(removeButton.dataset.removeSaved); return; }
      const refundButton = event.target.closest('[data-refund-txn]');
      if (refundButton && !refundButton.disabled) { gotoRefund(refundButton.dataset.refundTxn); return; }
      const notif = event.target.closest('[data-notif-id]');
      if (notif) {
        if (notif.dataset.notifKind === 'refund') showView('refunds');
        else { openDetails(notif.dataset.notifId); }
        markNotificationsRead();
        return;
      }
      if (!event.target.closest('#notifPanel') && !event.target.closest('#bellBtn')) $('#notifPanel').classList.remove('open');
      if (!event.target.closest('#profileMenu') && !event.target.closest('#profileBtn')) $('#profileMenu').classList.remove('open');
    });

    $('#menuBtn').addEventListener('click', openSidebar);
    $('#sidebarClose').addEventListener('click', closeSidebar);
    $('#sidebarScrim').addEventListener('click', closeSidebar);

    $('#bellBtn').addEventListener('click', () => {
      $('#profileMenu').classList.remove('open');
      const panel = $('#notifPanel');
      panel.classList.toggle('open');
      if (panel.classList.contains('open')) markNotificationsRead();
    });
    $('#profileBtn').addEventListener('click', () => {
      $('#notifPanel').classList.remove('open');
      $('#profileMenu').classList.toggle('open');
    });
    $('#markRead').addEventListener('click', () => { markNotificationsRead(); toast('info', 'Notifications cleared', 'All recent activity was marked as read.'); });
    $('#refreshBtn').addEventListener('click', () => refreshState({ toast: true }));
    $('#refreshBtn2').addEventListener('click', () => refreshState({ toast: true }));

    $('#globalSearch').addEventListener('input', (event) => {
      setSearch(event.target.value);
      if (event.target.value && $('#view-transactions').classList.contains('active') === false) showView('transactions');
    });
    $('#txnSearch').addEventListener('input', (event) => setSearch(event.target.value));
    $('#filterChips').addEventListener('click', (event) => {
      const chip = event.target.closest('[data-filter]');
      if (!chip) return;
      state.filter = chip.dataset.filter;
      renderTransactions();
    });
    $('#methodFilter').addEventListener('change', (event) => {
      state.methodFilter = event.target.value;
      renderTransactions();
    });

    $('#methodPicker').addEventListener('click', (event) => {
      const pick = event.target.closest('[data-pick]');
      if (!pick) return;
      selectMethod(pick.dataset.pick);
    });

    $('#payAmount').addEventListener('input', (event) => {
      let value = event.target.value.replace(/[^0-9.]/g, '');
      const firstDot = value.indexOf('.');
      if (firstDot !== -1) value = value.slice(0, firstDot + 1) + value.slice(firstDot + 1).replace(/[.]/g, '');
      event.target.value = value;
      event.target.classList.remove('invalid');
      $('#payAmountError').classList.remove('show');
      updateSummary();
    });

    $$('.quick-amounts button').forEach((button) => {
      button.addEventListener('click', () => {
        $('#payAmount').value = button.dataset.amount;
        $('#payAmount').classList.remove('invalid');
        $('#payAmountError').classList.remove('show');
        updateSummary();
        $('#payAmount').focus();
      });
    });

    $('#payForm').addEventListener('submit', (event) => {
      event.preventDefault();
      submitPayment();
    });

    $('#cancelPay').addEventListener('click', () => {
      if (!payFormHasData()) { resetPayForm(); toast('info', 'Form cleared', 'Start a new payment whenever you are ready.'); return; }
      openConfirm({
        tone: 'warn',
        icon: 'i-alert',
        title: 'Discard this payment?',
        body: 'The details you entered will be cleared. No transaction has been created yet.',
        confirmText: 'Discard',
        danger: true,
        onConfirm: () => { resetPayForm(); toast('info', 'Payment discarded', 'No transaction was created.'); }
      });
    });

    $('#refundTxn').addEventListener('change', onRefundSelection);
    $('#refundFull').addEventListener('click', () => {
      const selected = refundableTransactions().find((t) => t.id === $('#refundTxn').value);
      if (selected) $('#refundAmount').value = String(selected.remainingRefundable);
    });
    $('#refundAmount').addEventListener('input', (event) => {
      let value = event.target.value.replace(/[^0-9.]/g, '');
      const firstDot = value.indexOf('.');
      if (firstDot !== -1) value = value.slice(0, firstDot + 1) + value.slice(firstDot + 1).replace(/[.]/g, '');
      event.target.value = value;
      event.target.classList.remove('invalid');
      $('#refundAmountError').classList.remove('show');
    });
    $('#refundSubmit').addEventListener('click', submitRefund);
    $('#clearRefund').addEventListener('click', () => {
      $('#refundTxn').value = '';
      $('#refundAmount').value = '';
      $('#refundResult').innerHTML = '';
      renderRefunds();
    });

    $('#exportBtn').addEventListener('click', exportCsv);
    $('#exportBtn2').addEventListener('click', exportCsv);
    $('#demoBtn').addEventListener('click', loadDemoData);

    $('#prefToasts').addEventListener('change', (event) => { state.prefs.toasts = event.target.checked; persistPrefs(); });
    $('#prefAuto').addEventListener('change', (event) => { state.prefs.auto = event.target.checked; persistPrefs(); });
    $('#prefCompact').addEventListener('change', (event) => { state.prefs.compact = event.target.checked; persistPrefs(); applyPrefs(); });

    $('#modalCancel').addEventListener('click', closeConfirm);
    $('#modalScrim').addEventListener('click', (event) => { if (event.target === $('#modalScrim')) closeConfirm(); });
    $('#modalConfirm').addEventListener('click', async () => {
      const action = state.confirming;
      if (!action) { closeConfirm(); return; }
      const button = $('#modalConfirm');
      const label = button.textContent;
      button.disabled = true;
      button.textContent = 'Please wait...';
      try {
        await action();
        closeConfirm();
      } catch (error) {
        toast('error', 'Request failed', error.message);
      } finally {
        button.disabled = false;
        button.textContent = label;
      }
    });

    $('#detailClose').addEventListener('click', closeDetails);
    $('#detailScrim').addEventListener('click', (event) => { if (event.target === $('#detailScrim')) closeDetails(); });

    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') { closeConfirm(); closeDetails(); closeMenus(); closeSidebar(); }
      if (event.key === '/' && !/INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName)) {
        event.preventDefault();
        $('#globalSearch').focus();
      }
    });

    window.addEventListener('hashchange', () => {
      const name = location.hash.replace('#', '');
      if (VIEWS.indexOf(name) !== -1) showView(name);
    });

    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible' && state.prefs.auto) refreshState();
    });
  }

  async function boot() {
    state.saved = loadSaved();
    state.prefs = loadPrefs();
    state.filter = 'all';
    bind();
    applyPrefs();
    setGreeting();
    await loadMethods();
    await refreshState();
    const hashView = location.hash.replace('#', '');
    if (VIEWS.indexOf(hashView) !== -1) showView(hashView);
    setTimeout(() => $('#boot').classList.add('done'), 260);
    setInterval(() => {
      if (state.prefs.auto && document.visibilityState === 'visible') refreshState();
    }, 15000);
  }

  boot().catch((error) => {
    $('#boot').classList.add('done');
    toast('error', 'Unable to load workspace', error.message);
  });
})();
</script>
</body>
</html>
""";
}

/**
 * Core abstraction for every payment method. All channel-specific behaviour is
 * resolved through dynamic polymorphism.
 */
abstract class Payment {
    protected final double amount;
    protected final Map<String, String> details;

    protected Payment(double amount, Map<String, String> details) {
        this.amount = amount;
        this.details = details == null ? Collections.emptyMap() : details;
    }

    public double getAmount() {
        return amount;
    }

    abstract String methodKey();

    abstract String methodName();

    abstract String maskedDetails();

    abstract List<String> validate(PaymentValidator validator);

    abstract PaymentResult process();

    abstract RefundResult refund(Transaction transaction, double refundAmount);

    protected String value(String key) {
        String raw = details.get(key);
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (key.toLowerCase(Locale.ROOT).contains("email") || key.toLowerCase(Locale.ROOT).contains("account")) {
            return trimmed;
        }
        if (key.equals("cardNumber") || key.equals("cvv") || key.equals("ifsc")) {
            return trimmed;
        }
        return trimmed;
    }
}

record PaymentResult(String status, String message) {
}

record RefundResult(boolean success, String message) {
}

class CreditCardPayment extends Payment {

    CreditCardPayment(double amount, Map<String, String> details) {
        super(amount, details);
    }

    @Override
    String methodKey() {
        return "credit_card";
    }

    @Override
    String methodName() {
        return "Credit Card";
    }

    @Override
    List<String> validate(PaymentValidator validator) {
        String name = value("cardName");
        String number = value("cardNumber");
        String expiry = value("expiry");
        String cvv = value("cvv");

        validator.required("Cardholder name", name);
        if (name.length() > 0 && name.length() < 3) {
            validator.add("Cardholder name must be at least 3 characters.");
        }
        validator.luhn("Card number", number);
        validator.expiry("Expiry date", expiry);
        validator.digits("CVV", cvv, 3, 4);
        return validator.errors();
    }

    @Override
    String maskedDetails() {
        String digits = value("cardNumber").replace(" ", "").replace("-", "");
        String last4 = digits.length() >= 4 ? digits.substring(digits.length() - 4) : "0000";
        return network(digits) + " \u2022\u2022\u2022\u2022 " + last4;
    }

    @Override
    PaymentResult process() {
        return new PaymentResult("SUCCESS", "Card payment authorized by the issuing bank.");
    }

    @Override
    RefundResult refund(Transaction transaction, double refundAmount) {
        return new RefundResult(true, PaymentApp.money(refundAmount) + " refunded to " + transaction.getMasked()
                + ". The amount should reflect on the card in 3-5 business days.");
    }

    private String network(String digits) {
        if (digits.isEmpty()) {
            return "Card";
        }
        switch (digits.charAt(0)) {
            case '4':
                return "Visa";
            case '5':
                return "Mastercard";
            case '3':
                return "Amex";
            case '6':
                return "RuPay";
            case '9':
                return "RuPay";
            default:
                return "Card";
        }
    }
}

class PayPalPayment extends Payment {

    PayPalPayment(double amount, Map<String, String> details) {
        super(amount, details);
    }

    @Override
    String methodKey() {
        return "paypal";
    }

    @Override
    String methodName() {
        return "PayPal";
    }

    @Override
    List<String> validate(PaymentValidator validator) {
        validator.email("PayPal email", value("email"));
        return validator.errors();
    }

    @Override
    String maskedDetails() {
        return value("email");
    }

    @Override
    PaymentResult process() {
        return new PaymentResult("SUCCESS", "PayPal payment completed. Funds are available instantly.");
    }

    @Override
    RefundResult refund(Transaction transaction, double refundAmount) {
        return new RefundResult(true, PaymentApp.money(refundAmount) + " refunded to " + transaction.getMasked()
                + ". The refund is available in the PayPal balance immediately.");
    }
}

class BankTransferPayment extends Payment {

    BankTransferPayment(double amount, Map<String, String> details) {
        super(amount, details);
    }

    @Override
    String methodKey() {
        return "bank_transfer";
    }

    @Override
    String methodName() {
        return "Bank Transfer";
    }

    @Override
    List<String> validate(PaymentValidator validator) {
        validator.required("Account holder name", value("accountName"));
        validator.digits("Account number", value("accountNumber"), 9, 18);
        validator.ifsc("IFSC code", value("ifsc"));
        validator.required("Bank name", value("bankName"));
        return validator.errors();
    }

    @Override
    String maskedDetails() {
        String account = value("accountNumber").replace(" ", "");
        String last4 = account.length() >= 4 ? account.substring(account.length() - 4) : "0000";
        String bank = value("bankName");
        return (bank.isEmpty() ? "Bank" : bank) + " \u2022\u2022\u2022\u2022 " + last4;
    }

    @Override
    PaymentResult process() {
        return new PaymentResult("PENDING", "Transfer initiated. Awaiting confirmation from your bank.");
    }

    @Override
    RefundResult refund(Transaction transaction, double refundAmount) {
        return new RefundResult(true, PaymentApp.money(refundAmount) + " scheduled back to " + transaction.getMasked()
                + ". The amount is credited within 2-3 business days.");
    }
}

class PaymentValidator {
    private final List<String> errors = new ArrayList<>();

    boolean amount(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0) {
            errors.add("Enter an amount greater than zero.");
            return false;
        }
        if (amount > 100000) {
            errors.add("Amount exceeds the per-transaction limit of " + PaymentApp.money(100000) + ".");
            return false;
        }
        if (Math.abs(amount * 100 - Math.rint(amount * 100)) > 0.000001) {
            errors.add("Amount can have at most two decimal places.");
            return false;
        }
        return true;
    }

    boolean required(String label, String value) {
        if (value == null || value.trim().isEmpty()) {
            errors.add(label + " is required.");
            return false;
        }
        return true;
    }

    boolean digits(String label, String value, int min, int max) {
        String digits = value == null ? "" : value.replace(" ", "").replace("-", "");
        if (!digits.matches("[0-9]{" + min + "," + max + "}")) {
            errors.add(label + " must contain " + min + "-" + max + " digits.");
            return false;
        }
        return true;
    }

    boolean luhn(String label, String value) {
        String digits = value == null ? "" : value.replace(" ", "").replace("-", "");
        if (!digits.matches("[0-9]{13,19}")) {
            errors.add(label + " must contain 13-19 digits.");
            return false;
        }
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubleIt) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleIt = !doubleIt;
        }
        if (sum % 10 != 0) {
            errors.add("This card number is not valid. Check the digits and try again.");
            return false;
        }
        return true;
    }

    boolean expiry(String label, String value) {
        String expiry = value == null ? "" : value.trim();
        if (!expiry.matches("(0[1-9]|1[0-2])/[0-9]{2}")) {
            errors.add(label + " must be in MM/YY format.");
            return false;
        }
        int month = Integer.parseInt(expiry.substring(0, 2));
        int year = 2000 + Integer.parseInt(expiry.substring(3));
        if (YearMonth.of(year, month).isBefore(YearMonth.now())) {
            errors.add("This card has expired. Use a card with a future expiry date.");
            return false;
        }
        return true;
    }

    boolean email(String label, String value) {
        String email = value == null ? "" : value.trim();
        if (!email.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
            errors.add(label + " must be a valid email address.");
            return false;
        }
        return true;
    }

    boolean ifsc(String label, String value) {
        String ifsc = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!ifsc.matches("[A-Z]{4}0[A-Z0-9]{6}")) {
            errors.add(label + " must be 11 characters, for example HDFC0001234.");
            return false;
        }
        return true;
    }

    void add(String message) {
        errors.add(message);
    }

    List<String> errors() {
        return errors;
    }
}

class Transaction {
    static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH);

    private final String id;
    private final String methodKey;
    private final String methodName;
    private final String masked;
    private final double amount;
    private final LocalDateTime createdAt;
    private String status;
    private String message;
    private double refundedAmount;
    private final List<RefundRecord> refunds = new ArrayList<>();

    Transaction(String id, String methodKey, String methodName, String masked, double amount, LocalDateTime createdAt) {
        this.id = id;
        this.methodKey = methodKey;
        this.methodName = methodName;
        this.masked = masked;
        this.amount = amount;
        this.createdAt = createdAt;
    }

    String getId() {
        return id;
    }

    String getMethodKey() {
        return methodKey;
    }

    String getMethodName() {
        return methodName;
    }

    String getMasked() {
        return masked;
    }

    double getAmount() {
        return amount;
    }

    LocalDateTime getCreatedAt() {
        return createdAt;
    }

    String getStatus() {
        return status;
    }

    void setStatus(String status) {
        this.status = status;
    }

    String getMessage() {
        return message;
    }

    void setMessage(String message) {
        this.message = message;
    }

    double getRefundedAmount() {
        return refundedAmount;
    }

    void addRefundedAmount(double value) {
        refundedAmount = round(refundedAmount + value);
    }

    List<RefundRecord> getRefunds() {
        return refunds;
    }

    boolean isRefundable() {
        return "SUCCESS".equals(status) && amount - refundedAmount > 0.0001;
    }

    double getRemainingRefundable() {
        return round(amount - refundedAmount);
    }

    String toJson() {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"id\":").append(Json.quote(id))
                .append(",\"methodKey\":").append(Json.quote(methodKey))
                .append(",\"methodName\":").append(Json.quote(methodName))
                .append(",\"masked\":").append(Json.quote(masked))
                .append(",\"amount\":").append(Json.number(amount))
                .append(",\"refundedAmount\":").append(Json.number(refundedAmount))
                .append(",\"remainingRefundable\":").append(Json.number(getRemainingRefundable()))
                .append(",\"date\":").append(Json.quote(createdAt.toString()))
                .append(",\"dateLabel\":").append(Json.quote(createdAt.format(DATE_LABEL)))
                .append(",\"status\":").append(Json.quote(status))
                .append(",\"message\":").append(Json.quote(message == null ? "" : message))
                .append(",\"refundable\":").append(isRefundable())
                .append(",\"refunds\":[");
        for (int i = 0; i < refunds.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(refunds.get(i).toJson());
        }
        builder.append("]}");
        return builder.toString();
    }

    static double round(double value) {
        return new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}

record RefundRecord(String id, String transactionId, String methodName, double amount,
                    LocalDateTime createdAt, String message) {

    String toJson() {
        return "{\"id\":" + Json.quote(id)
                + ",\"transactionId\":" + Json.quote(transactionId)
                + ",\"methodName\":" + Json.quote(methodName)
                + ",\"amount\":" + Json.number(amount)
                + ",\"date\":" + Json.quote(createdAt.toString())
                + ",\"dateLabel\":" + Json.quote(createdAt.format(Transaction.DATE_LABEL))
                + ",\"status\":\"COMPLETED\""
                + ",\"message\":" + Json.quote(message) + "}";
    }
}

class PaymentGateway {
    record RefundOutcome(boolean success, String message, Transaction transaction) {
    }

    private final List<Transaction> transactions = new ArrayList<>();
    private final List<RefundRecord> refunds = new ArrayList<>();
    private final ScheduledExecutorService settlement = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "bank-settlement");
        thread.setDaemon(true);
        return thread;
    });
    private int transactionSequence = 1000;
    private int refundSequence = 500;

    synchronized Transaction pay(Payment payment) {
        PaymentValidator validator = new PaymentValidator();
        validator.amount(payment.getAmount());
        List<String> errors = payment.validate(validator);

        Transaction transaction = new Transaction(
                "TXN-" + (++transactionSequence),
                payment.methodKey(),
                payment.methodName(),
                payment.maskedDetails(),
                Transaction.round(payment.getAmount()),
                LocalDateTime.now());

        if (!errors.isEmpty()) {
            transaction.setStatus("FAILED");
            transaction.setMessage(String.join(" ", errors));
            transactions.add(transaction);
            return transaction;
        }

        PaymentResult result = payment.process();
        transaction.setStatus(result.status());
        transaction.setMessage(result.message());
        transactions.add(transaction);

        if ("PENDING".equals(result.status())) {
            settlement.schedule(() -> settle(transaction.getId()), 14, TimeUnit.SECONDS);
        }
        return transaction;
    }

    private synchronized void settle(String transactionId) {
        for (Transaction transaction : transactions) {
            if (transaction.getId().equals(transactionId) && "PENDING".equals(transaction.getStatus())) {
                transaction.setStatus("SUCCESS");
                transaction.setMessage("Bank transfer confirmed. Funds have settled.");
            }
        }
    }

    synchronized RefundOutcome refund(String transactionId, double amount) {
        if (transactionId == null || transactionId.isBlank()) {
            return new RefundOutcome(false, "Enter a transaction ID to refund.", null);
        }
        Transaction target = null;
        for (Transaction transaction : transactions) {
            if (transaction.getId().equalsIgnoreCase(transactionId.trim())) {
                target = transaction;
                break;
            }
        }
        if (target == null) {
            return new RefundOutcome(false, "No transaction found with ID " + transactionId.trim() + ".", null);
        }
        if ("PENDING".equals(target.getStatus())) {
            return new RefundOutcome(false, "This bank transfer is still pending. Refunds unlock once the payment is confirmed.", target);
        }
        if (!"SUCCESS".equals(target.getStatus())) {
            return new RefundOutcome(false, "Only successful payments can be refunded. Current status: "
                    + prettify(target.getStatus()) + ".", target);
        }
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0) {
            return new RefundOutcome(false, "Enter a refund amount greater than zero.", target);
        }
        if (Math.abs(amount * 100 - Math.rint(amount * 100)) > 0.000001) {
            return new RefundOutcome(false, "Refund amount can have at most two decimal places.", target);
        }
        double remaining = target.getRemainingRefundable();
        if (amount > remaining + 0.0001) {
            return new RefundOutcome(false, "Refund exceeds the refundable balance of " + PaymentApp.money(remaining) + ".", target);
        }

        Payment payment = PaymentApp.createPayment(target.getMethodKey(), 0, Collections.emptyMap());
        RefundResult result = payment.refund(target, Transaction.round(amount));
        if (!result.success()) {
            return new RefundOutcome(false, result.message(), target);
        }

        target.addRefundedAmount(amount);
        target.setMessage(result.message());
        if (target.getRemainingRefundable() <= 0.0001) {
            target.setStatus("REFUNDED");
        }
        RefundRecord record = new RefundRecord(
                "RFD-" + (++refundSequence),
                target.getId(),
                target.getMethodName(),
                Transaction.round(amount),
                LocalDateTime.now(),
                result.message());
        target.getRefunds().add(record);
        refunds.add(record);
        return new RefundOutcome(true, result.message(), target);
    }

    synchronized String stateJson() {
        int success = 0;
        int failed = 0;
        int pending = 0;
        int refunded = 0;
        double processed = 0;
        double refundedAmount = 0;

        for (Transaction transaction : transactions) {
            switch (transaction.getStatus()) {
                case "SUCCESS": {
                    success++;
                    processed += transaction.getAmount();
                    break;
                }
                case "PENDING": {
                    pending++;
                    break;
                }
                case "FAILED": {
                    failed++;
                    break;
                }
                case "REFUNDED": {
                    refunded++;
                    processed += transaction.getAmount();
                    break;
                }
                default: {
                    break;
                }
            }
            refundedAmount += transaction.getRefundedAmount();
        }

        StringBuilder builder = new StringBuilder();
        builder.append("{\"totals\":{\"count\":").append(transactions.size())
                .append(",\"success\":").append(success)
                .append(",\"failed\":").append(failed)
                .append(",\"pending\":").append(pending)
                .append(",\"refunded\":").append(refunded)
                .append(",\"processedAmount\":").append(Json.number(processed))
                .append(",\"refundedAmount\":").append(Json.number(refundedAmount))
                .append("},\"transactions\":[");

        for (int i = transactions.size() - 1; i >= 0; i--) {
            if (i < transactions.size() - 1) {
                builder.append(',');
            }
            builder.append(transactions.get(i).toJson());
        }

        builder.append("],\"refunds\":[");
        for (int i = refunds.size() - 1; i >= 0; i--) {
            if (i < refunds.size() - 1) {
                builder.append(',');
            }
            builder.append(refunds.get(i).toJson());
        }
        builder.append("],\"serverTime\":")
                .append(Json.quote(LocalDateTime.now().format(Transaction.DATE_LABEL)))
                .append('}');
        return builder.toString();
    }

    private String prettify(String status) {
        return switch (status) {
            case "PENDING" -> "Pending";
            case "FAILED" -> "Failed";
            case "REFUNDED" -> "Refunded";
            default -> "Success";
        };
    }
}

final class Json {
    private final String source;
    private int position;

    private Json(String source) {
        this.source = source;
    }

    static Object parse(String text) {
        Json parser = new Json(text == null ? "" : text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.position < parser.source.length()) {
            throw new IllegalArgumentException("Unexpected trailing characters in JSON.");
        }
        return value;
    }

    static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }

    static String number(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private Object readValue() {
        skipWhitespace();
        if (position >= source.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON.");
        }
        char c = source.charAt(position);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        Map<String, Object> object = new LinkedHashMap<>();
        position++;
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return object;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            object.put(key, readValue());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return object;
            }
            if (c != ',') {
                throw new IllegalArgumentException("Invalid object in JSON.");
            }
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        position++;
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return list;
        }
        while (true) {
            list.add(readValue());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw new IllegalArgumentException("Invalid array in JSON.");
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder builder = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return builder.toString();
            }
            if (c == '\\') {
                char escaped = next();
                switch (escaped) {
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    case '/' -> builder.append('/');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        String hex = source.substring(position, position + 4);
                        builder.append((char) Integer.parseInt(hex, 16));
                        position += 4;
                    }
                    default -> throw new IllegalArgumentException("Invalid escape in JSON string.");
                }
            } else {
                builder.append(c);
            }
        }
    }

    private Double readNumber() {
        int start = position;
        while (position < source.length()) {
            char c = source.charAt(position);
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) {
                position++;
            } else {
                break;
            }
        }
        if (start == position) {
            throw new IllegalArgumentException("Invalid value in JSON.");
        }
        return Double.parseDouble(source.substring(start, position));
    }

    private Object readLiteral(String literal, Object value) {
        if (!source.startsWith(literal, position)) {
            throw new IllegalArgumentException("Invalid literal in JSON.");
        }
        position += literal.length();
        return value;
    }

    private void expect(char expected) {
        if (next() != expected) {
            throw new IllegalArgumentException("Expected '" + expected + "' in JSON.");
        }
    }

    private char peek() {
        return position < source.length() ? source.charAt(position) : '\0';
    }

    private char next() {
        if (position >= source.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON.");
        }
        return source.charAt(position++);
    }

    private void skipWhitespace() {
        while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
            position++;
        }
    }
}
