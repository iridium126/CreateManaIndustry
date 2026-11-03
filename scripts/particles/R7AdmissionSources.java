import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import com.iridium126.createmanaindustry.client.particles.engine.hardware.*;
/** Exact include-expanded sources used by both the qualifier and receipt. */
public final class R7AdmissionSources extends ModelPopulationGpuValidation {
    public static void main(String[] args)throws Exception{
        Path out=Path.of(System.getProperty("cmi.benchmark.output"));Files.createDirectories(out);String update=source("update.comp"),dome=source("dome_contacts.comp");var variant=HardwareUpdateSource.build(update);var contact=HardwareDomeSource.build(dome);
        Files.writeString(out.resolve("update-plain.glsl"),update,StandardOpenOption.CREATE_NEW);Files.writeString(out.resolve("dome-plain.glsl"),dome,StandardOpenOption.CREATE_NEW);
        Files.writeString(out.resolve("update-force.glsl"),HardwareUpdateSource.build(update,true).source(),StandardOpenOption.CREATE_NEW);
        Files.writeString(out.resolve("admission-sources.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("update_plain_sha256",HardwareBackendRuntime.sha(update),"dome_plain_sha256",HardwareBackendRuntime.sha(dome),"update_variant_sha256",HardwareBackendRuntime.sha(variant.source()),"update_force_variant_sha256",HardwareBackendRuntime.sha(HardwareUpdateSource.build(update,true).source()),"dome_consumer_sha256",HardwareBackendRuntime.sha(contact.consumer()),"dome_prepare_sha256",HardwareBackendRuntime.sha(contact.prepare()),"ordinary_prepare_sha256",HardwareBackendRuntime.sha(Files.readString(Path.of("src/main/resources/assets/createmanaindustry/shaders/particles/diagnostics/hardware_prepare.comp")))))+"\n",StandardOpenOption.CREATE_NEW);
    }
}
