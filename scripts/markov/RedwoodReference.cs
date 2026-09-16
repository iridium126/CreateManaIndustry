using System.Diagnostics;
using System.Reflection;
using System.Xml.Linq;
using System.IO.Compression;
using System.Security.Cryptography;

var assembly = Assembly.LoadFrom(Path.GetFullPath(args[0]));
var type = assembly.GetType("Interpreter", true)!;
var interpreter = type.GetMethod("Load")!.Invoke(null, new object[] { XElement.Load(args[1]), 81, 81, 192 })!;
int seed = int.Parse(args[2]);
var watch = Stopwatch.StartNew();
var frames = (IEnumerable<(byte[], char[], int, int, int)>)type.GetMethod("Run")!.Invoke(interpreter, new object[] { seed, 0, false })!;
byte[] state = null!;
foreach (var frame in frames) state = frame.Item1;
Console.WriteLine($"C# seed={seed} generation={watch.Elapsed.TotalSeconds:F3}s sha256={Convert.ToHexString(SHA256.HashData(state)).ToLowerInvariant()}");
using var output = new GZipStream(File.Create(args[3]), CompressionLevel.Fastest);
output.Write(state);
