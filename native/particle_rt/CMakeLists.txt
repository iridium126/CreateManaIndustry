cmake_minimum_required(VERSION 3.24)
project(CmiParticleRt LANGUAGES CXX RC)
set(CMAKE_CXX_STANDARD 17)
find_package(JNI REQUIRED)
set(NVCC "${CRT_ROOT}/bin/nvcc.exe")
set(DEVICE_INCLUDES "-I${CUDA_ROOT}/include" "-I${CRT_ROOT}/include" "-I${OPTIX_ROOT}/include" "-I${CCCL_ROOT}/include" "-I${CMAKE_CURRENT_SOURCE_DIR}")
set(STORM_MATH_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../src/main/resources/assets/createmanaindustry/shaders/particles/chunks")
add_custom_command(OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/spatial.fatbin"
 COMMAND "${NVCC}" --fatbin -std=c++17 --generate-code=arch=compute_75,code=compute_75 --generate-code=arch=compute_89,code=sm_89 ${DEVICE_INCLUDES} "${CMAKE_CURRENT_SOURCE_DIR}/grid.cu" -o "${CMAKE_CURRENT_BINARY_DIR}/spatial.fatbin"
 DEPENDS grid.cu Spatial.h ResourceLayout.h VERBATIM)
add_custom_command(OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/spatial.optixir"
 COMMAND "${NVCC}" --optix-ir -std=c++17 -arch=compute_75 ${DEVICE_INCLUDES} "${CMAKE_CURRENT_SOURCE_DIR}/rt.cu" -o "${CMAKE_CURRENT_BINARY_DIR}/spatial.optixir"
 DEPENDS rt.cu Spatial.h StormStep.h ResourceLayout.h OcclusionProof.h VERBATIM)
add_custom_command(OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/storm_step.fatbin"
 COMMAND "${NVCC}" --fatbin -std=c++17 --fmad=false --generate-code=arch=compute_75,code=compute_75 --generate-code=arch=compute_89,code=sm_89 ${DEVICE_INCLUDES} "-I${STORM_MATH_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}/StormStep.cu" -o "${CMAKE_CURRENT_BINARY_DIR}/storm_step.fatbin"
 DEPENDS StormStep.cu StormStep.h Spatial.h StormCollision.cuh StormNavigation.cuh StormDome.cuh StormHelix.cuh StormContact.cuh StormColumn.cuh ResourceLayout.h "${STORM_MATH_DIR}/storm_home.glsl" VERBATIM)
add_custom_target(particle_device ALL DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/spatial.fatbin" "${CMAKE_CURRENT_BINARY_DIR}/spatial.optixir" "${CMAKE_CURRENT_BINARY_DIR}/storm_step.fatbin")
set(CMI_RT_FATBIN "${CMAKE_CURRENT_BINARY_DIR}/spatial.fatbin")
set(CMI_RT_OPTIXIR "${CMAKE_CURRENT_BINARY_DIR}/spatial.optixir")
set(CMI_RT_STORM_STEP "${CMAKE_CURRENT_BINARY_DIR}/storm_step.fatbin")
configure_file(ParticleRt.rc.in "${CMAKE_CURRENT_BINARY_DIR}/ParticleRt.rc" @ONLY)
set_source_files_properties("${CMAKE_CURRENT_BINARY_DIR}/ParticleRt.rc" PROPERTIES
 OBJECT_DEPENDS "${CMI_RT_FATBIN};${CMI_RT_OPTIXIR};${CMI_RT_STORM_STEP};${CMAKE_CURRENT_SOURCE_DIR}/Version.h")
add_library(CmiParticleRt SHARED Spatial.cpp Interop.cpp StormControl.cpp "${CMAKE_CURRENT_BINARY_DIR}/ParticleRt.rc")
set_property(SOURCE Spatial.cpp APPEND PROPERTY OBJECT_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/TerrainAcceleration.h")
set_property(TARGET CmiParticleRt PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
target_include_directories(CmiParticleRt PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}" ${JNI_INCLUDE_DIRS} "${CUDA_ROOT}/include" "${CRT_ROOT}/include" "${OPTIX_ROOT}/include")
target_link_libraries(CmiParticleRt PRIVATE "${CUDA_ROOT}/lib/x64/cuda.lib" d3d12 dxgi opengl32)
target_compile_definitions(CmiParticleRt PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
add_dependencies(CmiParticleRt particle_device)
