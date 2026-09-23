package com.iridium126.createmanaindustry.compat.ysm.diagnostic;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Test-only mod for explaining why a launcher quick-play request stays on the title screen. */
@Mod(value = "cmi_ysm_client_diagnostic", dist = Dist.CLIENT)
public final class YsmClientDiagnostic {
    private int ticks;
    private String lastScreen = "";
    private boolean attempted;
    public YsmClientDiagnostic() { NeoForge.EVENT_BUS.addListener(this::tick); }
    private void tick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        String screen = client.screen == null ? "none" : client.screen.getClass().getName();
        if (!lastScreen.equals(screen)) {
            System.out.println("CMI_YSM_DIAG screen=" + screen + " player=" + (client.player != null));
            lastScreen = screen;
        }
        String endpoint = System.getProperty("cmi.ysm.autoConnect", "");
        if (attempted || endpoint.isBlank() || ++ticks < 200 || client.player != null
                || !screen.endsWith("TitleScreen")) return;
        attempted = true;
        try {
            ClassLoader loader = getClass().getClassLoader();
            Class<?> addressClass = Class.forName("net.minecraft.client.multiplayer.resolver.ServerAddress", false, loader);
            Object address = addressClass.getMethod("parseString", String.class).invoke(null, endpoint);
            Class<?> dataClass = Class.forName("net.minecraft.client.multiplayer.ServerData", false, loader);
            var ctor = Arrays.stream(dataClass.getConstructors()).filter(c -> c.getParameterCount() == 3
                    && c.getParameterTypes()[0] == String.class && c.getParameterTypes()[1] == String.class
                    && c.getParameterTypes()[2].isEnum()).findFirst().orElseThrow();
            @SuppressWarnings({"unchecked", "rawtypes"}) Object type = Enum.valueOf((Class) ctor.getParameterTypes()[2], "OTHER");
            Object server = ctor.newInstance("CMI YSM probe", endpoint, type);
            Class<?> connect = Class.forName("net.minecraft.client.gui.screens.ConnectScreen", false, loader);
            var method = Arrays.stream(connect.getMethods()).filter(m -> Modifier.isStatic(m.getModifiers())
                    && m.getName().equals("startConnecting")).findFirst().orElseThrow();
            var parameters = method.getParameterTypes(); Object[] args = new Object[parameters.length];
            for (int i = 0; i < args.length; i++) {
                Class<?> parameter = parameters[i];
                if (parameter.isInstance(client.screen)) args[i] = client.screen;
                else if (parameter.isInstance(client)) args[i] = client;
                else if (parameter.isInstance(address)) args[i] = address;
                else if (parameter.isInstance(server)) args[i] = server;
                else if (parameter == boolean.class) args[i] = false;
                else args[i] = null;
            }
            method.invoke(null, args);
            System.out.println("CMI_YSM_DIAG forced local connection to " + endpoint);
        } catch (Throwable failure) {
            System.err.println("CMI_YSM_DIAG connection failed: " + failure);
            failure.printStackTrace();
        }
    }
}
