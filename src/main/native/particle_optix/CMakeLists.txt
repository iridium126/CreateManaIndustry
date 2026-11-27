cmake_minimum_required(VERSION 3.24)
project(CmiParticleOptix LANGUAGES CXX RC)
set(CMAKE_CXX_STANDARD 17)
find_package(JNI REQUIRED)
set(NVCC "${CRT_ROOT}/bin/nvcc.exe")
set(PARTICLE_COMMON "${CMAKE_CURRENT_SOURCE_DIR}/../particle_common")
set(SPATIAL_LAYOUT "${PARTICLE_COMMON}/ResourceLayout.h" "${PARTICLE_COMMON}/ParticleVectors.h" "${PARTICLE_COMMON}/SpatialParticle.h")
set(DEVICE_INCLUDES "-I${CUDA_ROOT}/include" "-I${CRT_ROOT}/include" "-I${OPTIX_ROOT}/include" "-I${CCCL_ROOT}/include" "-I${CMAKE_CURRENT_SOURCE_DIR}" "-I${PARTICLE_COMMON}")
set(STORM_MATH_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../resources/assets/createmanaindustry/shaders/particles/chunks")
add_custom_command(OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/spatial.fatbin"
 COMMAND "${NVCC}" --fatbin -std=c++17 --generate-code=arch=compute_75,code=compute_75 --generate-code=arch=compute_89,code=sm_89 ${DEVICE_INCLUDES} "${CMAKE_CURRENT_SOURCE_DIR}/CudaSpatial.cu" -o "${CMAKE_CURRENT_BINARY_DIR}/spatial.fatbin"
 DEPENDS CudaSpatial.cu Spatial.h "${PARTICLE_COMMON}/StormStep.h" ${SPATIAL_LAYOUT} VERBATIM)
add_custom_command(OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/spatial.optixir"
 COMMAND "${NVCC}" --optix-ir -std=c++17 -arch=compute_75 ${DEVICE_INCLUDES} "${CMAKE_CURRENT_SOURCE_DIR}/OptixSpatial.cu" -o "${CMAKE_CURRENT_BINARY_DIR}/spatial.optixir"
 DEPENDS OptixSpatial.cu Spatial.h "${PARTICLE_COMMON}/StormStep.h" ${SPATIAL_LAYOUT} OcclusionProof.h VERBATIM)
add_custom_command(OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/storm_step.fatbin"
 COMMAND "${NVCC}" --fatbin -std=c++17 --fmad=false --generate-code=arch=compute_75,code=compute_75 --generate-code=arch=compute_89,code=sm_89 ${DEVICE_INCLUDES} "-I${STORM_MATH_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}/StormStep.cu" -o "${CMAKE_CURRENT_BINARY_DIR}/storm_step.fatbin"
 DEPENDS StormStep.cu "${PARTICLE_COMMON}/StormStep.h" Spatial.h "${PARTICLE_COMMON}/StormTerrain.h" StormVector.cuh StormCollision.cuh StormNavigation.cuh StormDome.cuh StormHelix.cuh StormContact.cuh StormColumn.cuh ${SPATIAL_LAYOUT} "${STORM_MATH_DIR}/storm_home.glsl" VERBATIM)
add_custom_target(particle_device ALL DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/spatial.fatbin" "${CMAKE_CURRENT_BINARY_DIR}/spatial.optixir" "${CMAKE_CURRENT_BINARY_DIR}/storm_step.fatbin")
set(CMI_OPTIX_FATBIN "${CMAKE_CURRENT_BINARY_DIR}/spatial.fatbin")
set(CMI_OPTIX_OPTIXIR "${CMAKE_CURRENT_BINARY_DIR}/spatial.optixir")
set(CMI_OPTIX_STORM_STEP "${CMAKE_CURRENT_BINARY_DIR}/storm_step.fatbin")
configure_file(ParticleOptix.rc.in "${CMAKE_CURRENT_BINARY_DIR}/ParticleOptix.rc" @ONLY)
set_source_files_properties("${CMAKE_CURRENT_BINARY_DIR}/ParticleOptix.rc" PROPERTIES
 OBJECT_DEPENDS "${CMI_OPTIX_FATBIN};${CMI_OPTIX_OPTIXIR};${CMI_OPTIX_STORM_STEP};${CMAKE_CURRENT_SOURCE_DIR}/Version.h")
add_library(CmiParticleOptix SHARED OptixSpatial.cpp CudaOpenGlInterop.cpp StormControl.cpp "${CMAKE_CURRENT_BINARY_DIR}/ParticleOptix.rc")
set_property(SOURCE OptixSpatial.cpp APPEND PROPERTY OBJECT_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/TerrainAcceleration.h")
set_property(TARGET CmiParticleOptix PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
target_include_directories(CmiParticleOptix PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}" "${PARTICLE_COMMON}" ${JNI_INCLUDE_DIRS} "${CUDA_ROOT}/include" "${CRT_ROOT}/include" "${OPTIX_ROOT}/include")
target_link_libraries(CmiParticleOptix PRIVATE "${CUDA_ROOT}/lib/x64/cuda.lib" d3d12 dxgi opengl32)
target_compile_definitions(CmiParticleOptix PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
add_dependencies(CmiParticleOptix particle_device)
