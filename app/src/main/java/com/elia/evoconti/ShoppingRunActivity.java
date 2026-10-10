package com.elia.evoconti;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.Image;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import it.evocore.telemetry.TelemetryRuntime;

public final class ShoppingRunActivity extends ComponentActivity {
    static final String EXTRA_SESSION_ID = "shopping_session_id";
    static final String EXTRA_STORE = "shopping_store";
    static final String EXTRA_ACCOUNT = "shopping_account";
    static final String EXTRA_ONLINE = "shopping_online";
    static final String RESULT_TOTAL = "shopping_total";
    static final String RESULT_ITEMS = "shopping_items";

    private static final int REQUEST_CAMERA_PERMISSION = 81;
    private static final int OCR_NONE = 0;
    private static final int OCR_PRICE = 1;
    private static final int OCR_PRODUCT = 2;
    private static final int BG = 0xFF080A0E;
    private static final int SURFACE = 0xF213181D;
    private static final int FIELD = 0xF20E1317;
    private static final int LINE = 0xFF30383E;
    private static final int INK = 0xFFF3F6F4;
    private static final int MUTED = 0xFF9CA8A3;
    private static final int GREEN = 0xFF43D19A;
    private static final int GOLD = 0xFFF0C96D;
    private static final int CORAL = 0xFFFF7165;
    private static final int BLUE = 0xFF57B5E8;

    private EvoDb db;
    private ProductCatalogConnector catalog;
    private long sessionId;
    private String storeName;
    private String accountName;
    private long lastItemId = -1;
    private String lastAcceptedBarcode = "";
    private long noBarcodeSince;
    private boolean sameBarcodeArmed = true;
    private volatile int pendingOcrMode = OCR_NONE;
    private final AtomicBoolean processingFrame = new AtomicBoolean(false);

    private PreviewView previewView;
    private ScannerOverlayView scannerOverlay;
    private TextView totalView;
    private TextView summaryView;
    private TextView statusView;
    private LinearLayout itemsView;
    private Button priceButton;
    private Button productButton;
    private ExecutorService cameraExecutor;
    private BarcodeScanner barcodeScanner;
    private TextRecognizer textRecognizer;
    private ProcessCameraProvider cameraProvider;
    private ToneGenerator tone;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        db = new EvoDb(this);
        catalog = new ProductCatalogConnector();
        Intent intent = getIntent();
        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1);
        storeName = clean(intent.getStringExtra(EXTRA_STORE));
        accountName = clean(intent.getStringExtra(EXTRA_ACCOUNT));
        if (intent.hasExtra(EXTRA_ONLINE)) catalog.setConsent(this, intent.getBooleanExtra(EXTRA_ONLINE, false));
        EvoDb.ShoppingSession session = sessionId > 0 ? db.shoppingSession(sessionId) : null;
        if (session == null) {
            sessionId = db.createShoppingSession(storeName, accountName);
        } else {
            storeName = session.storeName;
            accountName = session.account;
        }
        if (storeName.isEmpty()) storeName = "Supermercato";
        buildUi();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmPauseOrExit();
            }
        });
        refreshCart();
        cameraExecutor = Executors.newSingleThreadExecutor();
        barcodeScanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                        Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128)
                .enableAllPotentialBarcodes()
                .build());
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        try {
            tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 55);
        } catch (Exception ignored) {
            tone = null;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        scannerOverlay = new ScannerOverlayView();
        root.addView(scannerOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(18), dp(12), dp(18), dp(14));
        top.setBackground(box(0xE6080A0E, 0, 0));
        TextView title = text("SCANSIONE ATTIVA", 10, GREEN, Typeface.BOLD);
        top.addView(title);
        totalView = text("0,00 EUR", 30, INK, Typeface.BOLD);
        top.addView(totalView);
        summaryView = text("0 prodotti", 13, MUTED, Typeface.NORMAL);
        top.addView(summaryView);
        statusView = text(storeName + "  |  inquadra un barcode", 12, BLUE, Typeface.BOLD);
        top.addView(statusView, margins(0, dp(6), 0, 0));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(top, topParams);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(12), dp(10), dp(12), dp(10));
        bottom.setBackground(box(SURFACE, dp(8), LINE));

        ScrollView itemScroll = new ScrollView(this);
        itemScroll.setFillViewport(false);
        itemsView = new LinearLayout(this);
        itemsView.setOrientation(LinearLayout.VERTICAL);
        itemScroll.addView(itemsView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bottom.addView(itemScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(202)));

        LinearLayout captureActions = new LinearLayout(this);
        captureActions.setOrientation(LinearLayout.HORIZONTAL);
        captureActions.setGravity(Gravity.CENTER);
        priceButton = actionButton("Prezzo", GOLD, FIELD);
        priceButton.setOnClickListener(v -> requestOcr(OCR_PRICE));
        productButton = actionButton("Prodotto", BLUE, FIELD);
        productButton.setOnClickListener(v -> requestOcr(OCR_PRODUCT));
        Button manual = actionButton("Aggiungi", INK, FIELD);
        manual.setOnClickListener(v -> showManualItemDialog(""));
        captureActions.addView(priceButton, weightedMargins());
        captureActions.addView(productButton, weightedMargins());
        captureActions.addView(manual, weightedMargins());
        bottom.addView(captureActions, margins(0, dp(8), 0, 0));

        LinearLayout finishActions = new LinearLayout(this);
        finishActions.setOrientation(LinearLayout.HORIZONTAL);
        Button pause = actionButton("Pausa", MUTED, FIELD);
        pause.setOnClickListener(v -> pauseSession());
        Button finish = actionButton("Concludi", Color.BLACK, GREEN);
        finish.setOnClickListener(v -> finishSession());
        finishActions.addView(pause, weightedMargins());
        finishActions.addView(finish, weightedMargins());
        bottom.addView(finishActions, margins(0, dp(7), 0, 0));

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        bottomParams.setMargins(dp(8), 0, dp(8), dp(6));
        root.addView(bottom, bottomParams);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset;
            int bottomInset;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                topInset = bars.top;
                bottomInset = bars.bottom;
            } else {
                topInset = insets.getSystemWindowInsetTop();
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            top.setPadding(dp(18), dp(12) + topInset, dp(18), dp(14));
            bottom.setPadding(dp(12), dp(10), dp(12), dp(10) + bottomInset);
            return insets;
        });
        setContentView(root);
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
                status(storeName + "  |  inquadra un barcode", BLUE);
            } catch (Exception error) {
                status("Fotocamera non disponibile", CORAL);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeFrame(ImageProxy imageProxy) {
        if (!processingFrame.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }
        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            processingFrame.set(false);
            imageProxy.close();
            return;
        }
        InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
        int ocrMode = pendingOcrMode;
        if (ocrMode != OCR_NONE) {
            pendingOcrMode = OCR_NONE;
            textRecognizer.process(image)
                    .addOnSuccessListener(result -> {
                        if (ocrMode == OCR_PRICE) handlePriceText(result.getText());
                        else handleProductText(result.getText());
                    })
                    .addOnFailureListener(error -> status("Testo non riconosciuto", CORAL))
                    .addOnCompleteListener(task -> closeFrame(imageProxy));
            return;
        }
        barcodeScanner.process(image)
                .addOnSuccessListener(this::handleBarcodeFrame)
                .addOnFailureListener(error -> status("Lettura barcode in attesa", GOLD))
                .addOnCompleteListener(task -> closeFrame(imageProxy));
    }

    private void closeFrame(ImageProxy imageProxy) {
        imageProxy.close();
        processingFrame.set(false);
    }

    private void handleBarcodeFrame(List<Barcode> barcodes) {
        String accepted = "";
        for (Barcode barcode : barcodes) {
            String value = normalizeBarcode(barcode.getRawValue());
            if (value.length() >= 8) {
                accepted = value;
                break;
            }
        }
        long now = System.currentTimeMillis();
        if (accepted.isEmpty()) {
            if (noBarcodeSince == 0) noBarcodeSince = now;
            if (now - noBarcodeSince >= 650) sameBarcodeArmed = true;
            return;
        }
        noBarcodeSince = 0;
        if (accepted.equals(lastAcceptedBarcode) && !sameBarcodeArmed) return;
        sameBarcodeArmed = false;
        lastAcceptedBarcode = accepted;
        acceptBarcode(accepted);
    }

    private void acceptBarcode(String barcode) {
        EvoDb.Product product = db.product(barcode);
        EvoDb.PriceObservation localPrice = db.bestStorePrice(barcode, storeName);
        double price = localPrice == null ? 0 : localPrice.price;
        boolean confirmed = localPrice != null && System.currentTimeMillis() - localPrice.observedAt <= 86_400_000L;
        String priceSource = localPrice == null ? "mancante" :
                "memoria " + localPrice.storeName + " " + shortDate(localPrice.observedAt);
        String name = product == null ? "Prodotto " + barcode : product.displayName();
        String brand = product == null ? "" : product.brand;
        String category = product == null ? "Alimentare" : product.category;
        long itemId = db.addOrIncrementShoppingItem(sessionId, barcode, name, brand,
                price, priceSource, confirmed, category);
        lastItemId = itemId;
        feedback();
        refreshCart();
        EvoDb.ShoppingItem item = db.shoppingItem(itemId);
        if (item != null && item.quantity > 1) {
            status(item.name + " | quantita " + item.quantity, GREEN);
        } else if (price > 0) {
            status(name + " | " + money(price), confirmed ? GREEN : GOLD);
        } else {
            status("Prezzo mancante | " + name, GOLD);
        }
        boolean staleProduct = product == null || System.currentTimeMillis() - product.sourceUpdatedAt > 30L * 86_400_000L;
        if (catalog.hasConsent(this) && (staleProduct || price <= 0)) resolveProduct(itemId, barcode);
    }

    private void resolveProduct(long itemId, String barcode) {
        catalog.resolveAsync(this, db, barcode, storeName, result -> {
            if (isFinishing() || isDestroyed()) return;
            EvoDb.ShoppingItem item = db.shoppingItem(itemId);
            if (item == null) return;
            if (result.hasProduct()) db.updateShoppingItemProduct(itemId, result.product);
            item = db.shoppingItem(itemId);
            if (item != null && item.unitPrice <= 0 && result.hasSuggestedPrice()) {
                db.updateShoppingItem(item.id, item.name, item.quantity, result.suggestedPrice,
                        result.priceSource, false, item.category);
                status(item.name + " | stima " + money(result.suggestedPrice), GOLD);
            } else if (result.hasProduct()) {
                status(result.product.displayName() + " riconosciuto", GREEN);
            } else if (!result.error.isEmpty()) {
                status(result.error, GOLD);
            }
            refreshCart();
        });
    }

    private void requestOcr(int mode) {
        if (mode == OCR_PRICE && lastItemId <= 0) {
            toast("Seleziona o scansiona prima un prodotto");
            return;
        }
        pendingOcrMode = mode;
        scannerOverlay.setPriceMode(mode == OCR_PRICE);
        status(mode == OCR_PRICE ? "Lettura cartellino prezzo" : "Lettura confezione", mode == OCR_PRICE ? GOLD : BLUE);
    }

    private void handlePriceText(String rawText) {
        scannerOverlay.setPriceMode(false);
        List<PriceLabelParser.Candidate> candidates = new PriceLabelParser().candidates(rawText);
        if (candidates.isEmpty()) {
            status("Nessun prezzo certo rilevato", CORAL);
            return;
        }
        int count = Math.min(6, candidates.size());
        String[] labels = new String[count];
        for (int i = 0; i < count; i++) {
            PriceLabelParser.Candidate candidate = candidates.get(i);
            labels[i] = money(candidate.value) + " | " + candidate.line;
        }
        if (count == 1) {
            applyPrice(candidates.get(0).value, "cartellino OCR");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Prezzo del prodotto")
                .setItems(labels, (dialog, which) -> applyPrice(candidates.get(which).value, "cartellino OCR"))
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void handleProductText(String rawText) {
        scannerOverlay.setPriceMode(false);
        String suggestion = new ProductTextParser().bestName(rawText);
        if (suggestion.isEmpty()) {
            status("Confezione non riconosciuta", CORAL);
            return;
        }
        showManualItemDialog(suggestion);
    }

    private void applyPrice(double value, String source) {
        EvoDb.ShoppingItem item = db.shoppingItem(lastItemId);
        if (item == null || value <= 0) return;
        db.updateShoppingItem(item.id, item.name, item.quantity, value, source, true, item.category);
        if (!clean(item.barcode).isEmpty()) {
            db.rememberPrice(item.barcode, storeName, value, source, 1, System.currentTimeMillis());
        }
        status(item.name + " | " + money(value) + " confermato", GREEN);
        refreshCart();
    }

    private void refreshCart() {
        EvoDb.ShoppingSummary summary = db.shoppingSummary(sessionId);
        List<EvoDb.ShoppingItem> items = db.shoppingItems(sessionId);
        if (lastItemId <= 0 && !items.isEmpty()) lastItemId = items.get(items.size() - 1).id;
        totalView.setText(money(summary.total));
        String meta = summary.quantity + (summary.quantity == 1 ? " prodotto" : " prodotti");
        if (summary.missingPrices > 0) meta += " | " + summary.missingPrices + " senza prezzo";
        if (summary.estimatedPrices > 0) meta += " | " + summary.estimatedPrices + " stimati";
        summaryView.setText(meta);
        priceButton.setEnabled(lastItemId > 0);
        priceButton.setAlpha(lastItemId > 0 ? 1 : .45f);
        itemsView.removeAllViews();
        if (items.isEmpty()) {
            TextView empty = text("Carrello vuoto", 15, MUTED, Typeface.BOLD);
            empty.setGravity(Gravity.CENTER);
            itemsView.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));
            return;
        }
        for (EvoDb.ShoppingItem item : items) itemsView.addView(itemRow(item));
    }

    private View itemRow(EvoDb.ShoppingItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(7), dp(7), dp(7));
        row.setBackground(box(FIELD, dp(7), item.id == lastItemId ? GREEN : LINE));
        row.setOnClickListener(v -> editItem(item.id));
        row.setContentDescription("Modifica " + item.name);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        String title = item.name + (item.quantity > 1 ? "  x" + item.quantity : "");
        copy.addView(text(title, 14, INK, Typeface.BOLD));
        String source = item.unitPrice <= 0 ? "prezzo mancante" : item.priceSource;
        copy.addView(text(source, 11, item.priceConfirmed ? GREEN : (item.unitPrice > 0 ? GOLD : CORAL), Typeface.NORMAL));
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView price = text(item.unitPrice <= 0 ? "--" : money(item.unitPrice * item.quantity),
                14, item.unitPrice <= 0 ? CORAL : INK, Typeface.BOLD);
        price.setGravity(Gravity.END);
        row.addView(price, new LinearLayout.LayoutParams(dp(94), ViewGroup.LayoutParams.WRAP_CONTENT));

        Button plus = actionButton("+", INK, 0xFF172536);
        plus.setTextSize(20);
        plus.setContentDescription("Aumenta quantita " + item.name);
        plus.setOnClickListener(v -> {
            db.updateShoppingItem(item.id, item.name, item.quantity + 1, item.unitPrice,
                    item.priceSource, item.priceConfirmed, item.category);
            lastItemId = item.id;
            refreshCart();
        });
        row.addView(plus, new LinearLayout.LayoutParams(dp(44), dp(42)));
        return withMargins(row, 0, dp(3), 0, dp(3));
    }

    private void showManualItemDialog(String suggestedName) {
        LinearLayout form = dialogForm();
        EditText name = input("Nome prodotto");
        name.setText(suggestedName);
        EditText barcode = input("Barcode opzionale");
        barcode.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText price = input("Prezzo EUR");
        price.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        Spinner category = spinner(db.listCategories());
        setSpinner(category, "Alimentare");
        form.addView(name);
        form.addView(barcode);
        form.addView(price);
        form.addView(category);
        new AlertDialog.Builder(this)
                .setTitle(suggestedName.isEmpty() ? "Prodotto manuale" : "Conferma confezione")
                .setView(form)
                .setPositiveButton("Aggiungi", (dialog, which) -> {
                    String itemName = clean(name.getText().toString());
                    if (itemName.isEmpty()) itemName = "Prodotto";
                    String code = normalizeBarcode(barcode.getText().toString());
                    double unitPrice = parse(price.getText().toString());
                    long id = db.addOrIncrementShoppingItem(sessionId, code, itemName, "", unitPrice,
                            unitPrice > 0 ? "manuale" : "mancante", unitPrice > 0, selected(category));
                    if (!code.isEmpty()) {
                        EvoDb.Product product = new EvoDb.Product();
                        product.barcode = code;
                        product.name = itemName;
                        product.brand = "";
                        product.quantityLabel = "";
                        product.category = selected(category);
                        product.imageUrl = "";
                        product.source = "locale";
                        db.upsertProduct(product);
                        if (unitPrice > 0) db.rememberPrice(code, storeName, unitPrice, "manuale", 1, System.currentTimeMillis());
                    }
                    lastItemId = id;
                    feedback();
                    refreshCart();
                    status(itemName + " aggiunto", GREEN);
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void editItem(long itemId) {
        EvoDb.ShoppingItem item = db.shoppingItem(itemId);
        if (item == null) return;
        lastItemId = itemId;
        LinearLayout form = dialogForm();
        EditText name = input("Nome prodotto");
        name.setText(item.name);
        EditText quantity = input("Quantita");
        quantity.setInputType(InputType.TYPE_CLASS_NUMBER);
        quantity.setText(String.valueOf(item.quantity));
        EditText price = input("Prezzo unitario EUR");
        price.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (item.unitPrice > 0) price.setText(String.format(Locale.US, "%.2f", item.unitPrice));
        Spinner category = spinner(db.listCategories());
        setSpinner(category, item.category);
        form.addView(name);
        form.addView(quantity);
        form.addView(price);
        form.addView(category);
        new AlertDialog.Builder(this)
                .setTitle("Modifica prodotto")
                .setView(form)
                .setPositiveButton("Salva", (dialog, which) -> {
                    int qty = Math.max(1, parseInt(quantity.getText().toString()));
                    double unitPrice = parse(price.getText().toString());
                    String updatedName = clean(name.getText().toString());
                    String source = unitPrice == item.unitPrice && item.priceConfirmed ? item.priceSource : "manuale confermato";
                    db.updateShoppingItem(item.id, updatedName, qty, unitPrice,
                            unitPrice > 0 ? source : "mancante", unitPrice > 0, selected(category));
                    if (!clean(item.barcode).isEmpty() && unitPrice > 0) {
                        db.rememberPrice(item.barcode, storeName, unitPrice, "manuale confermato", 1, System.currentTimeMillis());
                    }
                    status(updatedName + (unitPrice > 0 ? " | " + money(unitPrice) + " confermato" : " | prezzo mancante"),
                            unitPrice > 0 ? GREEN : CORAL);
                    refreshCart();
                })
                .setNeutralButton("Elimina", (dialog, which) -> {
                    db.removeShoppingItem(item.id);
                    if (lastItemId == item.id) lastItemId = -1;
                    refreshCart();
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void finishSession() {
        EvoDb.ShoppingSummary summary = db.shoppingSummary(sessionId);
        if (summary.quantity == 0) {
            toast("Il carrello e vuoto");
            return;
        }
        if (summary.missingPrices > 0) {
            EvoDb.ShoppingItem missing = firstMissingItem();
            new AlertDialog.Builder(this)
                    .setTitle("Prezzi mancanti")
                    .setMessage("Mancano " + summary.missingPrices + " prezzi. Il totale non sarebbe attendibile.")
                    .setPositiveButton("Rivedi", (dialog, which) -> {
                        if (missing != null) editItem(missing.id);
                    })
                    .setNegativeButton("Continua scansione", null)
                    .show();
            return;
        }
        if (summary.estimatedPrices > 0) {
            new AlertDialog.Builder(this)
                    .setTitle("Totale con stime")
                    .setMessage(money(summary.total) + " include " + summary.estimatedPrices +
                            " prezzi da memoria o fonte pubblica. Puoi salvarli oppure verificarli sui cartellini.")
                    .setPositiveButton("Salva totale", (dialog, which) -> persistSession())
                    .setNegativeButton("Verifica", null)
                    .show();
            return;
        }
        persistSession();
    }

    private void persistSession() {
        db.updateShoppingSession(sessionId, storeName, accountName);
        double total = db.completeShoppingSession(sessionId, storeName, accountName);
        if (total < 0) {
            toast("Controlla i prezzi prima di salvare");
            return;
        }
        EvoDb.ShoppingSummary summary = db.shoppingSummary(sessionId);
        Intent result = new Intent();
        result.putExtra(RESULT_TOTAL, total);
        result.putExtra(RESULT_ITEMS, summary.quantity);
        setResult(RESULT_OK, result);
        toast("Spesa salvata: " + money(total));
        finish();
    }

    private EvoDb.ShoppingItem firstMissingItem() {
        for (EvoDb.ShoppingItem item : db.shoppingItems(sessionId)) if (item.unitPrice <= 0) return item;
        return null;
    }

    private void pauseSession() {
        db.updateShoppingSession(sessionId, storeName, accountName);
        setResult(RESULT_CANCELED);
        finish();
    }

    private void confirmPauseOrExit() {
        EvoDb.ShoppingSummary summary = db.shoppingSummary(sessionId);
        if (summary.quantity == 0) {
            db.discardShoppingSession(sessionId);
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Corsa spesa in corso")
                .setMessage("Il carrello resta sul telefono e potrai riprenderlo.")
                .setPositiveButton("Metti in pausa", (dialog, which) -> pauseSession())
                .setNeutralButton("Elimina", (dialog, which) -> {
                    db.discardShoppingSession(sessionId);
                    finish();
                })
                .setNegativeButton("Resta qui", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAMERA_PERMISSION) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else status("Fotocamera non autorizzata | inserimento manuale disponibile", CORAL);
    }

    @Override
    protected void onDestroy() {
        if (cameraProvider != null) cameraProvider.unbindAll();
        if (barcodeScanner != null) barcodeScanner.close();
        if (textRecognizer != null) textRecognizer.close();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (tone != null) tone.release();
        if (db != null) db.close();
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        TelemetryRuntime.start(this);
        TelemetryRuntime.refreshSession();
        TelemetryRuntime.screen("shopping_run");
    }

    @Override
    protected void onStop() {
        TelemetryRuntime.stop();
        super.onStop();
    }

    private void feedback() {
        if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_ACK, 90);
        try {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null) {
                vibrator.vibrate(VibrationEffect.createOneShot(65, 90));
            }
        } catch (Exception ignored) {
        }
    }

    private void status(String message, int color) {
        runOnUiThread(() -> {
            statusView.setText(message);
            statusView.setTextColor(color);
        });
    }

    private LinearLayout dialogForm() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(8), dp(18), 0);
        return form;
    }

    private EditText input(String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setHintTextColor(MUTED);
        field.setTextColor(INK);
        field.setTextSize(15);
        field.setSingleLine(true);
        field.setPadding(dp(12), 0, dp(12), 0);
        field.setBackground(box(FIELD, dp(7), LINE));
        field.setLayoutParams(margins(0, dp(4), 0, dp(4)));
        field.setMinHeight(dp(50));
        return field;
    }

    private Spinner spinner(List<String> values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setBackground(box(FIELD, dp(7), LINE));
        spinner.setLayoutParams(margins(0, dp(4), 0, dp(4)));
        spinner.setMinimumHeight(dp(50));
        return spinner;
    }

    private void setSpinner(Spinner spinner, String value) {
        if (spinner == null || value == null) return;
        for (int i = 0; i < spinner.getCount(); i++) {
            if (value.equalsIgnoreCase(String.valueOf(spinner.getItemAtPosition(i)))) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    private String selected(Spinner spinner) {
        return spinner.getSelectedItem() == null ? "Alimentare" : spinner.getSelectedItem().toString();
    }

    private Button actionButton(String label, int foreground, int background) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(foreground);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setBackground(box(background, dp(7), background == GREEN ? GREEN : LINE));
        return button;
    }

    private TextView text(String value, int size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setLetterSpacing(0f);
        view.setLineSpacing(0, 1.08f);
        return view;
    }

    private GradientDrawable box(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams weightedMargins() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private View withMargins(View view, int left, int top, int right, int bottom) {
        view.setLayoutParams(margins(left, top, right, bottom));
        return view;
    }

    private String money(double value) {
        return String.format(Locale.ITALY, "%.2f EUR", value);
    }

    private String shortDate(long timestamp) {
        if (timestamp <= 0) return "";
        return new SimpleDateFormat("dd/MM", Locale.ITALY).format(timestamp);
    }

    private double parse(String raw) {
        try {
            return Double.parseDouble(clean(raw).replace(',', '.'));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int parseInt(String raw) {
        try {
            return Integer.parseInt(clean(raw));
        } catch (Exception ignored) {
            return 1;
        }
    }

    private String normalizeBarcode(String raw) {
        return raw == null ? "" : raw.replaceAll("[^0-9]", "");
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private final class ScannerOverlayView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF guide = new RectF();
        private float phase;
        private boolean priceMode;
        private final ValueAnimator animator;

        ScannerOverlayView() {
            super(ShoppingRunActivity.this);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            animator = ValueAnimator.ofFloat(0, 1);
            animator.setDuration(1_700);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setRepeatMode(ValueAnimator.REVERSE);
            animator.addUpdateListener(value -> {
                phase = (float) value.getAnimatedValue();
                invalidate();
            });
        }

        void setPriceMode(boolean value) {
            priceMode = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth() * .78f;
            float height = priceMode ? dp(118) : dp(178);
            float left = (getWidth() - width) / 2;
            float top = Math.max(dp(160), getHeight() * .31f);
            guide.set(left, top, left + width, top + height);
            int color = priceMode ? GOLD : GREEN;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(color);
            canvas.drawRoundRect(guide, dp(7), dp(7), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((color & 0x00FFFFFF) | 0xCC000000);
            float y = guide.top + phase * guide.height();
            canvas.drawRect(guide.left + dp(9), y, guide.right - dp(9), y + dp(2), paint);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            updateAnimation();
        }

        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            updateAnimation();
        }

        @Override
        protected void onDetachedFromWindow() {
            animator.cancel();
            super.onDetachedFromWindow();
        }

        private void updateAnimation() {
            if (isAttachedToWindow() && getWindowVisibility() == VISIBLE) {
                if (!animator.isStarted()) animator.start();
            } else {
                animator.cancel();
            }
        }
    }
}
