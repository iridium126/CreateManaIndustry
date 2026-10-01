package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.util.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.simibubi.create.AllPartialModels;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.lwjgl.BufferUtils;

/** Reload-time extraction from Create's actual baked models, including addon package styles. */
public final class PackageModelCache {
    public record Style(int box,int rig) {}
    public record Baked(Map<ResourceLocation,Style> styles,ByteBuffer vertices,ByteBuffer ranges,int meshCount,ByteBuffer attributes) {
        public Baked {styles=Map.copyOf(styles);}
        public void upload(PackagePoolGpu gpu) {gpu.uploadMeshes(vertices.duplicate(),ranges.duplicate(),meshCount,attributes.duplicate());}
    }
    private PackageModelCache() {}

    /** Unsupported/tinted custom models are omitted and must stay Create-owned. No world access occurs here. */
    public static Baked bake() {
        RenderSystem.assertOnRenderThread();
        List<float[]> data=new ArrayList<>(),normals=new ArrayList<>();List<int[]> spans=new ArrayList<>();
        Map<ResourceLocation,Style> styles=new LinkedHashMap<>();
        List<ResourceLocation> keys=new ArrayList<>(AllPartialModels.PACKAGES.keySet());
        keys.sort(Comparator.comparing(ResourceLocation::toString));
        for(ResourceLocation key:keys) {
            Mesh box=mesh(AllPartialModels.PACKAGES.get(key)),rig=mesh(AllPartialModels.PACKAGE_RIGGING.get(key));
            if(box==null)continue;
            int b=append(data,spans,box.vertices),r=rig==null?PackagePoolGpu.NO_MESH:append(data,spans,rig.vertices);
            normals.add(box.normals);if(rig!=null)normals.add(rig.normals);
            styles.put(key,new Style(b,r));
        }
        int floats=data.stream().mapToInt(a->a.length).sum();
        ByteBuffer vertices=BufferUtils.createByteBuffer(floats*4),ranges=BufferUtils.createByteBuffer(spans.size()*16);
        for(float[] mesh:data)for(float v:mesh)vertices.putFloat(v);
        for(int[] span:spans)for(int v:span)ranges.putInt(v);
        ByteBuffer rawNormals=BufferUtils.createByteBuffer(floats);
        for(float[] mesh:normals)for(float v:mesh)rawNormals.putFloat(v);
        vertices.flip();ranges.flip();rawNormals.flip();
        return new Baked(styles,vertices,ranges,spans.size(),PackageMeshAttributes.build(vertices,ranges,spans.size(),rawNormals));
    }
    private static int append(List<float[]> data,List<int[]> spans,float[] mesh) {
        int first=data.stream().mapToInt(a->a.length/12).sum();
        float radius=0;
        for(int p=0;p<mesh.length;p+=12) {
            float x=mesh[p]-.5f,y=mesh[p+1]-.5f,z=mesh[p+2]-.5f;
            radius=Math.max(radius,(float)Math.sqrt(x*x+y*y+z*z));
        }
        int index=spans.size();data.add(mesh);
        spans.add(new int[]{first,mesh.length/12,Float.floatToRawIntBits(radius),0});return index;
    }
    private record Mesh(float[] vertices,float[] normals) {}
    private static Mesh mesh(PartialModel partial) {
        if(partial==null)return null;
        List<BakedQuad> quads=new ArrayList<>();RandomSource random=RandomSource.create(42);
        try {
            var model=partial.get();
            for(Direction face:Direction.values()) {
                random.setSeed(42);quads.addAll(model.getQuads(Blocks.AIR.defaultBlockState(),face,random,ModelData.EMPTY,null));
            }
            random.setSeed(42);quads.addAll(model.getQuads(Blocks.AIR.defaultBlockState(),null,random,ModelData.EMPTY,null));
        } catch(RuntimeException unsupported) {return null;}
        if(quads.isEmpty())return null;
        float[] out=new float[quads.size()*6*12],normals=new float[quads.size()*6*3];int p=0,np=0;
        for(BakedQuad quad:quads) {
            int[] packed=quad.getVertices();if(quad.isTinted() || packed.length!=32)return null;
            for(int v:new int[]{0,1,2,2,3,0}) {
                int b=v*8;
                for(int j=0;j<3;j++)out[p++]=Float.intBitsToFloat(packed[b+j]);
                out[p++]=Float.intBitsToFloat(packed[b+4]);out[p++]=Float.intBitsToFloat(packed[b+5]);
                int normal=packed[b+7];
                for(int j=0;j<3;j++) {
                    float n=normal==0?(j==0?quad.getDirection().getStepX():j==1?quad.getDirection().getStepY():quad.getDirection().getStepZ())
                            :(byte)(normal>>(j*8))/127f;
                    out[p++]=quad.isShade()?n:0;
                    normals[np++]=n;
                }
                int color=packed[b+3];
                for(int j=0;j<4;j++)out[p++]=((color>>(j*8))&255)/255f;
            }
        }
        for(float f:out)if(!Float.isFinite(f))return null;
        for(float f:normals)if(!Float.isFinite(f))return null;
        return new Mesh(out,normals);
    }
}
