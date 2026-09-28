// Example opt-in internal type. Standard spawn still owns seed, lifetime and identity.
void cmi_rising_spark_spawn(uint header, inout vec4 p0, inout vec4 p1, inout vec4 p2, inout vec4 p3) {
    p1.y += 2.0;
}
