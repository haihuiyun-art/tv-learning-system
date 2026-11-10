package com.tvlearning.system;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

final class LocalControlServer extends NanoHTTPD {
    private static final long SESSION_LIFETIME_MS = 30 * 60 * 1000L;
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private final Context context;
    private final PolicyStore store;
    private final PolicyEngine engine;
    private final QuestionBank questions;
    private final AppRepository apps;
    private final Map<String, SessionData> sessions = new HashMap<>();
    private final Map<String, LoginAttempts> loginAttempts = new HashMap<>();

    LocalControlServer(Context context, int port) {
        super(port);
        this.context = context.getApplicationContext();
        store = PolicyStore.get(this.context);
        engine = PolicyEngine.get(this.context);
        questions = QuestionBank.get(this.context);
        apps = new AppRepository(this.context);
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            String uri = session.getUri();
            if (uri.startsWith("/api/")) {
                return secure(serveApi(session), true);
            }
            if (session.getMethod() != Method.GET) {
                return secure(json(Response.Status.METHOD_NOT_ALLOWED,
                        error("method_not_allowed", "不支持的请求方法")), true);
            }
            return serveAsset(uri);
        } catch (JSONException | IllegalArgumentException e) {
            return secure(json(Response.Status.BAD_REQUEST,
                    error("invalid_request", e.getMessage() == null ? "请求内容不正确" : e.getMessage())), true);
        } catch (IOException e) {
            return secure(json(Response.Status.INTERNAL_ERROR,
                    error("local_io_error", "本地保存失败，请检查设备存储空间")), true);
        } catch (RuntimeException e) {
            store.addLog("家长网页请求异常：" + e.getClass().getSimpleName());
            return secure(json(Response.Status.INTERNAL_ERROR,
                    error("internal_error", "设备处理请求失败")), true);
        }
    }

    private synchronized Response serveApi(IHTTPSession session) throws IOException, JSONException {
        String uri = session.getUri();
        Method method = session.getMethod();
        if (Method.GET.equals(method) && "/api/bootstrap".equals(uri)) {
            return json(Response.Status.OK, new JSONObject()
                    .put("configured", store.isConfigured())
                    .put("deviceName", store.getDeviceName()));
        }
        if (Method.POST.equals(method) && "/api/setup".equals(uri)) {
            if (store.isConfigured()) {
                return json(Response.Status.CONFLICT, error("already_configured", "设备已完成初始化"));
            }
            JSONObject body = readJsonBody(session);
            String remote = session.getRemoteIpAddress();
            if (isBlocked(remote)) {
                return json(Response.Status.TOO_MANY_REQUESTS, error("rate_limited", "尝试次数过多，请稍后再试"));
            }
            boolean success = store.completeSetup(body.getString("pairingCode"), body.getString("pin"));
            if (!success) {
                recordFailure(remote);
                return json(Response.Status.UNAUTHORIZED, error("setup_failed", "配对码不正确，或 PIN 不符合 6～8 位数字要求"));
            }
            clearFailures(remote);
            return authenticatedResponse(createSession());
        }
        if (Method.POST.equals(method) && "/api/login".equals(uri)) {
            JSONObject body = readJsonBody(session);
            String remote = session.getRemoteIpAddress();
            if (isBlocked(remote)) {
                return json(Response.Status.TOO_MANY_REQUESTS, error("rate_limited", "尝试次数过多，请稍后再试"));
            }
            if (!PolicyEngine.verifyParentSecret(store, body.getString("pin"))) {
                recordFailure(remote);
                return json(Response.Status.UNAUTHORIZED, error("login_failed", "家长 PIN 不正确"));
            }
            clearFailures(remote);
            return authenticatedResponse(createSession());
        }

        SessionData auth = authenticate(session);
        if (auth == null) {
            return json(Response.Status.UNAUTHORIZED, error("unauthorized", "请先登录家长账号"));
        }
        if (!Method.GET.equals(method)) {
            String csrf = session.getHeaders().get("x-csrf-token");
            if (csrf == null || !csrf.equals(auth.csrfToken)) {
                return json(Response.Status.FORBIDDEN, error("csrf_failed", "请求验证失败，请刷新页面后重试"));
            }
        }

        if (Method.POST.equals(method) && "/api/logout".equals(uri)) {
            String token = readSessionToken(session);
            if (token != null) {
                sessions.remove(token);
            }
            Response response = json(Response.Status.OK, new JSONObject().put("success", true));
            response.addHeader("Set-Cookie", "tv_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
            return response;
        }
        if (Method.POST.equals(method) && "/api/security/change-pin".equals(uri)) {
            JSONObject body = readJsonBody(session);
            String remote = session.getRemoteIpAddress();
            if (isBlocked(remote)) {
                return json(Response.Status.TOO_MANY_REQUESTS, error("rate_limited", "尝试次数过多，请稍后再试"));
            }
            String newPin = body.getString("newPin").trim();
            if (!newPin.matches("\\d{6,8}")) {
                throw new IllegalArgumentException("新 PIN 必须为 6～8 位数字");
            }
            if (!store.changePin(body.getString("currentSecret"), newPin)) {
                recordFailure(remote);
                return json(Response.Status.UNAUTHORIZED, error("credential_invalid", "当前 PIN 或恢复码不正确"));
            }
            clearFailures(remote);
            sessions.clear();
            return json(Response.Status.OK, new JSONObject()
                    .put("success", true)
                    .put("relogin", true));
        }
        if (Method.GET.equals(method) && "/api/state".equals(uri)) {
            return json(Response.Status.OK, stateJson().put("csrfToken", auth.csrfToken));
        }
        if (Method.POST.equals(method) && "/api/policy".equals(uri)) {
            engine.updatePolicy(readJsonBody(session));
            return json(Response.Status.OK, new JSONObject().put("success", true));
        }
        if (Method.POST.equals(method) && "/api/control".equals(uri)) {
            JSONObject request = readJsonBody(session);
            String action = request.getString("action");
            if ("set_app_enabled".equals(action)) {
                store.setAppEnabled(request.getString("packageName"), request.getBoolean("enabled"));
            } else {
                engine.control(request);
            }
            return json(Response.Status.OK, new JSONObject().put("success", true));
        }
        if (Method.GET.equals(method) && "/api/questions".equals(uri)) {
            return json(Response.Status.OK, questions.toJson());
        }
        if (Method.GET.equals(method) && "/api/questions/bank".equals(uri)) {
            return json(Response.Status.OK, questions.getBankJson(session.getParms().get("bankId")));
        }
        if (Method.POST.equals(method) && "/api/questions/import".equals(uri)) {
            JSONObject body = readJsonBody(session);
            String name = body.has("name") ? body.getString("name") : "";
            JSONObject bank = questions.importQuestions(body.toString(), name);
            return json(Response.Status.OK, new JSONObject()
                    .put("success", true)
                    .put("version", questions.getVersion())
                    .put("count", questions.getCount())
                    .put("bank", bank)
                    .put("summary", questions.summaryJson()));
        }
        if (Method.POST.equals(method) && "/api/questions/enable".equals(uri)) {
            JSONObject body = readJsonBody(session);
            questions.setEnabled(body.getString("bankId"), body.getBoolean("enabled"));
            return json(Response.Status.OK, new JSONObject()
                    .put("success", true)
                    .put("summary", questions.summaryJson()));
        }
        if (Method.POST.equals(method) && "/api/questions/delete".equals(uri)) {
            questions.deleteBank(readJsonBody(session).getString("bankId"));
            return json(Response.Status.OK, new JSONObject()
                    .put("success", true)
                    .put("summary", questions.summaryJson()));
        }
        return json(Response.Status.NOT_FOUND, error("not_found", "接口不存在"));
    }

    private JSONObject stateJson() throws JSONException {
        PolicyStore.RuntimeState runtime = store.getRuntime();
        JSONArray logs = new JSONArray(store.getLogs());
        return new JSONObject()
                .put("deviceName", store.getDeviceName())
                .put("protectionEnabled", store.isProtectionEnabled())
                .put("state", runtime.state)
                .put("remainingWatchSeconds", runtime.remainingWatchSeconds)
                .put("cooldownRemainingSeconds", runtime.cooldownRemainingSeconds)
                .put("dailyUsedMinutes", runtime.dailyUsedSeconds / 60)
                .put("policy", store.getPolicy().toJson())
                .put("apps", apps.toJson())
                .put("questions", questions.summaryJson())
                .put("address", WatchPolicyService.getAddress())
                .put("logs", logs);
    }

    private Response serveAsset(String uri) {
        String name;
        if ("/".equals(uri) || "/index.html".equals(uri)) {
            name = "index.html";
        } else if ("/app.css".equals(uri) || "/app.js".equals(uri) || "/questions-template.json".equals(uri)) {
            name = uri.substring(1);
        } else {
            return secure(json(Response.Status.NOT_FOUND, error("not_found", "页面不存在")), true);
        }
        try {
            InputStream input = context.getAssets().open("parent/" + name);
            Response response = newFixedLengthResponse(Response.Status.OK, mimeType(name), input, input.available());
            return secure(response, false);
        } catch (IOException e) {
            return secure(json(Response.Status.NOT_FOUND, error("not_found", "页面资源不存在")), true);
        }
    }

    private JSONObject readJsonBody(IHTTPSession session) throws IOException, JSONException {
        String length = session.getHeaders().get("content-length");
        if (length != null) {
            try {
                if (Long.parseLong(length) > MAX_BODY_BYTES) {
                    throw new IllegalArgumentException("请求内容超过 4 MB");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("请求长度不正确");
            }
        }
        Map<String, String> files = new HashMap<>();
        try {
            session.parseBody(files);
        } catch (NanoHTTPD.ResponseException e) {
            throw new IllegalArgumentException("请求体解析失败");
        }
        String body = files.get("postData");
        if (body == null || body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("请求为空或超过 4 MB");
        }
        return new JSONObject(body);
    }

    private Response authenticatedResponse(SessionData session) throws JSONException {
        Response response = json(Response.Status.OK, new JSONObject().put("csrfToken", session.csrfToken));
        response.addHeader("Set-Cookie", "tv_session=" + session.token
                + "; Path=/; HttpOnly; SameSite=Strict");
        return response;
    }

    private SessionData createSession() {
        cleanupSessions();
        SessionData session = new SessionData();
        session.token = PasswordHasher.newToken();
        session.csrfToken = PasswordHasher.newToken();
        session.expiresAt = System.currentTimeMillis() + SESSION_LIFETIME_MS;
        sessions.put(session.token, session);
        return session;
    }

    private SessionData authenticate(IHTTPSession session) {
        cleanupSessions();
        String token = readSessionToken(session);
        if (token == null) {
            return null;
        }
        SessionData data = sessions.get(token);
        if (data == null || data.expiresAt < System.currentTimeMillis()) {
            sessions.remove(token);
            return null;
        }
        data.expiresAt = System.currentTimeMillis() + SESSION_LIFETIME_MS;
        return data;
    }

    private String readSessionToken(IHTTPSession session) {
        return new CookieHandler(session.getHeaders()).read("tv_session");
    }

    private void cleanupSessions() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, SessionData>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expiresAt < now) {
                iterator.remove();
            }
        }
    }

    private boolean isBlocked(String remote) {
        LoginAttempts value = loginAttempts.get(remote);
        return value != null && value.blockedUntil > System.currentTimeMillis();
    }

    private void recordFailure(String remote) {
        LoginAttempts value = loginAttempts.get(remote);
        if (value == null) {
            value = new LoginAttempts();
            loginAttempts.put(remote, value);
        }
        long now = System.currentTimeMillis();
        if (value.blockedUntil > 0 && value.blockedUntil <= now) {
            value.count = 0;
            value.blockedUntil = 0;
        }
        value.count++;
        if (value.count >= 5) {
            value.count = 0;
            value.blockedUntil = now + 30000;
        }
    }

    private void clearFailures(String remote) {
        loginAttempts.remove(remote);
    }

    private static JSONObject error(String code, String message) {
        try {
            return new JSONObject().put("error", code).put("message", message);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Response json(Response.Status status, JSONObject body) {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString());
    }

    private static Response secure(Response response, boolean noStore) {
        response.addHeader("X-Content-Type-Options", "nosniff");
        response.addHeader("X-Frame-Options", "DENY");
        response.addHeader("Referrer-Policy", "no-referrer");
        response.addHeader("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                        + "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'");
        response.addHeader("Cache-Control", noStore ? "no-store" : "no-cache");
        return response;
    }

    private static String mimeType(String name) {
        if (name.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (name.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        return "application/json; charset=utf-8";
    }

    private static final class SessionData {
        String token;
        String csrfToken;
        long expiresAt;
    }

    private static final class LoginAttempts {
        int count;
        long blockedUntil;
    }
}
