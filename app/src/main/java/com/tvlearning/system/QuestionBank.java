package com.tvlearning.system;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

final class QuestionBank {
    private static final int MAX_QUESTIONS = 500;
    private static final int MAX_BANKS = 50;
    private static final int MAX_TOTAL_QUESTIONS = 5000;
    private static final int MAX_BANK_BYTES = 4 * 1024 * 1024;
    private static final String BANK_ID_PATTERN = "[A-Za-z0-9_-]{20,64}";
    private static QuestionBank instance;

    private final Context context;
    private final File bankDirectory;
    private final File metadataFile;
    private final Random random = new Random();
    private final List<Bank> banks = new ArrayList<>();
    private List<Question> activeQuestions = Collections.emptyList();

    static synchronized QuestionBank get(Context context) {
        if (instance == null) {
            instance = new QuestionBank(context.getApplicationContext());
        }
        return instance;
    }

    private QuestionBank(Context context) {
        this.context = context;
        bankDirectory = new File(context.getFilesDir(), "question_banks");
        metadataFile = new File(bankDirectory, "metadata.json");
        try {
            initialize();
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("本地题库初始化失败", e);
        }
    }

    synchronized Question getQuestion(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (Question question : activeQuestions) {
            if (question.id.equals(id)) {
                return question;
            }
        }
        if (id.indexOf('/') < 0) {
            for (Question question : activeQuestions) {
                if (question.sourceId.equals(id)) {
                    return question;
                }
            }
        }
        return null;
    }

    synchronized Question chooseNext(String excludedId) {
        if (activeQuestions.isEmpty()) {
            return null;
        }
        if (activeQuestions.size() == 1) {
            return activeQuestions.get(0);
        }
        int selected = random.nextInt(activeQuestions.size());
        for (int i = 0; i < activeQuestions.size(); i++) {
            Question candidate = activeQuestions.get((selected + i) % activeQuestions.size());
            if (!matchesId(candidate, excludedId)) {
                return candidate;
            }
        }
        return activeQuestions.get(selected);
    }

    synchronized boolean isCorrect(String id, int answer) {
        Question question = getQuestion(id);
        return question != null && question.answer == answer;
    }

    synchronized String getExplanation(String id) {
        Question question = getQuestion(id);
        return question == null ? "" : question.explanation;
    }

    synchronized int getCount() {
        return activeQuestions.size();
    }

    synchronized String getVersion() {
        Bank only = null;
        int enabledCount = 0;
        for (Bank bank : banks) {
            if (isActive(bank)) {
                enabledCount++;
                only = bank;
            }
        }
        if (enabledCount == 1) {
            return only.version;
        }
        return enabledCount == 0 ? "无启用题库" : enabledCount + " 个文件";
    }

    synchronized JSONObject summaryJson() {
        try {
            int enabledFileCount = 0;
            int totalQuestionCount = 0;
            int errorFileCount = 0;
            for (Bank bank : banks) {
                totalQuestionCount += bank.questions.size();
                if (isActive(bank)) {
                    enabledFileCount++;
                }
                if (!bank.error.isEmpty()) {
                    errorFileCount++;
                }
            }
            return new JSONObject()
                    .put("count", activeQuestions.size())
                    .put("version", getVersion())
                    .put("fileCount", banks.size())
                    .put("enabledFileCount", enabledFileCount)
                    .put("enabledQuestionCount", activeQuestions.size())
                    .put("totalQuestionCount", totalQuestionCount)
                    .put("errorFileCount", errorFileCount);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    synchronized JSONObject toJson() {
        try {
            JSONArray items = new JSONArray();
            for (Bank bank : banks) {
                items.put(bank.toJson());
            }
            return summaryJson().put("banks", items);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    synchronized JSONObject getBankJson(String bankId) {
        Bank bank = findBank(bankId);
        if (bank == null) {
            throw new IllegalArgumentException("题库文件不存在");
        }
        try {
            JSONArray items = new JSONArray();
            for (Question question : bank.questions) {
                items.put(question.toJson(true));
            }
            return bank.toJson()
                    .put("formatVersion", 1)
                    .put("questions", items);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    synchronized JSONObject importQuestions(String json, String requestedName)
            throws JSONException, IOException {
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BANK_BYTES) {
            throw new IllegalArgumentException("题库文件超过 4 MB");
        }
        ParsedBank parsed = parse(json);
        String name = requestedName == null ? "" : requestedName.trim();
        if (name.isEmpty()) {
            name = parsed.version;
        }
        if (name.length() > 80) {
            throw new IllegalArgumentException("题库名称不能超过 80 个字符");
        }

        Bank existing = findBankByName(name);
        if (existing == null && banks.size() >= MAX_BANKS) {
            throw new IllegalArgumentException("最多只能保存 50 个题库文件");
        }
        int total = 0;
        for (Bank bank : banks) {
            if (bank != existing) {
                total += bank.questions.size();
            }
        }
        if (total + parsed.questions.size() > MAX_TOTAL_QUESTIONS) {
            throw new IllegalArgumentException("所有题库合计最多保存 5000 道题");
        }

        Bank bank = existing;
        String oldVersion = null;
        String oldError = null;
        List<Question> oldQuestions = null;
        if (bank == null) {
            bank = new Bank();
            bank.id = newBankId();
            bank.enabled = true;
        } else {
            oldVersion = bank.version;
            oldError = bank.error;
            oldQuestions = bank.questions;
        }

        AtomicWrite contentWrite = new AtomicWrite(contentFile(bank.id));
        AtomicWrite metadataWrite = new AtomicWrite(metadataFile);
        try {
            contentWrite.replace(json);
            if (existing == null) {
                banks.add(bank);
            }
            bank.name = name;
            bank.version = parsed.version;
            bank.error = "";
            bank.questions = qualify(parsed.questions, bank.id);
            metadataWrite.replace(metadataJson().toString());
            metadataWrite.commit();
            contentWrite.commit();
        } catch (IOException | RuntimeException e) {
            rollback(metadataWrite, e);
            rollback(contentWrite, e);
            if (existing == null) {
                banks.remove(bank);
            } else {
                bank.version = oldVersion;
                bank.error = oldError;
                bank.questions = oldQuestions;
            }
            throw e;
        }
        rebuildActiveQuestions();
        PolicyStore.get(context).addLog((existing == null ? "题库已添加：" : "同名题库已更新：")
                + name + "（" + parsed.version + "），共 " + parsed.questions.size() + " 题");
        return bank.toJson();
    }

    synchronized void setEnabled(String bankId, boolean enabled) throws IOException {
        Bank bank = findBank(bankId);
        if (bank == null) {
            throw new IllegalArgumentException("题库文件不存在");
        }
        if (bank.enabled == enabled) {
            return;
        }
        boolean previous = bank.enabled;
        bank.enabled = enabled;
        try {
            saveMetadata();
        } catch (IOException | RuntimeException e) {
            bank.enabled = previous;
            throw e;
        }
        rebuildActiveQuestions();
        PolicyStore.get(context).addLog((enabled ? "题库已启用：" : "题库已停用：") + bank.name);
    }

    synchronized void deleteBank(String bankId) throws IOException {
        Bank bank = findBank(bankId);
        if (bank == null) {
            throw new IllegalArgumentException("题库文件不存在");
        }
        int index = banks.indexOf(bank);
        banks.remove(index);
        try {
            saveMetadata();
        } catch (IOException | RuntimeException e) {
            banks.add(index, bank);
            throw e;
        }
        rebuildActiveQuestions();
        deleteQuietly(contentFile(bank.id));
        deleteQuietly(new File(bankDirectory, bank.id + ".json.tmp"));
        deleteQuietly(new File(bankDirectory, bank.id + ".json.bak"));
        PolicyStore.get(context).addLog("题库已删除：" + bank.name);
    }

    private void initialize() throws IOException, JSONException {
        if (!bankDirectory.exists() && !bankDirectory.mkdirs()) {
            throw new IOException("无法创建题库目录");
        }
        if (!bankDirectory.isDirectory()) {
            throw new IOException("题库目录不可用");
        }
        recoverFile(metadataFile);
        File legacy = new File(context.getFilesDir(), "active_questions.json");
        File legacyBackup = new File(context.getFilesDir(), "active_questions.json.bak");
        if (metadataFile.exists()) {
            try {
                loadMetadata();
            } catch (IOException | JSONException | IllegalArgumentException e) {
                banks.clear();
                quarantineMetadata();
                rebuildFromFiles();
                saveMetadata();
                cleanupOrphanFiles();
                PolicyStore.get(context).addLog("题库元数据损坏，已从有效题库文件重建");
            }
            saveMetadata();
            cleanupOrphanFiles();
        } else if (legacy.exists() || legacyBackup.exists()) {
            migrateLegacy(legacy, legacyBackup);
        } else if (hasBankFiles()) {
            rebuildFromFiles();
            saveMetadata();
            cleanupOrphanFiles();
            PolicyStore.get(context).addLog("题库元数据缺失，已从有效题库文件重建");
        } else {
            importQuestions(readStream(context.getAssets().open("questions/sample.json")), "内置示例");
        }
        rebuildActiveQuestions();
    }

    private void loadMetadata() throws IOException, JSONException {
        JSONObject root = new JSONObject(readStream(new FileInputStream(metadataFile)));
        if (root.getInt("formatVersion") != 1) {
            throw new JSONException("不支持的题库元数据版本");
        }
        JSONArray items = root.getJSONArray("banks");
        if (items.length() > MAX_BANKS) {
            throw new JSONException("题库元数据超过文件数限制");
        }
        banks.clear();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject value = items.getJSONObject(i);
            Bank bank = new Bank();
            bank.id = requiredString(value, "bankId", 64);
            if (!bank.id.matches(BANK_ID_PATTERN) || !ids.add(bank.id)) {
                throw new JSONException("题库内部 ID 无效或重复");
            }
            bank.name = requiredString(value, "name", 80);
            bank.version = value.optString("version", "未知");
            bank.enabled = value.getBoolean("enabled");
            bank.error = value.optString("error", "");
            bank.questions = Collections.emptyList();
            loadBank(bank);
            banks.add(bank);
        }
    }

    private void rebuildFromFiles() {
        banks.clear();
        List<String> ids = new ArrayList<>();
        File[] files = bankDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                String id = bankIdFromFileName(file.getName());
                if (id != null && file.isFile()) {
                    ids.add(id);
                }
            }
        }
        Collections.sort(ids);
        for (String id : ids) {
            if (banks.size() >= MAX_BANKS) {
                PolicyStore.get(context).addLog("重建题库时忽略了超过 50 个限制的文件");
                break;
            }
            Bank bank = new Bank();
            bank.id = id;
            bank.name = "恢复题库";
            bank.version = "未知";
            bank.enabled = true;
            bank.error = "";
            bank.questions = Collections.emptyList();
            loadBank(bank);
            String baseName = bank.error.isEmpty() ? bank.version : "恢复题库";
            bank.name = baseName;
            for (int suffix = 2; findBankByName(bank.name) != null; suffix++) {
                String suffixText = " (" + suffix + ")";
                bank.name = baseName.substring(0, Math.min(baseName.length(), 80 - suffixText.length())) + suffixText;
            }
            banks.add(bank);
        }
    }

    private void loadBank(Bank bank) {
        File file = contentFile(bank.id);
        try {
            recoverFile(file);
            if (!file.exists()) {
                throw new IOException("题库文件不存在");
            }
            ParsedBank parsed = parse(readStream(new FileInputStream(file)));
            bank.version = parsed.version;
            bank.questions = qualify(parsed.questions, bank.id);
            bank.error = "";
        } catch (IOException | JSONException | IllegalArgumentException e) {
            bank.questions = Collections.emptyList();
            bank.error = errorMessage(e);
            PolicyStore.get(context).addLog("题库文件已隔离：" + bank.name + "（" + bank.error + "）");
        }
    }

    private void migrateLegacy(File legacy, File backup) throws IOException, JSONException {
        File source = legacy.exists() ? legacy : backup;
        String raw = null;
        ParsedBank parsed = null;
        String failure = null;
        try {
            raw = readStream(new FileInputStream(source));
            parsed = parse(raw);
        } catch (IOException | JSONException e) {
            failure = errorMessage(e);
        }

        if (parsed != null) {
            JSONObject migrated = importQuestions(raw, parsed.version);
            String migratedBankId = migrated.getString("bankId");
            PolicyStore.RuntimeState state = PolicyStore.get(context).getRuntime();
            boolean changed = false;
            for (Question question : parsed.questions) {
                String sourceId = question.sourceId == null ? question.id : question.sourceId;
                if (sourceId.equals(state.currentQuestionId)) {
                    state.currentQuestionId = migratedBankId + "/" + sourceId;
                    changed = true;
                }
                if (sourceId.equals(state.lastQuestionId)) {
                    state.lastQuestionId = migratedBankId + "/" + sourceId;
                    changed = true;
                }
            }
            if (changed) {
                PolicyStore.get(context).saveRuntime(state, true);
            }
        } else {
            Bank bank = new Bank();
            bank.id = newBankId();
            bank.name = "旧题库（损坏）";
            bank.version = "未知";
            bank.enabled = true;
            bank.error = failure == null ? "旧题库无法读取" : failure;
            bank.questions = Collections.emptyList();
            AtomicWrite contentWrite = new AtomicWrite(contentFile(bank.id));
            boolean renamed = false;
            try {
                if (raw != null) {
                    contentWrite.replace(raw);
                } else {
                    if (!source.renameTo(contentFile(bank.id))) {
                        throw new IOException("无法迁移损坏的旧题库");
                    }
                    renamed = true;
                }
                banks.add(bank);
                saveMetadata();
                contentWrite.commit();
            } catch (IOException | RuntimeException e) {
                banks.remove(bank);
                if (renamed) {
                    deleteQuietly(legacy);
                    if (!contentFile(bank.id).renameTo(source)) {
                        e.addSuppressed(new IOException("无法恢复旧题库文件"));
                    }
                } else {
                    rollback(contentWrite, e);
                }
                throw e;
            }
        }
        deleteQuietly(legacy);
        deleteQuietly(backup);
        rebuildActiveQuestions();
        PolicyStore.get(context).addLog("旧版 active_questions.json 已迁移为多文件题库");
    }

    private void saveMetadata() throws IOException {
        AtomicWrite write = new AtomicWrite(metadataFile);
        write.replace(metadataJson().toString());
        write.commit();
    }

    private JSONObject metadataJson() {
        try {
            JSONArray items = new JSONArray();
            for (Bank bank : banks) {
                items.put(new JSONObject()
                        .put("bankId", bank.id)
                        .put("name", bank.name)
                        .put("version", bank.version)
                        .put("questionCount", bank.questions.size())
                        .put("enabled", bank.enabled)
                        .put("error", bank.error));
            }
            return new JSONObject()
                    .put("formatVersion", 1)
                    .put("initialized", true)
                    .put("banks", items);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private void rebuildActiveQuestions() {
        List<Question> result = new ArrayList<>();
        for (Bank bank : banks) {
            if (isActive(bank)) {
                result.addAll(bank.questions);
            }
        }
        activeQuestions = Collections.unmodifiableList(result);
    }

    private static boolean isActive(Bank bank) {
        return bank.enabled && bank.error.isEmpty() && !bank.questions.isEmpty();
    }

    private static boolean matchesId(Question question, String id) {
        return id != null && (question.id.equals(id) || question.sourceId.equals(id));
    }

    private Bank findBank(String bankId) {
        if (bankId == null) {
            return null;
        }
        for (Bank bank : banks) {
            if (bank.id.equals(bankId)) {
                return bank;
            }
        }
        return null;
    }

    private Bank findBankByName(String name) {
        for (Bank bank : banks) {
            if (bank.name.equals(name)) {
                return bank;
            }
        }
        return null;
    }

    private String newBankId() {
        String id;
        do {
            id = UUID.randomUUID().toString();
        } while (findBank(id) != null || contentFile(id).exists());
        return id;
    }

    private File contentFile(String bankId) {
        if (bankId == null || !bankId.matches(BANK_ID_PATTERN)) {
            throw new IllegalArgumentException("题库内部 ID 无效");
        }
        return new File(bankDirectory, bankId + ".json");
    }

    private static String bankIdFromFileName(String name) {
        if (name == null || !name.endsWith(".json")) {
            return null;
        }
        String id = name.substring(0, name.length() - 5);
        return id.matches(BANK_ID_PATTERN) ? id : null;
    }

    private boolean hasBankFiles() {
        File[] files = bankDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && bankIdFromFileName(file.getName()) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private void cleanupOrphanFiles() {
        Set<String> known = new HashSet<>();
        for (Bank bank : banks) {
            known.add(bank.id + ".json");
            known.add(bank.id + ".json.tmp");
            known.add(bank.id + ".json.bak");
        }
        File[] files = bankDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                boolean temporary = name.matches("[A-Za-z0-9_-]{20,64}\\.json\\.(tmp|bak)");
                if (temporary && !known.contains(name)) {
                    deleteQuietly(file);
                }
            }
        }
    }

    private void quarantineMetadata() throws IOException {
        if (!metadataFile.exists()) {
            return;
        }
        File corrupt = new File(bankDirectory, "metadata.corrupt.json");
        if (corrupt.exists() && !corrupt.delete()) {
            throw new IOException("无法保留旧题库元数据副本");
        }
        if (!metadataFile.renameTo(corrupt)) {
            throw new IOException("无法隔离损坏的题库元数据");
        }
    }

    private static List<Question> qualify(List<Question> questions, String bankId) {
        for (Question question : questions) {
            question.sourceId = question.id;
            question.id = bankId + "/" + question.sourceId;
        }
        return questions;
    }

    private static ParsedBank parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (!(root.get("formatVersion") instanceof Number) || root.getInt("formatVersion") != 1) {
            throw new JSONException("不支持的题库格式版本");
        }
        String bankVersion = requiredString(root, "version", 80);
        JSONArray items = root.getJSONArray("questions");
        if (items.length() < 1 || items.length() > MAX_QUESTIONS) {
            throw new JSONException("题库应包含 1 至 500 道题");
        }
        List<Question> parsed = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> stems = new HashSet<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject value = items.getJSONObject(i);
            Question question = new Question();
            question.id = requiredString(value, "id", 64);
            if (!question.id.matches("[A-Za-z0-9_-]+") || !ids.add(question.id)) {
                throw new JSONException("题目 ID 无效或重复：" + question.id);
            }
            question.grade = requiredString(value, "grade", 30);
            question.subject = requiredString(value, "subject", 30);
            question.topic = requiredString(value, "topic", 80);
            question.difficulty = requiredString(value, "difficulty", 10);
            if (!question.difficulty.matches("简单|中等|困难")) {
                throw new JSONException("题目难度只能是简单、中等或困难");
            }
            question.stem = requiredString(value, "stem", 2000);
            if (!stems.add(question.stem)) {
                throw new JSONException("存在重复题干：" + question.stem);
            }
            JSONArray options = value.getJSONArray("options");
            if (options.length() < 2 || options.length() > 6) {
                throw new JSONException("每题应有 2 至 6 个选项");
            }
            question.options = new ArrayList<>();
            for (int j = 0; j < options.length(); j++) {
                String option = options.getString(j).trim();
                if (option.isEmpty() || option.length() > 300) {
                    throw new JSONException("选项为空或过长");
                }
                question.options.add(option);
            }
            if (!(value.get("answer") instanceof Number)) {
                throw new JSONException("正确答案下标必须是整数");
            }
            double numericAnswer = value.getDouble("answer");
            if (Double.isNaN(numericAnswer) || Double.isInfinite(numericAnswer)
                    || numericAnswer != Math.floor(numericAnswer)) {
                throw new JSONException("正确答案下标必须是整数");
            }
            question.answer = value.getInt("answer");
            if (question.answer < 0 || question.answer >= question.options.size()) {
                throw new JSONException("正确答案下标超出选项范围");
            }
            question.explanation = requiredString(value, "explanation", 3000);
            question.source = requiredString(value, "source", 200);
            parsed.add(question);
        }
        ParsedBank result = new ParsedBank();
        result.questions = Collections.unmodifiableList(parsed);
        result.version = bankVersion;
        return result;
    }

    private static String requiredString(JSONObject value, String name, int maxLength) throws JSONException {
        String result = value.getString(name).trim();
        if (result.isEmpty() || result.length() > maxLength) {
            throw new JSONException(name + " 为空或过长");
        }
        return result;
    }

    private static String readStream(InputStream input) throws IOException {
        try (InputStream stream = input) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                if (output.size() > MAX_BANK_BYTES) {
                    throw new IOException("题库文件超过 4 MB");
                }
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeFile(File file, String value) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
    }

    private static String errorMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = "无法读取或解析题库文件";
        }
        message = message.trim();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private static void deleteQuietly(File file) {
        if (file.exists()) {
            file.delete();
        }
    }

    private static void rollback(AtomicWrite write, Exception original) {
        try {
            write.rollback();
        } catch (IOException rollbackError) {
            original.addSuppressed(rollbackError);
        }
    }

    private static void recoverFile(File target) throws IOException {
        AtomicWrite recovery = new AtomicWrite(target);
        recovery.prepare();
    }

    private static final class AtomicWrite {
        final File target;
        final File temporary;
        final File backup;
        boolean originalMoved;
        boolean replaced;

        AtomicWrite(File target) {
            this.target = target;
            temporary = new File(target.getParentFile(), target.getName() + ".tmp");
            backup = new File(target.getParentFile(), target.getName() + ".bak");
        }

        void prepare() throws IOException {
            if (temporary.exists() && !temporary.delete()) {
                throw new IOException("无法清理题库临时文件");
            }
            if (backup.exists()) {
                if (target.exists()) {
                    if (!backup.delete()) {
                        throw new IOException("无法清理题库旧备份");
                    }
                } else if (!backup.renameTo(target)) {
                    throw new IOException("无法恢复题库备份");
                }
            }
        }

        void replace(String value) throws IOException {
            prepare();
            writeFile(temporary, value);
            if (target.exists()) {
                if (!target.renameTo(backup)) {
                    deleteQuietly(temporary);
                    throw new IOException("无法备份旧题库文件");
                }
                originalMoved = true;
            }
            if (!temporary.renameTo(target)) {
                IOException failure = new IOException("无法保存题库文件");
                if (originalMoved && backup.exists()) {
                    if (backup.renameTo(target)) {
                        originalMoved = false;
                    } else {
                        failure.addSuppressed(new IOException("无法恢复旧题库文件"));
                    }
                }
                deleteQuietly(temporary);
                throw failure;
            }
            replaced = true;
        }

        void commit() {
            deleteQuietly(temporary);
            deleteQuietly(backup);
        }

        void rollback() throws IOException {
            deleteQuietly(temporary);
            if (replaced) {
                deleteQuietly(target);
                replaced = false;
            }
            if (originalMoved && backup.exists() && !backup.renameTo(target)) {
                throw new IOException("无法回滚题库文件");
            }
            originalMoved = false;
        }
    }

    static final class Question {
        String id;
        String sourceId;
        String grade;
        String subject;
        String topic;
        String difficulty;
        String stem;
        List<String> options;
        int answer;
        String explanation;
        String source;

        JSONObject toJson(boolean includeAnswer) {
            try {
                JSONObject value = new JSONObject()
                        .put("id", sourceId == null ? id : sourceId)
                        .put("grade", grade)
                        .put("subject", subject)
                        .put("topic", topic)
                        .put("difficulty", difficulty)
                        .put("stem", stem)
                        .put("options", new JSONArray(options))
                        .put("explanation", explanation)
                        .put("source", source);
                if (includeAnswer) {
                    value.put("answer", answer);
                }
                return value;
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final class Bank {
        String id;
        String name;
        String version;
        boolean enabled;
        String error = "";
        List<Question> questions = Collections.emptyList();

        JSONObject toJson() {
            try {
                return new JSONObject()
                        .put("bankId", id)
                        .put("name", name)
                        .put("version", version)
                        .put("count", questions.size())
                        .put("questionCount", questions.size())
                        .put("enabled", enabled)
                        .put("valid", error.isEmpty())
                        .put("error", error);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final class ParsedBank {
        List<Question> questions;
        String version;
    }
}
