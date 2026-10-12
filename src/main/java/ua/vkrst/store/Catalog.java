package ua.vkrst.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Каталог товарів. Зберігається у файлі data/products.tsv, тому переживає перезапуск бота.
 * Новий товар додає власник бота прямо з Telegram (/add), редагувати код не потрібно.
 */
public final class Catalog {

    public enum Category {
        PATCHES("Патчі"),
        PINS("Піни"),
        FLAGS("Прапори"),
        BOOKS("Книги");

        public final String title;

        Category(String title) {
            this.title = title;
        }
    }

    /**
     * Один товар = одна картка. Фото, назва і ціна живуть в одному записі,
     * тому вони завжди відповідають одне одному.
     *
     * @param id     короткий унікальний код товару
     * @param image  назва локального файлу в images/ (запасний варіант, може бути порожнім)
     * @param fileId Telegram file_id фото (ставиться, коли власник надсилає фото боту)
     */
    public record Product(String id, Category category, String title, int price, String image, String fileId) {
        public String caption() {
            return title + " — " + price + "₴";
        }

        public boolean hasFileId() {
            return fileId != null && !fileId.isBlank();
        }
    }

    private static final Path FILE = Path.of("data", "products.tsv");
    private static final List<Product> PRODUCTS = new ArrayList<>();

    static {
        load();
    }

    private Catalog() {
    }

    // ---------- читання ----------

    public static synchronized List<Product> products(Category category) {
        return PRODUCTS.stream().filter(p -> p.category() == category).collect(Collectors.toList());
    }

    public static synchronized Optional<Product> find(String id) {
        return PRODUCTS.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    // ---------- зміни ----------

    public static synchronized Product add(Category category, String title, int price, String image, String fileId) {
        Product p = new Product(
                UUID.randomUUID().toString().substring(0, 8),
                category, clean(title), price, clean(image), clean(fileId));
        PRODUCTS.add(p);
        save();
        return p;
    }

    public static synchronized boolean remove(String id) {
        boolean removed = PRODUCTS.removeIf(p -> p.id().equals(id));
        if (removed) save();
        return removed;
    }

    /** Прив'язує нове фото (Telegram file_id) до наявного товару. */
    public static synchronized Optional<Product> setPhoto(String id, String fileId) {
        for (int i = 0; i < PRODUCTS.size(); i++) {
            Product p = PRODUCTS.get(i);
            if (p.id().equals(id)) {
                Product updated = new Product(p.id(), p.category(), p.title(), p.price(), p.image(), clean(fileId));
                PRODUCTS.set(i, updated);
                save();
                return Optional.of(updated);
            }
        }
        return Optional.empty();
    }

    /** Змінює назву наявного товару. */
    public static synchronized Optional<Product> setTitle(String id, String title) {
        return replace(id, p -> new Product(p.id(), p.category(), clean(title), p.price(), p.image(), p.fileId()));
    }

    /** Змінює ціну наявного товару. */
    public static synchronized Optional<Product> setPrice(String id, int price) {
        return replace(id, p -> new Product(p.id(), p.category(), p.title(), price, p.image(), p.fileId()));
    }

    private static Optional<Product> replace(String id, java.util.function.UnaryOperator<Product> change) {
        for (int i = 0; i < PRODUCTS.size(); i++) {
            Product p = PRODUCTS.get(i);
            if (p.id().equals(id)) {
                Product updated = change.apply(p);
                PRODUCTS.set(i, updated);
                save();
                return Optional.of(updated);
            }
        }
        return Optional.empty();
    }

    // ---------- збереження ----------

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').strip();
    }

    private static void load() {
        if (!Files.exists(FILE)) {
            seed();
            save();
            return;
        }
        try {
            for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                // id \t категорія \t ціна \t image \t fileId \t назва
                String[] f = line.split("\t", -1);
                if (f.length < 6) continue;
                PRODUCTS.add(new Product(f[0], Category.valueOf(f[1]), f[5], Integer.parseInt(f[2]), f[3], f[4]));
            }
        } catch (IOException | RuntimeException e) {
            e.printStackTrace();
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            String data = PRODUCTS.stream()
                    .map(p -> String.join("\t", p.id(), p.category().name(), String.valueOf(p.price()),
                            p.image(), p.fileId(), p.title()))
                    .collect(Collectors.joining("\n"));
            Files.writeString(FILE, data, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Не вдалося зберегти " + FILE + ": " + e.getMessage());
        }
    }

    /**
     * Стартовий асортимент (створюється лише при першому запуску, поки немає data/products.tsv).
     * Фото до товарів власник додає в боті (кнопка «📷 Змінити фото»),
     * або кладе файл images/<id>.jpg — id вказано першим параметром.
     */
    private static void seed() {
        // --- Патчі ---
        seed("patch-spas-bw", Category.PATCHES, "Патч «Спас Нерукотворний» (чорно-білий)", 450);
        seed("patch-spas-olive", Category.PATCHES, "Патч «Спас Нерукотворний» (олива)", 470);
        seed("patch-khrystos-voskres", Category.PATCHES, "Патч «Христос-Воскрес»", 350);
        seed("patch-chekayu-voskresinnia", Category.PATCHES, "Патч «Чекаю воскресіння мертвих»", 300);
        seed("patch-nenavydzhu-antykhrysta", Category.PATCHES, "Патч «Я ненавиджу Антихриста»", 250);
        seed("patch-pitbul-kyiv", Category.PATCHES, "Патч «Пітбуль Київ»", 250);
        seed("patch-hetmanski-khuliahiny", Category.PATCHES, "Патч «Гетьманські_Хулагіни»", 300);
        seed("patch-mech-mykhaila", Category.PATCHES, "Патч «Меч Михаїла»", 350);
        seed("patch-orden", Category.PATCHES, "Патч «Орден»", 250);
        // --- Піни ---
        seed("pin-holgofa", Category.PINS, "Пін «Голгофа»", 350);
        seed("pin-klynok-viry", Category.PINS, "Пін «Клинок Віри»", 250);
        // --- Прапори ---
        seed("flag-spas", Category.FLAGS, "Прапор «Спас Нерукотворний»", 650);
    }

    private static void seed(String id, Category category, String title, int price) {
        PRODUCTS.add(new Product(id, category, title, price, "", ""));
    }
}
