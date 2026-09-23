package com.iridium126.createmanaindustry.compat.ysm.model;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.imageio.ImageIO;
import com.google.gson.*;
import com.iridium126.createmanaindustry.compat.ysm.internal.pojo.RawYsmModel.*;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Group;

/** Builds a plaintext resource package in memory, before any filesystem/network side effect. */
public final class YsmCompiledExporter {
    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private long bytes;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static Map<String, byte[]> build(YsmCompiledModel snapshot, List<Group> roots) {
        Set<String> parts = new HashSet<>(); snapshot.roots().forEach(g -> parts.add(g.root().part()));
        if (!snapshot.digest().equals(YsmGeometry.validateRoots(roots, parts))) throw new IllegalArgumentException("Source snapshot mismatch");
        var exporter = new YsmCompiledExporter();
        for (Group root : roots) exporter.json(root.root().part(), YsmGeometryJson.write(root));
        exporter.resources(snapshot);
        return Collections.unmodifiableMap(exporter.files);
    }
    private void resources(YsmCompiledModel snapshot) {
        var model = snapshot.resources();
        JsonObject config = new JsonObject(); config.addProperty("spec", 2);
        JsonObject meta = new JsonObject(); meta.addProperty("name", model.metadata.name); meta.addProperty("tips", model.metadata.tips);
        JsonObject license = new JsonObject(); license.addProperty("type", model.metadata.licenseType); license.addProperty("desc", model.metadata.licenseDescription);
        meta.add("license", license); meta.add("link", strings(model.metadata.links));
        JsonArray authors = new JsonArray();
        for (var author : model.metadata.authors) {
            JsonObject entry = new JsonObject(); entry.addProperty("name", author.name); entry.addProperty("role", author.role); entry.addProperty("comment", author.comment);
            entry.add("contact", strings(author.contacts));
            if (author.avatarImage != null) {
                String avatarName = author.avatar == null || author.avatar.isBlank() ? author.name : author.avatar;
                String path = "avatar/" + safeFilename(avatarName + ".png");
                put(path, image(author.avatarImage)); entry.addProperty("avatar", path);
            }
            authors.add(entry);
        }
        for (RawImage avatar : model.metadata.extraAvatars)
            put("avatar/" + safeFilename(avatar.name + ".png"), image(avatar));
        meta.add("authors", authors); config.add("metadata", meta);
        config.add("properties", properties(model.properties));
        JsonObject fileConfig = new JsonObject(), player = new JsonObject(), geometries = new JsonObject();
        if (model.mainEntity.mainModel != null) geometries.addProperty("main", "models/main.json");
        if (model.mainEntity.armModel != null) geometries.addProperty("arm", "models/arm.json");
        player.add("model", geometries);
        player.add("texture", textures(model.mainEntity.textures));
        player.add("animation", animations(model.mainEntity.animationFiles));
        player.add("animation_controllers", controllers("player", model.mainEntity.animationControllers));
        fileConfig.add("player", player);
        fileConfig.add("vehicles", entities("vehicle", model.vehicles));
        fileConfig.add("projectiles", entities("projectiles", model.projectiles));
        fileConfig.add("sub_entities", entities("SubEntity", model.subEntities));
        fileConfig.addProperty("sound_path", "sounds");
        config.add("files", fileConfig);
        model.soundFiles.forEach((name, data) -> put(mappedPath("sounds", name, ".ogg"), data.data));
        model.functionFiles.forEach((name, data) -> put(mappedPath("functions", name, ".molang"), data.data));
        model.languageFiles.forEach((name, data) -> json(mappedPath("lang", name, ".json"), strings(data.data)));
        json("ysm.json", config);
    }
    private JsonObject properties(RawProperties properties) {
        JsonObject out = new JsonObject();
        out.addProperty("width_scale", properties.widthScale); out.addProperty("height_scale", properties.heightScale);
        out.addProperty("default_texture", properties.defaultTexture); out.addProperty("preview_animation", properties.previewAnimation);
        out.addProperty("free", properties.isFree);
        out.addProperty("render_layers_first", properties.renderLayersFirst); out.addProperty("all_cutout", properties.allCutout);
        out.addProperty("disable_preview_rotation", properties.disablePreviewRotation); out.addProperty("gui_no_lighting", properties.guiNoLighting);
        out.addProperty("merge_multiline_expr", properties.mergeMultilineExpr);
        out.add("extra_animation", strings(properties.extraAnimations));
        JsonArray classes = new JsonArray();
        for (var category : properties.extraAnimationClassifies) {
            JsonObject item = new JsonObject(); item.addProperty("id", category.id); item.add("extra_animation", strings(category.extras)); classes.add(item);
        }
        out.add("extra_animation_classify", classes);
        JsonArray buttons = new JsonArray();
        for (var button : properties.extraAnimationButtons) {
            JsonObject item = new JsonObject(); item.addProperty("id", button.id); item.addProperty("name", button.name); item.addProperty("description", button.description);
            JsonArray forms = new JsonArray();
            for (var form : button.forms) {
                JsonObject f = new JsonObject(); f.addProperty("type", form.type); f.addProperty("title", form.title); f.addProperty("description", form.description);
                f.addProperty("value", form.defaultValue); f.addProperty("step", form.step); f.addProperty("min", form.min); f.addProperty("max", form.max); f.add("labels", strings(form.labels)); forms.add(f);
            }
            item.add("config_forms", forms); buttons.add(item);
        }
        out.add("extra_animation_buttons", buttons);
        for (RawImage image : properties.backgroundImages) {
            String path;
            if ("gui_background".equals(image.name) && !properties.guiBackground.isEmpty()) path = properties.guiBackground;
            else if ("gui_foreground".equals(image.name) && !properties.guiForeground.isEmpty()) path = properties.guiForeground;
            else path = "background/" + leaf(image.name, ".png");
            YsmCompiledExporter.safePath(path); put(path, image(image));
        }
        if (!properties.guiBackground.isEmpty()) out.addProperty("gui_background", properties.guiBackground);
        if (!properties.guiForeground.isEmpty()) out.addProperty("gui_foreground", properties.guiForeground);
        return out;
    }
    private JsonObject entities(String kind, Map<String, RawSubEntity> entities) {
        JsonObject out = new JsonObject();
        for (var entry : entities.entrySet()) {
            String modelName = entry.getKey();
            RawSubEntity entity = entry.getValue();
            JsonObject item = new JsonObject();
            if (entity.model != null) item.addProperty("model", "models/" + safeFilename(modelName + ".json"));
            String texture = entityTexture(modelName, entity.textures);
            if (texture != null) item.addProperty("texture", texture);
            if (!entity.animationFiles.isEmpty()) {
                String path = "animations/" + kind + "/" + safeFilename(modelName + ".animation.json");
                animationDocument(entity.animationFiles, path);
                item.addProperty("animation", path);
            }
            var controllerNamespace = kind + "/" + modelName;
            var controllers = controllers(controllerNamespace, entity.animationControllers);
            if (!controllers.isEmpty()) item.add("controller", controllers.get(0));
            out.add(modelName, item);
        }
        return out;
    }
    private String entityTexture(String modelName, Map<String, RawTexture> textures) {
        if (textures.isEmpty()) return null;
        String basePath = "textures/" + safeFilename(modelName + ".png");
        RawTexture base = textures.values().iterator().next();
        put(basePath, png(base.data, base.width, base.height, base.imageFormat));
        Set<Integer> seen = new HashSet<>();
        for (RawTexture.SubTexture sub : base.subTextures) {
            if (!seen.add(sub.specularType)) throw unsupported("Duplicate entity subtexture");
            String suffix = switch (sub.specularType) { case 1 -> "_normal"; case 2 -> "_specular"; default -> throw unsupported("Unknown subtexture type"); };
            put("textures/" + safeFilename(modelName + suffix + ".png"), png(sub.data, sub.width, sub.height, sub.imageFormat));
        }
        return basePath;
    }
    private JsonArray textures(Map<String, RawTexture> textures) {
        JsonArray out = new JsonArray();
        for (var texture : textures.values()) {
            String path = "textures/" + safeFilename(texture.name + ".png");
            put(path, png(texture.data, texture.width, texture.height, texture.imageFormat));
            if (texture.subTextures.isEmpty()) { out.add(path); continue; }
            JsonObject entry = new JsonObject(); entry.addProperty("uv", path);
            for (var sub : texture.subTextures) {
                String kind = switch(sub.specularType) { case 1 -> "normal"; case 2 -> "specular"; default -> throw unsupported("Unknown subtexture type"); };
                if (entry.has(kind)) throw unsupported("Duplicate subtexture type");
                String suffix = "normal".equals(kind) ? "_normal" : "_specular";
                String subPath = "textures/" + safeFilename(texture.name + suffix + ".png");
                put(subPath, png(sub.data, sub.width, sub.height, sub.imageFormat)); entry.addProperty(kind, subPath);
            }
            out.add(entry);
        }
        return out;
    }
    private JsonObject animations(Map<String, RawAnimationFile> files) {
        JsonObject references = new JsonObject();
        for (var file : files.entrySet()) {
            String component = switch (file.getKey()) { case "fp_arm" -> "fp.arm"; case "irons_spell_books" -> "iss"; default -> file.getKey(); };
            String path = "animations/" + safeFilename(component + ".animation.json");
            animationDocument(Map.of(file.getKey(), file.getValue()), path);
            references.addProperty(file.getKey(), path);
        }
        return references;
    }
    private void animationDocument(Map<String, RawAnimationFile> files, String path) {
        JsonObject animations = new JsonObject();
        for (var file : files.values()) for (var animation : file.animations.values()) {
                JsonObject value = new JsonObject();
                if (Float.isFinite(animation.length) && animation.length > 0) value.addProperty("animation_length", animation.length);
                switch(animation.loopMode) {
                    case 0 -> { } case 1 -> value.addProperty("loop", true);
                    case 2 -> { } case 3 -> value.addProperty("loop", "hold_on_last_frame"); default -> { }
                }
                if (animation.blendWeight != null) value.add("blend_weight", scalar(animation.blendWeight));
                JsonObject bones = new JsonObject();
                for (var bone : animation.boneAnimations) {
                    JsonObject channels = new JsonObject(); channel(channels, "rotation", bone.rotation); channel(channels, "position", bone.position); channel(channels, "scale", bone.scale);
                    if (!channels.isEmpty()) bones.add(bone.boneName, channels);
                }
                if (!bones.isEmpty()) value.add("bones", bones);
                JsonObject timeline = new JsonObject();
                for (var event : animation.timelineEvents) {
                    String time = time(event.timestamp); JsonArray events = new JsonArray();
                    event.events.forEach(events::add); timeline.add(time, events);
                }
                if (!timeline.isEmpty()) value.add("timeline", timeline);
                JsonObject sounds = new JsonObject();
                for (var sound : animation.soundEffects) {
                    String time = time(sound.timestamp);
                    JsonObject effect = new JsonObject(); effect.addProperty("effect", sound.effectName); sounds.add(time, effect);
                }
                if (!sounds.isEmpty()) value.add("sound_effects", sounds);
                animations.add(animation.name, value);
        }
        JsonObject document = new JsonObject(); document.addProperty("format_version", "1.8.0"); document.add("animations", animations); json(path, document);
    }
    private static void channel(JsonObject bone, String name, List<RawKeyframe> frames) {
        if (frames.isEmpty()) return;
        JsonObject out = new JsonObject();
        for (var frame : frames) {
            String time = time(frame.timestamp);
            JsonObject keyframe = new JsonObject();
            JsonArray post = new JsonArray(); for (Object item : frame.postData) post.add(scalar(item)); keyframe.add("post", post);
            if (frame.hasPreData) { JsonArray pre = new JsonArray(); for (Object item : frame.preData) pre.add(scalar(item)); keyframe.add("pre", pre); }
            if (frame.interpolationMode == 1) keyframe.addProperty("lerp_mode", "step");
            else if (frame.interpolationMode == 2) keyframe.addProperty("lerp_mode", "catmullrom");
            out.add(time, keyframe);
        }
        bone.add(name, out);
    }
    private JsonArray controllers(String namespace, Map<String, RawAnimationController> controllers) {
        JsonArray references = new JsonArray();
        if (controllers.isEmpty()) return references;
        Map<String, JsonObject> fileContents = new LinkedHashMap<>();
        for (var controller : controllers.values()) {
            JsonObject c = new JsonObject();
            if (controller.initialState != null && !controller.initialState.isEmpty()) c.addProperty("initial_state", controller.initialState);
            JsonObject states = new JsonObject();
            for (var state : controller.states) {
                JsonObject s = new JsonObject(); JsonArray animations = animationEntries(state.animations), transitions = conditional(state.transitions);
                if (!animations.isEmpty()) s.add("animations", animations);
                if (!transitions.isEmpty()) s.add("transitions", transitions);
                if (!state.onEntry.isEmpty()) s.add("on_entry", array(state.onEntry));
                if (!state.onExit.isEmpty()) s.add("on_exit", array(state.onExit));
                if (!state.soundEffects.isEmpty()) {
                    JsonArray effects = new JsonArray();
                    for (String name : state.soundEffects) { JsonObject effect = new JsonObject(); effect.addProperty("effect", name); effects.add(effect); }
                    s.add("sound_effects", effects);
                }
                if (state.hasBlendTransitionValue) s.addProperty("blend_transition", state.blendTransitionValue);
                else if (!state.blendTransitions.isEmpty()) { JsonObject curve = new JsonObject(); state.blendTransitions.forEach((k,v) -> curve.addProperty(time(k),v)); s.add("blend_transitions", curve); }
                if (state.blendViaShortestPath) s.addProperty("blend_via_shortest_path", true);
                if (states.has(state.name)) throw unsupported("Duplicate controller state"); states.add(state.name,s);
            }
            c.add("states",states);
            String fileName = "player".equals(namespace) ? controller.fileName : namespace;
            if (fileName == null || fileName.isBlank()) fileName = "controller";
            JsonObject content = fileContents.computeIfAbsent(fileName, ignored -> new JsonObject());
            content.add(controller.name, c);
        }
        for (var file : fileContents.entrySet()) {
            JsonObject document = new JsonObject(); document.addProperty("format_version","1.19.0"); document.add("animation_controllers",file.getValue());
            String path = controllerPath(file.getKey(), "player".equals(namespace));
            json(path,document); references.add(path);
        }
        return references;
    }
    private static String controllerPath(String name, boolean player) {
        String pathName = name;
        if (player) pathName = safeFilename(name.replace('/', '_'));
        else {
            String[] parts = name.split("/", -1);
            for (int i = 0; i < parts.length; i++) parts[i] = safeFilename(parts[i]);
            pathName = String.join("/", parts);
        }
        String path = "controller/" + pathName + ".json";
        safePath(path);
        return path;
    }
    static String safeFilename(String name) {
        String result = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        while (result.endsWith(" ") || result.endsWith(".")) result = result.substring(0, result.length() - 1);
        return result.isEmpty() ? "unnamed_file" : result;
    }
    private static JsonArray conditional(Map<String,String> values) {
        JsonArray out = new JsonArray(); values.forEach((key,value) -> { JsonObject item=new JsonObject();item.addProperty(key,value);out.add(item); });return out;
    }
    private static JsonArray animationEntries(Map<String,String> values) {
        JsonArray out = new JsonArray();
        values.forEach((key, value) -> {
            if (value == null || value.isEmpty()) out.add(key);
            else { JsonObject item = new JsonObject(); item.addProperty(key, value); out.add(item); }
        });
        return out;
    }
    private static JsonArray array(List<String> values) { JsonArray out=new JsonArray();values.forEach(out::add);return out; }
    private static JsonObject strings(Map<String,String> values) { JsonObject out=new JsonObject();values.forEach(out::addProperty);return out; }
    private static JsonElement scalar(Object value) {
        if (value instanceof String string) return new JsonPrimitive(string);
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) return new JsonPrimitive(number);
        throw unsupported("Invalid animation expression");
    }
    private static String time(float value) { if (!Float.isFinite(value) || value<0) throw unsupported("Invalid animation timestamp");return Float.toString(value); }
    private static byte[] image(RawImage image) { return png(image.data,image.width,image.height,image.isPng ? 2 : image.format); }
    private static byte[] png(byte[] data,int width,int height,int format) {
        if(data==null || width<1 || height<1 || width>16384 || height>16384 || (long)width*height>16_777_216)throw unsupported("Invalid texture size");
        if(data.length>=8 && data[0]==(byte)137 && data[1]==80 && data[2]==78 && data[3]==71) return data.clone();
        if(format!=-1 || data.length!=(long)width*height*4)throw unsupported("Texture encoding " + format);
        BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            int i=(y*width+x)*4;int argb=(data[i+3]&255)<<24|(data[i]&255)<<16|(data[i+1]&255)<<8|(data[i+2]&255);image.setRGB(x,y,argb);
        }
        try(var out=new ByteArrayOutputStream()) { if(!ImageIO.write(image,"png",out))throw unsupported("PNG writer unavailable");return out.toByteArray(); }
        catch(java.io.IOException failure){throw new IllegalArgumentException("Texture encoding failed",failure);}
    }
    private void json(String path,JsonObject value) { put(path,GSON.toJson(value).getBytes(StandardCharsets.UTF_8)); }
    private void put(String path,byte[] data) {
        safePath(path);
        if(data==null || files.keySet().stream().anyMatch(existing -> existing.equalsIgnoreCase(path)))throw unsupported("Missing or duplicate resource");
        if(data.length>YsmResourceArchive.MAX_BYTES-bytes)throw unsupported("Resource package exceeds Java byte-array limit");
        files.put(path,data.clone()); bytes+=data.length;
    }
    private static String leaf(String name,String suffix) {
        if(name==null || name.isBlank() || name.contains("/") || name.contains("\\"))throw unsupported("Invalid resource name");
        String result=name.endsWith(suffix)?name:name+suffix;safePath(result);return result;
    }
    private static String mappedPath(String folder, String name, String suffix) {
        if (name == null || name.isBlank()) throw unsupported("Invalid resource name");
        String relative = name.replace('\\', '/');
        if (!relative.endsWith(suffix)) relative += suffix;
        int slash = relative.lastIndexOf('/');
        String filename = relative.substring(slash + 1);
        relative = (slash < 0 ? "" : relative.substring(0, slash + 1)) + safeFilename(filename);
        String path = folder + "/" + relative;
        safePath(path);
        return path;
    }
    public static void safePath(String path) {
        if(path==null || path.startsWith("/") || path.matches(".*[\\\\:\\x00<>\"|?*].*"))throw unsupported("Unsafe resource path");
        if (path.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xD800 && c <= 0xDFFF))
            throw unsupported("Invalid resource path characters");
        for(String part:path.split("/",-1)) {
            String device=part.split("\\.",2)[0].toUpperCase(Locale.ROOT);
            if(part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || device.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))throw unsupported("Unsafe resource path");
        }
    }
    private static IllegalArgumentException unsupported(String reason) { return new IllegalArgumentException("Cannot export YSM without data loss: " + reason); }
    private YsmCompiledExporter() {}
}
