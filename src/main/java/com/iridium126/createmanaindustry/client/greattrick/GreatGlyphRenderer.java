package com.iridium126.createmanaindustry.client.greattrick;

import com.iridium126.createmanaindustry.compat.trickster.GreatPattern;
import com.iridium126.createmanaindustry.compat.trickster.GreatTrickGlyph;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.enjarai.trickster.Trickster;
import dev.enjarai.trickster.render.CircleRenderer;
import dev.enjarai.trickster.render.fragment.FragmentRenderer;
import dev.enjarai.trickster.spell.Fragment;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector2f;
import java.util.List;

/** Drawn dots and continuous lines share cyan ink; unused dots retain Trickster's theme. */
public final class GreatGlyphRenderer implements FragmentRenderer<GreatTrickGlyph> {
    private static final float INK_R = 0x44/255f, INK_G = 0xbc/255f, INK_B = 0xf9/255f;
    private static final ThreadLocal<Drawing> DRAWING = new ThreadLocal<>();
    private record Drawing(Fragment glyph,List<Integer> path) {}
    public interface DrawingScope extends AutoCloseable { void close(); }

    public static DrawingScope drawing(Fragment glyph,List<Integer> path) {
        Drawing previous = DRAWING.get();
        if (path == null) DRAWING.remove();
        else DRAWING.set(new Drawing(glyph,List.copyOf(path)));
        return () -> { if (previous == null) DRAWING.remove(); else DRAWING.set(previous); };
    }

    public static Vector2f dot(int i,float x,float y,float radius) {
        return new Vector2f((float)GreatGridGeometry.x(i,x,radius),(float)GreatGridGeometry.y(i,y,radius));
    }

    @Override
    public void render(GreatTrickGlyph glyph,PoseStack matrices,MultiBufferSource buffers,float x,float y,
            float radius,float alpha,float forcedAlpha,Vec3 normal,float delta,CircleRenderer renderer) {
        Drawing drawing = DRAWING.get();
        List<Integer> path = drawing != null && drawing.glyph() == glyph ? drawing.path() : null;
        draw(glyph.pattern(),matrices,buffers,x,y,radius/CircleRenderer.PATTERN_TO_PART_RATIO,
                alpha*Trickster.CONFIG.patternOpacity(),renderer,path);
    }

    @Override
    public float getProportionalHeight(GreatTrickGlyph glyph) { return 1f/CircleRenderer.PATTERN_TO_PART_RATIO; }

    @Override
    public boolean renderRedrawDots() { return false; }

    private static void draw(GreatPattern pattern,PoseStack matrices,MultiBufferSource buffers,
            float x,float y,float radius,float alpha,CircleRenderer renderer,List<Integer> path) {
        float pixelSize = radius/CircleRenderer.PART_PIXEL_RADIUS;
        boolean drawing = path != null;
        boolean[] linked = new boolean[GreatPattern.DOT_COUNT];
        for (var edge : pattern.edges()) {
            linked[edge.a()] = linked[edge.b()] = true;
        }
        if (drawing) for (int dot : path) linked[dot] = true;
        // Connect dot centers without Trickster's patternEdgeGap; dots then cover the ink endpoints.
        for (var edge : pattern.edges()) {
            connectedLine(matrices,buffers,dot(edge.a(),x,y,radius),dot(edge.b(),x,y,radius),
                    pixelSize,alpha,renderer.inUI);
        }
        if (drawing && renderer.getLineToMouse()) {
            connectedLine(matrices,buffers,dot(path.getLast(),x,y,radius),
                    new Vector2f((float)renderer.getMouseX(),(float)renderer.getMouseY()),pixelSize,alpha,renderer.inUI);
        }
        int snapTarget = drawing ? GreatGridGeometry.snap(renderer.getMouseX(),renderer.getMouseY(),x,y,radius,
                ClientConfig.gridSnapThreshold) : -1;
        for (int i=0;i<GreatPattern.DOT_COUNT;i++) {
            if (!linked[i] && !renderer.inEditor) continue;
            Vector2f pos = dot(i,x,y,radius);
            float scale = Trickster.CONFIG.dotSize();
            if (renderer.inEditor) {
                if ((drawing ? snapTarget == i : CircleRenderer.isInsideHitbox(pos,pixelSize,renderer.getMouseX(),renderer.getMouseY()))
                        && isCircleClickable(radius)) scale *= 1.6f;
                else if (!linked[i]) {
                    if (!isCircleClickable(radius)) continue;
                    float distance = pos.distance((float)renderer.getMouseX(),(float)renderer.getMouseY());
                    scale *= Math.clamp(radius/distance-0.2f,0,1);
                }
            }
            float r = linked[i] ? INK_R : renderer.getR();
            float g = linked[i] ? INK_G : renderer.getG();
            float b = linked[i] ? INK_B : renderer.getB();
            hexagon(matrices,buffers,pos,pixelSize*scale,
                    r,g,b,alpha,renderer.inUI,Trickster.CONFIG.outlinePatterns());
        }
    }

    private static void connectedLine(PoseStack matrices,MultiBufferSource buffers,Vector2f start,Vector2f end,
            float pixelSize,float alpha,boolean ui) {
        float length = start.distance(end);
        float width = pixelSize*0.5f*Trickster.CONFIG.patternSize();
        if (length <= 1e-6f || width <= 0) return;
        float dx = -(end.y-start.y)/length*width, dy = (end.x-start.x)/length*width;
        CircleRenderer.drawFlatPolygon(matrices,buffers,
                start.x-dx,start.y-dy,start.x+dx,start.y+dy,end.x+dx,end.y+dy,end.x-dx,end.y-dy,
                0,INK_R,INK_G,INK_B,alpha,ui,Trickster.CONFIG.outlinePatterns());
    }

    private static boolean isCircleClickable(float radius) {
        return dev.enjarai.trickster.screen.scribing.CircleWidget.isCircleClickable(radius*CircleRenderer.PATTERN_TO_PART_RATIO);
    }

    private static void hexagon(PoseStack matrices,MultiBufferSource buffers,Vector2f pos,float size,
            float r,float g,float b,float alpha,boolean ui,boolean outline) {
        if (size <= 0) return;
        Vector2f[] corners = new Vector2f[6];
        for (int i=0;i<6;i++) {
            double angle = i*Math.PI/3;
            corners[i] = new Vector2f(pos.x+(float)Math.cos(angle)*size,pos.y+(float)Math.sin(angle)*size);
        }
        // Two quads share one diameter: no round approximation or added bloom.
        quad(matrices,buffers,corners[0],corners[1],corners[2],corners[3],r,g,b,alpha,ui);
        quad(matrices,buffers,corners[3],corners[4],corners[5],corners[0],r,g,b,alpha,ui);
        if (outline) {
            var vertices = buffers.getBuffer(RenderType.debugLineStrip(1));
            var matrix = matrices.last().pose();
            for (int i=0;i<=6;i++) {
                Vector2f point = corners[i%6];
                vertices.addVertex(matrix,point.x,point.y,0).setColor(r,g,b,alpha);
            }
        }
    }

    private static void quad(PoseStack matrices,MultiBufferSource buffers,Vector2f a,Vector2f b,Vector2f c,Vector2f d,
            float red,float green,float blue,float alpha,boolean ui) {
        CircleRenderer.drawFlatPolygon(matrices,buffers,a.x,a.y,b.x,b.y,c.x,c.y,d.x,d.y,0,
                red,green,blue,alpha,ui,false);
    }
}
