package ua.vkrst.store;

import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class Main {

    public static void main(String[] args) throws Exception {
        Map<String, String> env = loadEnv();

        String token = require(env, "BOT_TOKEN");
        long adminChatId = Long.parseLong(require(env, "ADMIN_CHAT_ID"));
        String monoUrl = env.getOrDefault(
                "MONO_URL", "https://send.monobank.ua/jar/21HJGxMV2a");

        try (TelegramBotsLongPollingApplication app = new TelegramBotsLongPollingApplication()) {
            app.registerBot(token, new StoreBot(token, adminChatId, monoUrl));
            System.out.println("VKRST.83 [STORE] bot started");
            Thread.currentThread().join();
        }
    }

    /** Змінні середовища + файл .env у корені проєкту (якщо є). */
    private static Map<String, String> loadEnv() throws IOException {
        Map<String, String> env = new HashMap<>(System.getenv());
        Path file = Path.of(".env");
        if (!Files.exists(file)) {
            return env;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            line = line.replace("\uFEFF", "").strip(); // прибираємо BOM від Блокнота
            if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                continue;
            }
            int i = line.indexOf('=');
            String key = line.substring(0, i).strip();
            String value = line.substring(i + 1).strip();
            if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            env.put(key, value);
        }
        return env;
    }

    private static String require(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set variable " + name + " in .env file");
        }
        return value;
    }
}