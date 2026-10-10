package com.elia.evoconti;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class EvoDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "evoconti.db";
    private static final int DB_VERSION = 12;

    EvoDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE categories (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT UNIQUE NOT NULL, color TEXT NOT NULL, removable INTEGER NOT NULL DEFAULT 1)");
        db.execSQL("CREATE TABLE accounts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT UNIQUE NOT NULL, type TEXT NOT NULL, balance REAL NOT NULL DEFAULT 0, " +
                "opening_balance REAL NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE transactions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, title TEXT NOT NULL, " +
                "amount REAL NOT NULL, category TEXT NOT NULL, account TEXT, created_at INTEGER NOT NULL, " +
                "note TEXT, barcode TEXT, location TEXT, usage_hours REAL, offer INTEGER, incident INTEGER, " +
                "photo_path TEXT, receipt_path TEXT, voice_path TEXT)");
        db.execSQL("CREATE TABLE recurring (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, title TEXT NOT NULL, " +
                "amount REAL NOT NULL, category TEXT NOT NULL, account TEXT, " +
                "start_at INTEGER NOT NULL DEFAULT 0, end_at INTEGER NOT NULL DEFAULT 0, " +
                "time_minutes INTEGER NOT NULL DEFAULT 540)");
        db.execSQL("CREATE TABLE goals (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, finality TEXT, category TEXT NOT NULL, " +
                "target REAL NOT NULL, saved REAL NOT NULL DEFAULT 0, start_at INTEGER NOT NULL, end_at INTEGER NOT NULL, " +
                "months INTEGER NOT NULL, creature INTEGER NOT NULL, xp INTEGER NOT NULL DEFAULT 0, " +
                "hp INTEGER NOT NULL DEFAULT 100, stage INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'active')");
        db.execSQL("CREATE TABLE pokedex (" +
                "species INTEGER PRIMARY KEY, discovered INTEGER NOT NULL DEFAULT 0, max_stage INTEGER NOT NULL DEFAULT 0)");
        createCheckInsTable(db);
        createDebtsTable(db);
        createInvestmentTables(db);
        createShoppingTables(db);
        createConversationTables(db);
        createAvatarPlacesTable(db);
        seed(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            addColumnIfMissing(db, "recurring", "start_at", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(db, "recurring", "end_at", "INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 5) {
            createCheckInsTable(db);
        }
        if (oldVersion < 6) {
            createDebtsTable(db);
        }
        if (oldVersion < 7) {
            addColumnIfMissing(db, "debts", "category", "TEXT NOT NULL DEFAULT 'Debiti'");
            addColumnIfMissing(db, "debts", "debt_type", "TEXT NOT NULL DEFAULT 'altro'");
            addColumnIfMissing(db, "debts", "note", "TEXT");
            createInvestmentTables(db);
        }
        if (oldVersion < 8) {
            addColumnIfMissing(db, "accounts", "opening_balance", "REAL NOT NULL DEFAULT 0");
            db.execSQL("UPDATE accounts SET opening_balance=balance");
            db.execSQL("UPDATE accounts SET balance=opening_balance + COALESCE((" +
                    "SELECT SUM(CASE WHEN transactions.type='income' THEN transactions.amount ELSE -transactions.amount END) " +
                    "FROM transactions WHERE transactions.account=accounts.name), 0)");
            addDefaultCategory(db, "Libri e cultura", "#8C63A9");
            addDefaultCategory(db, "Debiti", "#B24C63");
        }
        if (oldVersion < 9) {
            addDefaultCategory(db, "Entrate", "#1FD6A3");
        }
        if (oldVersion < 10) {
            createShoppingTables(db);
        }
        if (oldVersion < 11) {
            createConversationTables(db);
        }
        if (oldVersion < 12) {
            addColumnIfMissing(db, "recurring", "time_minutes", "INTEGER NOT NULL DEFAULT 540");
            createAvatarPlacesTable(db);
        }
        ensurePokedexSpecies(db);
    }

    private void createCheckInsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS checkins (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, mood TEXT NOT NULL, stress INTEGER NOT NULL, " +
                "energy INTEGER NOT NULL, note TEXT, created_at INTEGER NOT NULL)");
    }

    private void createDebtsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS debts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, total REAL NOT NULL, " +
                "remaining REAL NOT NULL, monthly_payment REAL NOT NULL, apr REAL NOT NULL DEFAULT 0, " +
                "start_at INTEGER NOT NULL, end_at INTEGER NOT NULL DEFAULT 0, " +
                "category TEXT NOT NULL DEFAULT 'Debiti', debt_type TEXT NOT NULL DEFAULT 'altro', note TEXT, " +
                "status TEXT NOT NULL DEFAULT 'active')");
    }

    private void createInvestmentTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS investment_assets (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, ticker TEXT, category TEXT, " +
                "asset_type TEXT NOT NULL, manual_price REAL NOT NULL DEFAULT 0, internet_price REAL NOT NULL DEFAULT 0, " +
                "last_update INTEGER NOT NULL DEFAULT 0, source TEXT NOT NULL DEFAULT 'manuale', risk TEXT, note TEXT, " +
                "study_reason TEXT, horizon TEXT, invested REAL NOT NULL DEFAULT 0, current_value REAL NOT NULL DEFAULT 0, " +
                "alert TEXT, created_at INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'watch')");
        db.execSQL("CREATE TABLE IF NOT EXISTS interest_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, item_type TEXT NOT NULL, " +
                "annual_rate REAL NOT NULL DEFAULT 0, amount REAL NOT NULL DEFAULT 0, duration_months INTEGER NOT NULL DEFAULT 12, " +
                "estimated_interest REAL NOT NULL DEFAULT 0, note TEXT, source TEXT NOT NULL DEFAULT 'manuale', created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS finance_cache (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, data_type TEXT NOT NULL, value TEXT NOT NULL, source TEXT NOT NULL, " +
                "reliability TEXT NOT NULL, updated_at INTEGER NOT NULL, disclaimer TEXT NOT NULL)");
    }

    private void createShoppingTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS products (" +
                "barcode TEXT PRIMARY KEY, name TEXT NOT NULL, brand TEXT, quantity_label TEXT, " +
                "category TEXT, image_url TEXT, source TEXT NOT NULL DEFAULT 'locale', " +
                "source_updated_at INTEGER NOT NULL DEFAULT 0, last_seen_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS store_prices (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, barcode TEXT NOT NULL, store_key TEXT NOT NULL, " +
                "store_name TEXT NOT NULL, price REAL NOT NULL, currency TEXT NOT NULL DEFAULT 'EUR', " +
                "source TEXT NOT NULL, confidence REAL NOT NULL DEFAULT 1, observed_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_store_prices_product_store " +
                "ON store_prices(barcode, store_key, observed_at DESC)");
        db.execSQL("CREATE TABLE IF NOT EXISTS shopping_sessions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, store_name TEXT NOT NULL, account TEXT, " +
                "status TEXT NOT NULL DEFAULT 'open', created_at INTEGER NOT NULL, completed_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS shopping_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL, barcode TEXT, " +
                "name TEXT NOT NULL, brand TEXT, quantity INTEGER NOT NULL DEFAULT 1, " +
                "unit_price REAL NOT NULL DEFAULT 0, price_source TEXT NOT NULL DEFAULT 'mancante', " +
                "price_confirmed INTEGER NOT NULL DEFAULT 0, category TEXT NOT NULL DEFAULT 'Alimentare', " +
                "scanned_at INTEGER NOT NULL, FOREIGN KEY(session_id) REFERENCES shopping_sessions(id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_shopping_items_session ON shopping_items(session_id, scanned_at)");
    }

    private void createConversationTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS ai_messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, persona TEXT NOT NULL, speaker TEXT NOT NULL, " +
                "primary_topic TEXT NOT NULL DEFAULT 'Generale', tags TEXT NOT NULL DEFAULT '', " +
                "body TEXT NOT NULL, context_snapshot TEXT, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ai_messages_persona_time " +
                "ON ai_messages(persona, created_at DESC)");
        db.execSQL("CREATE TABLE IF NOT EXISTS ai_memories (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, persona TEXT NOT NULL, memory_type TEXT NOT NULL, " +
                "topic TEXT NOT NULL DEFAULT 'Generale', summary TEXT NOT NULL, " +
                "source_message_id INTEGER NOT NULL DEFAULT 0, active INTEGER NOT NULL DEFAULT 1, " +
                "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ai_memories_persona_active " +
                "ON ai_memories(persona, active, updated_at DESC)");
    }

    private void createAvatarPlacesTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS avatar_places (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, label TEXT NOT NULL, place_type TEXT NOT NULL, " +
                "latitude REAL NOT NULL, longitude REAL NOT NULL, radius_m REAL NOT NULL DEFAULT 180, " +
                "created_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_avatar_places_type ON avatar_places(place_type)");
    }

    private void addColumnIfMissing(SQLiteDatabase db, String table, String column, String definition) {
        Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
        try {
            while (c.moveToNext()) {
                if (column.equals(c.getString(c.getColumnIndexOrThrow("name")))) return;
            }
        } finally {
            c.close();
        }
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private void seed(SQLiteDatabase db) {
        String[][] categories = {
                {"Alimentare", "#2E7D6F"}, {"Farmacia", "#7A9E3F"},
                {"Amministrativa", "#3B67B8"}, {"Sanitaria", "#B24C63"},
                {"Casa", "#D9A441"}, {"Trasporti", "#438B8B"},
                {"Elettronica", "#6B5FB5"}, {"Svago", "#E16A3D"},
                {"Incidenti", "#BF5B84"}, {"Libri e cultura", "#8C63A9"},
                {"Debiti", "#B24C63"}, {"Entrate", "#1FD6A3"}, {"Altro", "#5C6F7B"}
        };
        for (String[] item : categories) {
            addDefaultCategory(db, item[0], item[1]);
        }
        addAccount(db, "Contanti", "Cash", 0);
        addAccount(db, "Carta principale", "Carta", 0);
        addAccount(db, "Conto corrente", "Conto", 0);
        ensurePokedexSpecies(db);
    }

    private void ensurePokedexSpecies(SQLiteDatabase db) {
        for (int i = 0; i < CreatureCatalog.SPECIES_COUNT; i++) {
            ContentValues values = new ContentValues();
            values.put("species", i);
            values.put("discovered", 0);
            values.put("max_stage", 0);
            db.insertWithOnConflict("pokedex", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    private void addAccount(SQLiteDatabase db, String name, String type, double balance) {
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("type", type);
        values.put("balance", balance);
        values.put("opening_balance", balance);
        db.insertWithOnConflict("accounts", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private void addDefaultCategory(SQLiteDatabase db, String name, String color) {
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("color", color);
        values.put("removable", 1);
        db.insertWithOnConflict("categories", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    void addCategory(String name, String color) {
        if (name == null || name.trim().isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("name", clean(name));
        values.put("color", color == null ? "#5C6F7B" : color);
        values.put("removable", 1);
        getWritableDatabase().insertWithOnConflict("categories", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    void deleteCategory(String name) {
        getWritableDatabase().delete("categories", "name=?", new String[]{name});
    }

    void clearCategories() {
        getWritableDatabase().delete("categories", null, null);
    }

    List<String> listCategories() {
        List<String> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT name FROM categories ORDER BY name COLLATE NOCASE", null);
        try {
            while (c.moveToNext()) out.add(c.getString(0));
        } finally {
            c.close();
        }
        if (out.isEmpty()) {
            out.add("Altro");
        }
        return out;
    }

    List<String> listAccounts() {
        List<String> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT name FROM accounts ORDER BY name COLLATE NOCASE", null);
        try {
            while (c.moveToNext()) out.add(c.getString(0));
        } finally {
            c.close();
        }
        if (out.isEmpty()) out.add("Contanti");
        return out;
    }

    void addAccount(String name, String type, double balance) {
        addAccount(getWritableDatabase(), clean(name), clean(type), balance);
    }

    long addTransaction(String type, String title, double amount, String category, String account,
                        String note, String barcode, String location, double usageHours,
                        boolean offer, boolean incident, String photoPath, String receiptPath, String voicePath) {
        ContentValues values = new ContentValues();
        values.put("type", type);
        values.put("title", clean(title));
        values.put("amount", amount);
        values.put("category", clean(category));
        values.put("account", clean(account));
        values.put("created_at", System.currentTimeMillis());
        values.put("note", clean(note));
        values.put("barcode", clean(barcode));
        values.put("location", clean(location));
        values.put("usage_hours", usageHours);
        values.put("offer", offer ? 1 : 0);
        values.put("incident", incident ? 1 : 0);
        values.put("photo_path", clean(photoPath));
        values.put("receipt_path", clean(receiptPath));
        values.put("voice_path", clean(voicePath));
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long id = db.insert("transactions", null, values);
            String accountName = clean(account);
            if (id >= 0 && !accountName.isEmpty()) {
                double delta = "income".equals(type) ? Math.abs(amount) : -Math.abs(amount);
                db.execSQL("UPDATE accounts SET balance=balance+? WHERE name=?", new Object[]{delta, accountName});
            }
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    Product product(String barcode) {
        String code = clean(barcode);
        if (code.isEmpty()) return null;
        Cursor c = getReadableDatabase().rawQuery("SELECT * FROM products WHERE barcode=?", new String[]{code});
        try {
            if (!c.moveToFirst()) return null;
            return readProduct(c);
        } finally {
            c.close();
        }
    }

    void upsertProduct(Product product) {
        if (product == null || clean(product.barcode).isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("barcode", clean(product.barcode));
        values.put("name", clean(product.name).isEmpty() ? "Prodotto " + clean(product.barcode) : clean(product.name));
        values.put("brand", clean(product.brand));
        values.put("quantity_label", clean(product.quantityLabel));
        values.put("category", clean(product.category).isEmpty() ? "Alimentare" : clean(product.category));
        values.put("image_url", clean(product.imageUrl));
        values.put("source", clean(product.source).isEmpty() ? "locale" : clean(product.source));
        values.put("source_updated_at", product.sourceUpdatedAt <= 0 ? System.currentTimeMillis() : product.sourceUpdatedAt);
        values.put("last_seen_at", Math.max(product.lastSeenAt, System.currentTimeMillis()));
        getWritableDatabase().insertWithOnConflict("products", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    PriceObservation bestStorePrice(String barcode, String storeName) {
        String code = clean(barcode);
        String key = storeKey(storeName);
        if (code.isEmpty() || key.isEmpty()) return null;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM store_prices WHERE barcode=? AND store_key=? ORDER BY observed_at DESC LIMIT 1",
                new String[]{code, key});
        try {
            if (!c.moveToFirst()) return null;
            return readPriceObservation(c);
        } finally {
            c.close();
        }
    }

    void rememberPrice(String barcode, String storeName, double price, String source, double confidence, long observedAt) {
        if (clean(barcode).isEmpty() || price <= 0) return;
        rememberPrice(getWritableDatabase(), barcode, storeName, price, source, confidence, observedAt);
    }

    private void rememberPrice(SQLiteDatabase database, String barcode, String storeName, double price,
                               String source, double confidence, long observedAt) {
        String name = clean(storeName).isEmpty() ? "Negozio non indicato" : clean(storeName);
        ContentValues values = new ContentValues();
        values.put("barcode", clean(barcode));
        values.put("store_key", storeKey(name));
        values.put("store_name", name);
        values.put("price", Math.max(0, price));
        values.put("currency", "EUR");
        values.put("source", clean(source).isEmpty() ? "manuale" : clean(source));
        values.put("confidence", Math.max(0, Math.min(1, confidence)));
        values.put("observed_at", observedAt <= 0 ? System.currentTimeMillis() : observedAt);
        database.insert("store_prices", null, values);
    }

    long createShoppingSession(String storeName, String account) {
        ContentValues values = new ContentValues();
        values.put("store_name", clean(storeName).isEmpty() ? "Supermercato" : clean(storeName));
        values.put("account", clean(account));
        values.put("status", "open");
        values.put("created_at", System.currentTimeMillis());
        values.put("completed_at", 0);
        return getWritableDatabase().insert("shopping_sessions", null, values);
    }

    ShoppingSession latestOpenShoppingSession() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM shopping_sessions WHERE status='open' ORDER BY created_at DESC LIMIT 1", null);
        try {
            return c.moveToFirst() ? readShoppingSession(c) : null;
        } finally {
            c.close();
        }
    }

    ShoppingSession shoppingSession(long id) {
        Cursor c = getReadableDatabase().rawQuery("SELECT * FROM shopping_sessions WHERE id=?",
                new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? readShoppingSession(c) : null;
        } finally {
            c.close();
        }
    }

    void updateShoppingSession(long id, String storeName, String account) {
        ContentValues values = new ContentValues();
        values.put("store_name", clean(storeName).isEmpty() ? "Supermercato" : clean(storeName));
        values.put("account", clean(account));
        getWritableDatabase().update("shopping_sessions", values, "id=?", new String[]{String.valueOf(id)});
    }

    long addOrIncrementShoppingItem(long sessionId, String barcode, String name, String brand,
                                    double unitPrice, String priceSource, boolean confirmed, String category) {
        String code = clean(barcode);
        SQLiteDatabase database = getWritableDatabase();
        if (!code.isEmpty()) {
            Cursor existing = database.rawQuery(
                    "SELECT id,quantity FROM shopping_items WHERE session_id=? AND barcode=? ORDER BY id DESC LIMIT 1",
                    new String[]{String.valueOf(sessionId), code});
            try {
                if (existing.moveToFirst()) {
                    long id = existing.getLong(0);
                    ContentValues update = new ContentValues();
                    update.put("quantity", existing.getInt(1) + 1);
                    database.update("shopping_items", update, "id=?", new String[]{String.valueOf(id)});
                    touchProduct(code);
                    return id;
                }
            } finally {
                existing.close();
            }
        }
        ContentValues values = new ContentValues();
        values.put("session_id", sessionId);
        values.put("barcode", code);
        values.put("name", clean(name).isEmpty() ? (code.isEmpty() ? "Prodotto" : "Prodotto " + code) : clean(name));
        values.put("brand", clean(brand));
        values.put("quantity", 1);
        values.put("unit_price", Math.max(0, unitPrice));
        values.put("price_source", clean(priceSource).isEmpty() ? "mancante" : clean(priceSource));
        values.put("price_confirmed", confirmed ? 1 : 0);
        values.put("category", clean(category).isEmpty() ? "Alimentare" : clean(category));
        values.put("scanned_at", System.currentTimeMillis());
        long id = database.insert("shopping_items", null, values);
        touchProduct(code);
        return id;
    }

    void updateShoppingItemProduct(long id, Product product) {
        if (product == null) return;
        ContentValues values = new ContentValues();
        if (!clean(product.name).isEmpty()) values.put("name", clean(product.name));
        if (!clean(product.brand).isEmpty()) values.put("brand", clean(product.brand));
        if (!clean(product.category).isEmpty()) values.put("category", clean(product.category));
        getWritableDatabase().update("shopping_items", values, "id=?", new String[]{String.valueOf(id)});
        upsertProduct(product);
    }

    void updateShoppingItem(long id, String name, int quantity, double unitPrice,
                            String priceSource, boolean confirmed, String category) {
        ContentValues values = new ContentValues();
        values.put("name", clean(name).isEmpty() ? "Prodotto" : clean(name));
        values.put("quantity", Math.max(1, quantity));
        values.put("unit_price", Math.max(0, unitPrice));
        values.put("price_source", clean(priceSource).isEmpty() ? "manuale" : clean(priceSource));
        values.put("price_confirmed", confirmed ? 1 : 0);
        values.put("category", clean(category).isEmpty() ? "Alimentare" : clean(category));
        getWritableDatabase().update("shopping_items", values, "id=?", new String[]{String.valueOf(id)});
    }

    ShoppingItem shoppingItem(long id) {
        Cursor c = getReadableDatabase().rawQuery("SELECT * FROM shopping_items WHERE id=?",
                new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? readShoppingItem(c) : null;
        } finally {
            c.close();
        }
    }

    List<ShoppingItem> shoppingItems(long sessionId) {
        List<ShoppingItem> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM shopping_items WHERE session_id=? ORDER BY scanned_at,id",
                new String[]{String.valueOf(sessionId)});
        try {
            while (c.moveToNext()) out.add(readShoppingItem(c));
        } finally {
            c.close();
        }
        return out;
    }

    void removeShoppingItem(long id) {
        getWritableDatabase().delete("shopping_items", "id=?", new String[]{String.valueOf(id)});
    }

    ShoppingSummary shoppingSummary(long sessionId) {
        ShoppingSummary summary = new ShoppingSummary();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(SUM(quantity),0),COALESCE(SUM(quantity*unit_price),0)," +
                        "COALESCE(SUM(CASE WHEN unit_price<=0 THEN 1 ELSE 0 END),0)," +
                        "COALESCE(SUM(CASE WHEN unit_price>0 AND price_confirmed=0 THEN 1 ELSE 0 END),0) " +
                        "FROM shopping_items WHERE session_id=?",
                new String[]{String.valueOf(sessionId)});
        try {
            if (c.moveToFirst()) {
                summary.quantity = c.getInt(0);
                summary.total = c.getDouble(1);
                summary.missingPrices = c.getInt(2);
                summary.estimatedPrices = c.getInt(3);
            }
        } finally {
            c.close();
        }
        return summary;
    }

    double completeShoppingSession(long sessionId, String storeName, String account) {
        List<ShoppingItem> items = shoppingItems(sessionId);
        if (items.isEmpty()) return 0;
        for (ShoppingItem item : items) if (item.unitPrice <= 0 || item.quantity <= 0) return -1;
        String store = clean(storeName).isEmpty() ? "Supermercato" : clean(storeName);
        String accountName = clean(account);
        SQLiteDatabase database = getWritableDatabase();
        double total = 0;
        database.beginTransaction();
        try {
            ContentValues claim = new ContentValues();
            claim.put("status", "completing");
            int claimed = database.update("shopping_sessions", claim, "id=? AND status='open'",
                    new String[]{String.valueOf(sessionId)});
            if (claimed != 1) return 0;
            for (ShoppingItem item : items) {
                double lineTotal = item.unitPrice * item.quantity;
                total += lineTotal;
                ContentValues values = new ContentValues();
                values.put("type", "expense");
                values.put("title", clean(item.name).isEmpty() ? "Prodotto" : clean(item.name));
                values.put("amount", lineTotal);
                values.put("category", clean(item.category).isEmpty() ? "Alimentare" : clean(item.category));
                values.put("account", accountName);
                values.put("created_at", System.currentTimeMillis());
                values.put("note", "Corsa spesa | " + item.quantity + " x " +
                        String.format(Locale.ITALY, "%.2f EUR", item.unitPrice) + " | prezzo " + item.priceSource);
                values.put("barcode", clean(item.barcode));
                values.put("location", store);
                values.put("usage_hours", 0);
                values.put("offer", 0);
                values.put("incident", 0);
                values.put("photo_path", "");
                values.put("receipt_path", "");
                values.put("voice_path", "");
                database.insertOrThrow("transactions", null, values);
                if (!clean(item.barcode).isEmpty()) {
                    rememberPrice(database, item.barcode, store, item.unitPrice,
                            item.priceConfirmed ? "corsa_spesa_confermato" : item.priceSource,
                            item.priceConfirmed ? 1 : .55, System.currentTimeMillis());
                }
            }
            if (!accountName.isEmpty()) {
                database.execSQL("UPDATE accounts SET balance=balance-? WHERE name=?", new Object[]{total, accountName});
            }
            ContentValues session = new ContentValues();
            session.put("store_name", store);
            session.put("account", accountName);
            session.put("status", "completed");
            session.put("completed_at", System.currentTimeMillis());
            database.update("shopping_sessions", session, "id=?", new String[]{String.valueOf(sessionId)});
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        applyExpenseImpact(total, "Alimentare", false, false);
        return total;
    }

    void discardShoppingSession(long sessionId) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            database.delete("shopping_items", "session_id=?", new String[]{String.valueOf(sessionId)});
            database.delete("shopping_sessions", "id=?", new String[]{String.valueOf(sessionId)});
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    private void touchProduct(String barcode) {
        if (clean(barcode).isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("last_seen_at", System.currentTimeMillis());
        getWritableDatabase().update("products", values, "barcode=?", new String[]{clean(barcode)});
    }

    void addRecurring(String type, String title, double amount, String category, String account, long startAt, long endAt) {
        addRecurring(type, title, amount, category, account, startAt, endAt, 9 * 60);
    }

    void addRecurring(String type, String title, double amount, String category, String account,
                      long startAt, long endAt, int timeMinutes) {
        ContentValues values = new ContentValues();
        values.put("type", type);
        values.put("title", clean(title));
        values.put("amount", amount);
        values.put("category", clean(category));
        values.put("account", clean(account));
        values.put("start_at", startAt);
        values.put("end_at", endAt);
        values.put("time_minutes", Math.max(0, Math.min(23 * 60 + 59, timeMinutes)));
        getWritableDatabase().insert("recurring", null, values);
    }

    List<Recurring> recurring() {
        List<Recurring> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT * FROM recurring ORDER BY type,title", null);
        try {
            while (c.moveToNext()) {
                Recurring row = new Recurring();
                row.id = c.getLong(c.getColumnIndexOrThrow("id"));
                row.type = c.getString(c.getColumnIndexOrThrow("type"));
                row.title = c.getString(c.getColumnIndexOrThrow("title"));
                row.amount = c.getDouble(c.getColumnIndexOrThrow("amount"));
                row.category = c.getString(c.getColumnIndexOrThrow("category"));
                row.account = c.getString(c.getColumnIndexOrThrow("account"));
                row.startAt = c.getLong(c.getColumnIndexOrThrow("start_at"));
                row.endAt = c.getLong(c.getColumnIndexOrThrow("end_at"));
                int timeIndex = c.getColumnIndex("time_minutes");
                row.timeMinutes = timeIndex < 0 ? 9 * 60 : c.getInt(timeIndex);
                out.add(row);
            }
        } finally {
            c.close();
        }
        return out;
    }

    void deleteRecurring(long id) {
        getWritableDatabase().delete("recurring", "id=?", new String[]{String.valueOf(id)});
        syncLocalAiSteward();
    }

    long addAiMessage(String persona, String speaker, String topic, String tags, String body, String contextSnapshot) {
        String message = clean(body);
        if (message.isEmpty()) return -1;
        ContentValues values = new ContentValues();
        values.put("persona", clean(persona));
        values.put("speaker", clean(speaker));
        values.put("primary_topic", clean(topic).isEmpty() ? "Generale" : clean(topic));
        values.put("tags", clean(tags));
        values.put("body", message.length() > 4000 ? message.substring(0, 4000) : message);
        values.put("context_snapshot", clean(contextSnapshot));
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("ai_messages", null, values);
    }

    List<AiMessage> aiMessages(String persona, String topic, int limit) {
        List<AiMessage> out = new ArrayList<>();
        String wantedTopic = clean(topic);
        String selection = "persona=?";
        List<String> args = new ArrayList<>();
        args.add(clean(persona));
        if (!wantedTopic.isEmpty() && !"Tutto".equalsIgnoreCase(wantedTopic)) {
            selection += " AND (primary_topic=? OR tags LIKE ?)";
            args.add(wantedTopic);
            args.add("%" + wantedTopic + "%");
        }
        Cursor c = getReadableDatabase().query("ai_messages", null, selection,
                args.toArray(new String[0]), null, null, "created_at DESC, id DESC",
                String.valueOf(Math.max(1, Math.min(100, limit))));
        try {
            while (c.moveToNext()) out.add(0, readAiMessage(c));
        } finally {
            c.close();
        }
        return out;
    }

    int aiMessageCount(String persona) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM ai_messages WHERE persona=?", new String[]{clean(persona)});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    long rememberAi(String persona, String memoryType, String topic, String summary, long sourceMessageId) {
        String text = clean(summary).replaceAll("\\s+", " ");
        if (text.isEmpty()) return -1;
        if (text.length() > 600) text = text.substring(0, 600);
        SQLiteDatabase database = getWritableDatabase();
        Cursor existing = database.rawQuery(
                "SELECT id FROM ai_memories WHERE persona=? AND active=1 AND lower(summary)=lower(?) LIMIT 1",
                new String[]{clean(persona), text});
        try {
            if (existing.moveToFirst()) {
                long id = existing.getLong(0);
                ContentValues update = new ContentValues();
                update.put("updated_at", System.currentTimeMillis());
                update.put("topic", clean(topic).isEmpty() ? "Generale" : clean(topic));
                database.update("ai_memories", update, "id=?", new String[]{String.valueOf(id)});
                return id;
            }
        } finally {
            existing.close();
        }
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("persona", clean(persona));
        values.put("memory_type", clean(memoryType));
        values.put("topic", clean(topic).isEmpty() ? "Generale" : clean(topic));
        values.put("summary", text);
        values.put("source_message_id", Math.max(0, sourceMessageId));
        values.put("active", 1);
        values.put("created_at", now);
        values.put("updated_at", now);
        return database.insert("ai_memories", null, values);
    }

    List<AiMemory> aiMemories(String persona, int limit) {
        List<AiMemory> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("ai_memories", null,
                "persona=? AND active=1", new String[]{clean(persona)}, null, null,
                "updated_at DESC, id DESC", String.valueOf(Math.max(1, Math.min(100, limit))));
        try {
            while (c.moveToNext()) out.add(readAiMemory(c));
        } finally {
            c.close();
        }
        return out;
    }

    void forgetAiMemory(long id) {
        ContentValues values = new ContentValues();
        values.put("active", 0);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("ai_memories", values, "id=?", new String[]{String.valueOf(id)});
    }

    void clearAiConversation(String persona) {
        getWritableDatabase().delete("ai_messages", "persona=?", new String[]{clean(persona)});
    }

    void saveAvatarPlace(String label, String placeType, double latitude, double longitude, double radiusM) {
        AvatarPlace nearest = nearestAvatarPlace(latitude, longitude, 75);
        ContentValues values = new ContentValues();
        values.put("label", clean(label));
        values.put("place_type", clean(placeType));
        values.put("latitude", latitude);
        values.put("longitude", longitude);
        values.put("radius_m", Math.max(40, Math.min(2000, radiusM)));
        values.put("last_seen_at", System.currentTimeMillis());
        if (nearest == null) {
            values.put("created_at", System.currentTimeMillis());
            getWritableDatabase().insert("avatar_places", null, values);
        } else {
            getWritableDatabase().update("avatar_places", values, "id=?", new String[]{String.valueOf(nearest.id)});
        }
    }

    List<AvatarPlace> avatarPlaces() {
        List<AvatarPlace> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT * FROM avatar_places ORDER BY last_seen_at DESC", null);
        try {
            while (c.moveToNext()) out.add(readAvatarPlace(c));
        } finally {
            c.close();
        }
        return out;
    }

    AvatarPlace nearestAvatarPlace(double latitude, double longitude, double maxDistanceM) {
        AvatarPlace best = null;
        double bestDistance = Double.MAX_VALUE;
        for (AvatarPlace place : avatarPlaces()) {
            double distance = distanceMeters(latitude, longitude, place.latitude, place.longitude);
            double accepted = Math.min(maxDistanceM, place.radiusM);
            if (distance <= accepted && distance < bestDistance) {
                best = place;
                bestDistance = distance;
            }
        }
        if (best != null) best.distanceM = bestDistance;
        return best;
    }

    List<CalendarItem> calendarItems(long monthStart, long monthEnd) {
        List<CalendarItem> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,type,title,amount,category,created_at FROM transactions " +
                        "WHERE created_at>=? AND created_at<? ORDER BY created_at",
                new String[]{String.valueOf(monthStart), String.valueOf(monthEnd)});
        try {
            while (c.moveToNext()) {
                CalendarItem item = new CalendarItem();
                item.sourceId = c.getLong(0);
                item.kind = "income".equals(c.getString(1)) ? "income" : "expense";
                item.title = c.getString(2);
                item.amount = c.getDouble(3);
                item.category = c.getString(4);
                item.whenAt = c.getLong(5);
                out.add(item);
            }
        } finally {
            c.close();
        }
        for (Recurring recurring : recurring()) {
            long due = monthlyOccurrence(recurring.startAt, recurring.endAt, recurring.timeMinutes, monthStart, monthEnd);
            if (due <= 0) continue;
            CalendarItem item = new CalendarItem();
            item.sourceId = recurring.id;
            item.kind = "income".equals(recurring.type) ? "planned_income" : "planned_expense";
            item.title = recurring.title;
            item.amount = recurring.amount;
            item.category = recurring.category;
            item.whenAt = due;
            out.add(item);
        }
        for (Debt debt : debts()) {
            long due = monthlyOccurrence(debt.startAt, debt.endAt, 9 * 60, monthStart, monthEnd);
            if (due <= 0) continue;
            CalendarItem item = new CalendarItem();
            item.sourceId = debt.id;
            item.kind = "debt";
            item.title = debt.name;
            item.amount = debt.monthlyPayment;
            item.category = debt.category;
            item.whenAt = due;
            out.add(item);
        }
        out.sort((a, b) -> Long.compare(a.whenAt, b.whenAt));
        return out;
    }

    void addDebt(String name, double total, double remaining, double monthlyPayment, double apr, long startAt, long endAt) {
        addDebt(name, total, remaining, monthlyPayment, apr, startAt, endAt, "Debiti", "altro", "");
    }

    void addDebt(String name, double total, double remaining, double monthlyPayment, double apr, long startAt, long endAt,
                 String category, String debtType, String note) {
        ContentValues values = new ContentValues();
        values.put("name", clean(name));
        values.put("total", Math.max(0, total));
        values.put("remaining", Math.max(0, remaining));
        values.put("monthly_payment", Math.max(0, monthlyPayment));
        values.put("apr", Math.max(0, apr));
        values.put("start_at", startAt);
        values.put("end_at", endAt);
        values.put("category", clean(category).isEmpty() ? "Debiti" : clean(category));
        values.put("debt_type", clean(debtType).isEmpty() ? "altro" : clean(debtType));
        values.put("note", clean(note));
        values.put("status", "active");
        getWritableDatabase().insert("debts", null, values);
        syncLocalAiSteward();
    }

    long addGoal(String name, String finality, String category, double target, int months, int creature) {
        long now = System.currentTimeMillis();
        LocalAiReport health = localAiReport();
        Calendar end = Calendar.getInstance();
        end.setTimeInMillis(now);
        end.add(Calendar.MONTH, months);
        ContentValues values = new ContentValues();
        values.put("name", clean(name));
        values.put("finality", clean(finality));
        values.put("category", clean(category));
        values.put("target", target);
        values.put("saved", 0);
        values.put("start_at", now);
        values.put("end_at", end.getTimeInMillis());
        values.put("months", months);
        values.put("creature", creature);
        values.put("xp", 10);
        values.put("hp", health.avatarHp);
        values.put("stage", 0);
        values.put("status", "active");
        long id = getWritableDatabase().insert("goals", null, values);
        markCreature(creature, 0);
        syncLocalAiSteward();
        return id;
    }

    int activeGoalCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM goals WHERE status='active'", null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    void syncLocalAiSteward() {
        LocalAiReport report = localAiReport();
        List<Goal> rows = goals();
        for (Goal g : rows) {
            if ("complete".equals(g.status)) continue;
            int hp = Math.max(0, Math.min(100, report.avatarHp));
            double progress = g.target <= 0 ? 0 : Math.max(0, Math.min(1, g.saved / g.target));
            int stage = g.stage;
            if (g.saved >= g.target) {
                stage = CreatureCatalog.STAGES - 1;
            } else if (report.score >= 90 && progress >= 0.35) {
                stage = Math.max(stage, Math.min(CreatureCatalog.STAGES - 1, stageFor(g.saved, g.target, g.xp) + 2));
            } else if (report.score >= 75 && progress >= 0.18) {
                stage = Math.max(stage, Math.min(CreatureCatalog.STAGES - 1, stageFor(g.saved, g.target, g.xp) + 1));
            } else {
                stage = Math.max(stage, stageFor(g.saved, g.target, g.xp));
            }
            String status = g.saved >= g.target ? "complete" : "active";
            ContentValues values = new ContentValues();
            values.put("hp", hp);
            values.put("stage", stage);
            values.put("status", status);
            getWritableDatabase().update("goals", values, "id=?", new String[]{String.valueOf(g.id)});
            markCreature(g.creature, stage);
        }
    }

    void applyExpenseImpact(double amount, String category, boolean offer, boolean incident) {
        List<Goal> goals = goals();
        double monthlyIncome = monthlyIncome();
        double fixed = fixedMonthly();
        double spent = monthExpense();
        Calendar cal = Calendar.getInstance();
        int days = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        double flexibleDaily = Math.max(3, (monthlyIncome - fixed) / Math.max(1, days));
        for (Goal g : goals) {
            if (!"active".equals(g.status)) continue;
            int xpDelta = 3;
            if (offer) xpDelta += 4;
            if (incident || "Incidenti".equalsIgnoreCase(category)) {
                xpDelta -= 5;
            }
            if (amount > flexibleDaily * 1.4) {
                xpDelta -= 4;
            } else {
                xpDelta += 4;
            }
            double monthlyNeed = g.target / Math.max(1, g.months);
            if (monthlyIncome > 0 && monthlyIncome - fixed - spent >= monthlyNeed) {
                xpDelta += 5;
            }
            adjustGoal(g.id, xpDelta, 0);
        }
        syncLocalAiSteward();
    }

    void addGoalSavings(long id, double amount) {
        Goal g = goal(id);
        if (g == null) return;
        double saved = Math.min(g.target, g.saved + Math.max(0, amount));
        int stage = stageFor(saved, g.target, g.xp);
        String status = saved >= g.target ? "complete" : g.status;
        ContentValues values = new ContentValues();
        values.put("saved", saved);
        values.put("stage", stage);
        values.put("status", status);
        getWritableDatabase().update("goals", values, "id=?", new String[]{String.valueOf(id)});
        markCreature(g.creature, stage);
        syncLocalAiSteward();
    }

    void adjustGoal(long id, int xpDelta, double savedDelta) {
        Goal g = goal(id);
        if (g == null) return;
        int xp = Math.max(0, g.xp + xpDelta);
        double saved = Math.max(0, Math.min(g.target, g.saved + savedDelta));
        int stage = stageFor(saved, g.target, xp);
        String status = saved >= g.target ? "complete" : "active";
        ContentValues values = new ContentValues();
        values.put("xp", xp);
        values.put("saved", saved);
        values.put("stage", stage);
        values.put("status", status);
        getWritableDatabase().update("goals", values, "id=?", new String[]{String.valueOf(id)});
        markCreature(g.creature, stage);
    }

    private int stageFor(double saved, double target, int xp) {
        double progress = target <= 0 ? 0 : saved / target;
        int byProgress = (int) Math.floor(progress * CreatureCatalog.STAGES);
        int byXp = xp / 100;
        return Math.max(0, Math.min(CreatureCatalog.STAGES - 1, Math.max(byProgress, byXp)));
    }

    private void markCreature(int species, int stage) {
        ContentValues values = new ContentValues();
        values.put("species", species);
        values.put("discovered", 1);
        values.put("max_stage", stage);
        getWritableDatabase().insertWithOnConflict("pokedex", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        getWritableDatabase().execSQL("UPDATE pokedex SET discovered=1, max_stage=MAX(max_stage, ?) WHERE species=?",
                new Object[]{stage, species});
    }

    Goal goal(long id) {
        Cursor c = getReadableDatabase().rawQuery("SELECT * FROM goals WHERE id=?", new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? readGoal(c) : null;
        } finally {
            c.close();
        }
    }

    List<Goal> goals() {
        List<Goal> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT * FROM goals ORDER BY status='active' DESC, id DESC", null);
        try {
            while (c.moveToNext()) out.add(readGoal(c));
        } finally {
            c.close();
        }
        return out;
    }

    List<Tx> recentTransactions(int limit) {
        List<Tx> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT * FROM transactions ORDER BY created_at DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) out.add(readTx(c));
        } finally {
            c.close();
        }
        return out;
    }

    List<MoneyRow> expenseRows(int limit) {
        List<MoneyRow> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT title, amount, category, created_at FROM transactions WHERE type='expense' ORDER BY created_at DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                MoneyRow row = new MoneyRow();
                row.left = c.getString(0) + "  |  " + c.getString(2) + "  |  " + formatDate(c.getLong(3));
                row.right = "-" + exact(c.getDouble(1)) + " EUR";
                out.add(row);
            }
        } finally {
            c.close();
        }
        return out;
    }

    List<MoneyRow> programmedExpenseRows() {
        List<MoneyRow> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT title, amount, category, start_at, end_at FROM recurring WHERE type='fixed' ORDER BY title", null);
        try {
            while (c.moveToNext()) {
                MoneyRow row = new MoneyRow();
                String period = formatDay(c.getLong(3)) + " - " + (c.getLong(4) <= 0 ? "senza fine" : formatDay(c.getLong(4)));
                row.left = c.getString(0) + "  |  " + c.getString(2) + "  |  " + period;
                row.right = "-" + exact(c.getDouble(1)) + " EUR";
                out.add(row);
            }
        } finally {
            c.close();
        }
        return out;
    }

    List<MoneyRow> debtRows() {
        List<MoneyRow> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT name, remaining, monthly_payment, apr, debt_type, category FROM debts WHERE status='active' ORDER BY monthly_payment DESC", null);
        try {
            while (c.moveToNext()) {
                MoneyRow row = new MoneyRow();
                row.left = c.getString(0) + " | " + c.getString(4) + " | " + c.getString(5) +
                        " | residuo " + exact(c.getDouble(1)) + " EUR | TAEG " + exact(c.getDouble(3)) + "%";
                row.right = "-" + exact(c.getDouble(2)) + " EUR/mese";
                out.add(row);
            }
        } finally {
            c.close();
        }
        return out;
    }

    List<Debt> debts() {
        List<Debt> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT * FROM debts WHERE status='active' ORDER BY monthly_payment DESC", null);
        try {
            while (c.moveToNext()) {
                Debt row = new Debt();
                row.id = c.getLong(c.getColumnIndexOrThrow("id"));
                row.name = c.getString(c.getColumnIndexOrThrow("name"));
                row.total = c.getDouble(c.getColumnIndexOrThrow("total"));
                row.remaining = c.getDouble(c.getColumnIndexOrThrow("remaining"));
                row.monthlyPayment = c.getDouble(c.getColumnIndexOrThrow("monthly_payment"));
                row.apr = c.getDouble(c.getColumnIndexOrThrow("apr"));
                row.startAt = c.getLong(c.getColumnIndexOrThrow("start_at"));
                row.endAt = c.getLong(c.getColumnIndexOrThrow("end_at"));
                row.category = c.getString(c.getColumnIndexOrThrow("category"));
                row.debtType = c.getString(c.getColumnIndexOrThrow("debt_type"));
                row.note = c.getString(c.getColumnIndexOrThrow("note"));
                out.add(row);
            }
        } finally {
            c.close();
        }
        return out;
    }

    void payDebt(long id, double amount) {
        if (amount <= 0) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Cursor c = db.rawQuery("SELECT remaining FROM debts WHERE id=? AND status='active'",
                    new String[]{String.valueOf(id)});
            double remaining;
            try {
                if (!c.moveToFirst()) return;
                remaining = Math.max(0, c.getDouble(0) - amount);
            } finally {
                c.close();
            }
            ContentValues values = new ContentValues();
            values.put("remaining", remaining);
            if (remaining <= 0.005) values.put("status", "paid");
            db.update("debts", values, "id=?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        syncLocalAiSteward();
    }

    double monthlyDebtPayments() {
        long now = System.currentTimeMillis();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT SUM(MIN(monthly_payment, remaining)) FROM debts " +
                        "WHERE status='active' AND remaining>0 AND (start_at=0 OR start_at<=?)",
                new String[]{String.valueOf(now)});
        try {
            return c.moveToFirst() ? c.getDouble(0) : 0;
        } finally {
            c.close();
        }
    }

    double totalDebtRemaining() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT SUM(remaining) FROM debts WHERE status='active' AND remaining>0", null);
        try {
            return c.moveToFirst() ? c.getDouble(0) : 0;
        } finally {
            c.close();
        }
    }

    void addInvestmentAsset(String name, String ticker, String category, String assetType, double manualPrice,
                            String risk, String note, String studyReason, String horizon,
                            double invested, double currentValue, String alert) {
        ContentValues values = new ContentValues();
        values.put("name", clean(name));
        values.put("ticker", clean(ticker));
        values.put("category", clean(category));
        values.put("asset_type", clean(assetType).isEmpty() ? "altro" : clean(assetType));
        values.put("manual_price", Math.max(0, manualPrice));
        values.put("internet_price", 0);
        values.put("last_update", System.currentTimeMillis());
        values.put("source", "manuale");
        values.put("risk", clean(risk));
        values.put("note", clean(note));
        values.put("study_reason", clean(studyReason));
        values.put("horizon", clean(horizon));
        values.put("invested", Math.max(0, invested));
        values.put("current_value", Math.max(0, currentValue));
        values.put("alert", clean(alert));
        values.put("created_at", System.currentTimeMillis());
        values.put("status", "watch");
        getWritableDatabase().insert("investment_assets", null, values);
    }

    List<InvestmentAsset> investmentAssets(String typeFilter) {
        List<InvestmentAsset> out = new ArrayList<>();
        String filter = clean(typeFilter);
        Cursor c = filter.isEmpty()
                ? getReadableDatabase().rawQuery("SELECT * FROM investment_assets ORDER BY created_at DESC", null)
                : getReadableDatabase().rawQuery("SELECT * FROM investment_assets WHERE asset_type=? ORDER BY created_at DESC", new String[]{filter});
        try {
            while (c.moveToNext()) out.add(readInvestmentAsset(c));
        } finally {
            c.close();
        }
        return out;
    }

    void deleteInvestmentAsset(long id) {
        getWritableDatabase().delete("investment_assets", "id=?", new String[]{String.valueOf(id)});
    }

    InvestmentSummary investmentSummary() {
        InvestmentSummary s = new InvestmentSummary();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT SUM(invested), SUM(current_value), COUNT(*) FROM investment_assets", null);
        try {
            if (c.moveToFirst()) {
                s.totalInvested = c.getDouble(0);
                s.currentValue = c.getDouble(1);
                s.assetCount = c.getInt(2);
            }
        } finally {
            c.close();
        }
        s.gainLoss = s.currentValue - s.totalInvested;
        return s;
    }

    void addInterestItem(String name, String type, double annualRate, double amount, int durationMonths,
                         double estimatedInterest, String note, String source) {
        ContentValues values = new ContentValues();
        values.put("name", clean(name));
        values.put("item_type", clean(type).isEmpty() ? "altro" : clean(type));
        values.put("annual_rate", annualRate);
        values.put("amount", Math.max(0, amount));
        values.put("duration_months", Math.max(1, durationMonths));
        values.put("estimated_interest", Math.max(0, estimatedInterest));
        values.put("note", clean(note));
        values.put("source", clean(source).isEmpty() ? "manuale" : clean(source));
        values.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insert("interest_items", null, values);
    }

    List<InterestItem> interestItems() {
        List<InterestItem> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT * FROM interest_items ORDER BY created_at DESC", null);
        try {
            while (c.moveToNext()) {
                InterestItem row = new InterestItem();
                row.id = c.getLong(c.getColumnIndexOrThrow("id"));
                row.name = c.getString(c.getColumnIndexOrThrow("name"));
                row.type = c.getString(c.getColumnIndexOrThrow("item_type"));
                row.annualRate = c.getDouble(c.getColumnIndexOrThrow("annual_rate"));
                row.amount = c.getDouble(c.getColumnIndexOrThrow("amount"));
                row.durationMonths = c.getInt(c.getColumnIndexOrThrow("duration_months"));
                row.estimatedInterest = c.getDouble(c.getColumnIndexOrThrow("estimated_interest"));
                row.note = c.getString(c.getColumnIndexOrThrow("note"));
                row.source = c.getString(c.getColumnIndexOrThrow("source"));
                row.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
                out.add(row);
            }
        } finally {
            c.close();
        }
        return out;
    }

    void deleteInterestItem(long id) {
        getWritableDatabase().delete("interest_items", "id=?", new String[]{String.valueOf(id)});
    }

    double passiveInterest() {
        return interestByKind(true);
    }

    double activeInterest() {
        return interestByKind(false);
    }

    private double interestByKind(boolean passive) {
        double sum = 0;
        for (InterestItem row : interestItems()) {
            boolean isPassive = containsAny(clean(row.type).toLowerCase(Locale.ITALY), "debito", "prestito");
            if (isPassive == passive) sum += row.estimatedInterest;
        }
        return sum;
    }

    void cacheFinanceData(String dataType, String value, String source, String reliability) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("data_type", clean(dataType));
        values.put("value", clean(value));
        values.put("source", clean(source));
        values.put("reliability", clean(reliability).isEmpty() ? "informativa" : clean(reliability));
        values.put("updated_at", System.currentTimeMillis());
        values.put("disclaimer", "dato informativo, non consiglio");
        db.beginTransaction();
        try {
            db.delete("finance_cache", "data_type=? AND source=?",
                    new String[]{clean(dataType), clean(source)});
            db.insert("finance_cache", null, values);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    List<FinanceCacheEntry> financeCacheRows(int limit) {
        List<FinanceCacheEntry> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM finance_cache ORDER BY updated_at DESC LIMIT ?",
                new String[]{String.valueOf(Math.max(1, limit))});
        try {
            while (c.moveToNext()) {
                FinanceCacheEntry row = new FinanceCacheEntry();
                row.id = c.getLong(c.getColumnIndexOrThrow("id"));
                row.dataType = c.getString(c.getColumnIndexOrThrow("data_type"));
                row.value = c.getString(c.getColumnIndexOrThrow("value"));
                row.source = c.getString(c.getColumnIndexOrThrow("source"));
                row.reliability = c.getString(c.getColumnIndexOrThrow("reliability"));
                row.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
                row.disclaimer = c.getString(c.getColumnIndexOrThrow("disclaimer"));
                out.add(row);
            }
        } finally {
            c.close();
        }
        return out;
    }

    LocalAiReport localAiReport() {
        double income = monthlyIncome();
        double recurring = sumRecurring("fixed");
        double essentialRecurring = sumRecurringByNeed(true);
        double wantsRecurring = sumRecurringByNeed(false);
        double debtMonthly = monthlyDebtPayments();
        double debtRemaining = totalDebtRemaining();
        double spent = monthExpense();
        double margin = income - recurring - debtMonthly - spent;
        Map<String, Double> totals = categoryTotals();
        double essentialVariable = 0;
        double wantsVariable = 0;
        for (Map.Entry<String, Double> e : totals.entrySet()) {
            if (isEssentialCategory(e.getKey())) essentialVariable += e.getValue();
            else wantsVariable += e.getValue();
        }
        double essentialPressure = essentialRecurring + debtMonthly + essentialVariable;
        double wants = wantsRecurring + wantsVariable;
        double future = Math.max(0, margin);
        double essentialRatio = income <= 0 ? (essentialPressure > 0 ? 1 : 0) : essentialPressure / income;
        double wantsRatio = income <= 0 ? (wants > 0 ? 1 : 0) : wants / income;
        double futureRatio = income <= 0 ? 0 : future / income;
        double debtRatio = income <= 0 ? (debtMonthly > 0 ? 1 : 0) : debtMonthly / income;
        double emergencySaved = emergencyFundSaved();
        double emergencyBase = Math.max(1, essentialRecurring + essentialVariable + debtMonthly);
        double emergencyFundMonths = emergencySaved / emergencyBase;
        int impulseCount = monthImpulseCount();
        boolean setupMode = income <= 0 && recurring <= 0 && debtMonthly <= 0 && spent <= 0;
        CheckIn checkIn = latestCheckIn();
        if (checkIn != null && System.currentTimeMillis() - checkIn.createdAt > 7L * 24 * 60 * 60 * 1000) {
            checkIn = null;
        }
        List<Goal> goals = goals();
        int complete = 0;
        double avgProgress = 0;
        for (Goal g : goals) {
            if ("complete".equals(g.status)) complete++;
            avgProgress += g.target <= 0 ? 0 : Math.min(1, g.saved / g.target);
        }
        if (!goals.isEmpty()) avgProgress /= goals.size();

        BudgetHealthCalculator.Input input = new BudgetHealthCalculator.Input();
        input.setupMode = setupMode;
        input.income = income;
        input.margin = margin;
        input.essentialRatio = essentialRatio;
        input.wantsRatio = wantsRatio;
        input.futureRatio = futureRatio;
        input.debtRatio = debtRatio;
        input.debtRemaining = debtRemaining;
        input.emergencyFundMonths = emergencyFundMonths;
        input.averageGoalProgress = avgProgress;
        input.completedGoals = complete;
        input.impulseCount = impulseCount;
        if (checkIn != null) {
            input.stress = checkIn.stress;
            input.energy = checkIn.energy;
            input.positiveMood = "Motivato".equalsIgnoreCase(checkIn.mood) ||
                    "Sereno".equalsIgnoreCase(checkIn.mood);
        }
        BudgetHealthCalculator.Result health = new BudgetHealthCalculator().calculate(input);

        LocalAiReport report = new LocalAiReport();
        report.score = health.score;
        report.income = income;
        report.fixed = recurring;
        report.debtMonthly = debtMonthly;
        report.debtRemaining = debtRemaining;
        report.spent = spent;
        report.margin = margin;
        report.essential = essentialPressure;
        report.wants = wants;
        report.future = future;
        report.essentialRatio = essentialRatio;
        report.wantsRatio = wantsRatio;
        report.futureRatio = futureRatio;
        report.debtRatio = debtRatio;
        report.emergencyFundMonths = emergencyFundMonths;
        report.impulseCount = impulseCount;
        report.avatarHp = health.score;
        report.level = health.level;
        report.avatarState = health.avatarState;
        report.mainTask = health.mainTask;
        return report;
    }

    private boolean isEssentialCategory(String category) {
        String value = clean(category).toLowerCase(Locale.ITALY);
        return value.equals("alimentare") || value.equals("farmacia") ||
                value.equals("amministrativa") || value.equals("sanitaria") ||
                value.equals("casa") || value.equals("trasporti");
    }

    private double emergencyFundSaved() {
        double saved = 0;
        for (Goal g : goals()) {
            String text = (clean(g.name) + " " + clean(g.finality) + " " + clean(g.category)).toLowerCase(Locale.ITALY);
            if (containsAny(text, "emergenza", "fondo", "liquidita", "sicurezza")) saved += Math.max(0, g.saved);
        }
        return saved;
    }

    private int monthImpulseCount() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM transactions WHERE type='expense' AND created_at>=? AND created_at<? " +
                        "AND (incident=1 OR category='Incidenti')",
                new String[]{String.valueOf(monthStart()), String.valueOf(monthEnd())});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    void addCheckIn(String mood, int stress, int energy, String note) {
        ContentValues values = new ContentValues();
        values.put("mood", clean(mood).isEmpty() ? "Neutro" : clean(mood));
        values.put("stress", Math.max(0, Math.min(10, stress)));
        values.put("energy", Math.max(0, Math.min(10, energy)));
        values.put("note", clean(note));
        values.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insert("checkins", null, values);
        syncLocalAiSteward();
    }

    CheckIn latestCheckIn() {
        Cursor c = getReadableDatabase().rawQuery("SELECT * FROM checkins ORDER BY created_at DESC LIMIT 1", null);
        try {
            if (!c.moveToFirst()) return null;
            CheckIn row = new CheckIn();
            row.mood = c.getString(c.getColumnIndexOrThrow("mood"));
            row.stress = c.getInt(c.getColumnIndexOrThrow("stress"));
            row.energy = c.getInt(c.getColumnIndexOrThrow("energy"));
            row.note = c.getString(c.getColumnIndexOrThrow("note"));
            row.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
            return row;
        } finally {
            c.close();
        }
    }

    AttachmentStats attachmentStats() {
        AttachmentStats stats = new AttachmentStats();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT " +
                        "SUM(CASE WHEN location IS NOT NULL AND location<>'' THEN 1 ELSE 0 END), " +
                        "SUM(CASE WHEN photo_path IS NOT NULL AND photo_path<>'' THEN 1 ELSE 0 END), " +
                        "SUM(CASE WHEN receipt_path IS NOT NULL AND receipt_path<>'' THEN 1 ELSE 0 END), " +
                        "SUM(CASE WHEN barcode IS NOT NULL AND barcode<>'' THEN 1 ELSE 0 END), " +
                        "SUM(CASE WHEN voice_path IS NOT NULL AND voice_path<>'' THEN 1 ELSE 0 END) " +
                        "FROM transactions", null);
        try {
            if (c.moveToFirst()) {
                stats.locations = c.getInt(0);
                stats.photos = c.getInt(1);
                stats.receipts = c.getInt(2);
                stats.barcodes = c.getInt(3);
                stats.voices = c.getInt(4);
            }
        } finally {
            c.close();
        }
        Cursor latest = getReadableDatabase().rawQuery(
                "SELECT location, created_at FROM transactions WHERE location IS NOT NULL AND location<>'' ORDER BY created_at DESC LIMIT 1", null);
        try {
            if (latest.moveToFirst()) {
                stats.latestLocation = latest.getString(0);
                stats.latestLocationAt = latest.getLong(1);
            }
        } finally {
            latest.close();
        }
        return stats;
    }

    List<MoneyRow> contextRows(int limit) {
        List<MoneyRow> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT title, amount, category, location, barcode, photo_path, receipt_path, voice_path FROM transactions " +
                        "WHERE location<>'' OR barcode<>'' OR photo_path<>'' OR receipt_path<>'' OR voice_path<>'' " +
                        "ORDER BY created_at DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                StringBuilder tags = new StringBuilder();
                if (!clean(c.getString(3)).isEmpty()) tags.append("GPS ");
                if (!clean(c.getString(4)).isEmpty()) tags.append("barcode ");
                if (!clean(c.getString(5)).isEmpty()) tags.append("foto ");
                if (!clean(c.getString(6)).isEmpty()) tags.append("scontrino ");
                if (!clean(c.getString(7)).isEmpty()) tags.append("voce ");
                MoneyRow row = new MoneyRow();
                row.left = c.getString(0) + " | " + c.getString(2);
                row.right = tags.toString().trim();
                out.add(row);
            }
        } finally {
            c.close();
        }
        return out;
    }

    List<String> recurringLines() {
        List<String> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT type,title,amount,category,account,start_at,end_at FROM recurring ORDER BY type,title", null);
        try {
            while (c.moveToNext()) {
                String period = formatDay(c.getLong(5)) + " - " + (c.getLong(6) <= 0 ? "senza fine" : formatDay(c.getLong(6)));
                out.add(labelRecurringType(c.getString(0)) + " | " + c.getString(1) + " | " + c.getDouble(2) + " | " + c.getString(3) + " | " + period);
            }
        } finally {
            c.close();
        }
        return out;
    }

    double monthlyIncome() {
        double planned = sumRecurring("income");
        double recorded = sumTransactions("income", monthStart(), monthEnd());
        return Math.max(planned, recorded);
    }

    double fixedMonthly() {
        return sumRecurring("fixed") + monthlyDebtPayments();
    }

    double monthExpense() {
        return sumTransactions("expense", monthStart(), monthEnd());
    }

    Map<String, Double> categoryTotals() {
        Map<String, Double> out = new LinkedHashMap<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT category, SUM(amount) FROM transactions WHERE type='expense' AND created_at>=? AND created_at<? GROUP BY category ORDER BY SUM(amount) DESC",
                new String[]{String.valueOf(monthStart()), String.valueOf(monthEnd())});
        try {
            while (c.moveToNext()) out.put(c.getString(0), c.getDouble(1));
        } finally {
            c.close();
        }
        return out;
    }

    Map<String, Double> budgetCategoryTotals() {
        Map<String, Double> out = new LinkedHashMap<>(categoryTotals());
        long now = System.currentTimeMillis();
        Cursor recurring = getReadableDatabase().rawQuery(
                "SELECT category, SUM(amount) FROM recurring WHERE type='fixed' " +
                        "AND (start_at=0 OR start_at<=?) AND (end_at=0 OR end_at>=?) GROUP BY category",
                new String[]{String.valueOf(now), String.valueOf(now)});
        try {
            while (recurring.moveToNext()) addAmount(out, recurring.getString(0), recurring.getDouble(1));
        } finally {
            recurring.close();
        }
        Cursor debts = getReadableDatabase().rawQuery(
                "SELECT category, SUM(MIN(monthly_payment, remaining)) FROM debts " +
                        "WHERE status='active' AND remaining>0 AND (start_at=0 OR start_at<=?) GROUP BY category",
                new String[]{String.valueOf(now)});
        try {
            while (debts.moveToNext()) addAmount(out, debts.getString(0), debts.getDouble(1));
        } finally {
            debts.close();
        }
        return out;
    }

    Map<String, Double> accountTotals() {
        Map<String, Double> out = new LinkedHashMap<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT name, balance FROM accounts ORDER BY balance DESC, name COLLATE NOCASE", null);
        try {
            while (c.moveToNext()) out.put(c.getString(0), c.getDouble(1));
        } finally {
            c.close();
        }
        return out;
    }

    Map<String, Double> budgetAccountTotals() {
        Map<String, Double> out = new LinkedHashMap<>(accountTotals());
        long now = System.currentTimeMillis();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT account, type, SUM(amount) FROM recurring " +
                        "WHERE (start_at=0 OR start_at<=?) AND (end_at=0 OR end_at>=?) " +
                        "GROUP BY account, type",
                new String[]{String.valueOf(now), String.valueOf(now)});
        try {
            while (c.moveToNext()) {
                String account = clean(c.getString(0));
                if (account.isEmpty()) continue;
                double signed = "income".equals(c.getString(1)) ? c.getDouble(2) : -c.getDouble(2);
                addAmount(out, account, signed);
            }
        } finally {
            c.close();
        }
        return out;
    }

    private void addAmount(Map<String, Double> values, String key, double amount) {
        String safeKey = clean(key).isEmpty() ? "Altro" : clean(key);
        values.put(safeKey, values.getOrDefault(safeKey, 0d) + amount);
    }

    double[] dailyExpenses() {
        Calendar cal = Calendar.getInstance();
        int days = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        double[] out = new double[days];
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT created_at, amount FROM transactions WHERE type='expense' AND created_at>=? AND created_at<?",
                new String[]{String.valueOf(monthStart()), String.valueOf(monthEnd())});
        try {
            Calendar row = Calendar.getInstance();
            while (c.moveToNext()) {
                row.setTimeInMillis(c.getLong(0));
                int index = row.get(Calendar.DAY_OF_MONTH) - 1;
                if (index >= 0 && index < out.length) out[index] += c.getDouble(1);
            }
        } finally {
            c.close();
        }
        return out;
    }

    String smartCategory(String text) {
        if (text == null) return "Altro";
        String s = text.toLowerCase(Locale.ITALY);
        if (containsAny(s, "conad", "coop", "lidl", "eurospin", "esselunga", "pane", "latte", "pasta", "carne", "spesa")) return "Alimentare";
        if (containsAny(s, "farmacia", "tachipirina", "ibuprofene", "medicina", "integratore")) return "Farmacia";
        if (containsAny(s, "bollo", "tassa", "universita", "agenzia", "multa", "pagopa")) return "Amministrativa";
        if (containsAny(s, "dentista", "visita", "analisi", "ospedale", "medico", "oculista")) return "Sanitaria";
        if (containsAny(s, "amazon", "mediaworld", "unieuro", "telefono", "cavo", "pc", "tablet", "elettronica")) return "Elettronica";
        if (containsAny(s, "benzina", "treno", "bus", "metro", "parcheggio", "pedaggio")) return "Trasporti";
        if (containsAny(s, "affitto", "luce", "gas", "internet", "condominio")) return "Casa";
        if (containsAny(s, "cinema", "pizza", "ristorante", "bar", "gioco", "svago")) return "Svago";
        if (containsAny(s, "libro", "libri", "libreria", "isbn", "ebook", "fumetto")) return "Libri e cultura";
        return "Altro";
    }

    String insight() {
        double income = monthlyIncome();
        double fixed = fixedMonthly();
        double spent = monthExpense();
        Map<String, Double> totals = categoryTotals();
        if (income <= 0) {
            return "Imposta almeno una entrata programmata: puoi darle una data di inizio e fine, poi l'assistente locale calcolera soglie giornaliere e obiettivi realistici.";
        }
        double free = income - fixed - spent;
        if (totals.isEmpty()) {
            return "Inizia da poche voci: importo, categoria e conto. L'app imparera dai titoli ricorrenti e proporra categorie in automatico.";
        }
        String top = "";
        double topValue = 0;
        for (Map.Entry<String, Double> e : totals.entrySet()) {
            top = e.getKey();
            topValue = e.getValue();
            break;
        }
        if (free < 0) {
            return "Questo mese sei sotto di circa " + round(Math.abs(free)) + " EUR. Prima leva: ridurre " + top + ", che pesa " + round(topValue) + " EUR.";
        }
        double daily = free / Math.max(1, Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_MONTH) - Calendar.getInstance().get(Calendar.DAY_OF_MONTH) + 1);
        return "Margine stimato: " + round(free) + " EUR. Per non stressare gli obiettivi, prova a restare sotto " + round(Math.max(0, daily)) + " EUR al giorno.";
    }

    String csvExport() {
        StringBuilder sb = new StringBuilder();
        sb.append("tipo,data,titolo,importo,categoria,conto,offerta,incidente,barcode,luogo,ore_utilizzo,note\n");
        Cursor c = getReadableDatabase().rawQuery("SELECT * FROM transactions ORDER BY created_at DESC", null);
        try {
            while (c.moveToNext()) {
                Tx tx = readTx(c);
                sb.append(csv(tx.type)).append(',')
                        .append(csv(formatDate(tx.createdAt))).append(',')
                        .append(csv(tx.title)).append(',')
                        .append(tx.amount).append(',')
                        .append(csv(tx.category)).append(',')
                        .append(csv(tx.account)).append(',')
                        .append(tx.offer ? "si" : "no").append(',')
                        .append(tx.incident ? "si" : "no").append(',')
                        .append(csv(tx.barcode)).append(',')
                        .append(csv(tx.location)).append(',')
                        .append(tx.usageHours).append(',')
                        .append(csv(tx.note)).append('\n');
            }
        } finally {
            c.close();
        }
        return sb.toString();
    }

    List<PokeRow> pokedex() {
        List<PokeRow> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT species, discovered, max_stage FROM pokedex ORDER BY species", null);
        try {
            while (c.moveToNext()) {
                PokeRow row = new PokeRow();
                row.species = c.getInt(0);
                row.discovered = c.getInt(1) == 1;
                row.maxStage = c.getInt(2);
                out.add(row);
            }
        } finally {
            c.close();
        }
        return out;
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    private double sumRecurring(String type) {
        long now = System.currentTimeMillis();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT SUM(amount) FROM recurring WHERE type=? AND (start_at=0 OR start_at<=?) AND (end_at=0 OR end_at>=?)",
                new String[]{type, String.valueOf(now), String.valueOf(now)});
        try {
            return c.moveToFirst() ? c.getDouble(0) : 0;
        } finally {
            c.close();
        }
    }

    private double sumRecurringByNeed(boolean essential) {
        long now = System.currentTimeMillis();
        double total = 0;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT amount, category FROM recurring WHERE type='fixed' " +
                        "AND (start_at=0 OR start_at<=?) AND (end_at=0 OR end_at>=?)",
                new String[]{String.valueOf(now), String.valueOf(now)});
        try {
            while (c.moveToNext()) {
                if (isEssentialCategory(c.getString(1)) == essential) total += c.getDouble(0);
            }
        } finally {
            c.close();
        }
        return total;
    }

    private double sumTransactions(String type, long start, long end) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT SUM(amount) FROM transactions WHERE type=? AND created_at>=? AND created_at<?",
                new String[]{type, String.valueOf(start), String.valueOf(end)});
        try {
            return c.moveToFirst() ? c.getDouble(0) : 0;
        } finally {
            c.close();
        }
    }

    private long monthStart() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private long monthEnd() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(monthStart());
        c.add(Calendar.MONTH, 1);
        return c.getTimeInMillis();
    }

    private Goal readGoal(Cursor c) {
        Goal g = new Goal();
        g.id = c.getLong(c.getColumnIndexOrThrow("id"));
        g.name = c.getString(c.getColumnIndexOrThrow("name"));
        g.finality = c.getString(c.getColumnIndexOrThrow("finality"));
        g.category = c.getString(c.getColumnIndexOrThrow("category"));
        g.target = c.getDouble(c.getColumnIndexOrThrow("target"));
        g.saved = c.getDouble(c.getColumnIndexOrThrow("saved"));
        g.startAt = c.getLong(c.getColumnIndexOrThrow("start_at"));
        g.endAt = c.getLong(c.getColumnIndexOrThrow("end_at"));
        g.months = c.getInt(c.getColumnIndexOrThrow("months"));
        g.creature = c.getInt(c.getColumnIndexOrThrow("creature"));
        g.xp = c.getInt(c.getColumnIndexOrThrow("xp"));
        g.hp = c.getInt(c.getColumnIndexOrThrow("hp"));
        g.stage = c.getInt(c.getColumnIndexOrThrow("stage"));
        g.status = c.getString(c.getColumnIndexOrThrow("status"));
        return g;
    }

    private Tx readTx(Cursor c) {
        Tx tx = new Tx();
        tx.id = c.getLong(c.getColumnIndexOrThrow("id"));
        tx.type = c.getString(c.getColumnIndexOrThrow("type"));
        tx.title = c.getString(c.getColumnIndexOrThrow("title"));
        tx.amount = c.getDouble(c.getColumnIndexOrThrow("amount"));
        tx.category = c.getString(c.getColumnIndexOrThrow("category"));
        tx.account = c.getString(c.getColumnIndexOrThrow("account"));
        tx.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        tx.note = c.getString(c.getColumnIndexOrThrow("note"));
        tx.barcode = c.getString(c.getColumnIndexOrThrow("barcode"));
        tx.location = c.getString(c.getColumnIndexOrThrow("location"));
        tx.usageHours = c.getDouble(c.getColumnIndexOrThrow("usage_hours"));
        tx.offer = c.getInt(c.getColumnIndexOrThrow("offer")) == 1;
        tx.incident = c.getInt(c.getColumnIndexOrThrow("incident")) == 1;
        tx.photoPath = c.getString(c.getColumnIndexOrThrow("photo_path"));
        tx.receiptPath = c.getString(c.getColumnIndexOrThrow("receipt_path"));
        tx.voicePath = c.getString(c.getColumnIndexOrThrow("voice_path"));
        return tx;
    }

    private InvestmentAsset readInvestmentAsset(Cursor c) {
        InvestmentAsset row = new InvestmentAsset();
        row.id = c.getLong(c.getColumnIndexOrThrow("id"));
        row.name = c.getString(c.getColumnIndexOrThrow("name"));
        row.ticker = c.getString(c.getColumnIndexOrThrow("ticker"));
        row.category = c.getString(c.getColumnIndexOrThrow("category"));
        row.assetType = c.getString(c.getColumnIndexOrThrow("asset_type"));
        row.manualPrice = c.getDouble(c.getColumnIndexOrThrow("manual_price"));
        row.internetPrice = c.getDouble(c.getColumnIndexOrThrow("internet_price"));
        row.lastUpdate = c.getLong(c.getColumnIndexOrThrow("last_update"));
        row.source = c.getString(c.getColumnIndexOrThrow("source"));
        row.risk = c.getString(c.getColumnIndexOrThrow("risk"));
        row.note = c.getString(c.getColumnIndexOrThrow("note"));
        row.studyReason = c.getString(c.getColumnIndexOrThrow("study_reason"));
        row.horizon = c.getString(c.getColumnIndexOrThrow("horizon"));
        row.invested = c.getDouble(c.getColumnIndexOrThrow("invested"));
        row.currentValue = c.getDouble(c.getColumnIndexOrThrow("current_value"));
        row.alert = c.getString(c.getColumnIndexOrThrow("alert"));
        row.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        row.status = c.getString(c.getColumnIndexOrThrow("status"));
        return row;
    }

    private Product readProduct(Cursor c) {
        Product row = new Product();
        row.barcode = c.getString(c.getColumnIndexOrThrow("barcode"));
        row.name = c.getString(c.getColumnIndexOrThrow("name"));
        row.brand = c.getString(c.getColumnIndexOrThrow("brand"));
        row.quantityLabel = c.getString(c.getColumnIndexOrThrow("quantity_label"));
        row.category = c.getString(c.getColumnIndexOrThrow("category"));
        row.imageUrl = c.getString(c.getColumnIndexOrThrow("image_url"));
        row.source = c.getString(c.getColumnIndexOrThrow("source"));
        row.sourceUpdatedAt = c.getLong(c.getColumnIndexOrThrow("source_updated_at"));
        row.lastSeenAt = c.getLong(c.getColumnIndexOrThrow("last_seen_at"));
        return row;
    }

    private PriceObservation readPriceObservation(Cursor c) {
        PriceObservation row = new PriceObservation();
        row.id = c.getLong(c.getColumnIndexOrThrow("id"));
        row.barcode = c.getString(c.getColumnIndexOrThrow("barcode"));
        row.storeName = c.getString(c.getColumnIndexOrThrow("store_name"));
        row.price = c.getDouble(c.getColumnIndexOrThrow("price"));
        row.currency = c.getString(c.getColumnIndexOrThrow("currency"));
        row.source = c.getString(c.getColumnIndexOrThrow("source"));
        row.confidence = c.getDouble(c.getColumnIndexOrThrow("confidence"));
        row.observedAt = c.getLong(c.getColumnIndexOrThrow("observed_at"));
        return row;
    }

    private ShoppingSession readShoppingSession(Cursor c) {
        ShoppingSession row = new ShoppingSession();
        row.id = c.getLong(c.getColumnIndexOrThrow("id"));
        row.storeName = c.getString(c.getColumnIndexOrThrow("store_name"));
        row.account = c.getString(c.getColumnIndexOrThrow("account"));
        row.status = c.getString(c.getColumnIndexOrThrow("status"));
        row.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        row.completedAt = c.getLong(c.getColumnIndexOrThrow("completed_at"));
        return row;
    }

    private ShoppingItem readShoppingItem(Cursor c) {
        ShoppingItem row = new ShoppingItem();
        row.id = c.getLong(c.getColumnIndexOrThrow("id"));
        row.sessionId = c.getLong(c.getColumnIndexOrThrow("session_id"));
        row.barcode = c.getString(c.getColumnIndexOrThrow("barcode"));
        row.name = c.getString(c.getColumnIndexOrThrow("name"));
        row.brand = c.getString(c.getColumnIndexOrThrow("brand"));
        row.quantity = c.getInt(c.getColumnIndexOrThrow("quantity"));
        row.unitPrice = c.getDouble(c.getColumnIndexOrThrow("unit_price"));
        row.priceSource = c.getString(c.getColumnIndexOrThrow("price_source"));
        row.priceConfirmed = c.getInt(c.getColumnIndexOrThrow("price_confirmed")) == 1;
        row.category = c.getString(c.getColumnIndexOrThrow("category"));
        row.scannedAt = c.getLong(c.getColumnIndexOrThrow("scanned_at"));
        return row;
    }

    private AiMessage readAiMessage(Cursor c) {
        AiMessage row = new AiMessage();
        row.id = c.getLong(c.getColumnIndexOrThrow("id"));
        row.persona = c.getString(c.getColumnIndexOrThrow("persona"));
        row.speaker = c.getString(c.getColumnIndexOrThrow("speaker"));
        row.topic = c.getString(c.getColumnIndexOrThrow("primary_topic"));
        row.tags = c.getString(c.getColumnIndexOrThrow("tags"));
        row.body = c.getString(c.getColumnIndexOrThrow("body"));
        row.contextSnapshot = c.getString(c.getColumnIndexOrThrow("context_snapshot"));
        row.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        return row;
    }

    private AiMemory readAiMemory(Cursor c) {
        AiMemory row = new AiMemory();
        row.id = c.getLong(c.getColumnIndexOrThrow("id"));
        row.persona = c.getString(c.getColumnIndexOrThrow("persona"));
        row.memoryType = c.getString(c.getColumnIndexOrThrow("memory_type"));
        row.topic = c.getString(c.getColumnIndexOrThrow("topic"));
        row.summary = c.getString(c.getColumnIndexOrThrow("summary"));
        row.sourceMessageId = c.getLong(c.getColumnIndexOrThrow("source_message_id"));
        row.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        row.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        return row;
    }

    private AvatarPlace readAvatarPlace(Cursor c) {
        AvatarPlace row = new AvatarPlace();
        row.id = c.getLong(c.getColumnIndexOrThrow("id"));
        row.label = c.getString(c.getColumnIndexOrThrow("label"));
        row.placeType = c.getString(c.getColumnIndexOrThrow("place_type"));
        row.latitude = c.getDouble(c.getColumnIndexOrThrow("latitude"));
        row.longitude = c.getDouble(c.getColumnIndexOrThrow("longitude"));
        row.radiusM = c.getDouble(c.getColumnIndexOrThrow("radius_m"));
        row.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        row.lastSeenAt = c.getLong(c.getColumnIndexOrThrow("last_seen_at"));
        return row;
    }

    private long monthlyOccurrence(long startAt, long endAt, int timeMinutes, long monthStart, long monthEnd) {
        if (startAt <= 0 || startAt >= monthEnd || (endAt > 0 && endAt < monthStart)) return 0;
        Calendar source = Calendar.getInstance();
        source.setTimeInMillis(startAt);
        Calendar due = Calendar.getInstance();
        due.setTimeInMillis(monthStart);
        int wantedDay = source.get(Calendar.DAY_OF_MONTH);
        wantedDay = Math.min(wantedDay, due.getActualMaximum(Calendar.DAY_OF_MONTH));
        due.set(Calendar.DAY_OF_MONTH, wantedDay);
        due.set(Calendar.HOUR_OF_DAY, Math.max(0, Math.min(23, timeMinutes / 60)));
        due.set(Calendar.MINUTE, Math.max(0, Math.min(59, timeMinutes % 60)));
        due.set(Calendar.SECOND, 0);
        due.set(Calendar.MILLISECOND, 0);
        long result = due.getTimeInMillis();
        if (result < startAt || result >= monthEnd || (endAt > 0 && result > endAt)) return 0;
        return result;
    }

    private double distanceMeters(double latA, double lonA, double latB, double lonB) {
        double earth = 6_371_000d;
        double lat1 = Math.toRadians(latA);
        double lat2 = Math.toRadians(latB);
        double dLat = Math.toRadians(latB - latA);
        double dLon = Math.toRadians(lonB - lonA);
        double sinLat = Math.sin(dLat / 2d);
        double sinLon = Math.sin(dLon / 2d);
        double a = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        return earth * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a));
    }

    private String clean(String s) {
        return s == null ? "" : s.trim();
    }

    private String storeKey(String value) {
        String normalized = clean(value).toLowerCase(Locale.ITALY)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        return normalized.isEmpty() ? "negozio non indicato" : normalized;
    }

    private String csv(String text) {
        String value = text == null ? "" : text;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String formatDate(long ts) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ITALY).format(ts);
    }

    private String formatDay(long ts) {
        if (ts <= 0) return "da ora";
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).format(ts);
    }

    private String labelRecurringType(String type) {
        if ("income".equals(type)) return "Entrata programmata";
        return "Spesa programmata";
    }

    private String round(double value) {
        return String.format(Locale.ITALY, "%.0f", value);
    }

    private String exact(double value) {
        return String.format(Locale.ITALY, "%.2f", value);
    }

    static final class Goal {
        long id;
        String name;
        String finality;
        String category;
        double target;
        double saved;
        long startAt;
        long endAt;
        int months;
        int creature;
        int xp;
        int hp;
        int stage;
        String status;
    }

    static final class Tx {
        long id;
        String type;
        String title;
        double amount;
        String category;
        String account;
        long createdAt;
        String note;
        String barcode;
        String location;
        double usageHours;
        boolean offer;
        boolean incident;
        String photoPath;
        String receiptPath;
        String voicePath;
    }

    static final class PokeRow {
        int species;
        boolean discovered;
        int maxStage;
    }

    static final class MoneyRow {
        String left;
        String right;
    }

    static final class Recurring {
        long id;
        String type;
        String title;
        double amount;
        String category;
        String account;
        long startAt;
        long endAt;
        int timeMinutes;
    }

    static final class AiMessage {
        long id;
        String persona;
        String speaker;
        String topic;
        String tags;
        String body;
        String contextSnapshot;
        long createdAt;
    }

    static final class AiMemory {
        long id;
        String persona;
        String memoryType;
        String topic;
        String summary;
        long sourceMessageId;
        long createdAt;
        long updatedAt;
    }

    static final class AvatarPlace {
        long id;
        String label;
        String placeType;
        double latitude;
        double longitude;
        double radiusM;
        double distanceM;
        long createdAt;
        long lastSeenAt;
    }

    static final class CalendarItem {
        long sourceId;
        String kind;
        String title;
        String category;
        double amount;
        long whenAt;
    }

    static final class Debt {
        long id;
        String name;
        double total;
        double remaining;
        double monthlyPayment;
        double apr;
        long startAt;
        long endAt;
        String category;
        String debtType;
        String note;
    }

    static final class CheckIn {
        String mood;
        int stress;
        int energy;
        String note;
        long createdAt;
    }

    static final class AttachmentStats {
        int locations;
        int photos;
        int receipts;
        int barcodes;
        int voices;
        String latestLocation = "";
        long latestLocationAt;
    }

    static final class LocalAiReport {
        int score;
        int avatarHp;
        double income;
        double fixed;
        double debtMonthly;
        double debtRemaining;
        double spent;
        double margin;
        double essential;
        double wants;
        double future;
        double essentialRatio;
        double wantsRatio;
        double futureRatio;
        double debtRatio;
        double emergencyFundMonths;
        int impulseCount;
        String level;
        String avatarState;
        String mainTask;
    }

    static final class InvestmentAsset {
        long id;
        String name;
        String ticker;
        String category;
        String assetType;
        double manualPrice;
        double internetPrice;
        long lastUpdate;
        String source;
        String risk;
        String note;
        String studyReason;
        String horizon;
        double invested;
        double currentValue;
        String alert;
        long createdAt;
        String status;
    }

    static final class InvestmentSummary {
        double totalInvested;
        double currentValue;
        double gainLoss;
        int assetCount;
    }

    static final class InterestItem {
        long id;
        String name;
        String type;
        double annualRate;
        double amount;
        int durationMonths;
        double estimatedInterest;
        String note;
        String source;
        long createdAt;
    }

    static final class FinanceCacheEntry {
        long id;
        String dataType;
        String value;
        String source;
        String reliability;
        long updatedAt;
        String disclaimer;
    }

    static final class Product {
        String barcode;
        String name;
        String brand;
        String quantityLabel;
        String category;
        String imageUrl;
        String source;
        long sourceUpdatedAt;
        long lastSeenAt;

        String displayName() {
            if (brand == null || brand.trim().isEmpty() || name == null || name.toLowerCase(Locale.ITALY).contains(brand.toLowerCase(Locale.ITALY))) {
                return name == null ? "Prodotto" : name;
            }
            return brand + " " + name;
        }
    }

    static final class PriceObservation {
        long id;
        String barcode;
        String storeName;
        double price;
        String currency;
        String source;
        double confidence;
        long observedAt;
    }

    static final class ShoppingSession {
        long id;
        String storeName;
        String account;
        String status;
        long createdAt;
        long completedAt;
    }

    static final class ShoppingItem {
        long id;
        long sessionId;
        String barcode;
        String name;
        String brand;
        int quantity;
        double unitPrice;
        String priceSource;
        boolean priceConfirmed;
        String category;
        long scannedAt;
    }

    static final class ShoppingSummary {
        int quantity;
        double total;
        int missingPrices;
        int estimatedPrices;
    }
}
