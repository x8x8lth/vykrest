package ua.vkrst.store;

import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.ForwardMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ua.vkrst.store.Catalog.Category;
import ua.vkrst.store.Catalog.Product;
import ua.vkrst.store.Session.State;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class StoreBot implements LongPollingSingleThreadUpdateConsumer {

    // ---------- Тексти (з макета) ----------
    private static final String START_TEXT =
            "☦️ Слава Христу!\n💜 Вітаємо у VKRST.83 [STORE]\n\nОбирай потрібний розділ нижче 👇";

    private static final String CATEGORIES_TEXT =
            "Оберіть категорію товарів\n\nОберіть потрібний розділ, щоб переглянути асортимент 👇";

    private static final String FEEDBACK_TEXT =
            "Маєте запитання, відгук чи пропозицію? Напишіть нам — ми уважно прочитаємо кожне повідомлення.";

    private static final String FEEDBACK_THANKS =
            "Дякуємо за повідомлення! Ми обов'язково відповімо 💜";

    private static final String THANKS_TEXT =
            "Щиро дякуємо за замовлення! Очікуйте, скоро вам скинуть ваш ТТН. "
                    + "Доставка від одного дня до тижня.\n\nНа славу Божу!";

    // ---------- Назви картинок (папка images/) ----------
    private static final String IMG_START = "start.jpg";            // «крутая картина 1»
    private static final String IMG_CATEGORIES = "categories.jpg";  // «крутая картина 2»
    private static final String IMG_CATEGORIES_MORE = "categories_more.jpg"; // «крутая картина 3»

    private final TelegramClient client;
    private final long adminChatId;
    private final String monoUrl;
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();

    public StoreBot(String token, long adminChatId, String monoUrl) {
        this.client = new OkHttpTelegramClient(token);
        this.adminChatId = adminChatId;
        this.monoUrl = monoUrl;
    }

    /** Власник бота — той, чий id вказано в ADMIN_CHAT_ID (.env). */
    private boolean isAdmin(long chatId) {
        return chatId == adminChatId;
    }

    // =====================================================================
    //  Вхідні оновлення
    // =====================================================================

    @Override
    public void consume(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                onCallback(update.getCallbackQuery());
            } else if (update.hasMessage()) {
                onMessage(update.getMessage());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void onMessage(Message m) throws TelegramApiException {
        long chatId = m.getChatId();
        Session s = session(chatId);
        String text = m.hasText() ? m.getText() : "";

        if (text.startsWith("/start")) {
            s.state = State.NONE;
            s.clearDraft();
            showHome(chatId);
            return;
        }
        if (isAdmin(chatId) && text.startsWith("/cancel")) {
            s.state = State.NONE;
            s.clearDraft();
            showHome(chatId);
            return;
        }
        if (isAdmin(chatId) && text.startsWith("/add")) {
            startAddProduct(chatId, s);
            return;
        }

        switch (s.state) {
            case FEEDBACK -> handleFeedback(m, s);
            case AWAITING_PAYMENT, ORDER_PLACED -> handlePaymentProof(m, s);
            case ADMIN_PHOTO -> handleAdminPhoto(m, s);
            case ADMIN_TITLE -> handleAdminTitle(m, s);
            case ADMIN_PRICE -> handleAdminPrice(m, s);
            case ADMIN_EDIT_PHOTO -> handleAdminEditPhoto(m, s);
            case ADMIN_EDIT_TITLE -> handleAdminEditTitle(m, s);
            case ADMIN_EDIT_PRICE -> handleAdminEditPrice(m, s);
            case ADMIN_CATEGORY ->
                    sendText(chatId, "Оберіть категорію кнопкою вище 👆 (або /cancel щоб скасувати)", null);
            default -> showHome(chatId);
        }
    }

    private void onCallback(CallbackQuery cb) throws TelegramApiException {
        try {
            client.execute(AnswerCallbackQuery.builder().callbackQueryId(cb.getId()).build());
        } catch (TelegramApiException ignored) {
        }

        long chatId = cb.getMessage().getChatId();
        int messageId = cb.getMessage().getMessageId();
        Session s = session(chatId);
        String[] parts = cb.getData().split(":");

        deleteQuietly(chatId, messageId);

        switch (parts[0]) {
            case "home" -> {
                s.state = State.NONE;
                s.clearDraft();
                showHome(chatId);
            }
            case "order" -> showCategories(chatId, IMG_CATEGORIES);
            case "more" -> showCategories(chatId, IMG_CATEGORIES_MORE, IMG_CATEGORIES);
            case "feedback" -> {
                s.state = State.FEEDBACK;
                sendText(chatId, FEEDBACK_TEXT, column(btn("← На головну", "home")));
            }
            case "cat" -> showProduct(chatId, Category.valueOf(parts[1]), 0);
            case "view" -> showProduct(chatId, Category.valueOf(parts[1]), Integer.parseInt(parts[2]));
            case "add" -> addToCart(chatId, s, parts[1]);
            case "checkout" -> showCheckout(chatId, s);
            case "rm" -> removeFromCart(chatId, s, Integer.parseInt(parts[1]));
            case "pay" -> showPayment(chatId, s);

            // ----- тільки для власника -----
            case "adm" -> {
                if (isAdmin(chatId)) startAddProduct(chatId, s);
                else showHome(chatId);
            }
            case "admcat" -> {
                if (isAdmin(chatId)) {
                    s.draftCategory = Category.valueOf(parts[1]);
                    s.state = State.ADMIN_PHOTO;
                    sendText(chatId, "Категорія: " + s.draftCategory.title
                            + "\n\nНадішліть фото товару 📷\n(скасувати: /cancel)", null);
                } else showHome(chatId);
            }
            case "photo" -> {
                if (isAdmin(chatId)) {
                    s.editProductId = parts[1];
                    s.state = State.ADMIN_EDIT_PHOTO;
                    String name = Catalog.find(parts[1]).map(Product::caption).orElse("");
                    sendText(chatId, "Надішліть нове фото для товару:\n" + name
                            + "\n\n📷 Можна переслати допис із каналу — візьму з нього фото.\n(скасувати: /cancel)", null);
                } else showHome(chatId);
            }
            case "etitle" -> {
                if (isAdmin(chatId)) {
                    s.editProductId = parts[1];
                    s.state = State.ADMIN_EDIT_TITLE;
                    String name = Catalog.find(parts[1]).map(Product::caption).orElse("");
                    sendText(chatId, "Надішліть нову назву для товару:\n" + name
                            + "\n\n(скасувати: /cancel)", null);
                } else showHome(chatId);
            }
            case "eprice" -> {
                if (isAdmin(chatId)) {
                    s.editProductId = parts[1];
                    s.state = State.ADMIN_EDIT_PRICE;
                    String name = Catalog.find(parts[1]).map(Product::caption).orElse("");
                    sendText(chatId, "Надішліть нову ціну в гривнях (числом) для товару:\n" + name
                            + "\n\n(скасувати: /cancel)", null);
                } else showHome(chatId);
            }
            case "del" -> {
                if (isAdmin(chatId)) confirmDelete(chatId, parts[1]);
                else showHome(chatId);
            }
            case "delok" -> {
                if (isAdmin(chatId)) {
                    Catalog.remove(parts[1]);
                    sendText(chatId, "🗑 Товар видалено.", column(btn("До категорій", "order")));
                } else showHome(chatId);
            }
            default -> showHome(chatId);
        }
    }

    // =====================================================================
    //  Екрани
    // =====================================================================

    /** Головна: картина 1 + привітання + [Замовити] [Зворотній зв'язок]. */
    private void showHome(long chatId) throws TelegramApiException {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        buttons.add(btn("Замовити", "order"));
        buttons.add(btn("Зворотній зв'язок", "feedback"));
        if (isAdmin(chatId)) {
            buttons.add(btn("⚙️ Додати товар", "adm"));
        }
        sendScreen(chatId, START_TEXT, column(buttons.toArray(new InlineKeyboardButton[0])),
                localImage(IMG_START));
    }

    /** Категорії: картина 2 (або 3) + [Патчі] [Піни] [Прапори] [Книги]. */
    private void showCategories(long chatId, String... images) throws TelegramApiException {
        InlineKeyboardButton[] buttons = new InlineKeyboardButton[Category.values().length];
        for (int i = 0; i < buttons.length; i++) {
            Category c = Category.values()[i];
            buttons[i] = btn(c.title, "cat:" + c.name());
        }
        sendScreen(chatId, CATEGORIES_TEXT, column(buttons), localImage(images));
    }

    /** Картка товару з каруселлю: [<] [🛒 У кошик] [>]. */
    private void showProduct(long chatId, Category cat, int index) throws TelegramApiException {
        List<Product> list = Catalog.products(cat);
        if (list.isEmpty()) {
            sendText(chatId, "Цей розділ скоро поповниться 🙌", column(btn("← До категорій", "order")));
            return;
        }
        int n = list.size();
        int i = ((index % n) + n) % n; // циклічна навігація
        Product p = list.get(i);

        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(
                btn("‹", "view:" + cat.name() + ":" + (i - 1)),
                btn("🛒 У кошик", "add:" + p.id()),
                btn("›", "view:" + cat.name() + ":" + (i + 1))));
        rows.add(new InlineKeyboardRow(btn("← До категорій", "order")));
        if (isAdmin(chatId)) {
            rows.add(new InlineKeyboardRow(
                    btn("📷 Фото", "photo:" + p.id()),
                    btn("✏️ Назва", "etitle:" + p.id()),
                    btn("💰 Ціна", "eprice:" + p.id())));
            rows.add(new InlineKeyboardRow(btn("🗑 Видалити", "del:" + p.id())));
        }

        sendScreen(chatId, p.caption(), InlineKeyboardMarkup.builder().keyboard(rows).build(), productPhoto(p));
    }

    /** «Ви додали у кошик ... Бажаєте замовити ще?» */
    private void addToCart(long chatId, Session s, String productId) throws TelegramApiException {
        Optional<Product> found = Catalog.find(productId);
        if (found.isEmpty()) {
            sendText(chatId, "На жаль, цей товар вже недоступний 😔",
                    column(btn("До категорій", "order")));
            return;
        }
        Product p = found.get();
        s.cart.add(p);

        sendText(chatId,
                "Ви додали у кошик \"" + p.title() + "\".\nБажаєте замовити ще?",
                column(btn("Так, ще замовити", "more"),
                        btn("Ні, перейти до оплати", "checkout")));
    }

    /** «До сплати ... грн. Бажаєте щось прибрати?» */
    private void showCheckout(long chatId, Session s) throws TelegramApiException {
        if (s.cart.isEmpty()) {
            sendText(chatId, "Ваш кошик порожній 🛒", column(btn("Замовити", "order")));
            return;
        }
        String text = "🧾 Ваш кошик:\n" + s.cartSummary()
                + "\nДо сплати " + s.total() + " грн.\nБажаєте щось прибрати?";

        InlineKeyboardButton[] buttons = new InlineKeyboardButton[s.cart.size() + 1];
        for (int i = 0; i < s.cart.size(); i++) {
            buttons[i] = btn("❌ Прибрати: " + s.cart.get(i).title(), "rm:" + i);
        }
        buttons[buttons.length - 1] = btn("Ні, перейти до оплати", "pay");

        sendText(chatId, text, column(buttons));
    }

    private void removeFromCart(long chatId, Session s, int index) throws TelegramApiException {
        if (index >= 0 && index < s.cart.size()) {
            s.cart.remove(index);
        }
        if (s.cart.isEmpty()) {
            sendText(chatId, "Кошик порожній. Оберіть щось інше 👇", column(btn("Замовити", "order")));
        } else {
            showCheckout(chatId, s);
        }
    }

    /** Реквізити для оплати. Далі чекаємо скріншот + дані для НП. */
    private void showPayment(long chatId, Session s) throws TelegramApiException {
        if (s.cart.isEmpty()) {
            showCheckout(chatId, s);
            return;
        }
        s.resetOrderProgress();
        s.state = State.AWAITING_PAYMENT;
        String text = "Реквізити для оплати:\n\n"
                + monoUrl + "\n\n"
                + "—————————————————————\n\n"
                + "Після оплати надішліть 2 окремі повідомлення: скріншот квитанції та дані для відправки НП.\n\n"
                + "1. Номер телефону.\n"
                + "2. ПІБ.\n"
                + "3. Місто.\n"
                + "4. Номер відділення / поштомат.";
        sendText(chatId, text, null);
    }

    // =====================================================================
    //  Повідомлення користувача (зворотний зв'язок / підтвердження оплати)
    // =====================================================================

    private void handleFeedback(Message m, Session s) throws TelegramApiException {
        sendText(adminChatId, "💬 Зворотній зв'язок від " + who(m.getFrom()), null);
        forwardToAdmin(m);

        s.state = State.NONE;
        sendText(m.getChatId(), FEEDBACK_THANKS, column(btn("← На головну", "home")));
    }

    private void handlePaymentProof(Message m, Session s) throws TelegramApiException {
        long chatId = m.getChatId();

        if (s.state == State.ORDER_PLACED) {
            // замовлення вже передано — все, що клієнт дошле далі, пересилаємо власнику як доповнення
            sendText(adminChatId, "➕ Доповнення до замовлення від " + who(m.getFrom()), null);
            forwardToAdmin(m);
            return;
        }

        // AWAITING_PAYMENT: потрібні ДВА повідомлення — скріншот (фото/файл) і текст з даними для НП
        boolean isProof = m.hasPhoto() || m.hasDocument();
        boolean isText = m.hasText() && !m.getText().isBlank();
        if (!isProof && !isText) {
            sendText(chatId, "Надішліть скріншот квитанції (фото) або текстом дані для відправки НП 🙏", null);
            return;
        }

        s.pendingMessageIds.add(m.getMessageId());
        if (isProof) s.hasProof = true;
        else s.hasDelivery = true;

        if (!s.hasProof) {
            sendText(chatId, "✅ Дані для відправки отримано.\n\nТепер надішліть скріншот квитанції про оплату 📸", null);
            return;
        }
        if (!s.hasDelivery) {
            sendText(chatId, "✅ Скріншот отримано.\n\nТепер надішліть окремим повідомленням дані для відправки НП:\n\n"
                    + "1. Номер телефону.\n"
                    + "2. ПІБ.\n"
                    + "3. Місто.\n"
                    + "4. Номер відділення / поштомат.", null);
            return;
        }

        // є і скріншот, і дані — передаємо замовлення власнику
        sendText(adminChatId,
                "🛒 Нове замовлення від " + who(m.getFrom()) + "\n\n"
                        + s.cartSummary() + "\nРазом: " + s.total() + " грн",
                null);
        for (int messageId : s.pendingMessageIds) {
            forwardToAdmin(chatId, messageId);
        }

        s.cart.clear();
        s.resetOrderProgress();
        s.state = State.ORDER_PLACED;

        sendText(chatId, THANKS_TEXT, column(
                btn("Повернутись до головної сторінки", "home"),
                btn("Зворотній зв'язок", "feedback")));
    }

    // =====================================================================
    //  Додавання / видалення товару (тільки власник)
    //  /add → категорія → фото → назва → ціна → готово
    // =====================================================================

    private void startAddProduct(long chatId, Session s) throws TelegramApiException {
        s.clearDraft();
        s.state = State.ADMIN_CATEGORY;

        InlineKeyboardButton[] buttons = new InlineKeyboardButton[Category.values().length + 1];
        for (int i = 0; i < Category.values().length; i++) {
            Category c = Category.values()[i];
            buttons[i] = btn(c.title, "admcat:" + c.name());
        }
        buttons[buttons.length - 1] = btn("✖️ Скасувати", "home");

        sendText(chatId, "➕ Новий товар\n\nОберіть категорію:", column(buttons));
    }

    private void handleAdminPhoto(Message m, Session s) throws TelegramApiException {
        long chatId = m.getChatId();
        if (!m.hasPhoto()) {
            sendText(chatId, "Потрібне саме фото 📷. Надішліть картинку товару (або /cancel).", null);
            return;
        }
        var sizes = m.getPhoto();
        // останній елемент — найбільша версія фото; його file_id Telegram прив'язує до цього бота
        s.draftFileId = sizes.get(sizes.size() - 1).getFileId();
        s.state = State.ADMIN_TITLE;
        sendText(chatId, "Фото збережено ✅\n\nТепер надішліть назву товару, наприклад:\n"
                + "Патч \"Спас Нерукотворний\" (Олива)", null);
    }

    private void handleAdminTitle(Message m, Session s) throws TelegramApiException {
        long chatId = m.getChatId();
        if (!m.hasText() || m.getText().isBlank()) {
            sendText(chatId, "Надішліть назву текстом (або /cancel).", null);
            return;
        }
        String title = m.getText().strip();
        if (title.length() > 150) {
            sendText(chatId, "Задовга назва (максимум 150 символів). Скоротіть, будь ласка.", null);
            return;
        }
        s.draftTitle = title;
        s.state = State.ADMIN_PRICE;
        sendText(chatId, "Тепер надішліть ціну в гривнях числом, наприклад: 470", null);
    }

    private void handleAdminPrice(Message m, Session s) throws TelegramApiException {
        long chatId = m.getChatId();
        String digits = m.hasText() ? m.getText().replaceAll("[^0-9]", "") : "";
        if (digits.isEmpty() || digits.length() > 7 || Integer.parseInt(digits) <= 0) {
            sendText(chatId, "Не зрозумів ціну 🤔 Надішліть число, наприклад: 470", null);
            return;
        }
        int price = Integer.parseInt(digits);

        Product p = Catalog.add(s.draftCategory, s.draftTitle, price, "", s.draftFileId);
        s.state = State.NONE;
        s.clearDraft();

        InlineKeyboardMarkup kb = column(
                btn("➕ Додати ще", "adm"),
                btn("👀 Переглянути в магазині", "cat:" + p.category().name()),
                btn("← На головну", "home"));
        sendScreen(chatId, "✅ Товар додано в «" + p.category().title + "»:\n" + p.caption(), kb, productPhoto(p));
    }

    private void handleAdminEditPhoto(Message m, Session s) throws TelegramApiException {
        long chatId = m.getChatId();
        if (!m.hasPhoto()) {
            sendText(chatId, "Потрібне саме фото 📷 (можна переслати допис із каналу). Або /cancel.", null);
            return;
        }
        var sizes = m.getPhoto();
        String fileId = sizes.get(sizes.size() - 1).getFileId();

        Optional<Product> updated = Catalog.setPhoto(s.editProductId, fileId);
        s.state = State.NONE;
        s.clearDraft();

        if (updated.isEmpty()) {
            sendText(chatId, "Товар не знайдено (можливо, його видалили).", column(btn("До категорій", "order")));
            return;
        }
        Product p = updated.get();
        int index = Math.max(0, Catalog.products(p.category()).indexOf(p));
        sendScreen(chatId, "✅ Фото оновлено:\n" + p.caption(), column(
                btn("👀 Повернутись до картки", "view:" + p.category().name() + ":" + index),
                btn("← На головну", "home")), productPhoto(p));
    }

    private void handleAdminEditTitle(Message m, Session s) throws TelegramApiException {
        long chatId = m.getChatId();
        if (!m.hasText() || m.getText().isBlank()) {
            sendText(chatId, "Надішліть нову назву текстом (або /cancel).", null);
            return;
        }
        String title = m.getText().strip();
        if (title.length() > 150) {
            sendText(chatId, "Задовга назва (максимум 150 символів). Скоротіть, будь ласка.", null);
            return;
        }
        Optional<Product> updated = Catalog.setTitle(s.editProductId, title);
        finishEdit(chatId, s, updated, "✅ Назву оновлено:\n");
    }

    private void handleAdminEditPrice(Message m, Session s) throws TelegramApiException {
        long chatId = m.getChatId();
        String digits = m.hasText() ? m.getText().replaceAll("[^0-9]", "") : "";
        if (digits.isEmpty() || digits.length() > 7 || Integer.parseInt(digits) <= 0) {
            sendText(chatId, "Не зрозумів ціну 🤔 Надішліть число, наприклад: 470", null);
            return;
        }
        Optional<Product> updated = Catalog.setPrice(s.editProductId, Integer.parseInt(digits));
        finishEdit(chatId, s, updated, "✅ Ціну оновлено:\n");
    }

    private void finishEdit(long chatId, Session s, Optional<Product> updated, String prefix)
            throws TelegramApiException {
        s.state = State.NONE;
        s.clearDraft();
        if (updated.isEmpty()) {
            sendText(chatId, "Товар не знайдено (можливо, його видалили).", column(btn("До категорій", "order")));
            return;
        }
        Product p = updated.get();
        int index = Math.max(0, Catalog.products(p.category()).indexOf(p));
        sendScreen(chatId, prefix + p.caption(), column(
                btn("👀 Повернутись до картки", "view:" + p.category().name() + ":" + index),
                btn("← На головну", "home")), productPhoto(p));
    }

    private void confirmDelete(long chatId, String productId) throws TelegramApiException {
        Optional<Product> p = Catalog.find(productId);
        if (p.isEmpty()) {
            sendText(chatId, "Цей товар вже видалено.", column(btn("До категорій", "order")));
            return;
        }
        sendText(chatId, "Видалити товар?\n" + p.get().caption(), column(
                btn("Так, видалити", "delok:" + productId),
                btn("Скасувати", "cat:" + p.get().category().name())));
    }

    // =====================================================================
    //  Хелпери
    // =====================================================================

    private Session session(long chatId) {
        return sessions.computeIfAbsent(chatId, id -> new Session());
    }

    private void forwardToAdmin(Message m) throws TelegramApiException {
        forwardToAdmin(m.getChatId(), m.getMessageId());
    }

    private void forwardToAdmin(long fromChatId, int messageId) throws TelegramApiException {
        client.execute(ForwardMessage.builder()
                .chatId(String.valueOf(adminChatId))
                .fromChatId(String.valueOf(fromChatId))
                .messageId(messageId)
                .build());
    }

    private static String who(User u) {
        if (u == null) return "невідомий";
        StringBuilder sb = new StringBuilder(u.getFirstName() == null ? "" : u.getFirstName());
        if (u.getLastName() != null) sb.append(' ').append(u.getLastName());
        if (u.getUserName() != null) sb.append(" @").append(u.getUserName());
        sb.append(" (id ").append(u.getId()).append(')');
        return sb.toString();
    }

    /** Фото товару: спочатку Telegram file_id, інакше локальний файл з images/. */
    private InputFile productPhoto(Product p) {
        if (p.hasFileId()) {
            return new InputFile(p.fileId());
        }
        // запасний варіант: images/<id>.jpg (або .jpeg / .png)
        return localImage(p.image(), p.id() + ".jpg", p.id() + ".jpeg", p.id() + ".png");
    }

    /** Перший існуючий файл з папки images/ або null. */
    private InputFile localImage(String... names) {
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            Path path = Path.of("images", name);
            if (Files.exists(path)) {
                File f = path.toFile();
                return new InputFile(f);
            }
        }
        return null;
    }

    /** Надсилає фото з підписом, якщо воно є (і Telegram його прийняв), інакше — просто текст. */
    private void sendScreen(long chatId, String text, InlineKeyboardMarkup kb, InputFile photo)
            throws TelegramApiException {
        if (photo != null) {
            try {
                client.execute(SendPhoto.builder()
                        .chatId(String.valueOf(chatId))
                        .photo(photo)
                        .caption(text)
                        .replyMarkup(kb)
                        .build());
                return;
            } catch (TelegramApiException e) {
                System.err.println("Не вдалося надіслати фото, надсилаю текст: " + e.getMessage());
            }
        }
        sendText(chatId, text, kb);
    }

    private void sendText(long chatId, String text, InlineKeyboardMarkup kb) throws TelegramApiException {
        client.execute(SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .replyMarkup(kb)
                .build());
    }

    private void deleteQuietly(long chatId, int messageId) {
        try {
            client.execute(DeleteMessage.builder()
                    .chatId(String.valueOf(chatId))
                    .messageId(messageId)
                    .build());
        } catch (TelegramApiException ignored) {
            // повідомлення вже видалене або старше 48 годин
        }
    }

    private static InlineKeyboardButton btn(String text, String callbackData) {
        return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
    }

    /** Кожна кнопка — в окремому рядку. */
    private static InlineKeyboardMarkup column(InlineKeyboardButton... buttons) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (InlineKeyboardButton button : buttons) {
            rows.add(new InlineKeyboardRow(button));
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }
}
