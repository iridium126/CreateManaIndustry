package com.iridium126.createmanaindustry.client.particles.shaderpack;

import net.irisshaders.iris.shaderpack.IdMap;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.lwjgl.opengl.GL20;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;

/** Effective pack identity, frozen with the compiler generation. Never inferred from a pack name. */
public record ModelEntityContext(int allayId) {
    public static final ModelEntityContext NEUTRAL=new ModelEntityContext(0);
    public static ModelEntityContext resolve(IdMap map){return resolve(map.getEntityIdMap());}
    public static ModelEntityContext resolve(it.unimi.dsi.fastutil.objects.Object2IntFunction<NamespacedId> ids){return new ModelEntityContext(ids.applyAsInt(new NamespacedId("minecraft","allay")));}
    public int entityId(boolean carrier){return carrier?0:allayId;}
    public void upload(int program,boolean carrier){
        integer(program,"entityId",entityId(carrier));
        integer(program,"blockEntityId",0);integer(program,"currentRenderedItemId",0);
        int color=GpuUniforms.loc(program,"entityColor");if(color>=0)GL20.glUniform4f(color,0,0,0,0);
    }
    private static void integer(int program,String name,int value){GpuUniforms.setIntUniform(program,name,value);}
}
