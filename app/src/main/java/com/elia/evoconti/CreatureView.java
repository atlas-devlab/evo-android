package com.elia.evoconti;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

final class CreatureView extends View {
    private static final float VW = 320f;
    private static final float VH = 210f;
    private static final int SPRITE_COLS = 6;
    private static final int SPRITE_ROWS = 5;
    private static final int LIVE_MESH_X = 6;
    private static final int LIVE_MESH_Y = 8;
    static final int SCENE_IDLE = 0;
    static final int SCENE_SLEEP = 1;
    static final int SCENE_EAT = 2;
    static final int SCENE_MONEY = 3;
    static final int ENV_HOME = 0;
    static final int ENV_NATURE = 1;
    static final int ENV_CITY = 2;
    static final int ENV_SHOP = 3;
    static final int REACTION_CURIOUS = 1;
    static final int REACTION_GREETING = 2;
    static final int REACTION_HAPPY = 3;
    private static Bitmap sharedBaseSprites;
    private static Bitmap sharedEvolvedSprites;
    private static Bitmap sharedSceneAtlas;
    private static Bitmap sharedEnvironmentAtlas;
    private static final Bitmap[] sharedBaseTiles = new Bitmap[SPRITE_COLS * SPRITE_ROWS];
    private static final Bitmap[] sharedEvolvedTiles = new Bitmap[SPRITE_COLS * SPRITE_ROWS];

    private final Paint paint = new Paint();
    private final Paint textPaint = new Paint();
    private final Paint spritePaint = new Paint();
    private final Paint scenePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect spriteSrc = new Rect();
    private final RectF spriteDst = new RectF();
    private final Rect sceneSrc = new Rect();
    private final RectF sceneDst = new RectF();
    private final RectF shadowRect = new RectF();
    private final RectF[] tileRects = new RectF[12];
    private final float[] liveMesh = new float[(LIVE_MESH_X + 1) * (LIVE_MESH_Y + 1) * 2];
    private final Bitmap baseSprites;
    private final Bitmap evolvedSprites;
    private final Bitmap sceneAtlas;
    private final Bitmap environmentAtlas;
    private int species;
    private int selectedSpecies = -1;
    private int stage;
    private int hp = 100;
    private int healthScore = 100;
    private float phase;
    private float touchPulse;
    private float touchX = VW * 0.5f;
    private float touchY = VH * 0.5f;
    private float drawScale = 1f;
    private float drawLeft;
    private float drawTop;
    private float avatarCx;
    private float avatarGround;
    private long lastTouchMs = -3000;
    private boolean collectionMode;
    private int sceneMode = SCENE_IDLE;
    private int environmentMode = ENV_HOME;
    private int reaction = REACTION_CURIOUS;
    private boolean animate;

    CreatureView(Context context) {
        super(context);
        paint.setAntiAlias(false);
        textPaint.setAntiAlias(false);
        spritePaint.setAntiAlias(false);
        spritePaint.setFilterBitmap(false);
        spritePaint.setDither(false);
        scenePaint.setFilterBitmap(true);
        scenePaint.setDither(true);
        if (sharedBaseSprites == null) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            sharedBaseSprites = BitmapFactory.decodeResource(
                    getResources(), R.drawable.creature_sprites_base, options);
            sharedEvolvedSprites = BitmapFactory.decodeResource(
                    getResources(), R.drawable.creature_sprites_evolved, options);
            sharedSceneAtlas = BitmapFactory.decodeResource(
                    getResources(), R.drawable.creature_scene_atlas, options);
            sharedEnvironmentAtlas = BitmapFactory.decodeResource(
                    getResources(), R.drawable.creature_environment_atlas, options);
        }
        baseSprites = sharedBaseSprites;
        evolvedSprites = sharedEvolvedSprites;
        sceneAtlas = sharedSceneAtlas;
        environmentAtlas = sharedEnvironmentAtlas;
        setMinimumHeight(dp(380));
        setClickable(true);
        for (int i = 0; i < tileRects.length; i++) {
            tileRects[i] = new RectF();
        }
    }

    void setCreature(int species, int stage, int hp) {
        this.species = Math.floorMod(species, CreatureCatalog.SPECIES_COUNT);
        selectedSpecies = this.species;
        this.stage = Math.max(0, Math.min(CreatureCatalog.STAGES - 1, stage));
        this.hp = Math.max(0, Math.min(100, hp));
        this.healthScore = this.hp;
        invalidate();
    }

    void setHealthScore(int score) {
        healthScore = Math.max(0, Math.min(100, score));
        invalidate();
    }

    void setCollectionMode(boolean collectionMode) {
        this.collectionMode = collectionMode;
        invalidate();
    }

    void setSceneMode(int sceneMode) {
        this.sceneMode = Math.floorMod(sceneMode, 4);
        lastTouchMs = SystemClock.uptimeMillis();
        invalidate();
    }

    void setAutoSceneMode(int sceneMode) {
        this.sceneMode = Math.floorMod(sceneMode, 4);
        invalidate();
    }

    void setEnvironmentMode(int environmentMode) {
        this.environmentMode = Math.max(ENV_HOME, Math.min(ENV_SHOP, environmentMode));
        invalidate();
    }

    int sceneModeForTest() {
        return sceneMode;
    }

    int environmentModeForTest() {
        return environmentMode;
    }

    void react(int reaction) {
        this.reaction = Math.max(REACTION_CURIOUS, Math.min(REACTION_HAPPY, reaction));
        touchX = reaction == REACTION_GREETING ? VW * 0.72f : VW * 0.5f;
        touchY = VH * 0.38f;
        lastTouchMs = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int desired = Math.max(dp(360), (int) (width * 0.68f));
        int height = resolveSize(desired, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.uptimeMillis();
        phase = (now % 120000L) / 1000f;
        float age = Math.max(0, now - lastTouchMs);
        touchPulse = age < 900 ? 1f - age / 900f : 0f;

        drawScale = Math.min(getWidth() / VW, getHeight() / VH);
        drawLeft = (getWidth() - VW * drawScale) * 0.5f;
        drawTop = (getHeight() - VH * drawScale) * 0.5f;

        canvas.save();
        canvas.translate(drawLeft, drawTop);
        canvas.scale(drawScale, drawScale);
        if (collectionMode) drawArcade(canvas);
        else drawHeroArcade(canvas);
        canvas.restore();
        if (animate) postInvalidateDelayed(33);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animate = getWindowVisibility() == VISIBLE;
        if (animate) invalidate();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        animate = visibility == VISIBLE;
        if (animate) invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        animate = false;
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            float lx = (event.getX() - drawLeft) / Math.max(0.001f, drawScale);
            float ly = (event.getY() - drawTop) / Math.max(0.001f, drawScale);
            touchX = clamp(lx, 0, VW);
            touchY = clamp(ly, 0, VH);
            if (collectionMode) {
                for (int i = 0; i < tileRects.length; i++) {
                    if (tileRects[i].contains(touchX, touchY)) {
                        selectedSpecies = speciesForTile(i);
                        break;
                    }
                }
            } else reaction = touchX >= 92 && touchX <= 244 && touchY >= 22 && touchY <= 151
                    ? REACTION_HAPPY : REACTION_CURIOUS;
            lastTouchMs = SystemClock.uptimeMillis();
            performClick();
            invalidate();
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void drawArcade(Canvas canvas) {
        int selectedStage = selectedSpecies == species ? stage : Math.max(0, stage - 1);
        int selectedHp = selectedSpecies == species ? hp : 100;
        int accent = CreatureCatalog.accentColor(selectedSpecies, selectedStage);

        r(canvas, 0, 0, VW, VH, 0xFF06080D);
        drawMetalFrame(canvas, accent);
        drawSelectorGrid(canvas);
        drawFeaturedPanel(canvas, selectedStage, selectedHp);
        drawBottomControls(canvas);
        drawTouchFx(canvas, accent);
    }

    private void drawHeroArcade(Canvas canvas) {
        int accent = CreatureCatalog.accentColor(species, stage);
        int base = CreatureCatalog.baseColor(species);
        r(canvas, 0, 0, VW, VH, 0xFF06080D);
        r(canvas, 5, 5, 310, 200, 0xFF10141E);
        frame(canvas, 5, 5, 310, 200, 3, 0xFF242C39);
        frame(canvas, 10, 10, 300, 190, 2, withAlpha(accent, 235));
        r(canvas, 15, 15, 290, 137, 0xFF0B111A);
        frame(canvas, 15, 15, 290, 137, 1, withAlpha(accent, 150 + pulse(40)));
        drawSceneEnvironment(canvas, sceneMode, accent);
        drawLivingBackdrop(canvas, accent, base, species, stage);
        drawSceneBack(canvas, sceneMode, accent, base, species, stage);
        float lift = sceneMode == SCENE_SLEEP ? 1.2f : (sceneMode == SCENE_EAT ? (float) Math.sin(phase * 8f) * 1.5f : 0f);
        float left = stage >= 4 ? 44 : 61;
        float right = stage >= 4 ? 276 : 259;
        float top = stage >= 4 ? 16 : 28;
        drawSpriteAvatar(canvas, species, stage, left, top + lift - touchPulse * 5, right, 149 + lift - touchPulse * 5, true, true);
        drawSceneFront(canvas, sceneMode, accent, base, species, stage);
        drawHealthState(canvas, accent);

        r(canvas, 18, 153, 284, 39, 0xE6070A10);
        txt(canvas, CreatureCatalog.name(species), 28, 170, 11, 0xFFFFFFFF, true, Paint.Align.LEFT);
        txt(canvas, healthLabel() + " | " + CreatureCatalog.stageLabel(stage), 292, 170, 6.2f, 0xFFB9D7F6, false, Paint.Align.RIGHT);
        segmentedBar(canvas, 72, 176, 170, 5, 14, hp / 100f, 0xFFE9473F);
        segmentedBar(canvas, 72, 184, 170, 5, 14, (stage + 1) / 8f, 0xFF21B8FF);
        drawTinyIcon(canvas, 36, 179, 0xFFE9473F, 0);
        drawTinyIcon(canvas, 52, 187, 0xFF21D8FF, 1);
        for (int i = 0; i < 6; i++) {
            int color = elementColor(i);
            float cx = 254 + i * 8f;
            r(canvas, cx - 3, 181, 6, 6, 0xFF101721);
            frame(canvas, cx - 3, 181, 6, 6, 1, withAlpha(color, i == Math.floorMod(species, 6) ? 255 : 130));
        }
        drawTouchFx(canvas, accent);
    }

    private void drawHealthState(Canvas canvas, int accent) {
        if (healthScore >= 90) {
            frame(canvas, 35, 26, 250, 118, 2, withAlpha(0xFFFFE17A, 115 + pulse(80)));
            drawSpark(canvas, 74 + (float) Math.sin(phase * 3f) * 10f, 48, 2.2f, 0xFFFFE17A);
            drawSpark(canvas, 247 + (float) Math.cos(phase * 2.4f) * 8f, 68, 1.8f, 0xFFFFFFFF);
            txt(canvas, "FORTIFICATO", 160, 32, 7, withAlpha(0xFFFFE17A, 190), true, Paint.Align.CENTER);
        } else if (healthScore < 15) {
            r(canvas, 20, 18, 280, 130, withAlpha(0xFF243044, 120));
            for (int i = 0; i < 4; i++) {
                drawSickBubble(canvas, 72 + i * 48, 54 + (float) Math.sin(phase * 2f + i) * 11, 0xFF86D984);
            }
            txt(canvas, "CRITICO", 160, 32, 8, withAlpha(0xFFFF6A3D, 215), true, Paint.Align.CENTER);
        } else if (healthScore < 30) {
            r(canvas, 20, 18, 280, 130, withAlpha(0xFF263044, 105));
            drawSickBubble(canvas, 91, 61 + (float) Math.sin(phase * 2f) * 8, 0xFF8DCD6A);
            drawSickBubble(canvas, 232, 78 + (float) Math.cos(phase * 1.8f) * 7, 0xFF8DCD6A);
            drawSickBubble(canvas, 168, 48 + (float) Math.sin(phase * 2.4f) * 6, 0xFFFF6A3D);
            txt(canvas, "MALATO SERIO", 160, 32, 7, withAlpha(0xFFFF6A3D, 210), true, Paint.Align.CENTER);
        } else if (healthScore < 45) {
            r(canvas, 20, 18, 280, 130, withAlpha(0xFF20383B, 82));
            drawSickBubble(canvas, 91, 61 + (float) Math.sin(phase * 2f) * 8, 0xFF8DCD6A);
            drawSickBubble(canvas, 232, 78 + (float) Math.cos(phase * 1.8f) * 7, 0xFF8DCD6A);
            txt(canvas, "MALATO LIEVE", 160, 32, 7, withAlpha(0xFFFFC533, 205), true, Paint.Align.CENTER);
        } else if (healthScore < 60) {
            r(canvas, 20, 18, 280, 130, withAlpha(0xFF1C2837, 48));
            drawSickBubble(canvas, 246, 68 + (float) Math.sin(phase * 2.3f) * 6, 0xFF9AB2C9);
            txt(canvas, "AFFATICATO", 160, 32, 7, withAlpha(accent, 165), true, Paint.Align.CENTER);
        }
    }

    private String healthLabel() {
        if (healthScore >= 90) return "Fortificato";
        if (healthScore >= 75) return "Sano";
        if (healthScore >= 60) return "Stabile";
        if (healthScore >= 45) return "Affaticato";
        if (healthScore >= 30) return "Malato lieve";
        if (healthScore >= 15) return "Malato serio";
        return "Critico";
    }

    private void drawSickBubble(Canvas canvas, float cx, float cy, int color) {
        drawOrb(canvas, cx, cy, 3.4f, withAlpha(color, 180));
        r(canvas, cx - 1, cy - 5, 2, 10, withAlpha(0xFFFFFFFF, 90));
    }

    private void drawSceneEnvironment(Canvas canvas, int scene, int accent) {
        Bitmap atlas = sceneAtlas;
        int index = Math.floorMod(scene, 4);
        if (environmentMode != ENV_HOME && environmentAtlas != null && !environmentAtlas.isRecycled()) {
            atlas = environmentAtlas;
            if (environmentMode == ENV_NATURE) index = scene == SCENE_SLEEP ? 1 : 0;
            else if (environmentMode == ENV_CITY) index = 2;
            else index = 3;
        }
        if (atlas == null || atlas.isRecycled()) return;
        int cellWidth = atlas.getWidth() / 2;
        int cellHeight = atlas.getHeight() / 2;
        int col = index % 2;
        int row = index / 2;
        int cellLeft = col * cellWidth;
        int cellTop = row * cellHeight;
        int insetX = Math.max(2, cellWidth / 80);
        int drift = Math.round((float) Math.sin(phase * 0.22f + index) * Math.max(1, cellWidth / 220f));
        int cropTop = cellTop + Math.round(cellHeight * 0.22f);
        int cropBottom = cellTop + Math.round(cellHeight * 0.70f);
        sceneSrc.set(cellLeft + insetX + drift, cropTop,
                cellLeft + cellWidth - insetX + drift, cropBottom);
        sceneDst.set(16, 16, 304, 148);
        canvas.drawBitmap(atlas, sceneSrc, sceneDst, scenePaint);

        int atmosphere = scene == SCENE_SLEEP ? 0xFF07152E
                : (scene == SCENE_EAT ? 0xFFFFA640 : (scene == SCENE_MONEY ? 0xFFFFC533 : accent));
        r(canvas, 16, 16, 288, 132, withAlpha(atmosphere, index == SCENE_SLEEP ? 42 : 18));
        r(canvas, 18, 141, 284, 7, withAlpha(0xFF05070B, 120));
        r(canvas, 18, 141, 284, 1, withAlpha(accent, 105 + pulse(45)));
    }

    private void drawLivingBackdrop(Canvas canvas, int accent, int base, int sp, int shownStage) {
        int variant = Math.floorMod(sp, 6);
        if (sceneAtlas == null) {
            int room = darken(base, 0.68f);
            int floor = darken(base, 0.78f);
            r(canvas, 18, 18, 284, 80, room);
            r(canvas, 18, 98, 284, 51, floor);
            for (int y = 25; y < 94; y += 12) {
                r(canvas, 24, y, 272, 1, withAlpha(lighten(base, 0.25f), 36));
            }
            for (int x = 25; x < 296; x += 28) {
                r(canvas, x, 100, 1, 44, withAlpha(accent, 35));
            }
            r(canvas, 33, 30, 58, 37, 0xAA050A12);
            frame(canvas, 33, 30, 58, 37, 1, withAlpha(accent, 120));
            for (int i = 0; i < 5; i++) {
                int c = i % 2 == 0 ? withAlpha(0xFFFFC533, 150) : withAlpha(0xFF2BA8FF, 130);
                r(canvas, 40 + i * 9, 37 + (i % 3) * 7, 4, 4, c);
            }
            r(canvas, 229, 28, 47, 8, withAlpha(accent, 110 + pulse(60)));
            r(canvas, 238, 41, 30, 5, withAlpha(0xFFFFFFFF, 55));
        }
        if (variant == 0) {
            for (int i = 0; i < 4; i++) drawCoin(canvas, 42 + i * 12, 79 + (float) Math.sin(phase * 2 + i) * 3, 3.5f, 0xFFFFC533);
        } else if (variant == 1) {
            for (int i = 0; i < 5; i++) drawOrb(canvas, 52 + i * 18, 80 + (float) Math.sin(phase * 2.2f + i) * 7, 2.5f, 0xFF37C9FF);
        } else if (variant == 2) {
            for (int i = 0; i < 4; i++) leaf(canvas, 46 + i * 16, 82 + (i % 2) * 5, 0xFF66D94E, 0.7f);
        } else if (variant == 3) {
            flame(canvas, 55, 86, 0.55f + (float) Math.sin(phase * 4) * 0.05f);
            flame(canvas, 77, 84, 0.42f);
        } else if (variant == 4) {
            crystal(canvas, 54, 88, 0.55f);
            crystal(canvas, 73, 82, 0.4f);
        } else {
            r(canvas, 44, 76, 55, 20, 0xFF111722);
            frame(canvas, 44, 76, 55, 20, 1, withAlpha(accent, 100));
            r(canvas, 51, 82, 13, 4, 0xFF21D8FF);
            r(canvas, 68, 82, 22, 4, 0xFFFFC533);
        }
        if (shownStage >= 4) {
            r(canvas, 62, 37, 196, 80, withAlpha(accent, 34 + pulse(24)));
            for (int i = 0; i < 5; i++) {
                drawSpark(canvas, 70 + i * 42, 47 + (float) Math.sin(phase + i) * 9, 1.5f, withAlpha(0xFFFFFFFF, 160));
            }
            frame(canvas, 23, 23, 274, 119, 1, withAlpha(0xFFFFFFFF, 45 + pulse(60)));
        }
    }

    private void drawSceneBack(Canvas canvas, int scene, int accent, int base, int sp, int shownStage) {
        int variant = Math.floorMod(sp, 6);
        float power = shownStage >= 4 ? 1.25f : 1f;
        if (sceneAtlas != null) {
            if (scene == SCENE_SLEEP) {
                txt(canvas, "Z", 220 + (phase % 2f) * 7f, 60 - (phase % 2f) * 5f, 10,
                        withAlpha(0xFFEFF6FF, 190), true, Paint.Align.CENTER);
                txt(canvas, "z", 239 + (phase % 2f) * 5f, 45 - (phase % 2f) * 4f, 7,
                        withAlpha(0xFFEFF6FF, 145), true, Paint.Align.CENTER);
            } else if (scene == SCENE_EAT) {
                drawTinyIcon(canvas, 221 + (float) Math.sin(phase * 3f) * 5f, 111,
                        variant == 3 ? 0xFFFF6A3D : accent, variant == 3 ? 3 : variant);
                drawSpark(canvas, 101, 88 + (float) Math.sin(phase * 2f) * 6f, 1.4f, 0xFFFFC533);
            } else if (scene == SCENE_MONEY) {
                drawCoinStack(canvas, 48, 118, shownStage >= 4 ? 8 : 5, 0xFFFFC533);
                drawCoinStack(canvas, 247, 115, shownStage >= 4 ? 9 : 6, 0xFFFFE17A);
            } else {
                for (int i = 0; i < 7; i++) {
                    drawSpark(canvas, 55 + i * 35, 55 + (float) Math.sin(phase * 1.5f + i) * 15,
                            1.2f, withAlpha(accent, 115));
                }
            }
            return;
        }
        if (scene == SCENE_SLEEP) {
            r(canvas, 43, 102, 108, 27, 0xFF101A2C);
            frame(canvas, 43, 102, 108, 27, 1, withAlpha(0xFF75B9FF, 140));
            r(canvas, 52, 91, 40, 18, 0xFFD7EDFF);
            r(canvas, 55, 94, 34, 12, 0xFF91AFC9);
            drawMoon(canvas, 252, 55, 11);
            txt(canvas, "Z", 216 + (phase % 2f) * 7f, 58 - (phase % 2f) * 5f, 9, withAlpha(0xFFEFF6FF, 170), true, Paint.Align.CENTER);
            txt(canvas, "z", 232 + (phase % 2f) * 5f, 45 - (phase % 2f) * 4f, 7, withAlpha(0xFFEFF6FF, 135), true, Paint.Align.CENTER);
            if (shownStage >= 4) txt(canvas, "Z", 197, 37, 12, withAlpha(accent, 180), true, Paint.Align.CENTER);
        } else if (scene == SCENE_EAT) {
            r(canvas, 43, 118, 234, 13, 0xFF5E3E25);
            r(canvas, 51, 129, 8, 18, 0xFF342015);
            r(canvas, 260, 129, 8, 18, 0xFF342015);
            r(canvas, 226, 92, 32, 22, 0xFF1A2632);
            frame(canvas, 226, 92, 32, 22, 1, withAlpha(accent, 130));
            drawBowl(canvas, 160, 113, accent);
            if (variant == 1) drawOrb(canvas, 196, 111, 4f * power, 0xFF37C9FF);
            else if (variant == 2) leaf(canvas, 198, 111, 0xFF66D94E, 0.75f * power);
            else if (variant == 3) flame(canvas, 199, 117, 0.45f * power);
            else drawCoin(canvas, 198, 111, 4f * power, 0xFFFFC533);
        } else if (scene == SCENE_MONEY) {
            r(canvas, 42, 116, 236, 11, 0xFF2B2420);
            r(canvas, 52, 126, 10, 20, 0xFF18120E);
            r(canvas, 259, 126, 10, 20, 0xFF18120E);
            drawCoinStack(canvas, 56, 106, shownStage >= 4 ? 8 : 5, 0xFFFFC533);
            drawCoinStack(canvas, 238, 102, shownStage >= 4 ? 10 : 7, 0xFFFFC533);
            r(canvas, 223, 75, 44, 29, 0xFF111722);
            frame(canvas, 223, 75, 44, 29, 1, withAlpha(0xFF21D8FF, 120));
            r(canvas, 230, 82, 30, 7, 0xFF21D8FF);
        } else {
            r(canvas, 48, 122, 224, 9, 0xFF14202B);
            frame(canvas, 48, 122, 224, 9, 1, withAlpha(accent, 120));
            for (int i = 0; i < 7; i++) {
                drawSpark(canvas, 55 + i * 35, 55 + (float) Math.sin(phase * 1.5f + i) * 15, 1.2f, withAlpha(accent, 115));
            }
        }
    }

    private void drawSceneFront(Canvas canvas, int scene, int accent, int base, int sp, int shownStage) {
        int variant = Math.floorMod(sp, 6);
        float power = shownStage >= 4 ? 1.25f : 1f;
        if (sceneAtlas != null) {
            if (scene == SCENE_SLEEP) {
                drawSpark(canvas, 202 + (float) Math.sin(phase * 1.2f) * 7f, 96, 1.5f,
                        withAlpha(0xFFDDEAFF, 150));
            } else if (scene == SCENE_EAT) {
                drawBowl(canvas, 160, 139, accent);
                drawOrb(canvas, 151 + (float) Math.sin(phase * 1.8f) * 3f, 124, 1.3f,
                        withAlpha(0xFFFFFFFF, 105));
                drawOrb(canvas, 164 + (float) Math.cos(phase * 1.6f) * 3f, 119, 1.1f,
                        withAlpha(0xFFFFFFFF, 85));
            } else if (scene == SCENE_MONEY) {
                int coins = shownStage >= 4 ? 10 : 6;
                for (int i = 0; i < coins; i++) {
                    float x = 66 + i * (180f / Math.max(1, coins - 1));
                    float y = 105 + (float) Math.sin(phase * (4f + variant * 0.25f) + i) * (6f * power);
                    drawCoin(canvas, x, y, 4.5f, 0xFFFFC533);
                }
            } else {
                drawSpark(canvas, 98 + (float) Math.sin(phase * 3f) * 12f, 64, 1.8f, 0xFFFFE17A);
                drawCoin(canvas, 225 + (float) Math.sin(phase * 2.6f) * 10f, 74, 5f, 0xFFFFC533);
                if (shownStage >= 4) {
                    drawSpark(canvas, 228 + (float) Math.sin(phase * 3.3f) * 13f, 101, 2.1f,
                            withAlpha(0xFFFFFFFF, 180));
                }
            }
            r(canvas, 79, 143, 163, 5, 0xAA000000);
            return;
        }
        if (scene == SCENE_SLEEP) {
            r(canvas, 76, 119, 166, 26, darken(base, 0.18f));
            r(canvas, 76, 119, 166, 8, lighten(base, 0.15f));
            frame(canvas, 76, 119, 166, 26, 1, withAlpha(accent, 180));
            r(canvas, 96, 129 + (float) Math.sin(phase * 2f) * 1.5f, 126, 4, withAlpha(0xFFFFFFFF, 45));
        } else if (scene == SCENE_EAT) {
            drawBowl(canvas, 165, 136, accent);
            r(canvas, 118, 128, 88, 11, 0xFF6A4327);
            r(canvas, 124, 139, 8, 9, 0xFF2B1A11);
            r(canvas, 194, 139, 8, 9, 0xFF2B1A11);
            drawTinyIcon(canvas, 220 + (float) Math.sin(phase * 3f) * 5f, 116, variant == 3 ? 0xFFFF6A3D : accent, variant == 3 ? 3 : variant);
            drawSpark(canvas, 102, 102 + (float) Math.sin(phase * 2f) * 5f, 1.3f, 0xFFFFC533);
        } else if (scene == SCENE_MONEY) {
            int coins = shownStage >= 4 ? 10 : 6;
            for (int i = 0; i < coins; i++) {
                float x = 66 + i * (180f / Math.max(1, coins - 1));
                float y = 105 + (float) Math.sin(phase * (4f + variant * 0.25f) + i) * (6f * power);
                drawCoin(canvas, x, y, 4.5f, 0xFFFFC533);
            }
            drawCoinStack(canvas, 94, 135, shownStage >= 4 ? 9 : 6, 0xFFFFC533);
            drawCoinStack(canvas, 210, 132, shownStage >= 4 ? 7 : 4, 0xFFFFE17A);
        } else {
            drawSpark(canvas, 98 + (float) Math.sin(phase * 3f) * 12f, 64, 1.8f, 0xFFFFE17A);
            drawCoin(canvas, 225 + (float) Math.sin(phase * 2.6f) * 10f, 74, 5f, 0xFFFFC533);
            drawOrb(canvas, 74, 84 + (float) Math.sin(phase * 2.2f) * 7f, 4f, lighten(accent, 0.25f));
            if (shownStage >= 4) {
                drawSpark(canvas, 228 + (float) Math.sin(phase * 3.3f) * 13f, 101, 2.1f, withAlpha(0xFFFFFFFF, 180));
                drawSpark(canvas, 91, 112 + (float) Math.cos(phase * 2.7f) * 10f, 1.7f, withAlpha(accent, 210));
            }
        }
        r(canvas, 79, 143, 163, 5, 0xAA000000);
    }

    private void drawMoon(Canvas canvas, float cx, float cy, float r) {
        drawOrb(canvas, cx, cy, r, 0xFFDDEAFF);
        drawOrb(canvas, cx + r * 0.38f, cy - r * 0.18f, r * 0.82f, 0xFF0B111A);
    }

    private void drawBowl(Canvas canvas, float cx, float cy, int accent) {
        r(canvas, cx - 16, cy - 4, 32, 8, 0xFFDFE8F1);
        r(canvas, cx - 13, cy, 26, 8, 0xFF9CAFC2);
        r(canvas, cx - 9, cy - 8, 18, 5, withAlpha(accent, 210));
        r(canvas, cx + 20, cy - 11, 2, 16, 0xFFD8E2EA);
        r(canvas, cx + 18, cy - 12, 8, 2, 0xFFD8E2EA);
    }

    private void drawCoinStack(Canvas canvas, float x, float y, int count, int color) {
        for (int i = 0; i < count; i++) {
            r(canvas, x, y - i * 4, 20, 4, darken(color, 0.12f));
            r(canvas, x + 2, y - i * 4, 16, 1, lighten(color, 0.35f));
        }
    }

    private void drawMetalFrame(Canvas canvas, int accent) {
        r(canvas, 2, 2, 316, 206, 0xFF0D1119);
        frame(canvas, 4, 4, 312, 202, 3, 0xFF1F2532);
        frame(canvas, 7, 7, 306, 196, 1, 0xFF303746);
        frame(canvas, 9, 9, 190, 145, 2, 0xFF161C27);
        frame(canvas, 202, 8, 110, 194, 2, 0xFF1B202C);
        frame(canvas, 205, 11, 104, 188, 2, withAlpha(accent, 220));
        r(canvas, 14, 5, 42, 5, 0xFF171D29);
        r(canvas, 62, 5, 30, 5, 0xFF1D2431);
        r(canvas, 101, 3, 40, 7, 0xFF252A35);
        r(canvas, 248, 4, 50, 5, 0xFF171D29);
        r(canvas, 210, 14, 6, 35, withAlpha(accent, 120));
        r(canvas, 299, 17, 5, 32, withAlpha(accent, 130));
        for (int x = 18; x < 190; x += 24) {
            r(canvas, x, 201, 10, 3, x % 48 == 18 ? 0xFFFFB32C : 0xFF1E95FF);
        }
    }

    private void drawSelectorGrid(Canvas canvas) {
        int start = pageStart();
        float x0 = 14;
        float y0 = 15;
        float tile = 42;
        float gap = 4;
        for (int i = 0; i < 12; i++) {
            int sp = (start + i) % CreatureCatalog.SPECIES_COUNT;
            int col = i % 4;
            int row = i / 4;
            float x = x0 + col * (tile + gap);
            float y = y0 + row * (tile + gap);
            tileRects[i].set(x, y, x + tile, y + tile);
            drawTile(canvas, x, y, tile, sp, sp == selectedSpecies);
        }
    }

    private void drawTile(Canvas canvas, float x, float y, float size, int sp, boolean selected) {
        int tileStage = sp == species ? stage : Math.max(0, stage - 2);
        int base = CreatureCatalog.baseColor(sp);
        int accent = CreatureCatalog.accentColor(sp, tileStage);
        int border = selected ? 0xFFFFCA3A : accent;
        int glow = selected ? withAlpha(0xFFFFCA3A, 80 + pulse(80)) : withAlpha(accent, 65);

        r(canvas, x, y, size, size, 0xFF0A0F18);
        frame(canvas, x, y, size, size, 2, border);
        frame(canvas, x + 3, y + 3, size - 6, size - 6, 1, glow);
        r(canvas, x + 5, y + 5, size - 10, size - 10, withAlpha(darken(base, 0.55f), 170));
        for (int gy = 8; gy < size - 8; gy += 7) {
            r(canvas, x + 7, y + gy, size - 14, 1, 0x222D4054);
        }
        drawSpriteAvatar(canvas, sp, tileStage, x + 4, y + 5, x + size - 4, y + size - 4, selected && sp == selectedSpecies, false);
        if (selected) {
            drawSpark(canvas, x + 7, y + 8, 1.1f, 0xFFFFE27A);
            drawSpark(canvas, x + size - 7, y + size - 9, 0.9f, 0xFFFFFFFF);
        }
    }

    private void drawFeaturedPanel(Canvas canvas, int shownStage, int shownHp) {
        int sp = selectedSpecies;
        int base = CreatureCatalog.baseColor(sp);
        int accent = CreatureCatalog.accentColor(sp, shownStage);

        r(canvas, 209, 15, 96, 181, 0xFF080B12);
        r(canvas, 212, 18, 90, 116, 0xFF111A26);
        frame(canvas, 212, 18, 90, 116, 1, withAlpha(accent, 180));
        for (int y = 24; y < 128; y += 8) {
            r(canvas, 216, y, 82, 1, 0x26375366);
        }

        r(canvas, 227, 125, 52, 4, 0xBB000000);
        int aura = withAlpha(accent, 42 + pulse(30));
        r(canvas, 225, 30, 63, 79, aura);
        drawSpriteAvatar(canvas, sp, shownStage, 214, 22 - touchPulse * 3, 300, 129 - touchPulse * 3, true, true);

        txt(canvas, CreatureCatalog.name(sp), 217, 143, 8, 0xFFFFFFFF, true, Paint.Align.LEFT);
        txt(canvas, CreatureCatalog.stageLabel(shownStage), 298, 143, 5.5f, 0xFFB9D7F6, false, Paint.Align.RIGHT);
        segmentedBar(canvas, 227, 150, 69, 5, 12, shownHp / 120f, 0xFFE9473F);
        segmentedBar(canvas, 227, 159, 69, 5, 12, (shownStage + 1) / 8f, 0xFF21B8FF);
        segmentedBar(canvas, 227, 168, 69, 5, 12, 0.55f + shownStage * 0.04f, 0xFFFFC335);
        drawTinyIcon(canvas, 217, 152, 0xFFE9473F, 0);
        drawTinyIcon(canvas, 217, 161, 0xFF21D8FF, 1);
        drawTinyIcon(canvas, 217, 170, 0xFFFFC335, 2);

        for (int i = 0; i < 6; i++) {
            int color = elementColor(i);
            float cx = 224 + i * 14.5f;
            r(canvas, cx - 5, 183, 10, 10, 0xFF101721);
            frame(canvas, cx - 5, 183, 10, 10, 1, withAlpha(color, i == Math.floorMod(sp, 6) ? 255 : 135));
            drawTinyIcon(canvas, cx, 188, color, i);
        }
    }

    private void drawBottomControls(Canvas canvas) {
        r(canvas, 9, 158, 190, 43, 0xFF090D14);
        for (int i = 0; i < 6; i++) {
            float x = 58 + i * 23;
            r(canvas, x, 163, 18, 28, 0xFF111722);
            frame(canvas, x, 163, 18, 28, 1, 0xFF272F3E);
            r(canvas, x + 5, 172, 8, 9, 0xFF070A0F);
        }
        drawJoystick(canvas, 29, 184, 0xFFE8473F);
        drawJoystick(canvas, 184, 184, 0xFF3C93FF);
        for (int i = 0; i < 5; i++) {
            int color = i == 3 ? 0xFF20A7FF : 0xFFFF9D20;
            r(canvas, 70 + i * 22, 198, 8, 3, color);
        }
    }

    private void drawJoystick(Canvas canvas, float cx, float cy, int color) {
        r(canvas, cx - 12, cy + 8, 24, 6, 0xFF202635);
        r(canvas, cx - 2, cy - 14, 4, 24, 0xFF5B6474);
        r(canvas, cx - 8, cy - 20, 16, 16, darken(color, 0.18f));
        r(canvas, cx - 5, cy - 23, 10, 10, color);
        r(canvas, cx - 3, cy - 21, 4, 4, lighten(color, 0.45f));
    }

    private void drawSpriteAvatar(Canvas canvas, int sp, int spriteStage, float left, float top, float right, float bottom, boolean animated, boolean featured) {
        Bitmap sprite = spriteFor(sp, spriteStage);
        if (sprite == null || sprite.isRecycled()) {
            drawPixelAvatar(canvas, sp, spriteStage, hp, (left + right) * 0.5f, bottom - 3, featured ? 1.55f : 0.62f, animated);
            return;
        }

        spriteSrc.set(0, 0, sprite.getWidth(), sprite.getHeight());

        float boxW = right - left;
        float boxH = bottom - top;
        float srcAspect = spriteSrc.width() / (float) spriteSrc.height();
        float dstW = boxW;
        float dstH = dstW / srcAspect;
        if (dstH > boxH) {
            dstH = boxH;
            dstW = dstH * srcAspect;
        }
        float tempo = sceneMode == SCENE_SLEEP ? 1.1f : (sceneMode == SCENE_EAT ? 7.5f : (sceneMode == SCENE_MONEY ? 4.8f : 2.2f));
        float amplitude = sceneMode == SCENE_SLEEP ? 0.9f : (sceneMode == SCENE_EAT ? 2.6f : (sceneMode == SCENE_MONEY ? 3.2f : 2.2f));
        if (!featured) {
            tempo = 2.2f;
            amplitude = 0.8f;
        }
        float reactionLift = reaction == REACTION_HAPPY ? 9f : reaction == REACTION_GREETING ? 5f : 2.5f;
        float bob = animated ? (float) Math.sin(phase * tempo + sp) * amplitude -
                touchPulse * (featured ? reactionLift : 1.2f) : 0f;
        float evoScale = featured && spriteStage >= 4 ? 1.07f : 1f;
        float scale = featured ? (evoScale + touchPulse * 0.045f) : 1f;
        float cx = (left + right) * 0.5f;
        float dy = bottom - dstH + bob;
        spriteDst.set(cx - dstW * 0.5f, dy, cx + dstW * 0.5f, dy + dstH);
        if (scale != 1f) {
            float sw = spriteDst.width() * scale;
            float sh = spriteDst.height() * scale;
            spriteDst.set(cx - sw * 0.5f, spriteDst.bottom - sh, cx + sw * 0.5f, spriteDst.bottom);
        }
        avatarCx = spriteDst.centerX();
        avatarGround = spriteDst.bottom;

        float rotation = 0f;
        float livingScaleX = 1f;
        float livingScaleY = 1f;
        float livingDriftX = 0f;
        if (featured) {
            if (sceneMode == SCENE_SLEEP) {
                float breath = (float) Math.sin(phase * 1.15f + sp * 0.2f);
                rotation = -13f + breath * 0.8f;
                livingScaleX = 1.08f + breath * 0.018f;
                livingScaleY = 0.78f - breath * 0.012f;
                livingDriftX = 27f;
            } else if (sceneMode == SCENE_EAT) {
                rotation = (float) Math.sin(phase * 7.5f + sp) * 3.0f;
                livingScaleY = 1f + (float) Math.sin(phase * 7.5f + sp) * 0.026f;
            } else if (sceneMode == SCENE_MONEY) {
                rotation = (float) Math.sin(phase * 4.8f + sp) * 2.0f;
                livingDriftX = (float) Math.sin(phase * 2.4f + sp) * 3.2f;
            } else {
                rotation = (float) Math.sin(phase * 1.55f + sp) * 1.65f;
                livingScaleY = 1f + (float) Math.sin(phase * 2.1f + sp) * 0.017f;
            }
            float reactionSide = touchX < cx ? 1f : -1f;
            float reactionTilt = reaction == REACTION_GREETING ? 8f : reaction == REACTION_CURIOUS ? 5f : 2.5f;
            rotation += reactionSide * touchPulse * reactionTilt;
            livingScaleY += touchPulse * (reaction == REACTION_HAPPY ? 0.055f : 0.025f);
            livingScaleX += touchPulse * (reaction == REACTION_HAPPY ? 0.025f : 0f);
            spriteDst.offset(livingDriftX, 0f);
        }

        float shadowCx = spriteDst.centerX();
        float shadowHalf = spriteDst.width() * (sceneMode == SCENE_SLEEP ? 0.31f : 0.25f);
        shadowRect.set(shadowCx - shadowHalf, spriteDst.bottom - 3.5f,
                shadowCx + shadowHalf, spriteDst.bottom + 2.5f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(0xFF000000, sceneMode == SCENE_SLEEP ? 115 : 86));
        canvas.drawOval(shadowRect, paint);

        if (featured && collectionMode) {
            int accent = CreatureCatalog.accentColor(sp, stage);
            frame(canvas, spriteDst.left - 3, spriteDst.top - 3, spriteDst.width() + 6, spriteDst.height() + 6, 1, withAlpha(accent, 80 + pulse(70)));
        }
        int save = canvas.save();
        canvas.rotate(rotation, spriteDst.centerX(), spriteDst.bottom);
        canvas.scale(livingScaleX, livingScaleY, spriteDst.centerX(), spriteDst.bottom);
        if (featured && animated) drawLivingSpriteMesh(canvas, sprite, spriteDst, sp, spriteStage);
        else canvas.drawBitmap(sprite, spriteSrc, spriteDst, spritePaint);
        canvas.restoreToCount(save);
    }

    private void drawLivingSpriteMesh(Canvas canvas, Bitmap sprite, RectF destination,
                                      int sp, int spriteStage) {
        float width = destination.width();
        float height = destination.height();
        float t = phase + sp * 0.37f;
        float stageEnergy = 0.82f + Math.min(7, spriteStage) * 0.035f;
        float healthEnergy = 0.55f + healthScore / 220f;
        float breath = (float) Math.sin(t * (sceneMode == SCENE_SLEEP ? 1.15f : 2.05f));
        float headSway = (float) Math.sin(t * 1.42f) * stageEnergy * healthEnergy;
        float gait = (float) Math.sin(t * 5.4f);
        float action = (float) Math.sin(t * (sceneMode == SCENE_EAT ? 7.2f : 4.8f));
        boolean walking = sceneMode == SCENE_IDLE &&
                (environmentMode == ENV_NATURE || environmentMode == ENV_CITY);
        float autonomous = 0f;
        if (sceneMode == SCENE_IDLE && touchPulse <= 0) {
            float moment = (t % 8.5f + 8.5f) % 8.5f;
            if (moment > 6.8f) autonomous = (float) Math.sin((moment - 6.8f) / 1.7f * Math.PI);
            headSway += autonomous * ((sp & 1) == 0 ? 1.45f : -1.45f);
        }

        float blinkCycle = (t % 4.6f + 4.6f) % 4.6f;
        float blink = blinkCycle > 4.36f
                ? (float) Math.sin((blinkCycle - 4.36f) / 0.24f * Math.PI)
                : 0f;
        if (sceneMode == SCENE_SLEEP) blink = 1f;

        int index = 0;
        for (int y = 0; y <= LIVE_MESH_Y; y++) {
            float ny = y / (float) LIVE_MESH_Y;
            float headWeight = smoothClamp((0.62f - ny) / 0.50f);
            float torsoWeight = 1f - Math.min(1f, Math.abs(ny - 0.56f) / 0.34f);
            float feetWeight = smoothClamp((ny - 0.64f) / 0.30f);
            for (int x = 0; x <= LIVE_MESH_X; x++) {
                float nx = x / (float) LIVE_MESH_X;
                float px = destination.left + nx * width;
                float py = destination.top + ny * height;

                px += headSway * width * 0.010f * headWeight;
                py += (nx - 0.5f) * headSway * height * 0.016f * headWeight;
                px += (nx - 0.5f) * breath * width * 0.020f * torsoWeight;
                py -= Math.abs(nx - 0.5f) * breath * height * 0.009f * torsoWeight;
                py -= autonomous * height * 0.008f * headWeight;

                if (walking) {
                    float side = nx < 0.5f ? -1f : 1f;
                    px += side * gait * width * 0.027f * feetWeight;
                    py += side * gait * height * 0.022f * feetWeight;
                    py -= Math.abs(gait) * height * 0.015f * torsoWeight;
                } else if (sceneMode == SCENE_EAT) {
                    boolean activeSide = (sp & 1) == 0 ? nx > 0.58f : nx < 0.42f;
                    float hand = activeSide ? torsoWeight * smoothClamp((0.78f - ny) / 0.34f) : 0f;
                    py -= (action + 1f) * height * 0.019f * hand;
                    px += ((sp & 1) == 0 ? -1f : 1f) * action * width * 0.017f * hand;
                } else if (sceneMode == SCENE_MONEY) {
                    boolean activeSide = (sp & 1) == 0 ? nx < 0.46f : nx > 0.54f;
                    float hand = activeSide ? torsoWeight * smoothClamp((0.80f - ny) / 0.38f) : 0f;
                    py -= (action + 1f) * height * 0.023f * hand;
                    px += action * width * 0.020f * hand;
                } else if (sceneMode == SCENE_SLEEP) {
                    px += (nx - 0.5f) * breath * width * 0.027f;
                    py += breath * height * 0.009f * (1f - feetWeight);
                }

                if (blink > 0 && ny >= 0.22f && ny <= 0.50f) {
                    float faceCenter = 0.36f;
                    py += (faceCenter - ny) * height * 0.20f * blink;
                }

                if (healthScore < 45) {
                    float weakness = (45 - healthScore) / 45f;
                    px += (float) Math.sin(phase * 13f + y * 0.7f) * width * 0.004f * weakness * headWeight;
                    py += height * 0.016f * weakness * headWeight;
                }

                if (touchPulse > 0) {
                    float side = touchX < avatarCx ? -1f : 1f;
                    px += side * touchPulse * width * 0.016f * headWeight;
                    if (reaction == REACTION_GREETING) {
                        boolean waveSide = side < 0 ? nx > 0.56f : nx < 0.44f;
                        float wave = waveSide ? torsoWeight * smoothClamp((0.82f - ny) / 0.42f) : 0f;
                        py -= (float) Math.sin((1f - touchPulse) * 11f) * height * 0.025f * wave;
                    } else if (reaction == REACTION_HAPPY) {
                        py -= touchPulse * height * 0.020f * (1f - feetWeight);
                    }
                }

                liveMesh[index++] = px;
                liveMesh[index++] = py;
            }
        }
        canvas.drawBitmapMesh(sprite, LIVE_MESH_X, LIVE_MESH_Y, liveMesh, 0,
                null, 0, spritePaint);
    }

    private float smoothClamp(float value) {
        float clamped = Math.max(0f, Math.min(1f, value));
        return clamped * clamped * (3f - 2f * clamped);
    }

    private Bitmap spriteFor(int sp, int spriteStage) {
        int index = Math.floorMod(sp, SPRITE_COLS * SPRITE_ROWS);
        Bitmap source = spriteStage >= 4 ? evolvedSprites : baseSprites;
        Bitmap[] cache = spriteStage >= 4 ? sharedEvolvedTiles : sharedBaseTiles;
        if (source == null || source.isRecycled()) return null;
        if (cache[index] == null || cache[index].isRecycled()) {
            cache[index] = extractSprite(source, index);
        }
        return cache[index];
    }

    private static Bitmap extractSprite(Bitmap sheet, int index) {
        int col = index % SPRITE_COLS;
        int row = index / SPRITE_COLS;
        int left = Math.round(col * sheet.getWidth() / (float) SPRITE_COLS);
        int right = Math.round((col + 1) * sheet.getWidth() / (float) SPRITE_COLS);
        int top = Math.round(row * sheet.getHeight() / (float) SPRITE_ROWS);
        int bottom = Math.round((row + 1) * sheet.getHeight() / (float) SPRITE_ROWS);
        int width = right - left;
        int height = bottom - top;
        int[] pixels = new int[width * height];
        sheet.getPixels(pixels, 0, width, left, top, width, height);
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            int red = Color.red(color);
            int green = Color.green(color);
            int blue = Color.blue(color);
            int strongestOther = Math.max(red, blue);
            if (green > 220 && strongestOther < 55 && green - strongestOther > 175) {
                pixels[i] = Color.TRANSPARENT;
            }
        }
        stripForeignEdgeFragments(pixels, width, height);
        Bitmap sprite = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        sprite.setPixels(pixels, 0, width, 0, 0, width, height);
        return sprite;
    }

    private static void stripForeignEdgeFragments(int[] pixels, int width, int height) {
        boolean[] visited = new boolean[pixels.length];
        int[] queue = new int[pixels.length];
        int coreLeft = width / 4;
        int coreRight = width - coreLeft;
        int coreTop = height / 7;
        int coreBottom = height - coreTop;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int start = y * width + x;
                if (visited[start] || Color.alpha(pixels[start]) == 0) continue;
                int head = 0;
                int tail = 0;
                queue[tail++] = start;
                visited[start] = true;
                boolean touchesEdge = false;
                boolean reachesCore = false;
                while (head < tail) {
                    int index = queue[head++];
                    int px = index % width;
                    int py = index / width;
                    if (px <= 1 || px >= width - 2 || py <= 1 || py >= height - 2) touchesEdge = true;
                    if (px >= coreLeft && px < coreRight && py >= coreTop && py < coreBottom) reachesCore = true;
                    for (int oy = -1; oy <= 1; oy++) {
                        int ny = py + oy;
                        if (ny < 0 || ny >= height) continue;
                        for (int ox = -1; ox <= 1; ox++) {
                            if (ox == 0 && oy == 0) continue;
                            int nx = px + ox;
                            if (nx < 0 || nx >= width) continue;
                            int next = ny * width + nx;
                            if (!visited[next] && Color.alpha(pixels[next]) != 0) {
                                visited[next] = true;
                                queue[tail++] = next;
                            }
                        }
                    }
                }
                if (touchesEdge && !reachesCore) {
                    for (int i = 0; i < tail; i++) pixels[queue[i]] = Color.TRANSPARENT;
                }
            }
        }
    }

    private void drawPixelAvatar(Canvas canvas, int sp, int shownStage, int shownHp, float cx, float ground, float s, boolean animated) {
        avatarCx = cx;
        avatarGround = ground;
        int base = shownHp <= 0 ? 0xFF68707A : CreatureCatalog.baseColor(sp);
        int accent = shownHp <= 0 ? 0xFFAEB5BD : CreatureCatalog.accentColor(sp, shownStage);
        int dark = darken(base, 0.48f);
        int outline = 0xFF070A10;
        int bright = lighten(accent, 0.36f);
        int variant = Math.floorMod(sp, 8);
        float bob = animated ? ((float) Math.sin(phase * 2.6f + sp) * 1.3f - touchPulse * 2.8f) : 0f;
        float wave = animated ? (float) Math.sin(phase * 3.4f + sp) * 2.2f + touchPulse * 3.5f : 0f;
        ground += bob;

        block(canvas, cx - 15, ground - 2, 30, 3, s, 0xAA000000);
        if (shownStage >= 2 || variant == 0 || variant == 3) {
            block(canvas, cx - 17, ground - 37, 8, 32, s, darken(accent, 0.35f));
            block(canvas, cx - 21, ground - 30, 6, 19, s, darken(accent, 0.48f));
            block(canvas, cx + 12, ground - 35, 7, 26, s, darken(accent, 0.42f));
        }

        block(canvas, cx - 8, ground - 17, 5, 15, s, outline);
        block(canvas, cx + 3, ground - 17, 5, 15, s, outline);
        block(canvas, cx - 7, ground - 16, 4, 14, s, dark);
        block(canvas, cx + 3, ground - 16, 4, 14, s, dark);
        block(canvas, cx - 11, ground - 4, 9, 4, s, 0xFF20304E);
        block(canvas, cx + 2, ground - 4, 9, 4, s, 0xFF20304E);

        block(canvas, cx - 14, ground - 35, 28, 22, s, outline);
        block(canvas, cx - 12, ground - 33, 24, 19, s, base);
        block(canvas, cx - 10, ground - 31, 20, 5, s, lighten(base, 0.12f));
        block(canvas, cx - 13, ground - 23, 26, 5, s, dark);
        block(canvas, cx - 5, ground - 31, 10, 17, s, bright);
        block(canvas, cx - 3, ground - 24, 6, 6, s, outline);
        drawCoin(canvas, cx, ground - 21 * s, 3.2f * s, 0xFFFFCF37);

        block(canvas, cx - 18, ground - 33, 6, 5, s, accent);
        block(canvas, cx + 12, ground - 33, 6, 5, s, accent);
        block(canvas, cx - 18, ground - 29, 5, 14, s, outline);
        block(canvas, cx - 17, ground - 28, 4, 12, s, dark);
        block(canvas, cx + 14, ground - 30 - wave * 0.3f, 5, 13, s, outline);
        block(canvas, cx + 15, ground - 29 - wave * 0.3f, 4, 11, s, dark);
        block(canvas, cx - 20, ground - 17, 6, 6, s, accent);
        if (variant == 1 || variant == 5) {
            drawOrb(canvas, cx + 21 * s, ground - (23 + wave * 0.3f) * s, 4.5f * s, bright);
        } else {
            drawCoin(canvas, cx + 21 * s, ground - (23 + wave * 0.3f) * s, 4.4f * s, 0xFFFFC533);
        }

        if (variant == 0 || variant == 2 || variant == 6) {
            block(canvas, cx - 15, ground - 58, 6, 8, s, accent);
            block(canvas, cx + 9, ground - 58, 6, 8, s, accent);
            block(canvas, cx - 18, ground - 62, 5, 7, s, bright);
            block(canvas, cx + 13, ground - 62, 5, 7, s, bright);
        } else if (variant == 3 || variant == 7) {
            block(canvas, cx - 4, ground - 64, 8, 8, s, bright);
            block(canvas, cx - 8, ground - 59, 16, 5, s, accent);
        }

        block(canvas, cx - 14, ground - 54, 28, 23, s, outline);
        block(canvas, cx - 12, ground - 52, 24, 20, s, lighten(base, 0.14f));
        block(canvas, cx - 13, ground - 46, 26, 5, s, dark);
        block(canvas, cx - 8, ground - 56, 16, 5, s, outline);
        block(canvas, cx - 6, ground - 58, 5, 5, s, 0xFF1CE7FF);
        block(canvas, cx + 1, ground - 58, 5, 5, s, 0xFF1CE7FF);
        block(canvas, cx - 5, ground - 57, 3, 2, s, 0xFFFFFFFF);
        block(canvas, cx + 2, ground - 57, 3, 2, s, 0xFFFFFFFF);

        if (touchPulse > 0.2f && animated && sp == selectedSpecies) {
            drawSpark(canvas, cx - 6 * s, ground - 43 * s, 1.6f * s, 0xFFFFE17A);
            drawSpark(canvas, cx + 6 * s, ground - 43 * s, 1.6f * s, 0xFFFFE17A);
        } else {
            block(canvas, cx - 7, ground - 44, 4, 4, s, 0xFF04242C);
            block(canvas, cx + 3, ground - 44, 4, 4, s, 0xFF04242C);
            block(canvas, cx - 6, ground - 43, 2, 2, s, 0xFF6DFF8C);
            block(canvas, cx + 4, ground - 43, 2, 2, s, 0xFF6DFF8C);
        }
        block(canvas, cx - 5, ground - 37, 10, 2, s, outline);
        block(canvas, cx - 2, ground - 35, 4, 2, s, outline);

        drawElementFx(canvas, variant, cx, ground, s, bright, accent);
    }

    private void drawElementFx(Canvas canvas, int variant, float cx, float ground, float s, int bright, int accent) {
        float drift = (float) Math.sin(phase * 2.1f + variant) * 2f;
        if (variant == 1) {
            drawOrb(canvas, cx + 28 * s, ground - (47 + drift) * s, 2.8f * s, 0xFF37C9FF);
            drawOrb(canvas, cx - 23 * s, ground - (42 - drift) * s, 2.2f * s, 0xFF37C9FF);
        } else if (variant == 2) {
            leaf(canvas, cx + 24 * s, ground - 45 * s, 0xFF76E142, s);
            leaf(canvas, cx - 21 * s, ground - 38 * s, 0xFF53B83E, s);
        } else if (variant == 3) {
            flame(canvas, cx + 24 * s, ground - 43 * s, s);
        } else if (variant == 4) {
            crystal(canvas, cx - 22 * s, ground - 45 * s, s);
        } else if (variant == 5) {
            drawSpark(canvas, cx + 27 * s, ground - 49 * s, 1.6f * s, bright);
        } else {
            drawCoin(canvas, cx - 25 * s, ground - (43 + drift) * s, 3.2f * s, 0xFFFFC533);
        }
    }

    private int speciesForTile(int index) {
        return (pageStart() + index) % CreatureCatalog.SPECIES_COUNT;
    }

    private int pageStart() {
        return (species / 12) * 12;
    }

    private void segmentedBar(Canvas canvas, float x, float y, float w, float h, int segments, float fill, int color) {
        float gap = 1;
        float segW = (w - gap * (segments - 1)) / segments;
        int filled = Math.round(clamp(fill, 0, 1) * segments);
        for (int i = 0; i < segments; i++) {
            r(canvas, x + i * (segW + gap), y, segW, h, i < filled ? color : 0xFF202A35);
            r(canvas, x + i * (segW + gap), y, segW, 1, i < filled ? lighten(color, 0.35f) : 0xFF33404C);
        }
    }

    private void drawTouchFx(Canvas canvas, int accent) {
        if (touchPulse <= 0) return;
        int alpha = (int) (220 * touchPulse);
        frame(canvas, touchX - 13 - (1f - touchPulse) * 11, touchY - 13 - (1f - touchPulse) * 11,
                26 + (1f - touchPulse) * 22, 26 + (1f - touchPulse) * 22, 1, withAlpha(accent, alpha));
        for (int i = 0; i < 7; i++) {
            double a = phase * 2.4 + i * Math.PI * 2 / 7;
            float d = 12 + (1f - touchPulse) * 19;
            drawSpark(canvas, touchX + (float) Math.cos(a) * d, touchY + (float) Math.sin(a) * d * 0.7f,
                    1.3f + touchPulse * 1.2f, withAlpha(0xFFFFD35B, alpha));
        }
        String label = reaction == REACTION_GREETING ? "CIAO" : reaction == REACTION_HAPPY ? "+" : "?";
        txt(canvas, label, touchX, Math.max(18, touchY - 18), 7,
                withAlpha(0xFFFFFFFF, alpha), true, Paint.Align.CENTER);
    }

    private void drawTinyIcon(Canvas canvas, float cx, float cy, int color, int type) {
        if (type == 0) {
            r(canvas, cx - 3, cy - 2, 6, 4, color);
            r(canvas, cx - 2, cy - 4, 4, 8, color);
        } else if (type == 1) {
            r(canvas, cx - 2, cy - 4, 4, 8, color);
            r(canvas, cx - 4, cy - 1, 8, 4, lighten(color, 0.1f));
        } else if (type == 2) {
            drawCoin(canvas, cx, cy, 3.6f, color);
        } else if (type == 3) {
            flame(canvas, cx, cy + 4, 0.35f);
        } else if (type == 4) {
            drawSpark(canvas, cx, cy, 2.5f, color);
        } else {
            crystal(canvas, cx, cy + 4, 0.35f);
        }
    }

    private int elementColor(int type) {
        int[] colors = {0xFF70D34D, 0xFF23B9FF, 0xFFFF6A32, 0xFFFFD33B, 0xFFB45CFF, 0xFFC8E8FF};
        return colors[Math.floorMod(type, colors.length)];
    }

    private void drawCoin(Canvas canvas, float cx, float cy, float radius, int color) {
        rect(canvas, cx - radius, cy - radius, cx + radius, cy + radius, color);
        rect(canvas, cx - radius * 0.62f, cy - radius * 0.62f, cx + radius * 0.62f, cy + radius * 0.62f, lighten(color, 0.28f));
        rect(canvas, cx - radius * 0.18f, cy - radius * 0.48f, cx + radius * 0.18f, cy + radius * 0.48f, darken(color, 0.35f));
    }

    private void drawOrb(Canvas canvas, float cx, float cy, float radius, int color) {
        rect(canvas, cx - radius, cy - radius, cx + radius, cy + radius, darken(color, 0.2f));
        rect(canvas, cx - radius * 0.58f, cy - radius * 0.58f, cx + radius * 0.58f, cy + radius * 0.58f, color);
        rect(canvas, cx - radius * 0.45f, cy - radius * 0.45f, cx - radius * 0.05f, cy - radius * 0.05f, lighten(color, 0.45f));
    }

    private void drawSpark(Canvas canvas, float cx, float cy, float s, int color) {
        r(canvas, cx - 1.5f * s, cy - 0.5f * s, 3f * s, 1f * s, color);
        r(canvas, cx - 0.5f * s, cy - 1.5f * s, 1f * s, 3f * s, color);
        r(canvas, cx - 0.5f * s, cy - 0.5f * s, 1f * s, 1f * s, lighten(color, 0.35f));
    }

    private void flame(Canvas canvas, float cx, float cy, float s) {
        r(canvas, cx - 4 * s, cy - 12 * s, 8 * s, 11 * s, 0xFFFF5E2E);
        r(canvas, cx - 2 * s, cy - 16 * s, 4 * s, 7 * s, 0xFFFF9A2E);
        r(canvas, cx - 1 * s, cy - 9 * s, 3 * s, 6 * s, 0xFFFFE55B);
    }

    private void leaf(Canvas canvas, float cx, float cy, int color, float s) {
        r(canvas, cx - 5 * s, cy - 2 * s, 10 * s, 4 * s, darken(color, 0.15f));
        r(canvas, cx - 2 * s, cy - 5 * s, 6 * s, 10 * s, color);
        r(canvas, cx, cy - 4 * s, 1.2f * s, 8 * s, lighten(color, 0.3f));
    }

    private void crystal(Canvas canvas, float cx, float cy, float s) {
        r(canvas, cx - 4 * s, cy - 13 * s, 8 * s, 12 * s, 0xFF72D9FF);
        r(canvas, cx - 2 * s, cy - 17 * s, 4 * s, 5 * s, 0xFFC7F4FF);
        r(canvas, cx + 1 * s, cy - 12 * s, 3 * s, 10 * s, 0xFF2EA8D5);
    }

    private void block(Canvas canvas, float x, float y, float w, float h, float s, int color) {
        float left = avatarCx + (x - avatarCx) * s;
        float top = avatarGround + (y - avatarGround) * s;
        r(canvas, left, top, w * s, h * s, color);
    }

    private void r(Canvas canvas, float x, float y, float w, float h, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawRect(x, y, x + w, y + h, paint);
    }

    private void rect(Canvas canvas, float left, float top, float right, float bottom, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawRect(left, top, right, bottom, paint);
    }

    private void frame(Canvas canvas, float x, float y, float w, float h, float t, int color) {
        r(canvas, x, y, w, t, color);
        r(canvas, x, y + h - t, w, t, color);
        r(canvas, x, y, t, h, color);
        r(canvas, x + w - t, y, t, h, color);
    }

    private void txt(Canvas canvas, String value, float x, float y, float size, int color, boolean bold, Paint.Align align) {
        textPaint.setColor(color);
        textPaint.setTextSize(size);
        textPaint.setFakeBoldText(bold);
        textPaint.setTextAlign(align);
        canvas.drawText(value, x, y, textPaint);
        textPaint.setFakeBoldText(false);
    }

    private int pulse(int range) {
        return (int) (Math.max(0, Math.sin(phase * 4.2f)) * range);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int withAlpha(int color, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int lighten(int color, float amount) {
        return mix(color, Color.WHITE, amount);
    }

    private int darken(int color, float amount) {
        return mix(color, Color.BLACK, amount);
    }

    private int mix(int color, int other, float amount) {
        float a = Math.max(0, Math.min(1, amount));
        return Color.rgb(
                (int) (Color.red(color) * (1 - a) + Color.red(other) * a),
                (int) (Color.green(color) * (1 - a) + Color.green(other) * a),
                (int) (Color.blue(color) * (1 - a) + Color.blue(other) * a));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
