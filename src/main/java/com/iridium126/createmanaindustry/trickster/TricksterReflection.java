package com.iridium126.createmanaindustry.trickster;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;

/**
 * Central reflection bridge for Trickster internals.
 * <p>
 * Because Trickster is a Fabric mod loaded through Sinytra Connector, we
 * cannot reference its types at compile time.  This class discovers and caches
 * every class, field, method, and constructor the rest of the mod needs via
 * {@code Class.forName} / reflection, gated by three phased init methods:
 * <ol>
 *   <li>{@link #ensureDisplayInit()} — DisplayLink argument sync</li>
 *   <li>{@link #ensureChargeInit()}  — mana pool access + knot identification</li>
 *   <li>{@link #ensureRegisterInit()} — trick registration</li>
 * </ol>
 * <p>
 * Domain-specific operations are delegated to:
 * <ul>
 *   <li>{@link TricksterKnotUtils} — knot item/block entity checks</li>
 *   <li>{@link TricksterManaAccess} — mana read/write/transfer</li>
 *   <li>{@link TricksterDisplaySync} — DisplayLink → executor argument sync</li>
 * </ul>
 */
public final class TricksterReflection {

    // ---- init guards -----------------------------------------------------

    static volatile boolean displayInitialized;
    static volatile boolean displayAvailable;
    static volatile boolean chargeInitialized;
    static volatile boolean chargeAvailable;
    static volatile boolean registerInitialized;
    static volatile boolean registerAvailable;

    // ---- class references ------------------------------------------------

    static Class<?> spellConstructBlockEntityClass;
    static Class<?> modularSpellConstructBlockEntityClass;
    static Class<?> chargingArrayBlockEntityClass;
    static Class<?> defaultSpellExecutorClass;
    static Class<?> knotItemClass;
    static Class<?> infiniteManaPoolClass;
    static Class<?> manaClass;
    static Class<?> manaVariantClass;
    static Class<?> minecraftWorldClass;
    static Class<?> signatureClass;
    static Class<?> trickClass;
    static Class<?> vectorFragmentClass;
    static Class<?> numberFragmentClass;

    // ---- constructors & singletons ---------------------------------------

    static Constructor<?> stringFragmentCtor;
    static Constructor<?> loadArgumentTrickCtor;
    static Object voidFragmentInstance;
    static Method textLiteralMethod;

    // ---- display-sync fields ---------------------------------------------

    static Field spellConstructExecutorField;
    static Field modularExecutorsField;
    static Method spellExecutorGetDeepestStateMethod;
    static Method executionStateGetArgumentsMethod;
    static Field blockSpellSourceBlockEntityField;

    // ---- mana fields -----------------------------------------------------

    static Object manaComponentType;
    static Object emptyMana;
    static Object traditionalMana;
    static Object traditionalManaVariant;
    static Method manaComponentPoolMethod;
    static Method manaComponentWithMethod;
    static Method manaPoolMakeCloneMethod;
    static Method manaPoolGetManaMethod;
    static Method manaPoolGetMaxManaMethod;
    static Method manaPoolGetVariantMethod;
    static Method manaVariantOfMethod;
    static Method manaVariantGetManaMethod;
    static Method mutableManaPoolRefillMethod;
    static Method mutableManaPoolUseMethod;
    static Method itemStackGetComponentMethod;
    static Method itemStackSetComponentMethod;

    // ---- trick-registration fields ---------------------------------------

    static Method patternOfMethod;
    static Method tricksRegisterMethod;
    static Method numberFragmentNumberMethod;
    static Method vectorFragmentToBlockPosMethod;
    static Method spellContextSourceMethod;
    static Method spellContextUseManaMethod;
    static Method spellSourceGetWorldMethod;

    // ---- knot-crafting fields --------------------------------------------

    static Method getCrackedVersionMethod;
    static Method transferPropertiesToCrackedMethod;
    static Method getCreationCostMethod;

    static long manaScale = 1L;

    private TricksterReflection() {}

    // ---- public status ---------------------------------------------------

    /** {@code true} when at least one Trickster subsystem is available. */
    public static boolean isAvailable() {
        return ensureDisplayInit() || ensureChargeInit();
    }

    // ---- init phases (package-private so split classes can call them) ----

    static synchronized boolean ensureDisplayInit() {
        if (displayInitialized)
            return displayAvailable;
        displayInitialized = true;
        try {
            Class<?> stringFragmentClass = Class.forName("dev.enjarai.trickster.spell.fragment.StringFragment");
            stringFragmentCtor = stringFragmentClass.getConstructor(String.class);

            Class<?> voidFragmentClass = Class.forName("dev.enjarai.trickster.spell.fragment.VoidFragment");
            voidFragmentInstance = voidFragmentClass.getField("INSTANCE").get(null);

            spellConstructBlockEntityClass = Class.forName("dev.enjarai.trickster.block.SpellConstructBlockEntity");
            modularSpellConstructBlockEntityClass = Class
                    .forName("dev.enjarai.trickster.block.ModularSpellConstructBlockEntity");
            defaultSpellExecutorClass = Class
                    .forName("dev.enjarai.trickster.spell.execution.executor.DefaultSpellExecutor");

            spellConstructExecutorField = spellConstructBlockEntityClass.getField("executor");
            modularExecutorsField = modularSpellConstructBlockEntityClass.getField("executors");

            Class<?> spellExecutorClass = Class.forName("dev.enjarai.trickster.spell.SpellExecutor");
            spellExecutorGetDeepestStateMethod = spellExecutorClass.getMethod("getDeepestState");

            Class<?> executionStateClass = Class.forName("dev.enjarai.trickster.spell.execution.ExecutionState");
            executionStateGetArgumentsMethod = executionStateClass.getMethod("getArguments");

            Class<?> blockSpellSourceClass = Class
                    .forName("dev.enjarai.trickster.spell.execution.source.BlockSpellSource");
            blockSpellSourceBlockEntityField = blockSpellSourceClass.getField("blockEntity");

            displayAvailable = true;
        } catch (Throwable t) {
            CreateManaIndustry.LOGGER.warn("Trickster display integration unavailable", t);
            displayAvailable = false;
        }
        return displayAvailable;
    }

    static synchronized boolean ensureChargeInit() {
        if (chargeInitialized)
            return chargeAvailable;
        chargeInitialized = true;
        try {
            knotItemClass = Class.forName("dev.enjarai.trickster.item.KnotItem");
            chargingArrayBlockEntityClass = Class.forName("dev.enjarai.trickster.block.ChargingArrayBlockEntity");
            if (spellConstructBlockEntityClass == null)
                spellConstructBlockEntityClass = Class.forName("dev.enjarai.trickster.block.SpellConstructBlockEntity");
            if (modularSpellConstructBlockEntityClass == null)
                modularSpellConstructBlockEntityClass = Class
                        .forName("dev.enjarai.trickster.block.ModularSpellConstructBlockEntity");

            Class<?> modComponentsClass = Class.forName("dev.enjarai.trickster.item.component.ModComponents");
            Class<?> manaComponentClass = Class.forName("dev.enjarai.trickster.item.component.ManaComponent");
            Class<?> manaPoolClass = Class.forName("dev.enjarai.trickster.spell.mana.ManaPool");
            Class<?> mutableManaPoolClass = Class.forName("dev.enjarai.trickster.spell.mana.MutableManaPool");
            Class<?> manaeClass = Class.forName("dev.enjarai.trickster.spell.mana.type.Manae");
            infiniteManaPoolClass = Class.forName("dev.enjarai.trickster.spell.mana.InfiniteManaPool");
            manaClass = Class.forName("dev.enjarai.trickster.spell.mana.type.Mana");
            manaVariantClass = Class.forName("dev.enjarai.trickster.spell.mana.storage.ManaVariant");
            minecraftWorldClass = findClass(
                    "net.minecraft.world.level.Level",
                    "net.minecraft.world.World",
                    "net.minecraft.class_1937");

            manaComponentType = modComponentsClass.getField("MANA").get(null);
            emptyMana = manaeClass.getField("EMPTY").get(null);
            traditionalMana = manaeClass.getField("TRADITIONAL").get(null);
            manaComponentPoolMethod = manaComponentClass.getMethod("pool");
            manaComponentWithMethod = manaComponentClass.getMethod("with", manaPoolClass);
            manaPoolMakeCloneMethod = manaPoolClass.getMethod("makeClone", minecraftWorldClass);
            manaPoolGetManaMethod = manaPoolClass.getMethod("get", minecraftWorldClass);
            manaPoolGetMaxManaMethod = manaPoolClass.getMethod("getMax", minecraftWorldClass);
            manaPoolGetVariantMethod = manaPoolClass.getMethod("getVariant", minecraftWorldClass);
            manaVariantOfMethod = manaVariantClass.getMethod("of", manaClass);
            manaVariantGetManaMethod = manaVariantClass.getMethod("getMana");
            mutableManaPoolRefillMethod = mutableManaPoolClass.getMethod("refill", manaVariantClass, long.class,
                    minecraftWorldClass);
            mutableManaPoolUseMethod = mutableManaPoolClass.getMethod("use", manaVariantClass, long.class,
                    minecraftWorldClass);
            manaScale = manaPoolClass.getField("MANA_SCALE").getLong(null);
            traditionalManaVariant = manaVariantOfMethod.invoke(null, traditionalMana);

            itemStackGetComponentMethod = ItemStack.class.getMethod("get", DataComponentType.class);
            itemStackSetComponentMethod = ItemStack.class.getMethod("set", DataComponentType.class, Object.class);

            getCrackedVersionMethod = knotItemClass.getMethod("getCrackedVersion");
            transferPropertiesToCrackedMethod = knotItemClass.getMethod("transferPropertiesToCracked", minecraftWorldClass,
                    ItemStack.class, ItemStack.class);
            getCreationCostMethod = knotItemClass.getMethod("getCreationCost");
            chargeAvailable = true;
        } catch (Throwable t) {
            CreateManaIndustry.LOGGER.warn("Trickster charge integration unavailable", t);
            chargeAvailable = false;
        }
        return chargeAvailable;
    }

    static synchronized boolean ensureRegisterInit() {
        if (registerInitialized)
            return registerAvailable;
        registerInitialized = true;
        try {
            Class<?> patternClass = Class.forName("dev.enjarai.trickster.spell.Pattern");
            trickClass = Class.forName("dev.enjarai.trickster.spell.trick.Trick");
            Class<?> tricksClass = Class.forName("dev.enjarai.trickster.spell.trick.Tricks");
            Class<?> loadArgumentTrickClass = Class
                    .forName("dev.enjarai.trickster.spell.trick.func.LoadArgumentTrick");
            signatureClass = Class.forName("dev.enjarai.trickster.spell.type.Signature");
            vectorFragmentClass = Class.forName("dev.enjarai.trickster.spell.fragment.VectorFragment");
            numberFragmentClass = Class.forName("dev.enjarai.trickster.spell.fragment.NumberFragment");
            Class<?> spellContextClass = Class.forName("dev.enjarai.trickster.spell.SpellContext");
            Class<?> spellSourceClass = Class.forName("dev.enjarai.trickster.spell.execution.source.SpellSource");

            patternOfMethod = patternClass.getMethod("of", int[].class);
            textLiteralMethod = findTextLiteralMethod();
            loadArgumentTrickCtor = loadArgumentTrickClass.getConstructor(patternClass, int.class);
            tricksRegisterMethod = tricksClass.getMethod("register", String.class, trickClass);
            numberFragmentNumberMethod = numberFragmentClass.getMethod("number");
            vectorFragmentToBlockPosMethod = vectorFragmentClass.getMethod("toBlockPos");
            spellContextSourceMethod = spellContextClass.getMethod("source");
            try {
                spellContextUseManaMethod = spellContextClass.getMethod("useScaledMana", trickClass, double.class);
            } catch (NoSuchMethodException ignored) {
                spellContextUseManaMethod = spellContextClass.getMethod("useMana", trickClass, float.class);
            }
            spellSourceGetWorldMethod = spellSourceClass.getMethod("getWorld");

            registerAvailable = true;
        } catch (Throwable t) {
            CreateManaIndustry.LOGGER.warn("Trickster trick registration unavailable", t);
            registerAvailable = false;
        }
        return registerAvailable;
    }

    // ---- shared utilities ------------------------------------------------

    /** Create a Minecraft text component from a string via reflection. */
    public static Object makeText(String value) {
        if (!ensureRegisterInit())
            return value;
        try {
            return textLiteralMethod.invoke(null, value);
        } catch (ReflectiveOperationException e) {
            return value;
        }
    }

    private static Method findTextLiteralMethod() throws ReflectiveOperationException {
        for (String className : new String[] {
                "net.minecraft.network.chat.Component",
                "net.minecraft.text.Text",
                "net.minecraft.class_2561"
        }) {
            try {
                Class<?> textClass = Class.forName(className);
                for (String methodName : new String[] {"literal", "method_43470"}) {
                    try {
                        return textClass.getMethod(methodName, String.class);
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new ClassNotFoundException("Minecraft text literal factory");
    }

    private static Class<?> findClass(String... classNames) throws ClassNotFoundException {
        for (String className : classNames) {
            try {
                return Class.forName(className);
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new ClassNotFoundException(classNames.length == 0 ? "<empty>" : classNames[0]);
    }
}
