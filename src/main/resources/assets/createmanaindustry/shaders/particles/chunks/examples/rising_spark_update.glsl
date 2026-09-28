// Post-integration velocity adjustment; affects the following simulation step.
void cmi_rising_spark_update(uint header, inout vec4 p0, inout vec4 p1, inout vec4 p2, inout vec4 p3) {
    p1.y += uDt;
}
