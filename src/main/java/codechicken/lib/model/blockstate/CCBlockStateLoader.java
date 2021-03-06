package codechicken.lib.model.blockstate;

import codechicken.lib.util.ArrayUtils;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.gson.*;
import net.minecraft.client.renderer.block.model.ModelBlockDefinition;
import net.minecraft.client.renderer.block.model.ModelRotation;
import net.minecraft.client.renderer.block.model.Variant;
import net.minecraft.client.renderer.block.model.VariantList;
import net.minecraft.util.JsonUtils;
import net.minecraftforge.client.model.BlockStateLoader;
import net.minecraftforge.common.model.TRSRTransformation;
import org.apache.commons.io.IOUtils;

import java.io.Reader;
import java.io.StringReader;
import java.util.*;
import java.util.Map.Entry;

/**
 * Created by covers1624 on 17/11/2016.
 */
public class CCBlockStateLoader {

    public static final Gson GSON = new GsonBuilder().registerTypeAdapter(CCVariant.class, new CCVariant.Deserializer()).create();

    public static ModelBlockDefinition handleLoad(Reader reader, Gson vanillaGSON) {
        try {
            String json = IOUtils.toString(reader);
            ModelBlockDefinition cclDef = load(json);
            if (cclDef != null) {
                return cclDef;
            }
            return BlockStateLoader.load(new StringReader(json), vanillaGSON);
        } catch (Exception e) {
            Throwables.propagate(e);
        }
        return null;
    }

    public static ModelBlockDefinition load(String json) {
        try {
            JsonParser parser = new JsonParser();
            JsonObject object = parser.parse(json).getAsJsonObject();
            if (JsonUtils.hasField(object, "ccl_marker")) {

                String marker = JsonUtils.getString(object, "ccl_marker");
                String blockVariants = JsonUtils.getString(object, "block_variants");
                String inventoryVariants = JsonUtils.getString(object, "inventory_variants");
                String textureDomain = "";
                if (object.has("texture_domain")) {
                    textureDomain = object.get("texture_domain").getAsString();
                }

                final List<String> blockVariantKeys = Lists.newLinkedList(Arrays.asList(blockVariants.split(",")));
                final List<String> inventoryVariantKeys = Lists.newLinkedList(Arrays.asList(inventoryVariants.split(",")));

                CCVariant defaultVariant = null;
                if (object.has("defaults")) {
                    defaultVariant = GSON.fromJson(object.get("defaults"), CCVariant.class);
                }
                Map<String, Map<String, CCVariant>> variants = parseVariants(object.getAsJsonObject("variants"));

                Map<String, CCVariant> compiledVariants = new LinkedHashMap<String, CCVariant>();

                Map<String, List<String>> blockVariantValueMap = generateVariantValueMap(blockVariantKeys, variants);
                Map<String, List<String>> itemVariantValueMap = generateVariantValueMap(inventoryVariantKeys, variants);

                List<String> possibleCombos = new ArrayList<String>();
                possibleCombos.addAll(generatePossibleCombos(blockVariantValueMap));
                possibleCombos.addAll(generatePossibleCombos(itemVariantValueMap));

                for (String var : possibleCombos) {
                    Map<String, String> kvArray = ArrayUtils.convertKeyValueArrayToMap(var.split(","));
                    CCVariant finalVariant = defaultVariant.copy();
                    if (finalVariant == null) {
                        finalVariant = new CCVariant();
                    }
                    for (Entry<String, String> entry : kvArray.entrySet()) {
                        for (Entry<String, Map<String, CCVariant>> variantsEntry : variants.entrySet()) {
                            if (entry.getKey().equals(variantsEntry.getKey())) {
                                Map<String, CCVariant> variantMap = variantsEntry.getValue();
                                if (variantMap.containsKey(entry.getValue())) {
                                    finalVariant = finalVariant.with(variantMap.get(entry.getValue()));
                                }
                            }
                        }
                    }
                    for (Entry<String, String> entry : kvArray.entrySet()) {
                        for (Entry<String, Map<String, CCVariant>> variantsEntry : variants.entrySet()) {
                            if (entry.getKey().equals(variantsEntry.getKey())) {
                                Map<String, CCVariant> variantMap = variantsEntry.getValue();
                                if (variantMap.containsKey(entry.getValue())) {
                                    finalVariant = variantMap.get(entry.getValue()).applySubOverrides(finalVariant, kvArray);
                                }
                            }
                        }
                    }
                    compiledVariants.put(var, finalVariant);
                }

                Map<String, VariantList> variantList = new HashMap<String, VariantList>();
                for (Entry<String, CCVariant> entry : compiledVariants.entrySet()) {
                    List<Variant> vars = new ArrayList<Variant>();
                    CCVariant variant = entry.getValue();

                    boolean uvLock = variant.uvLock.or(false);
                    boolean smooth = variant.smooth.or(true);
                    boolean gui3d = variant.gui3d.or(true);
                    int weight = variant.weight.or(1);

                    if (variant.model != null && variant.textures.size() == 0 && variant.state.orNull() instanceof ModelRotation) {
                        vars.add(new Variant(variant.model, ((ModelRotation) variant.state.get()), uvLock, weight));
                    } else {
                        vars.add(new CCFinalVariant(variant.model, variant.state.or(TRSRTransformation.identity()), uvLock, smooth, gui3d, weight, variant.textures, textureDomain));
                    }
                    variantList.put(entry.getKey(), new VariantList(vars));
                }
                return new ModelBlockDefinition(variantList, null);

            }
        } catch (Exception e) {
            e.printStackTrace();
            Throwables.propagate(e);
        }
        return null;
    }

    public static Map<String, Map<String, CCVariant>> parseVariants(JsonObject variantElement) {
        Map<String, Map<String, CCVariant>> variants = new LinkedHashMap<String, Map<String, CCVariant>>();
        for (Entry<String, JsonElement> variantsEntry : variantElement.entrySet()) {
            String variantName = variantsEntry.getKey();
            Map<String, CCVariant> variantValues = variants.get(variantName);
            if (variantValues == null) {
                variantValues = new LinkedHashMap<String, CCVariant>();
                variants.put(variantName, variantValues);
            }
            for (Entry<String, JsonElement> variantEntry : variantsEntry.getValue().getAsJsonObject().entrySet()) {
                String variantValue = variantEntry.getKey();
                CCVariant variant = GSON.fromJson(variantEntry.getValue(), CCVariant.class);
                variantValues.put(variantValue, variant);
            }
        }
        return variants;
    }

    /**
     * Generates a list of strings for all possible combos of Key=Value.
     * Per key value list can have more than 1 valid value.
     * <p>
     * Credits to brandon3055 who wrote this for me!
     *
     * @param variantValueMap A map of all possible Keys to all possible values per key.
     * @return A compiled list of all possible combos.
     */
    public static List<String> generatePossibleCombos(Map<String, List<String>> variantValueMap) {
        List<String> possibleCombos = new ArrayList<String>();

        List<String> keys = Lists.newArrayList(variantValueMap.keySet());

        int comboCount = 1;
        for (String key : variantValueMap.keySet()) {
            comboCount *= variantValueMap.get(key).size();
        }

        int[] indexes = new int[variantValueMap.size()];
        for (int l = 0; l < comboCount; l++) {
            for (int in = 0; in < indexes.length; in++) {
                indexes[in]++;
                if (indexes[in] >= variantValueMap.get(keys.get(in)).size()) {
                    indexes[in] = 0;
                } else {
                    break;
                }
            }

            String combo = "";
            for (int i = 0; i < indexes.length; i++) {
                combo += keys.get(i) + "=" + variantValueMap.get(keys.get(i)).get(indexes[i]) + ",";
            }
            possibleCombos.add(combo.substring(0, combo.length() - 1));
        }
        return possibleCombos;
    }

    /**
     * Generates a map of Key -> List(Value) from the provided CCVariants.
     *
     * @param keys     The keys to get all values for.
     * @param variants The CCVariants parsed from json.
     * @return Map of Key to value lists.
     */
    public static Map<String, List<String>> generateVariantValueMap(List<String> keys, Map<String, Map<String, CCVariant>> variants) {
        Map<String, List<String>> variantValueMap = new LinkedHashMap<String, List<String>>();
        for (String variant : keys) {
            List<String> variantValues = new ArrayList<String>();
            for (String variantName : variants.keySet()) {
                if (variantName.equals(variant)) {
                    variantValues.addAll(variants.get(variant).keySet());
                }

                for (CCVariant subVariant : variants.get(variantName).values()) {
                    variantValues.addAll(subVariant.getPossibleVariantValues(variant));
                }
            }
            variantValueMap.put(variant, variantValues);
        }
        return variantValueMap;
    }

}
