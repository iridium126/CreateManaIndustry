// Adapted from OpenYSM (MIT); see licenses/openysm.
package com.iridium126.createmanaindustry.compat.ysm.internal.resource;

import com.iridium126.createmanaindustry.compat.ysm.internal.pojo.RawYsmModel;
import com.iridium126.createmanaindustry.compat.ysm.internal.security.YSMByteBuf;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class YSMBinaryDeserializer implements AutoCloseable{

    private final YSMByteBuf reader;
    private final int format;
    private final RawYsmModel model;

    public YSMBinaryDeserializer(byte[] decompressedData) {
        this.reader = new YSMByteBuf(decompressedData);
        this.format = resourceFormat(this.reader.readDword());
        this.model = new RawYsmModel();
        this.model.formatVersion = this.format;
    }

    public YSMBinaryDeserializer(byte[] decompressedData, int expectedFormat) {
        this.reader = new YSMByteBuf(decompressedData);
        this.format = resourceFormat(this.reader.readDword());
        if (this.format != expectedFormat)
            throw new IllegalArgumentException("YSM header/resource format mismatch: " + expectedFormat + " != " + this.format);
        this.model = new RawYsmModel();
        this.model.formatVersion = this.format;
    }

    private static int resourceFormat(long value) {
        if (value < 1 || value > Integer.MAX_VALUE) throw new IllegalArgumentException("Unsupported YSM resource format: " + value);
        return (int) value;
    }


    private RawYsmModel deserializeInternal(boolean closeOnExit) {

        if (format < 4) {
            deserializeLegacyV1();
        } else if (format <= 15) {
            deserializeLegacyV15();
        } else {
            deserializeModern();
        }
        if (closeOnExit) {
            this.reader.close();
        }

        return model;
    }

    public RawYsmModel deserialize() {
        return deserializeInternal(true);
    }

    public RawYsmModel deserializeKeepOpen() {
        return deserializeInternal(false);
    }

    private void deserializeLegacyV1() {
        int unknownNeedSkipBytes = reader.readVarInt();
        reader.skipBytes(unknownNeedSkipBytes);

        List<RawYsmModel.RawGeometry> tempModels = new ArrayList<>();
        int modelCount = reader.readCount();
        for (int i = 0; i < modelCount; ++i) {
            int modelId = reader.readVarInt(); // 棄用或僅作為內部ID
            int unknownMustBeOneFlag = reader.readVarInt();
            if (unknownMustBeOneFlag != 1) throw new RuntimeException("Expected 1");
            RawYsmModel.RawGeometry rawGeometry = parseModels();
            rawGeometry.modelType = modelId;
            tempModels.add(rawGeometry);
        }
        assignMainModels(tempModels);

        Map<Integer, RawYsmModel.RawAnimationFile> tempAnims = new HashMap<>();
        int animationBlobCount = reader.readCount();
        for (int i = 0; i < animationBlobCount; ++i) {
            int animationId = reader.readVarInt();
            int unknownPadding = reader.readVarInt();
            if (unknownPadding != 1) throw new RuntimeException("Expected 1");
            RawYsmModel.RawAnimationFile rawAnimationFile = parseAnimations();
            model.mainEntity.animationFiles.put(getAnimKeyFromType(animationId), rawAnimationFile);
            rawAnimationFile.animType = animationId;
            tempAnims.put(animationId, rawAnimationFile);
        }

        List<RawYsmModel.RawTexture> tempTextures = new ArrayList<>();
        int customTextureCount = reader.readCount();
        for (int i = 0; i < customTextureCount; ++i) {
            RawYsmModel.RawTexture tex = new RawYsmModel.RawTexture();
            tex.name = reader.readString();
            if (format < 4) {
                int unknownFormatFlag = reader.readVarInt();
                if (unknownFormatFlag != 0x01) throw new RuntimeException("Expected 0x01");
            }
            tex.data = reader.readByteArray();
            tex.width = reader.readVarInt();
            tex.height = reader.readVarInt();
            tex.imageFormat = -1; // RGBA
            model.mainEntity.textures.put(tex.name, tex);
            tempTextures.add(tex);
        }

        // Tables回填Hash
        int modelTableSize = reader.readCount();
        for (int i = 0; i < modelTableSize; ++i) {
            int modelId = reader.readVarInt();
            String modelHash = reader.readString();
            for (RawYsmModel.RawGeometry tempModel : tempModels) {
                if (tempModel.modelType == modelId)
                    tempModel.sha256 = modelHash;
            }
        }

        int animationTableSize = reader.readCount();
        for (int i = 0; i < animationTableSize; ++i) {
            int animationId = reader.readVarInt();
            tempAnims.get(animationId).fileHash = reader.readString();
        }

        int textureTableSize = reader.readCount();
        for (int i = 0; i < textureTableSize; ++i) {
            String textureName = reader.readString();
            String textureHash = reader.readString();
            RawYsmModel.RawTexture tex = model.mainEntity.textures.get(textureName);
            if (tex != null) tex.hash = textureHash;
        }

        String unkString = reader.readString();

    }

    private void deserializeLegacyV15() {
        int unknownNeedSkipBytes = reader.readVarInt();
        reader.skipBytes(unknownNeedSkipBytes);

        List<RawYsmModel.RawGeometry> tempModels = new ArrayList<>();
        int modelCount = reader.readCount();
        for (int i = 0; i < modelCount; ++i) {
            int modelId = reader.readVarInt();
            int unknownPadding = reader.readVarInt();
            if (unknownPadding != 1) throw new RuntimeException("Expected 1");
            RawYsmModel.RawGeometry rawGeometry = parseModels();
            rawGeometry.modelType = modelId;
            tempModels.add(rawGeometry);
        }

        assignMainModels(tempModels); //TODO

        Map<Integer, RawYsmModel.RawAnimationFile> tempAnims = new HashMap<>();
        int animationBlobCount = reader.readCount();
        for (int i = 0; i < animationBlobCount; ++i) {
            int animationId = reader.readVarInt();
            int unknownPadding = reader.readVarInt();
            if (unknownPadding != 1) throw new RuntimeException("Expected 1");
            RawYsmModel.RawAnimationFile rawAnimationFile = parseAnimations();
            model.mainEntity.animationFiles.put(getAnimKeyFromType(animationId), rawAnimationFile);
            rawAnimationFile.animType = animationId;
            tempAnims.put(animationId, rawAnimationFile);
        }

        List<RawYsmModel.RawAnimationController> tempControllers = new ArrayList<>();
        if (format > 9) {
            parseAnimationControllers(model.mainEntity.animationControllers, tempControllers);
            int animationControllerTableSize = reader.readCount();
            for (int i = 0; i < animationControllerTableSize; ++i) {
                String controllerName = reader.readString();
                String controllerHash = reader.readString();
                setControllerHash(model.mainEntity.animationControllers, controllerName, controllerHash);
            }
        }

        List<RawYsmModel.RawTexture> tempTextures = new ArrayList<>();
        int customTextureCount = reader.readCount();
        for (int i = 0; i < customTextureCount; ++i) {
            RawYsmModel.RawTexture tex = new RawYsmModel.RawTexture();
            tex.name = reader.readString();
            tex.data = reader.readByteArray();
            tex.width = reader.readVarInt();
            tex.height = reader.readVarInt();
            tex.imageFormat = -1; // RGBA

            int subTextureSize = reader.readCount();
            for (int j = 0; j < subTextureSize; ++j) {
                RawYsmModel.RawTexture.SubTexture sub = new RawYsmModel.RawTexture.SubTexture();
                sub.specularType = reader.readVarInt();
                sub.data = reader.readByteArray();
                sub.width = reader.readVarInt();
                sub.height = reader.readVarInt();
                sub.imageFormat = -1; // RGBA
                tex.subTextures.add(sub);
            }

            // 特殊處理一下
            if ("/ARROW\\".equals(tex.name))
                model.projectiles.get("minecraft:arrow").textures.put(tex.name, tex);

            model.mainEntity.textures.put(tex.name, tex);

            tempTextures.add(tex);
        }

        if (format > 9) {
            parseSoundFiles();
            int soundTableCount = reader.readCount();
            for (int i = 0; i < soundTableCount; ++i) {
                String soundName = reader.readString();
                String soundHash = reader.readString();
                RawYsmModel.RawDataFile sf = model.soundFiles.get(soundName);
                if (sf != null) sf.hash = soundHash;
            }
        }

        int extraTextureCount = reader.readCount();
        for (int i = 0; i < extraTextureCount; ++i) {
            RawYsmModel.RawTexture tex = new RawYsmModel.RawTexture();
            tex.name = reader.readString();
            tex.data = reader.readByteArray();
            tex.width = reader.readVarInt();
            tex.height = reader.readVarInt();
            tex.imageFormat = -1; // RGBA
            model.mainEntity.textures.put(tex.name, tex); // 作為額外紋理存入主實體
        }

        // Tables回填Hash
        int modelTableSize = reader.readCount();
        for (int i = 0; i < modelTableSize; ++i) {
            int modelId = reader.readVarInt();
            String modelHash = reader.readString();
            for (RawYsmModel.RawGeometry tempModel : tempModels) {
                if (tempModel.modelType == modelId)
                    tempModel.sha256 = modelHash;
            }
        }

        int animationTableSize = reader.readCount();
        for (int i = 0; i < animationTableSize; ++i) {
            int animationId = reader.readVarInt();
            String animationHash = reader.readString();
            if (tempAnims.get(animationId) == null) {
                "".hashCode();

            }
            tempAnims.get(animationId).fileHash = animationHash;
        }

        int textureTableSize = reader.readCount();
        for (int i = 0; i < textureTableSize; ++i) {
            String textureName = reader.readString();
            String textureHash = reader.readString();
            RawYsmModel.RawTexture tex = model.mainEntity.textures.get(textureName);
            if (tex != null) {
                tex.hash = textureHash;
            }
            int subTextureSize = reader.readCount();
            for (int j = 0; j < subTextureSize; ++j) {
                int specularType = reader.readVarInt();
                String specialImageHash = reader.readString();
                // 修復舊版丟棄子紋理Hash
                if (tex != null) {
                    for (RawYsmModel.RawTexture.SubTexture sub : tex.subTextures) {
                        if (sub.specularType == specularType) {
                            sub.hash = specialImageHash;
                            break;
                        }
                    }
                }
            }
        }
        for (RawYsmModel.RawSubEntity value : model.projectiles.values()) {
            for (RawYsmModel.RawTexture rawTexture : value.textures.values()) {
                RawYsmModel.RawTexture remove = model.mainEntity.textures.remove(rawTexture.name);
                if (remove != null) {

                }
            }
        }

        parseYSMJson();
    }

    private void deserializeModern() {
        parseSoundFiles();
        parseFunctionFiles();
        parseLanguageFiles();

        if (format < 26) {
            int subEntityTotalCount = reader.readCount();
            for (int i = 0; i < subEntityTotalCount; ++i) {
                parseSubEntity("SubEntity", i);
            }
            int footerFlag = reader.readVarInt(); // always 00
        } else {
            int vehiclesTotalCount = reader.readCount();
            for (int i = 0; i < vehiclesTotalCount; ++i) parseSubEntity("vehicle", i);

            int projectilesTotalCount = reader.readCount();
            for (int i = 0; i < projectilesTotalCount; ++i) parseSubEntity("projectiles", i);
        }

        int unknownEntityFlag = reader.readVarInt();
        if (unknownEntityFlag != 1) throw new RuntimeException("Expected 1 after SubEntities");

        int animationCount = reader.readCount();
        for (int i = 0; i < animationCount; ++i) {
            int type = reader.readVarInt();
            String hash = reader.readString();

            RawYsmModel.RawAnimationFile animRef = parseAnimations();
            model.mainEntity.animationFiles.put(
                    getAnimKeyFromType(type),
                    animRef
            );
            animRef.animType = type;
            animRef.fileHash = hash;
        }

        parseAnimationControllers(model.mainEntity.animationControllers, null);
        parseTextureFiles(model.mainEntity.textures);

        int modelTotalCount = reader.readCount();
        List<RawYsmModel.RawGeometry> tempMainModels = new ArrayList<>();
        for (int i = 0; i < modelTotalCount; ++i) {
            int modelType = reader.readVarInt();
            String hash = reader.readString();

            RawYsmModel.RawGeometry geoRef = parseModels();
            geoRef.sha256 = hash;
            geoRef.modelType = modelType;
            tempMainModels.add(geoRef);

        }
        assignMainModels(tempMainModels);

        parseYSMJson();
    }

    private void parseSubEntity(String categoryName, int index) {
        RawYsmModel.RawSubEntity subEntity = new RawYsmModel.RawSubEntity();
        String subModuleName = "";
        if (format <= 26) {
            subModuleName = reader.readString();
            subEntity.identifier = subModuleName;
        } else {
            subEntity.identifier = categoryName + "_" + index; // >=26沒有Header Name
        }
        boolean hasSubAnimation = reader.readVarInt() != 0;
        if (hasSubAnimation) {
            String hash = format > 15 ? reader.readString() : "";
            RawYsmModel.RawAnimationFile rawAnimationFile = parseAnimations();
            subEntity.animationFiles.put(categoryName, rawAnimationFile);
            rawAnimationFile.fileHash = hash;
        }

        boolean hasSubController = reader.readVarInt() != 0;
        if (hasSubController) {
            String controllerHash = reader.readString();
            parseControllerContents(subEntity.animationControllers, null, controllerHash, -1, categoryName + "/" + subModuleName);
        }

        RawYsmModel.RawTexture baseTex = new RawYsmModel.RawTexture();
        SpecialImageResult imgRes = parseSpecialImage();
        baseTex.hash = imgRes.hash;
        baseTex.data = imgRes.data;
        baseTex.width = reader.readVarInt();
        baseTex.height = reader.readVarInt();
        baseTex.imageFormat = reader.readVarInt();
        baseTex.unknownFlag = reader.readVarInt();
        baseTex.name = "base_texture_" + index;
        subEntity.textures.put(baseTex.name, baseTex);

        // Sub Textures
        int subTextureSize = reader.readCount();
        for (int i = 0; i < subTextureSize; ++i) {
            RawYsmModel.RawTexture.SubTexture subTex = new RawYsmModel.RawTexture.SubTexture();
            subTex.specularType = reader.readVarInt();
            SpecialImageResult specRes = parseSpecialImage();
            subTex.hash = specRes.hash;
            subTex.data = specRes.data;
            subTex.width = reader.readVarInt();
            subTex.height = reader.readVarInt();
            subTex.imageFormat = reader.readVarInt();
            subTex.unknownFlag = reader.readVarInt();
            baseTex.subTextures.add(subTex);
        }

        String modelHash = reader.readString();
        subEntity.model = parseModels();
        subEntity.model.sha256 = modelHash;


        if (format > 26) {
            int subModelCount = reader.readCount();
            for (int i = 0; i < subModelCount; i++) {
                String name = normalizeSubModelName(reader.readString());
                RawYsmModel.RawSubEntity alias = copySubEntity(subEntity);
                alias.identifier = name;
                alias.matchIds = new String[]{name};
                categoryMap(categoryName, name).putIfAbsent(name, alias);
            }
        } else {
            subModuleName = normalizeSubModelName(subModuleName);
            subEntity.identifier = subModuleName;
            subEntity.matchIds = new String[]{subModuleName};
            categoryMap(categoryName, subModuleName).putIfAbsent(subModuleName, subEntity);
        }
    }

    private Map<String, RawYsmModel.RawSubEntity> categoryMap(String category, String modelName) {
        if ("vehicle".equals(category)) return model.vehicles;
        if ("projectiles".equals(category)) return model.projectiles;
        if ("SubEntity".equals(category)) {
            return switch (modelName) {
                case "arrow", "trident" -> model.projectiles;
                case "horse", "minecart", "boat" -> model.vehicles;
                default -> model.subEntities;
            };
        }
        throw new IllegalArgumentException("Unknown YSM sub-entity category: " + category);
    }

    private static String normalizeSubModelName(String name) {
        int namespace = name.indexOf("minecraft:");
        return namespace < 0 ? name : name.substring(namespace + "minecraft:".length());
    }

    private static RawYsmModel.RawSubEntity copySubEntity(RawYsmModel.RawSubEntity source) {
        RawYsmModel.RawSubEntity copy = new RawYsmModel.RawSubEntity();
        copy.matchIds = source.matchIds == null ? null : source.matchIds.clone();
        copy.model = source.model;
        copy.textures.putAll(source.textures);
        copy.animationFiles.putAll(source.animationFiles);
        copy.animationControllers.putAll(source.animationControllers);
        return copy;
    }

    private RawYsmModel.RawGeometry parseModels() {
        RawYsmModel.RawGeometry geo = new RawYsmModel.RawGeometry();

        int boneCount = reader.readCount();
        for (int i = 0; i < boneCount; i++) {
            RawYsmModel.RawBone bone = new RawYsmModel.RawBone();
            bone.parentName = reader.readString();
            int cubeCount = reader.readCount();

            for (int j = 0; j < cubeCount; j++) {
                RawYsmModel.RawCube cube = new RawYsmModel.RawCube();
                int faceCount = reader.readCount();
                for (int k = 0; k < faceCount; k++) {
                    RawYsmModel.RawFace face = new RawYsmModel.RawFace();
                    face.normal = readVector3D(); // normal
                    for (int v = 0; v < 4; v++) {
                        face.positions[v] = readVector3D(); // position
                        face.u[v] = reader.readFloat(); // u
                        face.v[v] = reader.readFloat(); // v
                    }
                    cube.faces.add(face);
                }
                cube.unkInt1 = reader.readVarInt();
                cube.unkInt2 = reader.readVarInt();
                cube.unkInt3 = reader.readVarInt();
                bone.cubes.add(cube);
            }

            bone.name = reader.readString();
            bone.unkPad1 = reader.readVarInt();
            bone.unkPad2 = reader.readVarInt();
            bone.unkPad3 = reader.readVarInt();
            bone.unkPad4 = reader.readVarInt();
            bone.unkPad5 = reader.readVarInt();

            bone.pivot = readVector3D();
            bone.rotation = readVector3D();
            geo.bones.add(bone);
        }


        geo.identifier = reader.readString();
        geo.textureHeight = reader.readFloat();
        geo.textureWidth = reader.readFloat();
        geo.visibleBoundsHeight = reader.readFloat();
        geo.visibleBoundsWidth = reader.readFloat();

        int visibleBoundsOffsetSize = reader.readCount();
        geo.visibleBoundsOffset = new float[visibleBoundsOffsetSize];
        for (int i = 0; i < visibleBoundsOffsetSize; i++) {
            geo.visibleBoundsOffset[i] = reader.readFloat();
        }

        geo.unkFloat1 = reader.readFloat();
        geo.unkFloat2 = reader.readFloat();

        int hasInfoJsonFlag = reader.readVarInt();
        if (hasInfoJsonFlag > 0) {
            parseLegacyYSMInfo();
        }

        geo.footerPad1 = reader.readVarInt();
        geo.footerPad2 = reader.readVarInt();
        geo.footerPad3 = reader.readVarInt();

        return geo;
    }

    private void parseYSMJson() {
        model.properties.sha256 = reader.readString();
        int isNewVersionYsm = reader.readVarInt();

        if (isNewVersionYsm == 0) return;
        {
            if (format <= 15) {
                reader.readVarInt(); // unknown
            }

            model.metadata.name = reader.readString();
            model.metadata.tips = reader.readString();
            model.metadata.licenseType = reader.readString();
            model.metadata.licenseDescription = reader.readString();

            int authorsCount = reader.readCount();
            for (int i = 0; i < authorsCount; i++) {
                RawYsmModel.RawMetadata.Author author = new RawYsmModel.RawMetadata.Author();
                author.name = reader.readString();
                author.role = reader.readString();
                int contactsCount = reader.readCount();
                for (int j = 0; j < contactsCount; j++) {
                    author.contacts.put(reader.readString(), reader.readString());
                }
                author.comment = reader.readString();
                model.metadata.authors.add(author);
            }

            int linksCount = reader.readCount();
            for (int i = 0; i < linksCount; i++) {
                model.metadata.links.put(reader.readString(), reader.readString());
            }
        }

        model.properties.widthScale = reader.readFloat();
        model.properties.heightScale = reader.readFloat();

        int extraAnimationsCount = reader.readCount();
        for (int i = 0; i < extraAnimationsCount; i++) {
            model.properties.extraAnimations.put(reader.readString(), reader.readString());
        }

        if (format > 9) {
            int extraAnimationButtonsCount = reader.readCount();
            for (int i = 0; i < extraAnimationButtonsCount; i++) {
                RawYsmModel.ExtraAnimationButton btn = new RawYsmModel.ExtraAnimationButton();
                btn.id = reader.readString();
                btn.name = reader.readString();
                reader.readVarInt(); // buttonPadding

                int configurationFormsCount = reader.readCount();
                for (int j = 0; j < configurationFormsCount; j++) {
                    RawYsmModel.ConfigForm form = new RawYsmModel.ConfigForm();
                    form.type = reader.readString();
                    form.title = reader.readString();
                    form.description = reader.readString();
                    form.defaultValue = reader.readString();
                    form.step = reader.readFloat();
                    form.min = reader.readFloat();
                    form.max = reader.readFloat();
                    int labelsSize = reader.readCount();
                    for (int l = 0; l < labelsSize; l++) {
                        form.labels.put(reader.readString(), reader.readString());
                    }
                    btn.forms.add(form);
                }
                model.properties.extraAnimationButtons.add(btn);
            }

            int extraAnimationClassifyCount = reader.readCount();
            for (int i = 0; i < extraAnimationClassifyCount; i++) {
                RawYsmModel.ExtraAnimationClassify classify = new RawYsmModel.ExtraAnimationClassify();
                classify.id = reader.readString();
                int classificationExtrasCount = reader.readCount();
                for (int j = 0; j < classificationExtrasCount; j++) {
                    classify.extras.put(reader.readString(), reader.readString());
                }
                model.properties.extraAnimationClassifies.add(classify);
            }
        }

        model.properties.defaultTexture = reader.readString();
        model.properties.previewAnimation = reader.readString();
        model.properties.isFree = reader.readVarInt() != 0;

        if (format > 4) {
            model.properties.renderLayersFirst = reader.readVarInt() != 0;
        }

        if (format >= 15) {
            model.properties.allCutout = reader.readVarInt() != 0;
            model.properties.disablePreviewRotation = reader.readVarInt() != 0;
        }

        if (format > 15) {
            model.properties.guiNoLighting = reader.readVarInt() != 0;
            if (format >= 32) {
                model.properties.mergeMultilineExpr = reader.readVarInt() != 0;
            }

            model.properties.guiForeground = reader.readString();
            model.properties.guiBackground = reader.readString();

            int avatarsCount = reader.readCount();
            for (int i = 0; i < avatarsCount; i++) {
                RawYsmModel.RawImage avatar = new RawYsmModel.RawImage();
                avatar.name = reader.readString();
                avatar.data = reader.readByteArray();
                avatar.width = reader.readVarInt();
                avatar.height = reader.readVarInt();
                avatar.format = reader.readVarInt();
                avatar.unknownFlag = reader.readVarInt();
                avatar.isPng = false;
                if (i < model.metadata.authors.size()) {
                    model.metadata.authors.get(i).avatar = avatar.name;
                    model.metadata.authors.get(i).avatarImage = avatar;
                } else {
                    model.metadata.extraAvatars.add(avatar);
                }
            }
        }

        if (format <= 15) return;
        if (model.properties.guiBackground.isEmpty() && model.properties.guiForeground.isEmpty()) return;

        int backgroundImagesCount = reader.readCount();
        for (int i = 0; i < backgroundImagesCount; i++) {
            RawYsmModel.RawImage bg = new RawYsmModel.RawImage();
            bg.name = reader.readString();
            bg.data = reader.readByteArray();
            bg.width = reader.readVarInt();
            bg.height = reader.readVarInt();
            bg.format = reader.readVarInt();
            bg.unknownFlag = reader.readVarInt();
            model.properties.backgroundImages.add(bg);
        }
    }

    private void parseLegacyYSMInfo() {
        model.metadata.name = reader.readString();
        model.metadata.tips = reader.readString();
        int extraAnimationsCount = reader.readCount();
        for (int i = 0; i < extraAnimationsCount; i++) {
            reader.readString(); // extra animation name
        }
        int authorsCount = reader.readCount();
        for (int i = 0; i < authorsCount; i++) {
            RawYsmModel.RawMetadata.Author author = new RawYsmModel.RawMetadata.Author();
            author.name = reader.readString();
            model.metadata.authors.add(author);
        }
        model.metadata.licenseType = reader.readString();
        model.properties.isFree = reader.readVarInt() != 0;
    }

    private RawYsmModel.RawAnimationFile parseAnimations() {
        RawYsmModel.RawAnimationFile animFile = new RawYsmModel.RawAnimationFile();

        int animationCount = reader.readCount();
        for (int animIndex = 0; animIndex < animationCount; ++animIndex) {
            RawYsmModel.RawAnimation anim = new RawYsmModel.RawAnimation();
            anim.name = reader.readString();
            anim.length = reader.readFloat() / 20.0f;
            anim.loopMode = reader.readVarInt();

            if (format > 9) {
                anim.unkInt1 = reader.readVarInt();
                anim.unkInt2 = reader.readVarInt();
                if (anim.unkInt1 != 0 || anim.unkInt2 != 0)
                    throw new IllegalArgumentException("Unsupported YSM animation flags");
                int blendWeightMolangCount = reader.readCount();
                for (int i = 0; i < blendWeightMolangCount; i++) {
                    // 1 = float
                    // 2 = string
                    byte datatype = reader.readByte();

                    if (datatype == 0x01) {
                        anim.blendWeight = reader.readFloat();
                    } else if (datatype == 0x02) {
                        anim.blendWeight = reader.readString();
                    }
                }
                anim.unkInt4 = reader.readVarInt();
                if (anim.unkInt4 != 0) throw new IllegalArgumentException("Unsupported YSM animation flags");
            }

            int boneCount = reader.readCount();
            for (int i = 0; i < boneCount; ++i) {
                RawYsmModel.RawBoneAnimation ba = new RawYsmModel.RawBoneAnimation();
                ba.boneName = reader.readString();
                parseChannel(ba.rotation);
                parseChannel(ba.position);
                parseChannel(ba.scale);
                anim.boneAnimations.add(ba);
            }

            // Timeline
            int timelineEventGroupsCount = reader.readCount();
            for (int i = 0; i < timelineEventGroupsCount; ++i) {
                RawYsmModel.RawTimelineEvent event = new RawYsmModel.RawTimelineEvent();
                int timelineEventsCount = reader.readCount();
                for (int j = 0; j < timelineEventsCount; ++j) {
                    event.events.add(reader.readString());
                }
                event.timestamp = reader.readFloat() / 20.0f;
                anim.timelineEvents.add(event);
            }

            // Effects
            if (format > 9) {
                int soundEffectsCount = reader.readCount();
                for (int i = 0; i < soundEffectsCount; i++) {
                    RawYsmModel.RawSoundEffect sfx = new RawYsmModel.RawSoundEffect();
                    sfx.effectName = reader.readString();
                    sfx.timestamp = reader.readFloat() / 20.0f;
                    anim.soundEffects.add(sfx);
                }
            }
            animFile.animations.put(anim.name, anim);
        }

        return animFile;
    }

    private void parseChannel(List<RawYsmModel.RawKeyframe> channel) {
        int keyframeCount = reader.readCount();
        if (keyframeCount == 0) return;

        for (int i = 0; i < keyframeCount; i++) {
            RawYsmModel.RawKeyframe kf = new RawYsmModel.RawKeyframe();
            kf.timestamp = reader.readFloat() / 20.0f;
            kf.interpolationMode = reader.readVarInt();

            Object[] firstData = new Object[3];
            for (int j = 0; j < 3; j++) {
                byte datatype = reader.readByte();
                if (datatype == 0x01) {
                    firstData[j] = reader.readFloat();
                } else if (datatype == 0x02) {
                    firstData[j] = reader.readString();
                } else throw new IllegalArgumentException("Unsupported YSM keyframe value type: " + datatype);
            }

            int hasPreData = reader.readVarInt();
            if (hasPreData > 1) throw new IllegalArgumentException("Unsupported YSM keyframe pre-data flag");
            kf.hasPreData = hasPreData != 0;
            if (kf.hasPreData) {
                for (int j = 0; j < 3; j++) {
                    byte datatype = reader.readByte();
                    if (datatype == 0x01) {
                        kf.postData[j] = reader.readFloat();
                    } else if (datatype == 0x02) {
                        kf.postData[j] = reader.readString();
                    } else throw new IllegalArgumentException("Unsupported YSM keyframe value type: " + datatype);
                }

                kf.preData = firstData;
                kf.hasPreData = true;
            } else {

                kf.postData = firstData;
                kf.hasPreData = false;
            }
            channel.add(kf);
        }
    }

    private void parseAnimationControllers(Map<String, RawYsmModel.RawAnimationController> targetMap, List<RawYsmModel.RawAnimationController> outTempList) {
        int controllerFileCount = reader.readCount();
        for (int fileIndex = 0; fileIndex < controllerFileCount; fileIndex++) {
            String fileName;
            String hash = "";
            int legacyUnknown = -1;
            if (format <= 15) {
                fileName = "controller";
                legacyUnknown = reader.readVarInt();
            } else {
                fileName = reader.readString();
                hash = reader.readString();
            }
            parseControllerContents(targetMap, outTempList, hash, legacyUnknown, fileName);
        }
    }

    private void parseControllerContents(Map<String, RawYsmModel.RawAnimationController> targetMap,
            List<RawYsmModel.RawAnimationController> outTempList, String hash, int legacyUnknown, String fileName) {
        parseControllerEntries(reader.readCount(), targetMap, outTempList, hash, legacyUnknown, fileName);
    }

    private void parseControllerEntries(int controllerCount,
            Map<String, RawYsmModel.RawAnimationController> targetMap,
            List<RawYsmModel.RawAnimationController> outTempList, String hash, int legacyUnknown, String fileName) {
        for (int i = 0; i < controllerCount; i++) {
            RawYsmModel.RawAnimationController entry = new RawYsmModel.RawAnimationController();
            entry.animationName = reader.readString();
            entry.name = entry.animationName;
            entry.fileName = fileName;
            entry.initialState = reader.readString();
            entry.hash = hash;
            entry.legacyUnknownInt = legacyUnknown;

            int statesCount = reader.readCount();
            for (int s = 0; s < statesCount; s++) {
                RawYsmModel.RawControllerState state = new RawYsmModel.RawControllerState();
                state.name = reader.readString();
                int animationsSize = reader.readCount();
                for (int j = 0; j < animationsSize; j++) state.animations.put(reader.readString(), reader.readString());
                int transitionsSize = reader.readCount();
                for (int j = 0; j < transitionsSize; j++) state.transitions.put(reader.readString(), reader.readString());
                int onEntryCount = reader.readCount();
                for (int j = 0; j < onEntryCount; j++) state.onEntry.add(reader.readString());
                int onExitCount = reader.readCount();
                for (int j = 0; j < onExitCount; j++) state.onExit.add(reader.readString());
                if (reader.readVarInt() != 0) {
                    state.hasBlendTransitionValue = true;
                    state.blendTransitionValue = reader.readFloat();
                } else {
                    int blendTransitionsCount = reader.readCount();
                    for (int j = 0; j < blendTransitionsCount; j++) state.blendTransitions.put(reader.readFloat(), reader.readFloat());
                }
                state.blendViaShortestPath = reader.readVarInt() != 0;
                if (format > 26) {
                    int soundEffectsCount = reader.readCount();
                    for (int j = 0; j < soundEffectsCount; j++) state.soundEffects.add(reader.readString());
                }
                entry.states.add(state);
            }
            String key = fileName + "\u0000" + entry.animationName;
            targetMap.put(key, entry);
            if (outTempList != null) outTempList.add(entry);
        }
    }

    private static void setControllerHash(Map<String, RawYsmModel.RawAnimationController> controllers, String fileName, String hash) {
        for (RawYsmModel.RawAnimationController controller : controllers.values())
            if (fileName.equals(controller.fileName)) controller.hash = hash;
    }

    private void parseSoundFiles() {
        int soundCount = reader.readCount();
        for (int i = 0; i < soundCount; i++) {
            String soundName = reader.readString();
            String hash = "";
            if (format > 15) {
                hash = reader.readString(); // soundHash
            }
            byte[] data = reader.readByteArray();
            model.soundFiles.put(soundName, new RawYsmModel.RawDataFile(hash, data));
        }
    }

    private void parseFunctionFiles() {
        int functionCount = reader.readCount();
        for (int i = 0; i < functionCount; i++) {
            String functionName = reader.readString();
            String hash = reader.readString();
            byte[] data = reader.readByteArray();
            model.functionFiles.put(functionName, new RawYsmModel.RawDataFile(hash, data));
        }
    }

    private void parseLanguageFiles() {
        int languageCount = reader.readCount();
        for (int i = 0; i < languageCount; i++) {
            String languageName = reader.readString();
            String hash = reader.readString();
            int nodesCount = reader.readCount();
            Map<String, String> langMap = new java.util.LinkedHashMap<>();
            for (int j = 0; j < nodesCount; j++) {
                langMap.put(reader.readString(), reader.readString());
            }
            model.languageFiles.put(languageName, new RawYsmModel.RawLanguageFile(hash, langMap));
        }
    }

    private void parseTextureFiles(Map<String, RawYsmModel.RawTexture> targetMap) {
        int textureCount = reader.readCount();
        for (int i = 0; i < textureCount; i++) {
            RawYsmModel.RawTexture tex = new RawYsmModel.RawTexture();
            tex.name = reader.readString();
            tex.hash = reader.readString();
            tex.data = reader.readByteArray();
            tex.width = reader.readVarInt();
            tex.height = reader.readVarInt();
            tex.imageFormat = reader.readVarInt();
            tex.unknownFlag = reader.readVarInt();

            int subTextureSize = reader.readCount();
            for (int j = 0; j < subTextureSize; j++) {
                RawYsmModel.RawTexture.SubTexture subTex = new RawYsmModel.RawTexture.SubTexture();
                subTex.specularType = reader.readVarInt();
                SpecialImageResult specRes = parseSpecialImage();
                subTex.hash = specRes.hash;
                subTex.data = specRes.data;
                subTex.width = reader.readVarInt();
                subTex.height = reader.readVarInt();
                subTex.imageFormat = reader.readVarInt();
                subTex.unknownFlag = reader.readVarInt();
                tex.subTextures.add(subTex);
            }
            targetMap.put(tex.name, tex);
        }
    }

    private SpecialImageResult parseSpecialImage() {
        String imageHash = reader.readString();
        byte[] imageData = reader.readByteArray();
        return new SpecialImageResult(imageHash, imageData);
    }

    private float[] readVector3D() {
        return new float[]{reader.readFloat(), reader.readFloat(), reader.readFloat()};
    }

    private void assignMainModels(List<RawYsmModel.RawGeometry> tempMainModels) {
        for (RawYsmModel.RawGeometry tempMainModel : tempMainModels) {
            switch (tempMainModel.modelType) {
                case 1:
                    model.mainEntity.mainModel = tempMainModel;
                    break;
                case 2:
                    model.mainEntity.armModel = tempMainModel;
                    break;
            case 3:
                RawYsmModel.RawSubEntity subEntity = new RawYsmModel.RawSubEntity();
                subEntity.model = tempMainModel;
                subEntity.identifier = "arrow";
                subEntity.matchIds = new String[]{"arrow"};
                model.projectiles.put(subEntity.identifier, subEntity);
                    break;
                default:
                    throw new RuntimeException("Unknown model type: " + tempMainModel.modelType);
            }
        }
    }

    @Override
    public void close() throws Exception {
        if (reader != null) {
            reader.close();
        }
    }

    private static class SpecialImageResult {
        public String hash;
        public byte[] data;
        public SpecialImageResult(String hash, byte[] data) {
            this.hash = hash;
            this.data = data;
        }
    }

    public YSMByteBuf getReader() {
        return reader;
    }
    public static String getAnimKeyFromType(int type) {
        return switch (type) {
            case 1 -> "main";
            case 2 -> "arm";
            case 3 -> "extra";
            case 4 -> "tac";
            case 5 -> "arrow";
            case 6 -> "carryon";
            case 7 -> "parcool";
            case 8 -> "swem";
            case 9 -> "slashblade";
            case 10 -> "tlm";
            case 11 -> "fp_arm";
            case 12 -> "immersive_melodies";
            case 13 -> "irons_spell_books";
            default -> "unknown";
        };
    }}
