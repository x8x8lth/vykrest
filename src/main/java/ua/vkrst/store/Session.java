package ua.vkrst.store;

import ua.vkrst.store.Catalog.Category;
import ua.vkrst.store.Catalog.Product;

import java.util.ArrayList;
import java.util.List;

/** Стан користувача: що бот від нього очікує + кошик (+ чернетка нового товару для власника). */
public class Session {

    public enum State {
        NONE,               // звичайна навігація кнопками
        FEEDBACK,           // чекаємо повідомлення для зворотного зв'язку
        AWAITING_PAYMENT,   // показали реквізити, чекаємо скріншот + дані для НП
        ORDER_PLACED,       // замовлення передано власнику, додаткові повідомлення теж пересилаємо

        ADMIN_CATEGORY,     // власник обирає категорію нового товару
        ADMIN_PHOTO,        // чекаємо фото товару
        ADMIN_TITLE,        // чекаємо назву
        ADMIN_PRICE,        // чекаємо ціну
        ADMIN_EDIT_PHOTO,   // чекаємо нове фото для вже існуючого товару
        ADMIN_EDIT_TITLE,   // чекаємо нову назву для вже існуючого товару
        ADMIN_EDIT_PRICE    // чекаємо нову ціну для вже існуючого товару
    }

    public State state = State.NONE;
    public final List<Product> cart = new ArrayList<>();

    // оформлення замовлення: потрібно ДВА повідомлення — скріншот оплати + дані для НП
    public final List<Integer> pendingMessageIds = new ArrayList<>();
    public boolean hasProof;      // отримали скріншот (фото / файл)
    public boolean hasDelivery;   // отримали текст з даними для НП

    public void resetOrderProgress() {
        pendingMessageIds.clear();
        hasProof = false;
        hasDelivery = false;
    }

    // чернетка нового товару (використовується лише власником)
    public Category draftCategory;
    public String draftFileId;
    public String draftTitle;
    public String editProductId;

    public int total() {
        return cart.stream().mapToInt(Product::price).sum();
    }

    public String cartSummary() {
        StringBuilder sb = new StringBuilder();
        for (Product p : cart) {
            sb.append("• ").append(p.caption()).append('\n');
        }
        return sb.toString();
    }

    public void clearDraft() {
        draftCategory = null;
        draftFileId = null;
        draftTitle = null;
        editProductId = null;
    }
}
