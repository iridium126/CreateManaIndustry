package com.iridium126.createmanaindustry.client.greattrick;

import com.iridium126.createmanaindustry.compat.trickster.GreatPattern;
import com.iridium126.createmanaindustry.compat.trickster.GreatTrickGlyph;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import dev.enjarai.trickster.Trickster;
import dev.enjarai.trickster.ModSounds;
import dev.enjarai.trickster.render.CircleRenderer;
import dev.enjarai.trickster.screen.scribing.CircleSoupState;
import dev.enjarai.trickster.screen.scribing.CircleWidget;
import dev.enjarai.trickster.spell.Fragment;
import dev.enjarai.trickster.spell.PatternGlyph;
import dev.enjarai.trickster.spell.SpellPart;
import io.wispforest.owo.braid.framework.BuildContext;
import io.wispforest.owo.braid.framework.widget.Widget;
import io.wispforest.owo.braid.widgets.basic.*;
import io.wispforest.owo.braid.widgets.sharedstate.SharedState;
import io.wispforest.owo.braid.widgets.stack.Stack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import java.util.ArrayList;

/** Keeps Trickster's soup navigation and execution callback, with a separate nineteen-dot stroke. */
public final class GreatCircleState extends CircleWidget.State {
    private final GreatDrawingGesture gesture = new GreatDrawingGesture();
    private double mouseX = -9999, mouseY = -9999;

    private CircleAccess access() { return (CircleAccess)widget(); }

    private boolean drawable() {
        Fragment glyph = access().cmi$view().part.glyph;
        return access().cmi$mutable() && !access().cmi$hidden() && !access().cmi$inner()
                && !access().cmi$view().beingReplaced
                && (glyph instanceof GreatTrickGlyph || glyph instanceof PatternGlyph p && p.pattern().isEmpty());
    }

    @Override
    public Widget build(BuildContext context) {
        var state = SharedState.get(context, CircleSoupState.class);
        // Accept mouse-down here: otherwise Braid captures the parent soup as the drag target.
        Widget canvas = new MouseArea(area -> area
                .clickCallback((x,y,button,modifiers) -> mousePress(x,y,button,state))
                .releaseCallback((x,y,button,modifiers) -> mouseClick(x,y,button,state))
                .moveCallback(this::mouseMove)
                .dragCallback((x,y,dx,dy) -> mouseMove(x,y))
                .exitCallback(this::mouseLeave),
                new Sized(48,48,new CustomDraw((ctx,transform) -> {
                    var renderer = access().cmi$renderer();
                    renderer.setMousePosition(mouseX,mouseY);
                    renderer.setLineToMouse(gesture.drawing() && mouseX != -9999);
                    renderer.setConsiderInners(access().cmi$inner());
                    renderer.setLoading(access().cmi$view().loading ? (int)(System.currentTimeMillis()/250%2) : -1);
                    renderer.setCircleTexture(access().cmi$highlight() ? CircleRenderer.CIRCLE_TEXTURE_HIGHLIGHT : null);
                    SpellPart original = access().cmi$view().part;
                    SpellPart display = original;
                    if (drawable()) {
                        Fragment glyph = gesture.drawing() ? new GreatTrickGlyph(GreatPattern.fromPath(gesture.path()))
                                : original.glyph instanceof GreatTrickGlyph ? original.glyph : new GreatTrickGlyph(GreatPattern.EMPTY);
                        display = new SpellPart(glyph,original.subParts);
                    }
                    float forcedAlpha = access().cmi$hidden() ? Trickster.CONFIG.ponderOpacity() : Trickster.CONFIG.circleOpacity();
                    float alpha = (float)Math.clamp(1/(access().cmi$radius()/ctx.guiHeight()*3),0,forcedAlpha);
                    try (var ignored = GreatGlyphRenderer.drawing(display.glyph,gesture.path())) {
                        renderer.renderCircle(ctx.pose(),display,24,24,24,access().cmi$angle(),
                                Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false),alpha,forcedAlpha,new Vec3(-1,0,0),null);
                    }
                    CircleRenderer.VERTEX_CONSUMERS.endBatch();
                })));
        var children = new ArrayList<Widget>();
        children.add(canvas);
        if (access().cmi$eval() != null && !access().cmi$hidden()) {
            var offset = access().cmi$renderer().getDividerOffset(16,access().cmi$angle(),access().cmi$view().part.partCount());
            children.add(new Center(new Transform(new Matrix4f().translate(offset),
                    new MouseArea(area -> area
                            .clickCallback((x,y,button,modifiers) -> button == 0 && state.drawingIn == null && !state.dragging)
                            .releaseCallback((x,y,button,modifiers) -> button == 0 && clickEval(state)),
                    new Sized(6,6,new CustomDraw((ctx,transform) -> {
                        CircleRenderer.drawTexturedQuad(ctx.pose(),CircleRenderer.VERTEX_CONSUMERS,CircleWidget.CIRCLE_TEXTURE,
                                -1,7,-1,7,0,1,1,1,
                                (float)Math.clamp(1/(access().cmi$radius()/ctx.guiHeight()*3),0,0.8),true);
                        CircleRenderer.VERTEX_CONSUMERS.endBatch();
                    }))))));
        }
        return new Sized(48,48,new Stack(children));
    }

    private boolean mousePress(double x,double y,int button,CircleSoupState state) {
        if (button != 0 || !drawable() || state.dragging || state.drawingIn != null && state.drawingIn != this) return false;
        mouseX = x;
        mouseY = y;
        if (!gesture.press(hover(x,y))) return false;
        state.drawingIn = this;
        return true;
    }

    @Override
    public boolean mouseClick(double x,double y,int button,CircleSoupState state) {
        if (button != 0 || state.drawingIn != this) return false;
        GreatPattern completed = gesture.release();
        if (completed != null) apply(completed,state);
        return true;
    }

    @Override
    public void mouseMove(double x,double y) {
        mouseX = x;
        mouseY = y;
        if (!gesture.drawing()) return;
        var previous = gesture.path();
        gesture.move(GreatGridGeometry.snap(x,y,24,24,24/CircleRenderer.PATTERN_TO_PART_RATIO,ClientConfig.gridSnapThreshold));
        if (!gesture.path().equals(previous)) {
            boolean removing = gesture.path().size() < previous.size();
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ModSounds.DRAW,
                    ModSounds.randomPitch(removing ? 0.6f : 1f,0.2f)));
        }
    }

    @Override
    public void mouseLeave() { mouseX = -9999; mouseY = -9999; }

    private int hover(double x,double y) {
        float radius = 24/CircleRenderer.PATTERN_TO_PART_RATIO;
        int closest = -1;
        float distance = Float.MAX_VALUE;
        for (int i=0;i<GreatPattern.DOT_COUNT;i++) {
            var pos = GreatGlyphRenderer.dot(i,24,24,radius);
            float current = pos.distance((float)x,(float)y);
            if (current < distance && CircleRenderer.isInsideHitbox(pos,radius/CircleRenderer.PART_PIXEL_RADIUS,x,y)) {
                closest = i;
                distance = current;
            }
        }
        return closest;
    }

    private void apply(GreatPattern pattern,CircleSoupState state) {
        state.drawingIn = null;
        var view = access().cmi$view();
        view.replaceGlyph(new GreatTrickGlyph(pattern));
        GreatTrickClient.save(view.getUpperParent().part);
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ModSounds.COMPLETE,
                pattern.edges().isEmpty() ? 0.6f : 1f));
    }

    @Override
    public void finishDrawing(boolean apply,CircleSoupState state) {
        if (!gesture.drawing()) return;
        GreatPattern pattern = gesture.finish();
        if (apply) apply(pattern,state);
        else state.drawingIn = null;
    }

    @Override
    public void drawPoint(byte dot,CircleSoupState state) { /* Nine-key input has no nineteen-dot equivalent. */ }

    @Override
    public void didUpdateWidget(CircleWidget old) {
        if (!drawable() && gesture.drawing()) finishDrawing(false,SharedState.get(context(),CircleSoupState.class));
    }
}
