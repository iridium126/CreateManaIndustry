using System.Diagnostics;
using System.Reflection;
using System.Xml.Linq;

// Executes the existing upstream assembly without changing its source or counting graphics/file I/O.
var assembly = Assembly.LoadFrom(Path.GetFullPath(args[0]));
if (assembly.GetCustomAttribute<DebuggableAttribute>()?.IsJITOptimizerDisabled == true)
    throw new InvalidOperationException("Benchmark requires an optimized upstream Release assembly");
var type = assembly.GetType("Interpreter", true)!;
var load = type.GetMethod("Load")!;
var run = type.GetMethod("Run")!;
var interpreter = load.Invoke(null, new object[] { XElement.Load(args[1]), 19, 19, 18 })!;
byte[] Generate(int seed) {
    var frames = (IEnumerable<(byte[], char[], int, int, int)>) run.Invoke(interpreter, new object[] {seed, 0, false})!;
    byte[] result = null!;
    foreach (var frame in frames) result = frame.Item1;
    return result;
}
Directory.CreateDirectory(args[2]);
using (var output = new BinaryWriter(File.Create(Path.Combine(args[2], "reference.bin")))) {
    foreach (int seed in Enumerable.Range(0, 256).Concat(new[] {1000003, 2026, -1, int.MinValue, int.MaxValue})) {
        output.Write(seed);
        output.Write(Generate(seed));
    }
}
for (int i = 0; i < 1000; i++) Generate(i);
var times = new List<double>();
long checksum = 0;
for (int trial = 0; trial < 5; trial++) {
    var watch = Stopwatch.StartNew();
    for (int i = 0; i < 1000; i++) checksum += Generate(i)[9 + 9 * 19];
    times.Add(watch.Elapsed.TotalMilliseconds / 1000);
}
times.Sort();
string report = System.Text.Json.JsonSerializer.Serialize(new {
    runtime = System.Runtime.InteropServices.RuntimeInformation.FrameworkDescription,
    samples = 1000, warmup = 1000, trials = 5, median_ms = times[2], trials_ms = times, checksum
}, new System.Text.Json.JsonSerializerOptions { WriteIndented = true });
File.WriteAllText(Path.Combine(args[2], "reference-performance.json"), report);
Console.WriteLine(report);
