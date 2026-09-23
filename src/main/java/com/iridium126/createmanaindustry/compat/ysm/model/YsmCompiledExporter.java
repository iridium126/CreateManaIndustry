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
        JsonArray authors = new JsonArray(); int authorIndex = 0;
        for (var author : model.metadata.authors) {
            JsonObject entry = new JsonObject(); entry.addProperty("name", author.name); entry.addProperty("role", author.role); entry.addProperty("comment", author.comment);
            entry.add("contact", strings(author.contacts));
            if (author.avatarImage != null) {
                String path = "avatars/author_" + authorIndex + ".png";
                put(path, image(author.avatarImage)); entry.addProperty("avatar", path);
            }
            authors.add(entry); authorIndex++;
        }
        if (!model.metadata.extraAvatars.isEmpty()) throw unsupported("Unassociated author images");
        meta.add("authors", authors); config.add("metadata", meta);
        config.add("properties", properties(model.properties));
        JsonObject fileConfig = new JsonObject(), player = new JsonObject(), geometries = new JsonObject();
        if (model.mainEntity.mainModel != null) geometries.addProperty("main", "models/main.json");
        if (model.mainEntity.armModel != null) geometries.addProperty("arm", "models/arm.json");
        player.add("model", geometries);
        player.add("texture", textures("player", model.mainEntity.textures));
        player.add("animation", animations("player", model.mainEntity.animationFiles));
        player.add("animation_controllers", controllers("player", model.mainEntity.animationControllers));
        fileConfig.add("player", player);
        fileConfig.add("vehicles", entities("vehicle", model.vehicles));
        fileConfig.add("projectiles", entities("projectile", model.projectiles));
        fileConfig.addProperty("sound_path", "sounds");
        config.add("files", fileConfig);
        model.soundFiles.forEach((name, data) -> put("sounds/" + leaf(name, ".ogg"), data.data));
        model.functionFiles.forEach((name, data) -> put("functions/" + leaf(name, ".molang"), data.data));
        model.languageFiles.forEach((name, data) -> json("lang/" + leaf(name, ".json"), strings(data.data)));
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
            if (!"gui_background".equals(image.name) && !"gui_foreground".equals(image.name)) throw unsupported("Unknown GUI image reference");
            String path = "background/" + image.name + ".png"; put(path, image(image)); out.addProperty(image.name, path);
        }
        if (!properties.guiBackground.isEmpty() && !out.has("gui_background") || !properties.guiForeground.isEmpty() && !out.has("gui_foreground"))
            throw unsupported("Missing GUI image resource");
        return out;
    }
    private JsonArray entities(String kind, Map<String, RawSubEntity> entities) {
        JsonArray out = new JsonArray(); int index = 0;
        for (var entity : entities.values()) {
            JsonObject item = new JsonObject();
            if (entity.matchIds == null || entity.matchIds.length == 0) throw unsupported("Missing entity match references");
            JsonArray match = new JsonArray(); for (String id : entity.matchIds) match.add(id); item.add("match", match);
            if (entity.model != null) item.addProperty("model", "models/" + kind + "_" + index + ".json");
            item.add("texture", textures(kind + "_" + index, entity.textures));
            var animations = animations(kind + "_" + index, entity.animationFiles);
            if (animations.size() > 1) throw unsupported("Multiple sub-entity animation files");
            if (!animations.isEmpty()) item.add("animation", animations.entrySet().iterator().next().getValue());
            var controllers = controllers(kind + "_" + index, entity.animationControllers);
            if (!controllers.isEmpty()) item.add("animation_controllers", controllers);
            out.add(item); index++;
        }
        return out;
    }
    private JsonArray textures(String namespace, Map<String, RawTexture> textures) {
        JsonArray out = new JsonArray();
        for (var texture : textures.values()) {
            String path = "textures/" + namespace + "/" + leaf(texture.name, ".png");
            put(path, png(texture.data, texture.width, texture.height, texture.imageFormat));
            if (texture.subTextures.isEmpty()) { out.add(path); continue; }
            JsonObject entry = new JsonObject(); entry.addProperty("uv", path);
            for (var sub : texture.subTextures) {
                String kind = switch(sub.specularType) { case 1 -> "normal"; case 2 -> "specular"; default -> throw unsupported("Unknown subtexture type"); };
                if (entry.has(kind)) throw unsupported("Duplicate subtexture type");
                String subPath = "textures/" + namespace + "/" + kind + "_" + leaf(texture.name, ".png");
                put(subPath, png(sub.data, sub.width, sub.height, sub.imageFormat)); entry.addProperty(kind, subPath);
            }
            out.add(entry);
        }
        return out;
    }
    private JsonObject animations(String namespace, Map<String, RawAnimationFile> files) {
        JsonObject references = new JsonObject();
        for (var file : files.entrySet()) {
            JsonObject animations = new JsonObject();
            for (var animation : file.getValue().animations.values()) {
                JsonObject value = new JsonObject();
                if (Float.isFinite(animation.length)) value.addProperty("animation_length", animation.length);
                else if (animation.length != Float.POSITIVE_INFINITY) throw unsupported("Invalid animation length sentinel");
                switch(animation.loopMode) {
                    case 0 -> value.addProperty("loop", false); case 1 -> value.addProperty("loop", true);
                    case 2 -> { } case 3 -> value.addProperty("loop", "hold_on_last_frame"); default -> throw unsupported("Unknown animation loop mode");
                }
                if (animation.blendWeight != null) value.add("blend_weight", scalar(animation.blendWeight));
                JsonObject bones = new JsonObject();
                for (var bone : animation.boneAnimations) {
                    JsonObject channels = new JsonObject(); channel(channels, "rotation", bone.rotation); channel(channels, "position", bone.position); channel(channels, "scale", bone.scale);
                    if (bones.has(bone.boneName)) throw unsupported("Duplicate animation bone"); bones.add(bone.boneName, channels);
                }
                value.add("bones", bones); JsonObject timeline = new JsonObject();
                for (var event : animation.timelineEvents) {
                    String time = time(event.timestamp); JsonArray events = timeline.has(time) ? timeline.getAsJsonArray(time) : new JsonArray();
                    event.events.forEach(events::add); timeline.add(time, events);
                }
                if (!timeline.isEmpty()) value.add("timeline", timeline);
                JsonObject sounds = new JsonObject();
                for (var sound : animation.soundEffects) {
                    String time = time(sound.timestamp); if (sounds.has(time)) throw unsupported("Multiple sounds at one timestamp");
                    JsonObject effect = new JsonObject(); effect.addProperty("effect", sound.effectName); sounds.add(time, effect);
                }
                if (!sounds.isEmpty()) value.add("sound_effects", sounds);
                if (animations.has(animation.name)) throw unsupported("Duplicate animation name"); animations.add(animation.name, value);
            }
            JsonObject document = new JsonObject(); document.addProperty("format_version", "1.8.0"); document.add("animations", animations);
            String path = "animations/" + namespace + "/" + leaf(file.getKey(), ".json"); json(path, document); references.addProperty(file.getKey(), path);
        }
        return references;
    }
    private static void channel(JsonObject bone, String name, List<RawKeyframe> frames) {
        if (frames.isEmpty()) return;
        JsonObject out = new JsonObject();
        for (var frame : frames) {
            String time = time(frame.timestamp); if (out.has(time)) throw unsupported("Duplicate animation keyframe");
            JsonObject keyframe = new JsonObject();
            JsonArray post = new JsonArray(); for (Object item : frame.postData) post.add(scalar(item)); keyframe.add("post", post);
            if (frame.hasPreData) { JsonArray pre = new JsonArray(); for (Object item : frame.preData) pre.add(scalar(item)); keyframe.add("pre", pre); }
            if (frame.interpolationMode == 1) keyframe.addProperty("lerp_mode", "step");
            else if (frame.interpolationMode == 2) keyframe.addProperty("lerp_mode", "catmullrom");
            else if (frame.interpolationMode != 0) throw unsupported("Unknown keyframe interpolation");
            out.add(time, keyframe);
        }
        bone.add(name, out);
    }
    private JsonArray controllers(String namespace, Map<String, RawAnimationController> controllers) {
        JsonArray references = new JsonArray();
        if (controllers.isEmpty()) return references;
        JsonObject content = new JsonObject();
        for (var controller : controllers.values()) {
            JsonObject c = new JsonObject(); c.addProperty("initial_state", controller.initialState); JsonObject states = new JsonObject();
            for (var state : controller.states) {
                JsonObject s = new JsonObject(); JsonArray animations = conditional(state.animations), transitions = conditional(state.transitions);
                s.add("animations", animations); s.add("transitions", transitions);
                s.add("on_entry", array(state.onEntry)); s.add("on_exit", array(state.onExit)); s.add("sound_effects", array(state.soundEffects));
                if (state.blendTransitions.isEmpty()) s.addProperty("blend_transition", state.blendTransitionValue);
                else { JsonObject curve = new JsonObject(); state.blendTransitions.forEach((k,v) -> curve.addProperty(time(k),v)); s.add("blend_transition", curve); }
                s.addProperty("blend_via_shortest_path", state.blendViaShortestPath);
                if (states.has(state.name)) throw unsupported("Duplicate controller state"); states.add(state.name,s);
            }
            c.add("states",states); if (content.has(controller.name)) throw unsupported("Duplicate controller name"); content.add(controller.name,c);
        }
        JsonObject document = new JsonObject(); document.addProperty("format_version","1.10.0"); document.add("animation_controllers",content);
        String path = "animation_controllers/" + namespace + ".json"; json(path,document); references.add(path); return references;
    }
    private static JsonArray conditional(Map<String,String> values) {
        JsonArray out = new JsonArray(); values.forEach((key,value) -> { JsonObject item=new JsonObject();item.addProperty(key,value);out.add(item); });return out;
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
        if(files.size()>=4096 || data.length>64*1024*1024L-bytes)throw unsupported("Resource package size limit");
        files.put(path,data.clone()); bytes+=data.length;
    }
    private static String leaf(String name,String suffix) {
        if(name==null || name.isBlank() || name.contains("/") || name.contains("\\"))throw unsupported("Invalid resource name");
        String result=name.endsWith(suffix)?name:name+suffix;safePath(result);return result;
    }
    public static void safePath(String path) {
        if(path==null || path.length()>1024 || path.startsWith("/") || path.matches(".*[\\\\:\\x00<>\"|?*].*"))throw unsupported("Unsafe resource path");
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
