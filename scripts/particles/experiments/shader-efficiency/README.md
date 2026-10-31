# Withdrawn shader-efficiency experiments

`ModelShaderOptimization.atlas-entity.java.txt` reconstructs the discarded
atlas + entity comparison candidate on the final safety fixes. It differs from
production only by permitting `atlasSize`/`gtextureSize` comparisons. To reproduce,
use an isolated checkout and replace the production optimizer with this text,
then run `validateShaderEfficiencyGpu -PmodelDrawPack=complementary` with the R2
frozen reference. Never ship this experiment. The observed failure is recorded in
`build/shader-efficiency-real-quick.log`: production workload 0, pose 0, sword 1,
attachment 0 word 1108, `3b614f25 != 3b614f26`. Raw FP32 precision was not relaxed.

The domain candidate is generated solely in `UpdateDomainGpuValidation.java`
from the same template (`uModelPass=1/2`, `isModel=false/true`). Production retains
the unified update program. No experiment is included in resources or the JAR.
