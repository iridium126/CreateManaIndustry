using System.Reflection;
using System.Xml.Linq;
var assembly = Assembly.LoadFrom(Path.GetFullPath(args[0]));
var type = assembly.GetType("Interpreter", true)!;
var interpreter = type.GetMethod("Load")!.Invoke(null, new object[] { XElement.Load(args[1]), 121, 161, 1 })!;
Directory.CreateDirectory(args[2]);
foreach (int seed in new[] { 0, 1, 42, 137, 2026, -1, int.MinValue, int.MaxValue }) {
    var frames = (IEnumerable<(byte[], char[], int, int, int)>)type.GetMethod("Run")!
        .Invoke(interpreter, new object[] { seed, 0, false })!;
    foreach (var frame in frames) File.WriteAllBytes(Path.Combine(args[2], $"{seed}.bin"), frame.Item1);
    Console.WriteLine($"Upstream causeways seed={seed}");
}
